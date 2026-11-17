package com.jimboyo.localbeat.playlists

import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.library.fold
import java.util.Locale

/**
 * Moving the phone's own playlists to the music server (docs/control.md step 5):
 * each entry (a path under Music/, an Opus copy of the server's FLAC) becomes
 * the server's song.
 */
object MoveToServer {
    class Result(val trackIds: List<String>, val notFound: List<String>)

    /**
     * Finds each entry's server song: the server song this phone copy stands in
     * for, else the server song at the same path (any extension), else one with
     * the same artist and title. Entries not found are listed.
     */
    fun serverSongs(p: Playlist, lib: Library, serverTracks: List<Track>): Result {
        val byPath = serverTracks.associateBy { pathKey(it.path.removePrefix("server:")) }
        val byName = serverTracks.groupBy { nameKey(it) }
        val ids = ArrayList<String>()
        val missing = ArrayList<String>()
        for (rel in p.entries) {
            val phone = lib.trackByRelativePath(rel)
            val id = phone?.let(lib::serverId)
                ?: byPath[pathKey(rel)]?.id
                ?: phone?.let { t -> byName[nameKey(t)]?.firstOrNull()?.id }
            if (id != null) ids += id else missing += rel
        }
        return Result(ids, missing)
    }

    /** "Artist/Album/01. Artist - Title.opus" -> "artist/album/01. artist - title" */
    private fun pathKey(path: String): String = path.substringBeforeLast('.').lowercase(Locale.ROOT)

    private fun nameKey(t: Track) = fold(t.artist.ifBlank { t.filedUnder }) + "\u0000" + fold(t.title)
}
