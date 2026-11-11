package com.jimboyo.localbeat.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.jimboyo.localbeat.tags.OggTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

/**
 * Finds the audio files under Music/ and reads their tags.
 *
 * MediaStore is used only to *find* files (and for durations). Its title/artist
 * columns are never used: Android's scanner guesses tag encodings and garbles
 * some UTF-8 titles, the bug that started this app. Tags are read from the files.
 */
class LibraryRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val cacheFile = File(context.filesDir, "library.bin")
    private var started = false

    /** The phone's own songs (null until loaded) and the music server's (Connected edition). */
    @Volatile private var phoneTracks: List<Track>? = null
    @Volatile private var serverTracks: List<Track> = emptyList()

    private val _library = MutableStateFlow<Library?>(null)
    /** Everything: the phone's songs and the server's. Null until the first load (from the cache, else a scan) finishes. */
    val library: StateFlow<Library?> = _library.asStateFlow()

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val _onPhoneOnly = MutableStateFlow(prefs.getBoolean("onPhoneOnly", false))
    /** The lists show only what plays without the server: the phone's songs and the server songs kept on it. */
    val onPhoneOnly: StateFlow<Boolean> = _onPhoneOnly.asStateFlow()
    /** Server songs kept on the phone (Connected edition). */
    @Volatile private var keptIds: Set<String> = emptySet()

    private val _shown = MutableStateFlow<Library?>(null)
    /**
     * What the lists (and Android Auto) show: [library], or with [onPhoneOnly] just
     * what's on the phone. Playback looks songs up in [library], so a server song
     * already queued still plays.
     */
    val shown: StateFlow<Library?> = _shown.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Bumped after each scan, so cover art caches know to look again. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts the first load if needed: the cached library right away, then a scan
     * for changes, then a re-index of Music/ and another (cheap) scan.
     *
     * The re-index matters: files copied with `adb push` are left "pending" in
     * MediaStore (owned by the shell), and pending files are invisible to every
     * other app until something rescans them.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            LibraryCache.read(cacheFile)?.let(::publish)
            mutex.withLock {
                scan()
                if (hasPermission()) {
                    indexMusicFolder()
                    scan()
                }
            }
        }
    }

    suspend fun awaitLibrary(): Library {
        start()
        return library.filterNotNull().first()
    }

    suspend fun awaitShown(): Library {
        start()
        return shown.filterNotNull().first()
    }

    fun setOnPhoneOnly(on: Boolean) {
        if (_onPhoneOnly.value == on) return
        _onPhoneOnly.value = on
        prefs.edit().putBoolean("onPhoneOnly", on).apply()
        scope.launch { republish() }
    }

    /** The server songs kept on the phone changed. */
    fun setKept(ids: Set<String>) {
        if (ids == keptIds) return
        keptIds = ids
        if (_onPhoneOnly.value) republish()
    }

    @Synchronized
    private fun republish() {
        phoneTracks?.let(::publish)
        _generation.value++
    }

    /**
     * Rescans the library. With [indexFirst], first asks Android to index Music/
     * so files that were just copied over are found too.
     */
    fun rescan(indexFirst: Boolean = true) {
        start()
        scope.launch {
            mutex.withLock {
                if (indexFirst && hasPermission()) indexMusicFolder()
                scan()
            }
        }
    }

    private suspend fun scan() {
        if (!hasPermission()) {
            if (phoneTracks == null) publish(emptyList())
            return
        }
        _scanning.value = true
        try {
            val started = System.currentTimeMillis()
            val known = phoneTracks?.associateBy { it.path }.orEmpty()
            val rows = queryMediaStore()
            val io = Dispatchers.IO.limitedParallelism(4)
            val parsed = java.util.concurrent.atomic.AtomicInteger()
            val tracks = rows.map { row ->
                scope.async(io) {
                    val old = known[row.path]
                    if (old != null && old.modified == row.modified && old.size == row.size) {
                        old.copy(mediaStoreId = row.id, durationMs = row.durationMs, dateAdded = row.dateAdded)
                    } else {
                        parsed.incrementAndGet()
                        readTrack(row)
                    }
                }
            }.awaitAll()
            publish(tracks)
            LibraryCache.write(cacheFile, tracks)
            _generation.value++
            Log.i(TAG, "Scan: ${tracks.size} tracks, $parsed read, ${System.currentTimeMillis() - started} ms")
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            if (phoneTracks == null) publish(emptyList())
        } finally {
            _scanning.value = false
        }
    }

    /** The music server's catalogue changed (or the server was turned off: empty). */
    @Synchronized
    fun setServerTracks(tracks: List<Track>) {
        if (tracks === serverTracks) return
        serverTracks = tracks
        phoneTracks?.let(::publish)
        _generation.value++
    }

    @Synchronized
    private fun publish(phone: List<Track>) {
        phoneTracks = phone
        val all = Library(Library.merge(phone, serverTracks))
        _library.value = all
        _shown.value =
            if (!_onPhoneOnly.value || all.tracks.none { it.onServer }) all
            else Library(all.tracks.filter { !it.onServer || it.id in keptIds })
    }

    private class Row(val id: Long, val path: String, val modified: Long, val size: Long, val durationMs: Long, val dateAdded: Long)

    private fun queryMediaStore(): List<Row> {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).path
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED,
        )
        val rows = ArrayList<Row>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.DATA} LIKE ?", arrayOf("$musicDir/%"), null,
        )?.use { c ->
            while (c.moveToNext()) {
                rows += Row(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5))
            }
        }
        return rows
    }

    private fun readTrack(row: Row): Track {
        val tags = runCatching { readTags(row) }
            .onFailure { Log.w(TAG, "Can't read tags: ${row.path}", it) }
            .getOrNull() ?: TrackTags()
        val file = File(row.path)
        return Track(
            id = Track.idForPath(row.path),
            mediaStoreId = row.id,
            path = row.path,
            modified = row.modified,
            size = row.size,
            durationMs = row.durationMs,
            dateAdded = row.dateAdded,
            title = tags.title.ifBlank { TrackTags.titleFromFileName(file.name) },
            artist = tags.artist,
            albumArtist = tags.albumArtist,
            album = tags.album,
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
            discTotal = tags.discTotal,
            year = tags.year,
            genre = tags.genre,
            musicBrainzAlbumId = tags.musicBrainzAlbumId,
            hasEmbeddedArt = tags.hasEmbeddedArt,
        )
    }

    private fun readTags(row: Row): TrackTags {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, row.id)
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                if (OggTags.isOgg(ch)) return OggTags.read(ch)?.comments?.let(TrackTags::from) ?: TrackTags()
            }
            // MP3 / AAC: their tag formats declare their own encodings, so Android's reader is fine.
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(pfd.fileDescriptor)
                fun get(key: Int) = mmr.extractMetadata(key).orEmpty().trim()
                return TrackTags(
                    title = get(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = get(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    albumArtist = get(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    album = get(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    trackNumber = TrackTags.leadingInt(get(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)) ?: 0,
                    discNumber = TrackTags.leadingInt(get(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)) ?: 1,
                    year = TrackTags.year(get(MediaMetadataRetriever.METADATA_KEY_YEAR)),
                    genre = get(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    hasEmbeddedArt = mmr.embeddedPicture != null,
                )
            } finally {
                mmr.release()
            }
        }
    }

    /** Asks MediaStore to (re)index Music/, e.g. after files were copied with adb. */
    private suspend fun indexMusicFolder() {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).path
        withTimeoutOrNull(120_000) {
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(dir), null) { _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    companion object {
        private const val TAG = "LocalBeat"
    }
}
