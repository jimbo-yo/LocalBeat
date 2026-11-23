package com.jimboyo.localbeat.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
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
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.jimboyo.localbeat.R
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
        tree = BrowseTree(
            app.art,
            { app.allPlaylists() },
            { t -> app.server?.streamUri(t) },
            { id -> app.server?.playlistId(id) ?: id },
        )
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
        // Server songs (Connected edition) are checked as they start: the copy on the
        // phone if there is one, else the stream, if allowed (e.g. Wi-Fi only).
        val dataSources = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
            val server = app.server
            val uri = server?.resolve(spec.uri) ?: spec.uri
            if (uri.scheme == "localbeat") throw StreamBlockedException("Music server is turned off")
            if (uri.scheme == "http" || uri.scheme == "https") {
                server?.streamBlockedReason()?.let { throw StreamBlockedException(it) }
            }
            if (uri == spec.uri) spec else spec.withUri(uri)
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
                // Say why a server song stopped; other errors as before.
                var cause: Throwable? = error
                while (cause != null && cause !is StreamBlockedException) cause = cause.cause
                val onServer = player.currentMediaItem?.mediaId?.let(BrowseTree::trackIdOf)
                    ?.let { app.library.library.value?.track(it) }?.onServer == true
                val networkError = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                if (onServer && (cause != null || networkError)) {
                    // The server can't be used right now: check the connection, and carry on
                    // with the next song that's on the phone rather than stopping (only if playing).
                    app.server?.reportStreamFailure()
                    if (player.playWhenReady && skipToPlayable()) {
                        if (!toldSkipping) {
                            toldSkipping = true
                            Toast.makeText(this@PlaybackService, "Music server not connected: playing the songs on this phone", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    if (!player.playWhenReady) return
                }
                val message = cause?.message ?: when {
                    !onServer -> null
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Can't reach the music server. Is WireGuard on, and the address in Settings right?"
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "The music server refused that song."
                    else -> null
                }
                message?.let { Toast.makeText(this@PlaybackService, it, Toast.LENGTH_LONG).show() }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                // A server song started playing: fetch it whole, so seeking works in it soon.
                if (player.playWhenReady && events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                    val id = player.currentMediaItem?.mediaId?.let(BrowseTree::trackIdOf)
                    id?.let { app.library.library.value?.track(it) }?.takeIf { it.onServer }?.let { app.server?.fetchWhole(it) }
                }
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) saveQueue()
                if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                    app.server?.setInUse("playing", player.playWhenReady)
                }
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

        // The "On this phone" button follows the switch, wherever it's flipped.
        app.server?.let { server ->
            scope.launch {
                app.library.onPhoneOnly.drop(1).collect { session?.setMediaButtonPreferences(mediaButtons()) }
            }
            scope.launch {
                server.settings.drop(1).collect { session?.setMediaButtonPreferences(mediaButtons()) }
            }
            // Connected or not: the button shows it, and a tone says so while music plays.
            scope.launch {
                server.connected.drop(1).collect { on ->
                    session?.setMediaButtonPreferences(mediaButtons())
                    if (on) toldSkipping = false
                    if (player.playWhenReady && server.settings.value.connectionSound) chime(on)
                }
            }
        }

        // Let Android Auto refresh its lists when the library or playlists change.
        scope.launch {
            app.library.generation.drop(1).collect {
                notifyChanged(
                    BrowseTree.EXPLORE, BrowseTree.MOODS, BrowseTree.GENRES, BrowseTree.LIBRARY,
                    BrowseTree.ALBUMS, BrowseTree.ARTISTS, BrowseTree.DECADES, BrowseTree.SONGS, BrowseTree.RECENT,
                )
            }
        }
        scope.launch {
            app.playlists.playlists.drop(1).collect { list ->
                notifyChanged(BrowseTree.PLAYLISTS, *list.map { BrowseTree.PLAYLIST + it.id }.toTypedArray())
            }
        }
        app.server?.let { server ->
            scope.launch {
                server.playlists.drop(1).collect { list ->
                    notifyChanged(BrowseTree.PLAYLISTS, *list.map { BrowseTree.PLAYLIST + it.id }.toTypedArray())
                }
            }
        }
    }

    private suspend fun notifyChanged(vararg parentIds: String) {
        val s = session ?: return
        val lib = app.library.awaitShown()
        for (id in parentIds) s.notifyChildrenChanged(id, tree.children(id, lib)?.size ?: 0, null)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    // ---- the "On this phone" button (Connected edition) ----

    private val onPhoneCommand = SessionCommand(ON_PHONE_ACTION, Bundle.EMPTY)

    /**
     * Android Auto's playing screen (and the phone's media controls) get a button for
     * the "On this phone" switch: a phone while only the phone's music shows, a cloud
     * while everything does. Tapping it flips the switch.
     */
    private fun mediaButtons(): List<CommandButton> {
        val server = app.server ?: return emptyList()
        if (server.settings.value.enabled && !server.connected.value) return listOf(
            // Not connected: a crossed-out cloud. Tapping it tries the server at once.
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(R.drawable.ic_cloud_off)
                .setDisplayName("Music server not connected")
                .setSessionCommand(onPhoneCommand)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        )
        val on = app.library.onPhoneOnly.value
        return listOf(
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(if (on) R.drawable.ic_on_phone else R.drawable.ic_all_music)
                .setDisplayName(if (on) "Showing only music on this phone" else "Showing all music")
                .setSessionCommand(onPhoneCommand)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        )
    }

    // ---- when the music server isn't connected ----

    /** Said once per disconnection, not for every skipped song. */
    private var toldSkipping = false

    /**
     * Moves to the next song in the queue (in play order, shuffle included) that
     * plays without the server, and plays it. False if there is none.
     */
    private fun skipToPlayable(): Boolean {
        val lib = app.library.library.value ?: return false
        val kept = app.server?.offline?.value?.ready.orEmpty()
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return false
        val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
        val start = player.currentMediaItemIndex
        var i = start
        while (true) {
            i = timeline.getNextWindowIndex(i, repeat, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || i == start) return false
            val track = BrowseTree.trackIdOf(player.getMediaItemAt(i).mediaId)?.let(lib::track) ?: continue
            if (!track.onServer || track.id in kept) break
        }
        player.seekTo(i, 0)
        player.prepare()
        player.play()
        return true
    }

    /** A short tone over the music: two quick beeps for connected, a low buzz for not connected. */
    private fun chime(connected: Boolean) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() ?: return
        tone.startTone(if (connected) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 600)
        scope.launch {
            try {
                delay(1_000)
            } finally {
                tone.release()
            }
        }
    }

    override fun onDestroy() {
        app.server?.setInUse("playing", false)
        saveQueue(sync = true)
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
        val saved = kotlinx.coroutines.withContext(Dispatchers.IO) { app.queueStore.load() }
        if (saved == null || player.mediaItemCount > 0) {
            val hadItems = player.mediaItemCount > 0
            restored = true
            if (hadItems) saveQueue()
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
            restored = true
        } else {
            restored = true
            saveQueue()
        }
    }

    private fun savedItems(saved: QueueStore.Saved, lib: Library): MediaSession.MediaItemsWithStartPosition? {
        val resolved = saved.trackIds.map(lib::track)
        val tracks = resolved.filterNotNull()
        if (tracks.isEmpty()) return null
        val hasCurrent = saved.index in resolved.indices && resolved[saved.index] != null
        val index = if (hasCurrent) resolved.take(saved.index).count { it != null } else 0
        return MediaSession.MediaItemsWithStartPosition(
            tracks.map { tree.queueItem(it, lib) },
            index.coerceIn(0, tracks.lastIndex),
            if (hasCurrent) saved.positionMs else 0,
        )
    }

    private fun saveQueue(sync: Boolean = false) {
        if (!restored || !::player.isInitialized) return
        val ids = List(player.mediaItemCount) { BrowseTree.trackIdOf(player.getMediaItemAt(it).mediaId) ?: "" }
        val snapshot = QueueStore.Saved(ids, player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode)
        if (sync) app.queueStore.save(snapshot)
        else scope.launch(Dispatchers.IO) { app.queueStore.save(snapshot) }
    }

    // ---- session callback ----

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (app.server == null) return super.onConnect(session, controller)
            val base = if (controller.isTrusted) MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            else MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_AND_LIBRARY_COMMANDS
            val commands = if (controller.isTrusted) base.buildUpon().add(onPhoneCommand).build() else base
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(if (controller.isTrusted) mediaButtons() else emptyList())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ON_PHONE_ACTION) return super.onCustomCommand(session, controller, customCommand, args)
            if (!controller.isTrusted) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            val server = app.server
            if (server != null && server.settings.value.enabled && !server.connected.value) server.checkNow()
            else app.library.setOnPhoneOnly(!app.library.onPhoneOnly.value)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            app.library.start()
            return Futures.immediateFuture(LibraryResult.ofItem(tree.root(), LibraryParams.Builder().setExtras(tree.rootExtras()).build()))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId, app.library.awaitShown())?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = tree.children(parentId, app.library.awaitShown())
            if (children == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItemList(children.page(page, pageSize), params)
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            // Logged to find why search didn't work in Android Auto (2026-11-17): who asks, what, how many.
            val started = android.os.SystemClock.elapsedRealtime()
            val count = tree.search(query, app.library.awaitShown()).size
            android.util.Log.i("LocalBeat", "Search from ${browser.packageName} (trusted ${browser.isTrusted}, v${browser.controllerVersion}): " +
                "\"$query\" -> $count results in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items = tree.search(query, app.library.awaitShown()).page(page, pageSize)
            android.util.Log.i("LocalBeat", "Search results to ${browser.packageName}: \"$query\" page $page size $pageSize -> ${items.size} items")
            LibraryResult.ofItemList(items, params)
        }

        /** "Add to queue": albums and artists add all their songs. */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future {
            val lib = app.library.awaitShown()
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
            val lib = app.library.awaitShown()
            if (mediaItems.size == 1) {
                val q = tree.resolve(mediaItems[0], lib)
                MediaSession.MediaItemsWithStartPosition(q.tracks.map { tree.queueItem(it, lib) }, q.startIndex, startPositionMs)
            } else {
                val tracks = mediaItems.map { item -> BrowseTree.trackIdOf(item.mediaId)?.let(lib::track) }
                val resolved = tracks.filterNotNull().map { tree.queueItem(it, lib) }
                val start = if (resolved.isEmpty()) C.INDEX_UNSET
                else tracks.take(startIndex.coerceAtLeast(0)).count { it != null }.coerceIn(0, resolved.lastIndex)
                MediaSession.MediaItemsWithStartPosition(resolved, start, startPositionMs)
            }
        }

        /** Play pressed with nothing loaded (Bluetooth, Android Auto, after a reboot): resume the saved queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = kotlinx.coroutines.withContext(Dispatchers.IO) { app.queueStore.load() }
                ?: throw UnsupportedOperationException("Nothing to resume")
            savedItems(saved, app.library.awaitLibrary()) ?: throw UnsupportedOperationException("Saved queue is gone")
        }
    }

    private companion object {
        const val ON_PHONE_ACTION = "com.jimboyo.localbeat.ON_PHONE"
    }

    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> =
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) this
        else drop((page.coerceAtLeast(0).toLong() * pageSize).coerceAtMost(size.toLong()).toInt()).take(pageSize)
}
