package com.jimboyo.localbeat.server

import android.net.Uri
import com.jimboyo.localbeat.library.Track
import kotlinx.coroutines.flow.StateFlow

/** How to reach the music server (Settings → Music server). */
data class ServerSettings(
    val enabled: Boolean = false,
    /** e.g. `http://10.8.0.1:4533` */
    val address: String = "",
    val username: String = "",
    val password: String = "",
    /** Stream and download music over mobile data too (and metered Wi-Fi such as a hotspot). Off: Wi-Fi only. */
    val useMobileData: Boolean = false,
    /** A short tone when the server connects or disconnects while music is playing. */
    val connectionSound: Boolean = true,
)

/** Thrown when the player asks for a server song that isn't allowed right now (e.g. on mobile data). */
class StreamBlockedException(message: String) : java.io.IOException(message)

sealed interface ServerStatus {
    data object Off : ServerStatus
    data object Connecting : ServerStatus
    /** [name] and [version] as the server reports them; [extensions]: the OpenSubsonic extensions it declares. */
    data class Connected(val name: String, val version: String, val songs: Int, val extensions: Set<String>) : ServerStatus
    data class Failed(val message: String) : ServerStatus
}

/**
 * Server songs kept on the phone ("pinned"), so they play without the server:
 * no Wi-Fi, no WireGuard, no coverage. Track IDs throughout.
 */
data class OfflineState(
    /** Songs the user asked to keep, in the order asked. */
    val pinned: Set<String> = emptySet(),
    /** Pinned songs whose file is on the phone. */
    val ready: Set<String> = emptySet(),
    /** The song downloading now, and how far (0–1; an estimate when the server doesn't say the size). */
    val downloading: String? = null,
    val progress: Float = 0f,
    /** Why downloads are on hold (no Wi-Fi, server off or unreachable), or null. */
    val waiting: String? = null,
    /** Space the kept songs take. */
    val bytes: Long = 0,
) {
    val queued: Int get() = pinned.count { it !in ready }
}

/**
 * The user's own music server: Navidrome, or later LocalBeat's own server
 * (both speak OpenSubsonic; extras only when the server declares them). Only the
 * Connected edition has an implementation (see `Edition`); in the offline
 * edition this is null and the APK has no network code.
 *
 * Off by default. While on, the server's songs join the library ([tracks],
 * merged by [com.jimboyo.localbeat.library.LibraryRepository]), marked with
 * [Track.onServer].
 */
interface MusicServer {
    val settings: StateFlow<ServerSettings>
    val status: StateFlow<ServerStatus>
    /** The server's catalogue as tracks; empty while off. Kept between launches. */
    val tracks: StateFlow<List<Track>>
    /**
     * Whether server songs can play right now: the server is on, answering, and
     * allowed (not on mobile data with that turned off). While false, the lists
     * show only what's on the phone and server songs in the queue are skipped.
     * Drops at once on a failure; comes back only once the server has answered
     * steadily for a while (see [ConnectionGate]), so patchy coverage doesn't
     * flip it in and out.
     */
    val connected: StateFlow<Boolean>

    /**
     * The connection is only watched while something needs it: the app on screen
     * ("ui") or music set to play ("playing"). An idle phone sends nothing.
     */
    fun setInUse(key: String, inUse: Boolean)
    /** "Try now": check the server at once and connect without waiting if it answers. */
    fun checkNow()
    /** A server song failed to load or stopped mid-stream: check the connection now. */
    fun reportStreamFailure()

    /** Saves the settings and (re)connects, or turns the server off. */
    fun update(settings: ServerSettings)
    /** Fetches the catalogue again. */
    fun refresh()

    /** Where the player plays a server song from: its file if pinned, else the stream. */
    fun streamUri(track: Track): Uri?
    /**
     * Checked as each song loads: a queued stream whose song has since been pinned
     * plays from the file; a pinned file that was removed streams again.
     */
    fun resolve(uri: Uri): Uri

    val offline: StateFlow<OfflineState>
    /** Keeps these songs on the phone (the server's ones; downloaded in the background). */
    fun pin(tracks: List<Track>)
    /** Removes these songs from the phone (or stops downloading them). */
    fun unpin(trackIds: Collection<String>)
    /**
     * Why music can't come from the server right now (on mobile data with it
     * turned off), or null if it can. Checked as each song starts, so a queue
     * built on Wi-Fi stops when the phone moves to mobile data.
     */
    fun streamBlockedReason(): String?

    /**
     * Fetches a whole server song in the background as it starts playing, so the
     * server finishes converting it. Navidrome streams a song it's still
     * converting without seeking; once converted it serves it with seeking.
     */
    fun fetchWhole(track: Track)
    /** True once [fetchWhole] finished for this song: reloading it now gives a seekable stream. */
    fun wholeSongReady(track: Track): Boolean
    /** A server song's cover image, or null. Blocking: call off the main thread. */
    fun coverBytes(track: Track): ByteArray?
}
