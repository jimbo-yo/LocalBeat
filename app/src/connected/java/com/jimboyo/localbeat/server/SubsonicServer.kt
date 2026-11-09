package com.jimboyo.localbeat.server

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.jimboyo.localbeat.LocalBeatApp
import com.jimboyo.localbeat.library.LibraryCache
import com.jimboyo.localbeat.library.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The music server over OpenSubsonic (Connected edition). Off until the user
 * turns it on in Settings. The catalogue is fetched on start and on request, and
 * kept in a file so the library shows the server's albums straight away.
 */
class SubsonicServer(private val app: LocalBeatApp) : MusicServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val cacheFile = File(app.filesDir, "server-library.bin")

    private val _settings = MutableStateFlow(
        ServerSettings(
            enabled = prefs.getBoolean("enabled", false),
            address = prefs.getString("address", "").orEmpty(),
            username = prefs.getString("username", "").orEmpty(),
            password = prefs.getString("password", "").orEmpty(),
            useMobileData = prefs.getBoolean("mobileData", false),
        )
    )
    override val settings: StateFlow<ServerSettings> = _settings.asStateFlow()
    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Off)
    override val status: StateFlow<ServerStatus> = _status.asStateFlow()
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    override val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    @Volatile private var api: SubsonicApi? = null
    private var refreshJob: Job? = null

    init {
        val s = _settings.value
        if (s.enabled && s.address.isNotBlank()) {
            api = apiFor(s)
            scope.launch {
                LibraryCache.read(cacheFile)?.let { _tracks.value = it }
                fetch()
            }
        }
    }

    override fun update(settings: ServerSettings) {
        val s = settings.copy(address = settings.address.trim(), username = settings.username.trim())
        prefs.edit {
            putBoolean("enabled", s.enabled)
            putString("address", s.address)
            putString("username", s.username)
            putString("password", s.password)
            putBoolean("mobileData", s.useMobileData)
        }
        val old = _settings.value
        _settings.value = s
        // Only the mobile-data switch changed: no need to reconnect.
        if (old != s && old.copy(useMobileData = s.useMobileData) == s && api != null) return
        refreshJob?.cancel()
        if (!s.enabled) {
            api = null
            _status.value = ServerStatus.Off
            _tracks.value = emptyList()
            cacheFile.delete()
            return
        }
        if (s.address.isBlank()) {
            api = null
            _status.value = ServerStatus.Failed("Enter the server's address")
            return
        }
        api = apiFor(s)
        refresh()
    }

    override fun refresh() {
        if (api == null) return
        refreshJob?.cancel()
        refreshJob = scope.launch { fetch() }
    }

    private fun fetch() {
        val a = api ?: return
        _status.value = ServerStatus.Connecting
        try {
            val ping = a.ping()
            val extensions = if (ping.openSubsonic) a.extensions() else emptySet()
            val albums = a.albums()
            val songs = a.songs()
            val tracks = SubsonicApi.toTracks(serverKey(), albums, songs)
            if (api !== a) return // settings changed meanwhile
            _tracks.value = tracks
            LibraryCache.write(cacheFile, tracks)
            _status.value = ServerStatus.Connected(ping.type, ping.version, tracks.size, extensions)
            Log.i(TAG, "Server: ${ping.type} ${ping.version}, ${albums.size} albums, ${tracks.size} songs, extensions $extensions")
        } catch (e: Exception) {
            if (api !== a) return
            Log.w(TAG, "Server: ${e.message}")
            _status.value = ServerStatus.Failed(
                when {
                    e is SubsonicApi.SubsonicException && (e.code == 40 || e.code == 41) -> "Wrong username or password"
                    e is SubsonicApi.SubsonicException -> e.message ?: "The server refused"
                    e is java.net.UnknownHostException || e is java.net.ConnectException ||
                        e is java.net.SocketTimeoutException || e is java.net.NoRouteToHostException ->
                        "Can't reach the server. Is WireGuard on, and the address right?"
                    else -> e.message ?: "Can't connect"
                }
            )
        }
    }

    private fun apiFor(s: ServerSettings) = SubsonicApi(s.address, s.username, s.password)

    /** Keeps song IDs apart when the address or user changes. */
    private fun serverKey() = _settings.value.let { "${SubsonicApi.normalize(it.address)}|${it.username}" }

    override fun streamUri(track: Track): Uri? =
        api?.takeIf { track.onServer }?.let { Uri.parse(it.streamUrl(track.serverSongId)) }

    private val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)

    override fun streamBlockedReason(): String? {
        if (_settings.value.useMobileData) return null
        // "Metered" covers mobile data and metered Wi-Fi (hotspots). Through WireGuard,
        // the VPN reports its underlying network's status.
        return if (connectivity.isActiveNetworkMetered)
            "Not on Wi-Fi. To play from the server on mobile data, turn on “Use mobile data” in Settings → Music server."
        else null
    }

    /** Songs fetched whole recently (the server's conversion cache is size-limited, so only the last few). */
    private val wholeSongs = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private var wholeJob: Job? = null

    override fun fetchWhole(track: Track) {
        val a = api ?: return
        if (!track.onServer || wholeSongReady(track) || streamBlockedReason() != null) return
        wholeJob?.cancel()
        wholeJob = scope.launch {
            val c = URL(a.streamUrl(track.serverSongId)).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8_000
                c.readTimeout = 60_000
                if (c.responseCode != 200) return@launch
                val buf = ByteArray(64 * 1024)
                c.inputStream.use { input ->
                    while (true) {
                        if (!isActive) return@launch
                        if (input.read(buf) < 0) break
                    }
                }
                synchronized(wholeSongs) {
                    wholeSongs += track.serverSongId
                    while (wholeSongs.size > 10) wholeSongs.remove(wholeSongs.first())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fetching ${track.title}: ${e.message}")
            } finally {
                c.disconnect()
            }
        }
    }

    override fun wholeSongReady(track: Track) = track.serverSongId in wholeSongs

    override fun coverBytes(track: Track): ByteArray? {
        val a = api ?: return null
        if (track.serverCoverArt.isEmpty()) return null
        val c = URL(a.coverUrl(track.serverCoverArt, 1200)).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8_000
            c.readTimeout = 20_000
            if (c.responseCode != 200 || c.contentType?.startsWith("image/") != true) null
            else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val TAG = "LocalBeat"
    }
}
