package com.jimboyo.localbeat.ui

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.jimboyo.localbeat.playback.BrowseTree
import com.jimboyo.localbeat.playback.PlaybackService

/** One entry in the play queue. [key] stays unique even when a song is queued twice. */
data class QueueEntry(val key: String, val trackId: String)

/** The UI's view of the playback service, as Compose state. */
class PlayerConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    var controller by mutableStateOf<MediaController?>(null)
        private set

    var currentTrackId by mutableStateOf<String?>(null)
        private set
    var currentIndex by mutableStateOf(-1)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var queue by mutableStateOf<List<QueueEntry>>(emptyList())
        private set
    /** Incremented on seeks / discontinuities so paused seek bars refresh immediately. */
    var positionRevision by mutableIntStateOf(0)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) =
            sync(player, timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED), positionChanged = events.contains(Player.EVENT_POSITION_DISCONTINUITY))
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            if (future !== f) return@addListener
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            c.addListener(listener)
            controller = c
            sync(c)
            pending?.let { pending = null; playItem(it) }
        }, MoreExecutors.directExecutor())
    }

    /** A request that arrived before the controller connected (a voice search opening the app). */
    private var pending: MediaItem? = null

    /** Plays a request item, e.g. a voice search ([com.jimboyo.localbeat.playback.VoiceRequest]); waits for the connection if needed. */
    fun playItem(item: MediaItem) {
        val c = controller ?: run { pending = item; return }
        c.shuffleModeEnabled = false
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    fun release() {
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
    }

    private fun sync(p: Player, timelineChanged: Boolean = false, positionChanged: Boolean = false) {
        currentTrackId = p.currentMediaItem?.mediaId?.let(BrowseTree::trackIdOf)
        currentIndex = p.currentMediaItemIndex
        isPlaying = p.isPlaying
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        durationMs = p.duration.coerceAtLeast(0)
        if (positionChanged) positionRevision++
        val seen = HashMap<String, Int>()
        val q = List(p.mediaItemCount) { i ->
            val id = BrowseTree.trackIdOf(p.getMediaItemAt(i).mediaId).orEmpty()
            val n = seen.merge(id, 1, Int::plus)!!
            QueueEntry("$id#$n", id)
        }
        if (timelineChanged || q != queue) queue = q
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0

    /**
     * Plays a browse-tree item: an album, artist, playlist, or a track in its list (see [BrowseTree]).
     * [shuffled]: its songs go into the queue already mixed, song by song, so the queue shows
     * what plays (shuffle mode would keep album order on screen and start with the first song).
     */
    fun play(mediaId: String, shuffled: Boolean = false) {
        val c = controller ?: return
        c.shuffleModeEnabled = false
        c.setMediaItem(item(if (shuffled) BrowseTree.SHUFFLED + mediaId else mediaId))
        c.prepare()
        c.play()
    }

    /** Plays an explicit list of tracks (e.g. a cross-filtered genre + decade selection). */
    fun playTracks(tracks: List<com.jimboyo.localbeat.library.Track>, shuffled: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.shuffleModeEnabled = false
        c.setMediaItems((if (shuffled) tracks.shuffled() else tracks).map { item(BrowseTree.TRACK + it.id) }, 0, 0L)
        c.prepare()
        c.play()
    }

    /** Queues an explicit list of tracks right after the current song. */
    fun playNextTracks(tracks: List<com.jimboyo.localbeat.library.Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) return playTracks(tracks)
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { item(BrowseTree.TRACK + it.id) })
    }

    /** Queues an explicit list of tracks at the end of the queue. */
    fun addTracksToQueue(tracks: List<com.jimboyo.localbeat.library.Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) return playTracks(tracks)
        c.addMediaItems(tracks.map { item(BrowseTree.TRACK + it.id) })
    }

    /** Queues a song, album, playlist… right after the current song. */
    fun playNext(mediaId: String) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(mediaId)
        c.addMediaItem(c.currentMediaItemIndex + 1, item(mediaId))
    }

    /** Queues a song, album, playlist… at the end. */
    fun addToQueue(mediaId: String) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(mediaId)
        c.addMediaItem(item(mediaId))
    }

    fun skipTo(index: Int) = controller?.let {
        if (index in 0 until it.mediaItemCount) { it.seekToDefaultPosition(index); it.play() }
    }
    fun removeAt(index: Int) = controller?.let {
        if (index in 0 until it.mediaItemCount) it.removeMediaItem(index)
    }
    fun move(from: Int, to: Int) = controller?.let {
        if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount && from != to) it.moveMediaItem(from, to)
    }
    /** Empties the queue except the song that's playing. */
    fun clearUpcoming() {
        val c = controller ?: return
        if (c.currentMediaItemIndex + 1 < c.mediaItemCount) c.removeMediaItems(c.currentMediaItemIndex + 1, c.mediaItemCount)
        if (c.currentMediaItemIndex > 0) c.removeMediaItems(0, c.currentMediaItemIndex)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        positionRevision++
    }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun item(mediaId: String) = MediaItem.Builder().setMediaId(mediaId).build()
}
