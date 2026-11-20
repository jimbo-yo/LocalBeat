package com.jimboyo.localbeat.playback

import android.content.Context
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.server.MusicServer

/**
 * Seeking in music-server songs (Connected edition). Navidrome streams a song
 * it's still converting without seeking, so ExoPlayer can't seek in it. Once the
 * whole song has been fetched ([MusicServer.fetchWhole]) the server has it
 * converted, and reloading the song gives a stream that can seek: so a seek on
 * an unseekable server song reloads it at the new position.
 */
@OptIn(UnstableApi::class)
class ServerSeekPlayer(
    player: Player,
    private val context: Context,
    private val server: () -> MusicServer?,
    private val trackOf: (mediaId: String) -> Track?,
) : ForwardingPlayer(player) {

    // Media3 only offers "seek in this song" while the song is seekable, and a song the
    // server is still converting isn't: controllers would drop the seek before it got
    // here. So server songs always offer it, and the seek reloads or explains.

    private val listeners = HashMap<Player.Listener, Player.Listener>()

    private fun withServerSeek(commands: Player.Commands): Player.Commands =
        if (commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !currentIsServerSong()) commands
        else commands.buildUpon().add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build()

    private fun currentIsServerSong(): Boolean =
        server() != null && currentMediaItem?.let { trackOf(it.mediaId) }?.onServer == true

    override fun getAvailableCommands(): Player.Commands = withServerSeek(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean =
        super.isCommandAvailable(command) || (command == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM && currentIsServerSong())

    override fun addListener(listener: Player.Listener) {
        if (listener in listeners) return
        val wrapped = CommandsListener(listener)
        listeners[listener] = wrapped
        super.addListener(wrapped)
    }

    /**
     * Passes every event on, adding server seeking to the available commands. Written
     * out in full: Kotlin's `by` delegation skips Java default methods, and every
     * Player.Listener method is one, so a delegating wrapper silently drops events.
     */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    private inner class CommandsListener(private val l: Player.Listener) : Player.Listener {
        private var lastCommands: Player.Commands? = null

        private fun emitCommands(commands: Player.Commands) {
            val effective = withServerSeek(commands)
            if (effective != lastCommands) {
                lastCommands = effective
                l.onAvailableCommandsChanged(effective)
            }
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = emitCommands(availableCommands)
        override fun onEvents(player: Player, events: Player.Events) = l.onEvents(player, events)
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = l.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            l.onMediaItemTransition(mediaItem, reason)
            emitCommands(super@ServerSeekPlayer.getAvailableCommands())
        }
        override fun onTracksChanged(tracks: Tracks) = l.onTracksChanged(tracks)
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = l.onMediaMetadataChanged(mediaMetadata)
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = l.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = l.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = l.onLoadingChanged(isLoading)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = l.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = l.onPlayerStateChanged(playWhenReady, playbackState)
        override fun onPlaybackStateChanged(playbackState: Int) = l.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = l.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(reason: Int) = l.onPlaybackSuppressionReasonChanged(reason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = l.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = l.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = l.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: PlaybackException) = l.onPlayerError(error)
        override fun onPlayerErrorChanged(error: PlaybackException?) = l.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = l.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            l.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = l.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = l.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = l.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = l.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = l.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = l.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = l.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = l.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = l.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = l.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = l.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = l.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = l.onRenderedFirstFrame()
        override fun onCues(cues: List<Cue>) = l.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = l.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = l.onMetadata(metadata)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listeners.remove(listener) ?: listener)
    }

    override fun seekTo(positionMs: Long) {
        if (!reloadAt(currentMediaItemIndex, positionMs)) super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex != currentMediaItemIndex || !reloadAt(mediaItemIndex, positionMs)) super.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekToDefaultPosition() {
        if (!reloadAt(currentMediaItemIndex, 0L)) super.seekToDefaultPosition()
    }

    override fun seekForward() {
        val target = (currentPosition + seekForwardIncrement).let { t ->
            val d = duration
            if (d > 0) t.coerceAtMost(d) else t
        }
        if (!reloadAt(currentMediaItemIndex, target)) super.seekForward()
    }

    override fun seekBack() {
        val target = (currentPosition - seekBackIncrement).coerceAtLeast(0L)
        if (!reloadAt(currentMediaItemIndex, target)) super.seekBack()
    }

    /** True if handled: the seek either reloaded the song, or can't happen yet. */
    private fun reloadAt(index: Int, positionMs: Long): Boolean {
        if (isCurrentMediaItemSeekable) return false
        val s = server() ?: return false
        val item = currentMediaItem ?: return false
        val track = trackOf(item.mediaId)?.takeIf { it.onServer } ?: return false
        if (!s.wholeSongReady(track)) {
            Toast.makeText(context, "Still loading this song from the server; seeking works in a few seconds.", Toast.LENGTH_SHORT).show()
            return true
        }
        // A fresh URL (new auth salt) makes ExoPlayer load the song again, now seekable.
        val uri = s.streamUri(track) ?: return false
        replaceMediaItem(index, item.buildUpon().setUri(uri).build())
        super.seekTo(index, positionMs)
        return true
    }
}
