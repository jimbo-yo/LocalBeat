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
)

sealed interface ServerStatus {
    data object Off : ServerStatus
    data object Connecting : ServerStatus
    /** [name] and [version] as the server reports them; [extensions]: the OpenSubsonic extensions it declares. */
    data class Connected(val name: String, val version: String, val songs: Int, val extensions: Set<String>) : ServerStatus
    data class Failed(val message: String) : ServerStatus
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

    /** Saves the settings and (re)connects, or turns the server off. */
    fun update(settings: ServerSettings)
    /** Fetches the catalogue again. */
    fun refresh()

    /** Where the player streams a server song from. */
    fun streamUri(track: Track): Uri?
    /** A server song's cover image, or null. Blocking: call off the main thread. */
    fun coverBytes(track: Track): ByteArray?
}
