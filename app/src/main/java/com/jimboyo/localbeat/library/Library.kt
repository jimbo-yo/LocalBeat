package com.jimboyo.localbeat.library

import java.security.MessageDigest
import java.util.Locale

/** One audio file and its tags. Pure data, so the library logic is testable off-device. */
data class Track(
    /** Stable ID derived from the file path (survives MediaStore re-indexing). */
    val id: String,
    val mediaStoreId: Long,
    val path: String,
    val modified: Long,
    val size: Long,
    val durationMs: Long,
    val dateAdded: Long,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val trackNumber: Int,
    val discNumber: Int,
    val discTotal: Int,
    val year: Int,
    val genre: String,
    val musicBrainzAlbumId: String,
    val hasEmbeddedArt: Boolean,
    /** On a music server, not the phone: the server's song ID (empty for files on the phone). */
    val serverSongId: String = "",
    /** The server's cover art ID for this song, if it has one. */
    val serverCoverArt: String = "",
) {
    /** Who the album is filed under: the album artist, else the track artist. */
    val filedUnder: String get() = albumArtist.ifBlank { artist }
    /** Only on the music server (Connected edition), not on the phone. */
    val onServer: Boolean get() = serverSongId.isNotEmpty()

    companion object {
        fun idForPath(path: String) = "t" + shortHash(path)
    }
}

class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val year: Int,
    /** Sorted by disc, then track number. */
    val tracks: List<Track>,
) {
    val discCount: Int get() = maxOf(tracks.maxOfOrNull { it.discNumber } ?: 1, tracks.maxOfOrNull { it.discTotal } ?: 1)
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
    /** Most recent time any of the album's files was added to the phone. */
    val dateAdded: Long get() = tracks.maxOf { it.dateAdded }
    /** Every genre any of its songs is tagged with. */
    val genres: List<String> get() = tracks.flatMap { splitGenres(it.genre) }.distinctBy(::fold)
}

/** A named set of albums: a genre or a decade. */
class AlbumGroup(val id: String, val name: String, val albums: List<Album>)

/** "Rock; Alternative" or "Rock\u0000Alternative" (multiple tags) -> [Rock, Alternative] */
fun splitGenres(genre: String): List<String> =
    genre.split(';', '\u0000').map { it.trim() }.filter { it.isNotEmpty() }

class Artist(
    val id: String,
    val name: String,
    /** Sorted by year, then title. */
    val albums: List<Album>,
    /** Songs filed under this artist that have no album tag. */
    val looseTracks: List<Track>,
) {
    val tracks: List<Track> get() = albums.flatMap { it.tracks } + looseTracks
}

