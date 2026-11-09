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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.jimboyo.localbeat.tags.OggTags
import java.io.File
import java.io.FileInputStream
import java.io.IOException
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
    private val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)

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

    // Pinned songs: files named by track ID in filesDir/offline (".part" while downloading).
    private val offlineDir = File(app.filesDir, "offline").apply { mkdirs() }
    private val _offline = MutableStateFlow(OfflineState())
    override val offline: StateFlow<OfflineState> = _offline.asStateFlow()
    private var downloadJob: Job? = null

    init {
        val pinned = LinkedHashSet(prefs.getStringSet("pinned", emptySet()).orEmpty())
        // Leftovers: half-downloaded files, songs unpinned while the app was stopped.
        offlineDir.listFiles()?.forEach { if (it.name !in pinned) it.delete() }
        _offline.value = OfflineState(pinned, pinned.filter { File(offlineDir, it).exists() }.toSet())
        countBytes()

        val s = _settings.value
        if (s.enabled && s.address.isNotBlank()) {
            api = apiFor(s)
            scope.launch {
                loadSavedCatalogue()
                fetch()
            }
        } else {
            scope.launch { _tracks.value = pinnedOnly() }
        }

        // Downloads waiting for Wi-Fi (or for the server) carry on when the network changes.
        connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = resumeDownloads()
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = resumeDownloads()
        })
    }

    private fun resumeDownloads() {
        if (_offline.value.waiting != null) startDownloads()
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
        if (old != s && old.copy(useMobileData = s.useMobileData) == s && api != null) {
            startDownloads()
            return
        }
        refreshJob?.cancel()
        if (!s.enabled) {
            // Hide the server's songs, except the ones kept on the phone; keep the saved
            // list for when it's turned back on.
            api = null
            _status.value = ServerStatus.Off
            refreshJob = scope.launch { _tracks.value = pinnedOnly() }
            return
        }
        if (s.address.isBlank()) {
            api = null
            _status.value = ServerStatus.Failed("Enter the server's address")
            return
        }
        api = apiFor(s)
        refreshJob = scope.launch {
            // Show the last known songs straight away, even if the server can't be reached now.
            if (_tracks.value.isEmpty()) loadSavedCatalogue()
            fetch()
        }
    }

    /** The song list saved after the last successful fetch, if it's from this server and user. */
    private fun loadSavedCatalogue() {
        savedCatalogue()?.let { if (api != null) _tracks.value = it }
    }

    private fun savedCatalogue(): List<Track>? =
        if (prefs.getString("savedFor", null) != serverKey()) null else LibraryCache.read(cacheFile)

    /** With the server off: just the songs kept on the phone. */
    private fun pinnedOnly(): List<Track> {
        val ready = _offline.value.ready
        return if (ready.isEmpty()) emptyList() else savedCatalogue()?.filter { it.id in ready }.orEmpty()
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
            prefs.edit { putString("savedFor", serverKey()) }
            _status.value = ServerStatus.Connected(ping.type, ping.version, tracks.size, extensions)
            startDownloads()
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

    override fun streamUri(track: Track): Uri? {
        if (!track.onServer) return null
        offlineFile(track.id)?.let { return Uri.fromFile(it) }
        return api?.let { Uri.parse(it.streamUrl(track.serverSongId)) }
    }

    override fun resolve(uri: Uri): Uri {
        when (uri.scheme) {
            "http", "https" -> {
                val songId = uri.getQueryParameter("id") ?: return uri
                val id = SubsonicApi.trackId(serverKey(), songId)
                return offlineFile(id)?.let(Uri::fromFile) ?: uri
            }
            "file" -> {
                val file = File(uri.path ?: return uri)
                if (file.parentFile != offlineDir || file.exists()) return uri
                // Removed from the phone since it was queued: stream it instead.
                val track = _tracks.value.firstOrNull { it.id == file.name } ?: return uri
                return api?.let { Uri.parse(it.streamUrl(track.serverSongId)) } ?: uri
            }
            else -> return uri
        }
    }

    private fun offlineFile(id: String): File? =
        if (id in _offline.value.ready) File(offlineDir, id).takeIf { it.exists() } else null

    // ---- keeping songs on the phone ----

    override fun pin(tracks: List<Track>) {
        val ids = tracks.filter { it.onServer }.map { it.id }
        if (ids.isEmpty()) return
        updatePinned { it + ids }
        startDownloads()
    }

    override fun unpin(trackIds: Collection<String>) {
        val ids = trackIds.toSet()
        if (ids.none { it in _offline.value.pinned }) return
        updatePinned { it - ids }
        if (_offline.value.downloading in ids) downloadJob?.cancel()
        scope.launch {
            ids.forEach { File(offlineDir, it).delete(); File(offlineDir, "$it.part").delete() }
            countBytes()
            if (api == null) _tracks.value = _tracks.value.filter { it.id !in ids }
            startDownloads()
        }
    }

    private fun updatePinned(change: (Set<String>) -> Set<String>) {
        val st = _offline.updateAndGet { s ->
            val pinned = LinkedHashSet(change(s.pinned))
            s.copy(pinned = pinned, ready = s.ready.filterTo(HashSet()) { it in pinned })
        }
        prefs.edit { putStringSet("pinned", HashSet(st.pinned)) }
    }

    private fun countBytes() = _offline.update { s -> s.copy(bytes = s.ready.sumOf { File(offlineDir, it).length() }) }

    @Synchronized
    private fun startDownloads() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch { runDownloads() }
    }

    /** Downloads pinned songs one at a time until they're all here or something's in the way. */
    private suspend fun runDownloads() {
        while (true) {
            val st = _offline.value
            val todo = st.pinned.filter { it !in st.ready }
            if (todo.isEmpty()) return setWaiting(null)
            val a = api ?: return setWaiting("Turn the music server on to download ${plural(todo.size)}")
            if (streamBlockedReason() != null) return setWaiting("Waiting for Wi-Fi to download ${plural(todo.size)}")
            val byId = _tracks.value.associateBy { it.id }
            // Not in the server's list (yet, or any more): nothing to fetch for now.
            val track = todo.firstNotNullOfOrNull(byId::get) ?: return setWaiting(null)
            try {
                download(a, track)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Downloading ${track.title}: ${e.message}")
                return setWaiting("Can't reach the music server; ${plural(todo.size)} to download")
            }
        }
    }

    private fun setWaiting(reason: String?) = _offline.update { it.copy(waiting = reason) }

    private suspend fun download(a: SubsonicApi, t: Track) {
        _offline.update { it.copy(downloading = t.id, progress = 0f, waiting = null) }
        val part = File(offlineDir, "${t.id}.part")
        val c = URL(a.streamUrl(t.serverSongId)).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            if (c.contentType?.startsWith("application/json") == true || c.contentType?.startsWith("text/") == true) {
                throw IOException("the server sent an error instead of the song")
            }
            // A song being converted comes without a size: estimate from 160 kbps.
            val expected = c.contentLengthLong.takeIf { it > 0 } ?: (t.durationMs * 20).coerceAtLeast(1)
            var done = 0L
            var shown = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - shown > 128 * 1024) {
                            shown = done
                            _offline.update { it.copy(progress = (done.toFloat() / expected).coerceAtMost(0.99f)) }
                        }
                    }
                }
            }
            if (done == 0L) throw IOException("empty")
            if (t.id !in _offline.value.pinned || !part.renameTo(File(offlineDir, t.id))) return
            _offline.update { it.copy(ready = it.ready + t.id) }
            countBytes()
        } finally {
            c.disconnect()
            part.delete()
            _offline.update { if (it.downloading == t.id) it.copy(downloading = null, progress = 0f) else it }
        }
    }

    private fun plural(n: Int) = if (n == 1) "1 song" else "$n songs"

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
        if (!track.onServer || wholeSongReady(track) || offlineFile(track.id) != null || streamBlockedReason() != null) return
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
        // A kept song may carry its cover (LocalBeat's server keeps it; Navidrome's conversion doesn't).
        offlineFile(track.id)?.let { f ->
            runCatching {
                FileInputStream(f).channel.use { ch -> if (OggTags.isOgg(ch)) OggTags.read(ch, wantPicture = true)?.picture?.data else null }
            }.getOrNull()?.let { return it }
        }
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
