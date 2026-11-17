package com.jimboyo.localbeat.server

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.jimboyo.localbeat.LocalBeatApp
import com.jimboyo.localbeat.library.LibraryCache
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.playlists.Playlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
            connectionSound = prefs.getBoolean("connectionSound", true),
        )
    )
    override val settings: StateFlow<ServerSettings> = _settings.asStateFlow()
    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Off)
    override val status: StateFlow<ServerStatus> = _status.asStateFlow()
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    override val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    private val playlistsFile = File(app.filesDir, "server-playlists.json")
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    override val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    /** The server's playlists as last seen (the edits waiting are shown on top of them). */
    @Volatile private var base: List<Playlist> = emptyList()
    private val editsFile = File(app.filesDir, "server-playlist-edits.json")
    private var queue = EditQueue()
    @Volatile private var formPost = false
    private var sendJob: Job? = null
    /** Playlists made on the phone, by their stand-in ID, once the server gave the real one. */
    private val madeAs = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val _pendingEdits = MutableStateFlow(0)
    override val pendingEdits: StateFlow<Int> = _pendingEdits.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    override val playlistNotice: StateFlow<String?> = _notice.asStateFlow()

    @Volatile private var api: SubsonicApi? = null
    private var refreshJob: Job? = null

    // ---- the connection: connected or not, with a steadiness rule ----
    private val gate = ConnectionGate(STEADY_MS)
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    /** The last successful connection's details, shown again on reconnecting. */
    @Volatile private var lastConnected: ServerStatus.Connected? = null
    /** Whether the catalogue has been fetched from this server since it was set up. */
    @Volatile private var fetched = false
    /** When the catalogue was last fetched (elapsedRealtime), to fetch it again when it's old. */
    @Volatile private var lastFetch = 0L
    private val inUse = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Wakes the watcher early (network changed, a stream failed, came into use). */
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var watchJob: Job? = null

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
            updateWatch()
            scope.launch {
                loadSavedCatalogue()
                fetch()
            }
        } else {
            scope.launch { _tracks.value = pinnedOnly(); restorePlaylists() }
        }

        // Downloads waiting for Wi-Fi (or for the server) carry on when the network changes.
        connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { kick.trySend(Unit); resumeDownloads() }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { kick.trySend(Unit); resumeDownloads() }
            override fun onLost(network: android.net.Network) { kick.trySend(Unit) }
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
            putBoolean("connectionSound", s.connectionSound)
        }
        val old = _settings.value
        _settings.value = s
        // Only the mobile-data or sound switch changed: no need to reconnect.
        if (old != s && old.copy(useMobileData = s.useMobileData, connectionSound = s.connectionSound) == s && api != null) {
            kick.trySend(Unit)
            startDownloads()
            return
        }
        refreshJob?.cancel()
        gate.reset()
        fetched = false
        lastConnected = null
        _connected.value = false
        if (!s.enabled) {
            // Hide the server's songs, except the ones kept on the phone; keep the saved
            // list for when it's turned back on.
            api = null
            _status.value = ServerStatus.Off
            updateWatch()
            refreshJob = scope.launch { _tracks.value = pinnedOnly(); restorePlaylists() }
            return
        }
        if (s.address.isBlank()) {
            api = null
            _status.value = ServerStatus.Failed("Enter the server's address")
            return
        }
        api = apiFor(s)
        updateWatch()
        refreshJob = scope.launch {
            // Show the last known songs straight away, even if the server can't be reached now.
            if (_tracks.value.isEmpty()) loadSavedCatalogue()
            // Another server or user: not their playlists.
            restorePlaylists()
            fetch()
        }
    }

    /** The song list (and playlists) saved after the last successful fetch, if from this server and user. */
    private fun loadSavedCatalogue() {
        savedCatalogue()?.let { if (api != null) _tracks.value = it }
        restorePlaylists()
    }

    /** The playlists saved after the last fetch, if they're from this server and user. */
    private fun savedPlaylists(): List<Playlist> {
        if (prefs.getString("playlistsFor", null) != serverKey()) return emptyList()
        return runCatching { playlistsFromJson(playlistsFile.readText()) }.getOrDefault(emptyList())
    }

    private fun savePlaylists(lists: List<Playlist>) {
        runCatching {
            val tmp = File(playlistsFile.path + ".tmp")
            tmp.writeText(playlistsToJson(lists))
            if (!tmp.renameTo(playlistsFile)) throw IOException("can't replace ${playlistsFile.name}")
            prefs.edit { putString("playlistsFor", serverKey()) }
        }.onFailure { Log.w(TAG, "Saving the server's playlists: ${it.message}") }
    }

    // ---- editing playlists (docs/control.md step 5) ----

    /** The saved playlists and the edits still to send, for this server and user. */
    private fun restorePlaylists() {
        loadEdits()
        setBase(savedPlaylists())
    }

    override val playlistsOnServer: Boolean get() = _settings.value.let { it.enabled && it.address.isNotBlank() }

    @Synchronized
    private fun setBase(lists: List<Playlist>) {
        base = lists
        publishPlaylists()
    }

    /** The user (not the device) the playlists belong to: the owner of their own ones. */
    private fun me(): String = base.firstOrNull { !it.readOnly && it.owner.isNotEmpty() }?.owner ?: _settings.value.username

    @Synchronized
    private fun publishPlaylists() {
        _playlists.value = PlaylistEdits.applyAll(base, queue.edits, me())
        _pendingEdits.value = queue.edits.size
    }

    @Synchronized
    private fun loadEdits() {
        queue = if (prefs.getString("editsFor", null) != serverKey()) EditQueue()
        else runCatching { PlaylistEdits.fromJson(editsFile.readText()) }.getOrDefault(EditQueue())
    }

    @Synchronized
    private fun saveEdits() {
        runCatching {
            val tmp = File(editsFile.path + ".tmp")
            tmp.writeText(PlaylistEdits.toJson(queue))
            if (!tmp.renameTo(editsFile)) throw IOException("can't replace ${editsFile.name}")
            prefs.edit { putString("editsFor", serverKey()) }
        }.onFailure { Log.w(TAG, "Saving playlist changes: ${it.message}") }
    }

    private fun edit(e: PlaylistEdit) {
        synchronized(this) {
            queue = PlaylistEdits.enqueue(queue, e, base)
            saveEdits()
            publishPlaylists()
        }
        startSending()
    }

    override fun playlistId(id: String): String = madeAs[id] ?: id

    override fun createPlaylist(name: String, trackIds: List<String>): String {
        val id = PlaylistEdits.NEW + java.util.UUID.randomUUID().toString().take(8)
        edit(PlaylistEdit.Create(id, name.trim().ifEmpty { "Playlist" }, trackIds))
        return id
    }
    override fun addToPlaylist(id: String, trackIds: List<String>) { if (trackIds.isNotEmpty()) edit(PlaylistEdit.Add(playlistId(id), trackIds)) }
    override fun removeFromPlaylist(id: String, positions: List<Int>) { if (positions.isNotEmpty()) edit(PlaylistEdit.Remove(playlistId(id), positions)) }
    override fun setPlaylistSongs(id: String, trackIds: List<String>) = edit(PlaylistEdit.SetSongs(playlistId(id), trackIds))
    override fun renamePlaylist(id: String, name: String) { if (name.isNotBlank()) edit(PlaylistEdit.Rename(playlistId(id), name.trim())) }
    override fun deletePlaylist(id: String) = edit(PlaylistEdit.Delete(playlistId(id)))
    override fun clearPlaylistNotice() { _notice.value = null }

    @Synchronized
    private fun startSending() {
        if (queue.edits.isEmpty() || sendJob?.isActive == true) return
        sendJob = scope.launch { sendEdits() }
    }

    /** Sends the waiting edits in order while the server answers; stops (to retry later) when it doesn't. */
    private fun sendEdits() {
        val a = api ?: return
        if (!_connected.value) return
        val songIds = _tracks.value.associate { it.id to it.serverSongId }
        fun ids(trackIds: List<String>) = trackIds.mapNotNull { songIds[it] }
        while (true) {
            val e = synchronized(this) { queue.edits.firstOrNull() } ?: break
            try {
                if (e is PlaylistEdit.Create) {
                    val made = a.createPlaylist(e.name, ids(e.songs), formPost)
                    val p = SubsonicApi.toPlaylist(serverKey(), a.username, made) ?: throw IOException("no playlist ID")
                    synchronized(this) {
                        madeAs[e.playlistId] = p.id
                        queue = PlaylistEdits.created(queue, e.playlistId, p.id, p.changed)
                        base = listOf(p) + base
                    }
                } else {
                    val now = a.playlist(e.playlistId)
                    val known = synchronized(this) { queue.known[e.playlistId] }.orEmpty()
                    val name = base.firstOrNull { it.id == e.playlistId }?.name ?: "A playlist"
                    if (now == null || (known.isNotEmpty() && now.optString("changed") != known)) {
                        // Changed (or deleted) elsewhere: the server's version wins.
                        val theirs = now?.let { SubsonicApi.toPlaylist(serverKey(), a.username, it) }
                        synchronized(this) {
                            queue = PlaylistEdits.dropPlaylist(queue, e.playlistId)
                            base = if (theirs == null) base.filter { it.id != e.playlistId } else base.map { if (it.id == theirs.id) theirs else it }
                        }
                        if (e !is PlaylistEdit.Delete) _notice.value =
                            if (theirs == null) "“$name” is no longer on the music server; your changes to it weren't kept"
                            else "“$name” was changed elsewhere; the music server's version is kept"
                    } else {
                        when (e) {
                            is PlaylistEdit.Add -> a.updatePlaylist(e.playlistId, add = ids(e.songs), formPost = formPost)
                            is PlaylistEdit.Remove -> a.updatePlaylist(e.playlistId, remove = e.positions, formPost = formPost)
                            is PlaylistEdit.SetSongs -> a.setPlaylistSongs(e.playlistId, ids(e.songs), formPost)
                            is PlaylistEdit.Rename -> a.updatePlaylist(e.playlistId, name = e.name, formPost = formPost)
                            is PlaylistEdit.Delete -> a.deletePlaylist(e.playlistId)
                            is PlaylistEdit.Create -> Unit
                        }
                        val after = if (e is PlaylistEdit.Delete) null else a.playlist(e.playlistId)?.let { SubsonicApi.toPlaylist(serverKey(), a.username, it) }
                        synchronized(this) {
                            queue = PlaylistEdits.sent(queue, after?.changed)
                            base = if (after == null) base.filter { it.id != e.playlistId } else base.map { if (it.id == after.id) after else it }
                        }
                    }
                }
            } catch (x: SubsonicApi.SubsonicException) {
                // The server refused it (not ours, a song it doesn't have…): drop it and say so.
                Log.w(TAG, "Playlist change refused: ${x.message}")
                synchronized(this) { queue = PlaylistEdits.dropPlaylist(queue, e.playlistId) }
                _notice.value = "The music server didn't take a playlist change: ${x.message}"
            } catch (x: Exception) {
                Log.w(TAG, "Sending playlist changes: ${x.message}; will retry")
                return
            } finally {
                synchronized(this) {
                    saveEdits()
                    savePlaylists(base)
                    publishPlaylists()
                }
            }
        }
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
            // Playlists: a server without them (or a failure here) keeps the songs working.
            runCatching { a.playlists().mapNotNull { SubsonicApi.toPlaylist(serverKey(), a.username, it) } }
                .onSuccess { lists ->
                    if (api === a) {
                        formPost = "formPost" in extensions
                        setBase(lists)
                        savePlaylists(lists)
                    }
                }
                .onFailure { Log.w(TAG, "Server playlists: ${it.message}") }
            val connectedStatus = ServerStatus.Connected(ping.type, ping.version, tracks.size, extensions)
            lastConnected = connectedStatus
            fetched = true
            lastFetch = SystemClock.elapsedRealtime()
            _status.value = connectedStatus
            // A fresh, complete fetch: connected now (unless music can't come from it on this network).
            if (meteredBlock() == null) {
                gate.connectNow()
                _connected.value = true
            } else {
                gate.onFailure(SystemClock.elapsedRealtime())
                setConnected(false)
            }
            startDownloads()
            startSending()
            Log.i(TAG, "Server: ${ping.type} ${ping.version}, ${albums.size} albums, ${tracks.size} songs, extensions $extensions")
        } catch (e: Exception) {
            if (api !== a) return
            Log.w(TAG, "Server: ${e.message}")
            gate.onFailure(SystemClock.elapsedRealtime())
            _connected.value = false
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
            if (meteredBlock() != null) return setWaiting("Waiting for Wi-Fi to download ${plural(todo.size)}")
            if (!_connected.value) return setWaiting("Waiting for the music server to download ${plural(todo.size)}")
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
        meteredBlock()?.let { return it }
        // Known to be disconnected (not just still connecting at start-up).
        if (api != null && !_connected.value && _status.value !is ServerStatus.Connecting) return "The music server isn't connected."
        return null
    }

    /** Why the server can't be used on this network (mobile data with that turned off), or null. */
    private fun meteredBlock(): String? {
        if (_settings.value.useMobileData) return null
        // "Metered" covers mobile data and metered Wi-Fi (hotspots). Through WireGuard,
        // the VPN reports its underlying network's status.
        return if (connectivity.isActiveNetworkMetered)
            "Not on Wi-Fi. To play from the server on mobile data, turn on “Use mobile data” in Settings → Music server."
        else null
    }

    // ---- watching the connection ----

    override fun setInUse(key: String, inUse: Boolean) {
        val changed = if (inUse) this.inUse.add(key) else this.inUse.remove(key)
        if (changed) updateWatch()
        // Back on screen: albums added to the server since (a new rip) show up
        // without a manual refresh. The process outlives the screen (playback
        // service), so the fetch at start isn't enough.
        if (changed && inUse && key == "ui" && fetched && _connected.value &&
            SystemClock.elapsedRealtime() - lastFetch > STALE_MS
        ) refresh()
    }

    override fun checkNow() {
        scope.launch {
            val a = api ?: return@launch
            if (probe(a)) {
                gate.connectNow()
                if (!fetched) fetch() else setConnected(true)
            } else {
                gate.onFailure(SystemClock.elapsedRealtime())
                setConnected(false)
            }
        }
    }

    override fun reportStreamFailure() {
        kick.trySend(Unit)
    }

    /** Runs the watcher while the server is on and something needs it; stops it otherwise. */
    @Synchronized
    private fun updateWatch() {
        val run = api != null && inUse.isNotEmpty()
        if (!run) {
            watchJob?.cancel()
            watchJob = null
            return
        }
        if (watchJob?.isActive == true) kick.trySend(Unit)
        else watchJob = scope.launch { watch() }
    }

    /**
     * Connected: ask every 30 s; two failed answers 5 s apart disconnect.
     * Not connected: ask every 15 s; reconnect once the server has answered
     * steadily for [STEADY_MS]. Network changes and stream failures ask at once.
     */
    private suspend fun watch() {
        kick.tryReceive() // a fresh start asks straight away anyway
        while (true) {
            val a = api ?: return
            if (_status.value !is ServerStatus.Connecting) {
                val ok = probe(a)
                if (api !== a) continue
                if (ok) {
                    if (!_connected.value && gate.onSuccess(SystemClock.elapsedRealtime())) {
                        if (!fetched) fetch() else setConnected(true)
                    }
                } else if (_connected.value) {
                    // One lost answer isn't enough: ask again shortly.
                    delay(RECHECK_MS)
                    if (api === a && !probe(a)) {
                        gate.onFailure(SystemClock.elapsedRealtime())
                        setConnected(false)
                    }
                } else {
                    gate.onFailure(SystemClock.elapsedRealtime())
                }
            }
            withTimeoutOrNull(if (_connected.value) CONNECTED_EVERY_MS else DISCONNECTED_EVERY_MS) { kick.receive() }
        }
    }

    /** Is the server usable right now? No network call when the network itself rules it out. */
    private fun probe(a: SubsonicApi): Boolean = meteredBlock() == null && a.quickPing()

    private fun setConnected(on: Boolean) {
        if (on) {
            lastConnected?.let { _status.value = it }
            _connected.value = true
            startDownloads()
            startSending()
        } else {
            _connected.value = false
            _status.value = ServerStatus.Failed(
                if (meteredBlock() != null) "Not on Wi-Fi, and “Use mobile data” is off"
                else "Can't reach the music server"
            )
        }
        Log.i(TAG, "Server: ${if (on) "connected" else "not connected"}")
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
        /** How long the server must answer without a failure before reconnecting. */
        const val STEADY_MS = 120_000L
        const val CONNECTED_EVERY_MS = 30_000L
        const val DISCONNECTED_EVERY_MS = 15_000L
        const val RECHECK_MS = 5_000L
        /** The catalogue is fetched again on coming back on screen when older than this. */
        const val STALE_MS = 5 * 60_000L
    }
}
