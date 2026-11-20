package com.jimboyo.localbeat.playlists

import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Track

/**
 * A playlist: a name and its songs. A phone playlist ([PlaylistStore]) lists
 * paths relative to Music/ (the phone-sync layout); one from the music server
 * ([onServer], Connected edition) lists track IDs of server songs, which play
 * the phone's copy when there is one ([Library.track]).
 */
data class Playlist(
    val id: String,
    val name: String,
    val entries: List<String>,
    val onServer: Boolean = false,
    /** Server playlists: the user it belongs to. */
    val owner: String = "",
    /** Server playlists someone else shared (or made public): play only. */
    val readOnly: Boolean = false,
    val comment: String = "",
    /** Server playlists: when the server last changed it (to notice changes made elsewhere). */
    val changed: String = "",
) {
    /** The playlist's songs that can play now (entries for songs not here are kept but skipped). */
    fun tracks(lib: Library): List<Track> = entries.mapNotNull { resolve(it, lib) }

    /** One entry's song, if it can play now. */
    fun resolve(entry: String, lib: Library): Track? = if (onServer) lib.track(entry) else lib.trackByRelativePath(entry)
}

/**
 * Extended M3U, UTF-8 (.m3u8). Paths are relative to Music/, so a playlist shared
 * to the laptop works against ~/Music/phone as well.
 */
object M3u {
    private fun oneLine(s: String) = s.replace('\r', ' ').replace('\n', ' ')

    fun write(name: String, entries: List<String>, lib: Library?): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(oneLine(name)).append('\n')
        for (rel in entries) {
            val t = lib?.trackByRelativePath(rel)
            if (t != null) {
                append("#EXTINF:").append(t.durationMs / 1000).append(',')
                append(oneLine(t.artist.ifBlank { t.filedUnder })).append(" - ").append(oneLine(t.title)).append('\n')
            }
            // Paths starting with '#' would be read back as M3U comments; './' is standard relative syntax.
            val safeRel = oneLine(rel).let { if (it.startsWith("#")) "./$it" else it }
            append(safeRel).append('\n')
        }
    }

    class Parsed(val name: String?, val paths: List<String>)

    fun parse(text: String): Parsed {
        var name: String? = null
        val paths = ArrayList<String>()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:") -> name = line.removePrefix("#PLAYLIST:").trim().ifEmpty { null }
                line.startsWith("#") -> Unit
                else -> paths += if (line.startsWith("./")) line.removePrefix("./") else line
            }
        }
        return Parsed(name, paths)
    }

    /**
     * Matches a path from someone else's playlist to a track: tries it as given
     * (absolute, Windows-style, relative to the playlist), then ever-shorter tails
     * of it, then the bare file name if only one track has it.
     */
    fun resolve(path: String, lib: Library): Track? {
        val slashed = path.replace('\\', '/')
        // Only file:// URLs are percent-encoded; plain paths may contain a real '%'.
        val clean = if (!slashed.startsWith("file://")) slashed
        else slashed.removePrefix("file://").let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
        val parts = clean.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return null
        lib.trackByRelativePath(Library.relativePath(clean))?.let { return it }
        for (start in parts.indices) {
            lib.trackByRelativePath(parts.drop(start).joinToString("/"))?.let { return it }
        }
        return lib.tracksNamed(parts.last()).singleOrNull()
    }
}
