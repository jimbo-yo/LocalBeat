package com.jimboyo.localbeat.server

import android.net.Uri
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.playlists.Playlist
import kotlinx.coroutines.flow.StateFlow

import org.json.JSONObject

/** Embedded userspace WireGuard tunnel config (docs/onboarding.md). */
data class EmbeddedTunnelConfig(
    val privateKey: String,
    val address: String,
    val serverKey: String,
    val presharedKey: String = "",
    val endpointPublic: String? = null,
    val endpointLan: String? = null,
    val allowedIps: String = "10.8.0.1/32",
) {
    fun hasEndpoint(): Boolean = !endpointPublic.isNullOrBlank() || !endpointLan.isNullOrBlank()

    fun toJson(): String = JSONObject().apply {
        put("private_key", privateKey)
        put("address", address)
        put("server_key", serverKey)
        put("preshared_key", presharedKey)
        if (!endpointPublic.isNullOrBlank()) put("endpoint_public", endpointPublic)
        if (!endpointLan.isNullOrBlank()) put("endpoint_lan", endpointLan)
        put("allowed_ips", allowedIps)
    }.toString()

    override fun toString(): String =
        "EmbeddedTunnelConfig(address=$address, endpointLan=$endpointLan, endpointPublic=$endpointPublic)"

    companion object {
        fun fromJson(text: String?): EmbeddedTunnelConfig? {
            if (text.isNullOrBlank()) return null
            return runCatching {
                val j = JSONObject(text)
                val cfg = EmbeddedTunnelConfig(
                    privateKey = j.optString("private_key", ""),
                    address = j.optString("address", ""),
                    serverKey = j.optString("server_key", ""),
                    presharedKey = j.optString("preshared_key", ""),
                    endpointPublic = j.optString("endpoint_public", "").ifBlank { null },
                    endpointLan = j.optString("endpoint_lan", "").ifBlank { null },
                    allowedIps = j.optString("allowed_ips", "10.8.0.1/32"),
                )
                cfg.takeIf { it.privateKey.isNotBlank() && it.address.isNotBlank() && it.serverKey.isNotBlank() && it.hasEndpoint() }
            }.getOrNull()
        }
    }
}

enum class TunnelLinkState { Off, Connecting, ConnectedLan, ConnectedPublic, Stalled }

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
    /** Embedded WireGuard tunnel config (null for direct/legacy connections). */
    val tunnel: EmbeddedTunnelConfig? = null,
) {
    override fun toString(): String =
        "ServerSettings(enabled=$enabled, address=$address, username=$username, password=***, useMobileData=$useMobileData, connectionSound=$connectionSound, tunnel=${tunnel != null})"
}

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
    val tunnelState: StateFlow<TunnelLinkState>
    /** The server's catalogue as tracks; empty while off. Kept between launches. */
    val tracks: StateFlow<List<Track>>
    /**
     * The user's playlists on the server, then those shared with them and public
     * ones ([Playlist.onServer]; songs as server track IDs). Fetched with the
     * catalogue and kept between launches, so they're there without the server
     * too (their songs kept on the phone play).
     */
    val playlists: StateFlow<List<Playlist>>

    /** The server is set up and on: playlists live there (new ones are made there). */
    val playlistsOnServer: Boolean
    /** Playlist changes made on the phone not yet sent (the server out of reach). */
    val pendingEdits: StateFlow<Int>
    /** Something to tell the user about their playlists (changed elsewhere…), until cleared. */
    val playlistNotice: StateFlow<String?>
    fun clearPlaylistNotice()

    // Changes to the user's own server playlists: shown at once, sent now or
    // when the server can next be reached. Songs as server track IDs
    // ([com.jimboyo.localbeat.library.Library.serverId]); positions count from 0.
    /** Returns the new playlist's ID (a stand-in until the server gives one: see [playlistId]). */
    fun createPlaylist(name: String, trackIds: List<String>): String
    fun addToPlaylist(id: String, trackIds: List<String>)
    fun removeFromPlaylist(id: String, positions: List<Int>)
    /** The whole list in a new order. */
    fun setPlaylistSongs(id: String, trackIds: List<String>)
    fun renamePlaylist(id: String, name: String)
    fun deletePlaylist(id: String)
    /** The current ID of a playlist made on the phone (the server's, once it has been sent). */
    fun playlistId(id: String): String
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
    /** Claims a `localbeat://join/...` invite and connects immediately; returns the assigned device name. */
    suspend fun claimInvite(inviteText: String): Result<String>
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
