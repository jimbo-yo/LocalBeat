package com.jimboyo.localbeat.playlists

import android.content.Context
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.SortKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/** Playlists, one .m3u8 file each in the app's private storage. */
class PlaylistStore(private val context: Context) {
    private val dir = File(context.filesDir, "playlists").apply { mkdirs() }
    private val _playlists = MutableStateFlow(load())
    /** Sorted by name. */
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun get(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    fun create(name: String, entries: List<String> = emptyList()): Playlist =
        Playlist(UUID.randomUUID().toString().take(8), name.trim().ifEmpty { "Playlist" }, entries).also(::save)

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifEmpty { it.name }) }
    fun add(id: String, entries: List<String>) = update(id) { it.copy(entries = it.entries + entries) }
    fun removeAt(id: String, index: Int) = update(id) { p -> p.copy(entries = p.entries.filterIndexed { i, _ -> i != index }) }
    fun move(id: String, from: Int, to: Int) = update(id) { p ->
        if (from !in p.entries.indices || to !in p.entries.indices || from == to) return@update p
        p.copy(entries = p.entries.toMutableList().apply { add(to, removeAt(from)) })
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.m3u8").delete()
        _playlists.value = _playlists.value.filter { it.id != id }
    }

    /** Imports someone's .m3u/.m3u8. Returns the new playlist and how many entries matched no song here. */
    fun import(text: String, fileName: String?, lib: Library): Pair<Playlist, Int> {
        val parsed = M3u.parse(text)
        val tracks = parsed.paths.map { M3u.resolve(it, lib) }
        val name = parsed.name ?: fileName?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported playlist"
        val playlist = create(name, tracks.filterNotNull().map { Library.relativePath(it.path) })
        return playlist to tracks.count { it == null }
    }

    /** Writes a copy for sharing, named after the playlist. */
    fun exportFile(p: Playlist, lib: Library): File {
        val out = File(context.cacheDir, "share").apply { mkdirs() }
        val safe = p.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "playlist" }
        return File(out, "$safe.m3u8").apply { writeText(M3u.write(p.name, p.entries, lib)) }
    }

    @Synchronized
    private fun update(id: String, change: (Playlist) -> Playlist) {
        get(id)?.let { save(change(it)) }
    }

    @Synchronized
    private fun save(p: Playlist) {
        dir.mkdirs()
        val tmp = File(dir, "${p.id}.tmp")
        val target = File(dir, "${p.id}.m3u8")
        try {
            tmp.writeText(M3u.write(p.name, p.entries, null))
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return
            }
        } catch (e: Exception) {
            tmp.delete()
            return
        }
        _playlists.value = (_playlists.value.filter { it.id != p.id } + p).sortedBy { SortKey.of(it.name) }
    }

    private fun load(): List<Playlist> =
        dir.listFiles { f -> f.name.endsWith(".m3u8") }.orEmpty().mapNotNull { f ->
            runCatching {
                val parsed = M3u.parse(f.readText())
                Playlist(f.name.removeSuffix(".m3u8"), parsed.name ?: "Playlist", parsed.paths)
            }.getOrNull()
        }.sortedBy { SortKey.of(it.name) }
}