class Library(
    tracks: List<Track>,
    /** Server track ID → the phone copy that plays instead ([Library.standIns]). */
    val standIns: Map<String, String> = emptyMap(),
) {
    /** Sorted by title. */
    val tracks: List<Track>
    /** Sorted by title. */
    val albums: List<Album>
    /** Sorted by name. */
    val artists: List<Artist>
    /** Sorted by name. Albums in each sorted by artist, then year. */
    val genres: List<AlbumGroup>
    /** Oldest first. Albums in each sorted by year. */
    val decades: List<AlbumGroup>

    private val tracksById: Map<String, Track>
    private val albumsById: Map<String, Album>
    private val artistsById: Map<String, Artist>
    private val albumIdByTrack: Map<String, String>
    private val genresById: Map<String, AlbumGroup>
    private val decadesById: Map<String, AlbumGroup>

    init {
        this.tracks = tracks.sortedWith(compareBy { SortKey.of(it.title) })
        tracksById = tracks.associateBy { it.id }

        val albumGroups = tracks.filter { it.album.isNotBlank() }.groupBy(::albumKey)
        val albums = albumGroups.map { (key, group) ->
            val artistName = mostCommon(group.map { it.filedUnder }).ifBlank { UNKNOWN_ARTIST }
            Album(
                id = "a" + shortHash(key),
                title = mostCommon(group.map { it.album }),
                artist = artistName,
                artistId = artistId(artistName),
                year = group.maxOf { it.year },
                tracks = group.sortedWith(compareBy<Track>({ it.discNumber }, { it.trackNumber }, { SortKey.of(it.title) })),
            )
        }
        this.albums = albums.sortedWith(compareBy({ SortKey.of(it.title) }, { SortKey.of(it.artist) }))
        albumsById = albums.associateBy { it.id }
        albumIdByTrack = buildMap { albums.forEach { a -> a.tracks.forEach { put(it.id, a.id) } } }

        // Artists are grouped on a folded name, so "Guns N' Roses" and "Guns N’ Roses"
        // (tagged differently by different rippers) are one artist.
        val loose = tracks.filter { it.album.isBlank() }
        val byArtist = (albums.map { it.artist } + loose.map { it.filedUnder.ifBlank { UNKNOWN_ARTIST } })
            .groupBy { artistId(it) }
        artists = byArtist.map { (id, names) ->
            Artist(
                id = id,
                name = mostCommon(names),
                albums = albums.filter { it.artistId == id }.sortedWith(compareBy({ it.year }, { SortKey.of(it.title) })),
                looseTracks = loose.filter { artistId(it.filedUnder.ifBlank { UNKNOWN_ARTIST }) == id }
                    .sortedWith(compareBy { SortKey.of(it.title) }),
            )
        }.sortedWith(compareBy { SortKey.of(it.name) })
        artistsById = artists.associateBy { it.id }

        val byArtistThenYear = compareBy<Album>({ SortKey.of(it.artist) }, { it.year }, { SortKey.of(it.title) })
        genres = albums.flatMap { a -> a.genres.map { it to a } }
            .groupBy { fold(it.first) }
            .map { (key, pairs) ->
                AlbumGroup("g" + shortHash(key), titleCase(mostCommon(pairs.map { it.first })), pairs.map { it.second }.distinct().sortedWith(byArtistThenYear))
            }
            .sortedBy { SortKey.of(it.name) }
        decades = albums.filter { it.year > 0 }
            .groupBy { it.year / 10 * 10 }
            .toSortedMap()
            .map { (decade, list) -> AlbumGroup("d$decade", "${decade}s", list.sortedWith(compareBy({ it.year }, { SortKey.of(it.artist) }))) }
        genresById = genres.associateBy { it.id }
        decadesById = decades.associateBy { it.id }
    }

    fun genre(id: String): AlbumGroup? = genresById[id]
    fun decade(id: String): AlbumGroup? = decadesById[id]

    /** A track by its path relative to Music/ (as stored in playlists). */
    fun trackByRelativePath(rel: String): Track? = tracksByRelPath[rel.lowercase(Locale.ROOT)]
    fun tracksNamed(fileName: String): List<Track> = tracksByName[fileName.lowercase(Locale.ROOT)].orEmpty()
    private val tracksByRelPath by lazy { tracks.associateBy { relativePath(it.path).lowercase(Locale.ROOT) } }
    private val tracksByName by lazy { tracks.groupBy { it.path.substringAfterLast('/').lowercase(Locale.ROOT) } }

    /** A track by ID; a server song whose phone copy plays instead ([standIns]) gives that copy. */
    fun track(id: String): Track? = tracksById[id] ?: standIns[id]?.let(tracksById::get)
    fun album(id: String): Album? = albumsById[id]
    fun artist(id: String): Artist? = artistsById[id]
    fun albumOf(track: Track): Album? = albumIdByTrack[track.id]?.let(albumsById::get)
    fun albumOf(trackId: String): Album? = albumIdByTrack[trackId]?.let(albumsById::get)

    /** Albums by when they arrived on the phone, newest first. */
    val recentlyAdded: List<Album> get() = albums.sortedByDescending { it.dateAdded }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        val EMPTY = Library(emptyList())

        fun artistId(name: String) = "r" + shortHash(fold(name))

        /** "/storage/emulated/0/Music/A/B/x.opus" -> "A/B/x.opus" */
        fun relativePath(path: String): String =
            path.substringAfter("/Music/", path.substringAfterLast('/'))

        /** Genre tags from MusicBrainz are lowercase ("alternative rock"); show them capitalised. */
        private fun titleCase(s: String): String =
            if (s != s.lowercase(Locale.ROOT)) s
            else s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }

        /**
         * The phone's songs plus the server's, each song once: a server song that's
         * also on the phone (same album and disc/track number, or same title) is
         * dropped, so the phone's copy plays.
         */
        fun merge(phone: List<Track>, server: List<Track>): List<Track> {
            if (server.isEmpty()) return phone
            val have = HashSet<String>()
            for (t in phone) have += songKeys(t)
            return phone + server.filter { s -> songKeys(s).none { it in have } }
        }

        /**
         * For the server songs [merge] drops: server track ID → the phone copy's
         * ID, so something that names the server song (a server playlist) plays
         * the phone's copy.
         */
        fun standIns(phone: List<Track>, server: List<Track>): Map<String, String> {
            if (server.isEmpty() || phone.isEmpty()) return emptyMap()
            val byKey = HashMap<String, String>()
            for (t in phone) for (k in songKeys(t)) byKey.putIfAbsent(k, t.id)
            val out = HashMap<String, String>()
            for (s in server) songKeys(s).firstNotNullOfOrNull(byKey::get)?.let { out[s.id] = it }
            return out
        }

        /** A song's identities: by MusicBrainz release when tagged *and* by artist + album title (old rips lack the ID). */
        private fun songKeys(t: Track): List<String> {
            if (t.album.isBlank()) return listOf("loose:" + fold(t.filedUnder) + "\u0000" + fold(t.title))
            val albums = listOfNotNull(
                t.musicBrainzAlbumId.takeIf { it.isNotBlank() }?.let { "mb:" + it.lowercase(Locale.ROOT) },
                "tag:" + fold(t.filedUnder) + "\u0000" + fold(t.album),
            )
            return albums.flatMap { a ->
                listOfNotNull(a + "\u0000title:" + fold(t.title), if (t.trackNumber > 0) a + "\u0000#${t.discNumber}.${t.trackNumber}" else null)
            }
        }

        /** One album = one MusicBrainz release when tagged, else album artist + album title. */
        private fun albumKey(t: Track): String =
            if (t.musicBrainzAlbumId.isNotBlank()) "mb:" + t.musicBrainzAlbumId.lowercase(Locale.ROOT)
            else "tag:" + fold(t.filedUnder) + "\u0000" + fold(t.album)

        private fun mostCommon(values: List<String>): String =
            values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
    }
}

/**
 * Folds a name for grouping and matching: case, curly vs straight quotes, and
 * runs of whitespace don't distinguish two names.
 */
fun fold(s: String): String = s.trim().lowercase(Locale.ROOT)
    .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
    .replace(Regex("\\s+"), " ")

object SortKey {
    /** Sort ignoring case, quotes and a leading "The ". */
    fun of(s: String): String = fold(s).removePrefix("the ").trimStart('\'', '"', '(', '[')
}

fun shortHash(s: String): String {
    val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
    return d.take(8).joinToString("") { "%02x".format(it) }
}
