package com.jimboyo.localbeat.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.jimboyo.localbeat.audio.EqualizerProcessor
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.jimboyo.localbeat.LocalBeatApp
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.server.StreamBlockedException
import com.jimboyo.localbeat.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Playback plus the browse tree. This one service is what Android Auto, the
 * notification, the lock screen, Bluetooth/steering-wheel buttons and
 * LocalBeat's own UI all talk to.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as LocalBeatApp
    private lateinit var player: ExoPlayer
    private lateinit var tree: BrowseTree
    private var session: MediaLibrarySession? = null
    /** Don't save over the stored queue until it has been restored. */
    private var restored = false
    private var saveTicker: Job? = null

    override fun onCreate() {
        super.onCreate()
        tree = BrowseTree(app.art, { app.playlists.playlists.value }) { t -> app.server?.streamUri(t) }
        // The equaliser runs inside the audio sink, so it applies to every output.
        val equalizer = EqualizerProcessor { app.equalizer.state.value }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(equalizer))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        // Server songs (Connected edition) are checked as they start: e.g. Wi-Fi only.
        val dataSources = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
            if (spec.uri.scheme == "http" || spec.uri.scheme == "https") {
                app.server?.streamBlockedReason()?.let { throw StreamBlockedException(it) }
            }
            spec
        }
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones/Bluetooth disconnect
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Say why a server song stopped ("Not on Wi-Fi…"); other errors as before.
                var cause: Throwable? = error
                while (cause != null && cause !is StreamBlockedException) cause = cause.cause
                cause?.message?.let { Toast.makeText(this@PlaybackService, it, Toast.LENGTH_LONG).show() }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                // A server song started: fetch it whole, so seeking works in it soon.
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    val id = player.currentMediaItem?.mediaId?.let(BrowseTree::trackIdOf)
                    id?.let { app.library.library.value?.track(it) }?.takeIf { it.onServer }?.let { app.server?.fetchWhole(it) }
                }
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) saveQueue()
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    saveTicker?.cancel()
                    if (player.isPlaying) saveTicker = scope.launch {
                        while (isActive) { delay(15_000); saveQueue() }
                    }
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sessionPlayer = ServerSeekPlayer(player, this, { app.server }) { id ->
            BrowseTree.trackIdOf(id)?.let { app.library.library.value?.track(it) }
        }
        session = MediaLibrarySession.Builder(this, sessionPlayer, Callback())
            .setSessionActivity(openApp)
            .build()

        app.library.start()
        scope.launch { restoreQueue() }

        // Let Android Auto refresh its lists when the library or playlists change.
        scope.launch {
            app.library.generation.drop(1).collect { notifyChanged(BrowseTree.ALBUMS, BrowseTree.ARTISTS, BrowseTree.GENRES, BrowseTree.DECADES, BrowseTree.SONGS, BrowseTree.RECENT) }
        }
        scope.launch {
            app.playlists.playlists.drop(1).collect { list ->
                notifyChanged(BrowseTree.PLAYLISTS, *list.map { BrowseTree.PLAYLIST + it.id }.toTypedArray())
            }
        }
    }

    private suspend fun notifyChanged(vararg parentIds: String) {
        val s = session ?: return
        val lib = app.library.awaitLibrary()
        for (id in parentIds) s.notifyChildrenChanged(id, tree.children(id, lib)?.size ?: 0, null)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        saveQueue()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---- queue persistence ----

    private suspend fun restoreQueue() {
        val saved = app.queueStore.load()
        if (saved == null || player.mediaItemCount > 0) {
            restored = true
            return
        }
        val lib = app.library.awaitLibrary()
        if (player.mediaItemCount == 0) {
            val items = savedItems(saved, lib)
            if (items != null) {
                player.setMediaItems(items.mediaItems, items.startIndex, items.startPositionMs)
                player.shuffleModeEnabled = saved.shuffle
                player.repeatMode = saved.repeatMode
                player.prepare()
            }
        }
        restored = true
    }

    private fun savedItems(saved: QueueStore.Saved, lib: Library): MediaSession.MediaItemsWithStartPosition? {
        val current = saved.trackIds.getOrNull(saved.index)
        val tracks = saved.trackIds.mapNotNull(lib::track)
        if (tracks.isEmpty()) return null
        val index = tracks.indexOfFirst { it.id == current }
        return MediaSession.MediaItemsWithStartPosition(
            tracks.map { tree.queueItem(it, lib) },
            index.coerceAtLeast(0),
            if (index >= 0) saved.positionMs else 0,
        )
    }

    private fun saveQueue() {
        if (!restored || !::player.isInitialized) return
        val ids = List(player.mediaItemCount) { BrowseTree.trackIdOf(player.getMediaItemAt(it).mediaId) ?: "" }
        app.queueStore.save(
            QueueStore.Saved(ids, player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode)
        )
    }

    // ---- session callback ----

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            app.library.start()
            return Futures.immediateFuture(LibraryResult.ofItem(tree.root(), LibraryParams.Builder().setExtras(tree.rootExtras()).build()))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId, app.library.awaitLibrary())?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = tree.children(parentId, app.library.awaitLibrary())
            if (children == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItemList(children.page(page, pageSize), params)
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = tree.search(query, app.library.awaitLibrary()).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(tree.search(query, app.library.awaitLibrary()).page(page, pageSize), params)
        }

        /** "Add to queue": albums and artists add all their songs. */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future {
            val lib = app.library.awaitLibrary()
            mediaItems.flatMap { item ->
                val id = item.mediaId
                // A track in context is just that track when adding (the context is for "play").
                val q = if ('|' in id) tree.resolve(MediaItem.Builder().setMediaId(BrowseTree.TRACK + BrowseTree.trackIdOf(id)).build(), lib)
                else tree.resolve(item, lib)
                q.tracks.map { tree.queueItem(it, lib) }
            }
        }

        /** "Play": a tapped track plays its album/list from that track. */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val lib = app.library.awaitLibrary()
            if (mediaItems.size == 1) {
                val q = tree.resolve(mediaItems[0], lib)
                MediaSession.MediaItemsWithStartPosition(q.tracks.map { tree.queueItem(it, lib) }, q.startIndex, startPositionMs)
            } else {
                val tracks = mediaItems.map { item -> BrowseTree.trackIdOf(item.mediaId)?.let(lib::track) }
                val start = tracks.take(startIndex.coerceAtLeast(0)).count { it != null }
                MediaSession.MediaItemsWithStartPosition(tracks.filterNotNull().map { tree.queueItem(it, lib) }, start, startPositionMs)
            }
        }

        /** Play pressed with nothing loaded (Bluetooth, Android Auto, after a reboot): resume the saved queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = app.queueStore.load() ?: throw UnsupportedOperationException("Nothing to resume")
            savedItems(saved, app.library.awaitLibrary()) ?: throw UnsupportedOperationException("Saved queue is gone")
        }
    }

    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> =
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) this
        else drop((page.toLong() * pageSize).coerceAtMost(size.toLong()).toInt()).take(pageSize)
}
