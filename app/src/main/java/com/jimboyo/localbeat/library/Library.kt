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
    /** Multi-value MOOD tags (';'-separated), from Vorbis MOOD or OpenSubsonic `moods`. */
    val mood: String = "",
    /**
     * Made from a verified CD rip: a phone file Studio's sync tagged `LOCALBEAT_SOURCE=CD`,
     * or a music-server song from the FLAC library (`suffix` flac). Never guessed otherwise.
     */
    val cdRip: Boolean = false,
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
    /** Every mood any of its songs is tagged with. */
    val moods: List<String> get() = tracks.flatMap { splitGenres(it.mood) }.distinctBy(::fold)
    /** The decade (e.g. 1980), or 0 if no year tag. */
    val decade: Int get() = if (year > 0) year / 10 * 10 else 0
    val decadeId: String? get() = if (decade > 0) "d$decade" else null
    val decadeLabel: String? get() = if (decade > 0) "${decade}s" else null
    /** Every song is from a verified CD rip (see [Track.cdRip]). */
    val cdRip: Boolean get() = tracks.isNotEmpty() && tracks.all { it.cdRip }
}

/** A named set of albums: a genre, a mood, or a decade. */
class AlbumGroup(val id: String, val name: String, val albums: List<Album>)

/** A broad genre with its child subgenres (built from multi-value GENRE tags). */
class BroadGenreGroup(
    val id: String,
    val name: String,
    val albums: List<Album>,
    /** Child subgenres under this broad genre, largest first then A–Z. */
    val subgenres: List<AlbumGroup>,
) {
    fun asGroup(): AlbumGroup = AlbumGroup(id, name, albums)
}

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
    val genres: List<String> get() = albums.flatMap { it.genres }.distinctBy(::fold)
    val moods: List<String> get() = albums.flatMap { it.moods }.distinctBy(::fold)
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
    /** Every genre and subgenre tag, sorted by name. Albums in each sorted by artist, then year. */
    val genres: List<AlbumGroup>
    /** Two-level genre hierarchy: broad genres with their child subgenres, sorted by name. */
    val broadGenres: List<BroadGenreGroup>
    /** Sorted by name. Albums in each sorted by artist, then year. */
    val moods: List<AlbumGroup>
    /** Oldest first. Albums in each sorted by year. */
    val decades: List<AlbumGroup>

    private val tracksById: Map<String, Track>
    private val albumsById: Map<String, Album>
    private val artistsById: Map<String, Artist>
    private val albumIdByTrack: Map<String, String>
    private val genresById: Map<String, AlbumGroup>
    private val broadGenresById: Map<String, BroadGenreGroup>
    private val moodsById: Map<String, AlbumGroup>
    private val decadesById: Map<String, AlbumGroup>

    init {
        this.tracks = tracks.sortedWith(compareBy { SortKey.of(it.title) })
        tracksById = tracks.associateBy { it.id }

        val albumGroups = groupAlbums(tracks)
        val albums = albumGroups.map { (key, group) ->
            val artistName = albumArtistOf(group).ifBlank { UNKNOWN_ARTIST }
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

        // Two-level genre hierarchy: AI-enriched albums put [BroadGenre, Subgenre1, ...]
        // in GENRE. Legacy single-genre albums whose sole genre is uniquely known as a
        // subgenre of one broad genre in the library fold under that broad genre.
        val multiPrimaryKeys = HashSet<String>()
        val subParents = HashMap<String, MutableSet<String>>()
        val genreDisplay = HashMap<String, MutableList<String>>()
        for (a in albums) {
            val gs = a.genres
            for (g in gs) genreDisplay.getOrPut(fold(g)) { ArrayList() } += g
            if (gs.size >= 2) {
                val pKey = fold(gs[0])
                multiPrimaryKeys += pKey
                for (sub in gs.drop(1)) {
                    val sKey = fold(sub)
                    if (sKey != pKey) subParents.getOrPut(sKey) { HashSet() } += pKey
                }
            }
        }
        fun genreName(key: String): String = titleCase(mostCommon(genreDisplay[key].orEmpty()))
        val broadAlbums = HashMap<String, MutableList<Album>>()
        val broadSubs = HashMap<String, MutableMap<String, MutableList<Album>>>()
        for (a in albums) {
            val gs = a.genres
            if (gs.isEmpty()) continue
            val firstKey = fold(gs[0])
            val foldedParent = if (gs.size == 1 && firstKey !in multiPrimaryKeys) subParents[firstKey]?.singleOrNull() else null
            val pKey = foldedParent ?: firstKey
            broadAlbums.getOrPut(pKey) { ArrayList() } += a
            val subs = if (foldedParent != null) listOf(firstKey) else gs.drop(1).map(::fold).filter { it != pKey }
            val subMap = broadSubs.getOrPut(pKey) { HashMap() }
            for (sKey in subs) subMap.getOrPut(sKey) { ArrayList() } += a
        }
        broadGenres = broadAlbums.map { (pKey, list) ->
            val sortedAlbums = list.distinct().sortedWith(byArtistThenYear)
            val subs = broadSubs[pKey].orEmpty().map { (sKey, sList) ->
                AlbumGroup("g" + shortHash(sKey), genreName(sKey), sList.distinct().sortedWith(byArtistThenYear))
            }.sortedWith(compareByDescending<AlbumGroup> { it.albums.size }.thenBy { SortKey.of(it.name) })
            BroadGenreGroup("g" + shortHash(pKey), genreName(pKey), sortedAlbums, subs)
        }.sortedBy { SortKey.of(it.name) }

        moods = albums.flatMap { a -> a.moods.map { it to a } }
            .groupBy { fold(it.first) }
            .map { (key, pairs) ->
                AlbumGroup("m" + shortHash(key), titleCase(mostCommon(pairs.map { it.first })), pairs.map { it.second }.distinct().sortedWith(byArtistThenYear))
            }
            .sortedBy { SortKey.of(it.name) }
        decades = albums.filter { it.year > 0 }
            .groupBy { it.year / 10 * 10 }
            .toSortedMap()
            .map { (decade, list) -> AlbumGroup("d$decade", "${decade}s", list.sortedWith(compareBy({ it.year }, { SortKey.of(it.artist) }))) }
        genresById = genres.associateBy { it.id }
        broadGenresById = broadGenres.associateBy { it.id }
        moodsById = moods.associateBy { it.id }
        decadesById = decades.associateBy { it.id }
    }

    fun genre(id: String): AlbumGroup? = genresById[id]
    fun broadGenre(id: String): BroadGenreGroup? = broadGenresById[id]
    fun mood(id: String): AlbumGroup? = moodsById[id]
    fun decade(id: String): AlbumGroup? = decadesById[id]
    fun genreByName(name: String): AlbumGroup? = genresById["g" + shortHash(fold(name))]
    fun broadGenreByName(name: String): BroadGenreGroup? = broadGenresById["g" + shortHash(fold(name))]
    fun moodByName(name: String): AlbumGroup? = moodsById["m" + shortHash(fold(name))]

    /** A track by its path relative to Music/ (as stored in playlists). */
    fun trackByRelativePath(rel: String): Track? = tracksByRelPath[rel.lowercase(Locale.ROOT)]
    fun tracksNamed(fileName: String): List<Track> = tracksByName[fileName.lowercase(Locale.ROOT)].orEmpty()
    private val tracksByRelPath by lazy { tracks.associateBy { relativePath(it.path).lowercase(Locale.ROOT) } }
    private val tracksByName by lazy { tracks.groupBy { it.path.substringAfterLast('/').lowercase(Locale.ROOT) } }

    /**
     * The server's track ID for a song (to put it in a server playlist): its own
     * if it's a server song, else the server song this phone copy stands in for;
     * null if the server doesn't have it.
     */
    fun serverId(track: Track): String? = if (track.onServer) track.id else serverIdByPhoneId[track.id]
    private val serverIdByPhoneId by lazy { standIns.entries.associate { (server, phone) -> phone to server } }

    /** A track by ID; a server song whose phone copy plays instead ([standIns]) gives that copy. */
    fun track(id: String): Track? = tracksById[id] ?: standIns[id]?.let(tracksById::get)
    fun album(id: String): Album? = albumsById[id]
    fun artist(id: String): Artist? = artistsById[id]
    fun albumOf(track: Track): Album? = albumIdByTrack[track.id]?.let(albumsById::get)
    fun albumOf(trackId: String): Album? = albumIdByTrack[standIns[trackId] ?: trackId]?.let(albumsById::get)

    /** Albums by when they arrived on the phone, newest first. */
    val recentlyAdded: List<Album> get() = albums.sortedByDescending { it.dateAdded }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val VARIOUS_ARTISTS = "Various Artists"
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

        /**
         * Songs into albums: one MusicBrainz release when tagged, else album artist +
         * album title ([albumKey]); then groups that share a folder and album title
         * are one album, unless that would join two releases. That keeps together an
         * old album where `localbeat enrich` gave the release ID to all but a bonus
         * track or two (Dido's "No Angel" and "Hunter"), and a compilation with no
         * album artist (one group per performer otherwise). Keyed by the release
         * when there is one, else the biggest group's key.
         */
        internal fun groupAlbums(tracks: List<Track>): Map<String, List<Track>> {
            val byKey = tracks.filter { it.album.isNotBlank() }.groupBy(::albumKey)
            val parent = HashMap<String, String>().apply { byKey.keys.forEach { put(it, it) } }
            fun root(k: String): String {
                var r = k
                while (parent.getValue(r) != r) r = parent.getValue(r)
                parent[k] = r
                return r
            }
            val releases = HashMap<String, Int>().apply { byKey.keys.forEach { put(it, if (it.startsWith("mb:")) 1 else 0) } }
            val byPlace = HashMap<String, MutableList<String>>()
            for ((key, group) in byKey) {
                for (place in group.mapNotNull(::placeKey).toSet()) byPlace.getOrPut(place) { ArrayList() } += key
            }
            for (keys in byPlace.values) {
                val distinct = keys.distinct()
                if (distinct.size < 2) continue
                fun dominantArtist(rootKey: String): String {
                    val members = byKey.entries.filter { root(it.key) == rootKey }.flatMap { it.value }
                    return fold(mostCommon(members.map { it.filedUnder }))
                }
                fun trackCount(rootKey: String): Int =
                    byKey.entries.filter { root(it.key) == rootKey }.sumOf { it.value.size }
                fun tryMerge(k1: String, k2: String) {
                    val (a, b) = root(k1) to root(k2)
                    if (a == b || releases.getValue(a) + releases.getValue(b) > 1) return
                    parent[b] = a
                    releases[a] = releases.getValue(a) + releases.getValue(b)
                }
                // Pass 1: always merge groups in the same folder that share the same artist
                // (e.g. a bonus track missing the MusicBrainz release ID).
                for (i in distinct.indices) for (j in i + 1 until distinct.size) {
                    val (a, b) = root(distinct[i]) to root(distinct[j])
                    if (a != b && dominantArtist(a).isNotEmpty() && dominantArtist(a) == dominantArtist(b)) {
                        tryMerge(a, b)
                    }
                }
                // Pass 2: merge different artists in the same folder only when it looks like a
                // real Various Artists compilation — NOT when 2+ different artists each have a
                // multi-track album making up most of the folder (e.g. iTunes' Compilations/Greatest Hits).
                val roots = distinct.map(::root).distinct()
                val totalTracks = roots.sumOf(::trackCount)
                val multiTrack = roots.filter { trackCount(it) >= 3 }
                val collidedAlbums = multiTrack.size >= 2 && multiTrack.sumOf(::trackCount) * 4 >= totalTracks * 3
                if (!collidedAlbums) {
                    for (k in roots.drop(1)) tryMerge(roots[0], k)
                }
            }
            return byKey.entries.groupBy({ root(it.key) }, { it }).values.associate { parts ->
                val key = parts.firstOrNull { it.key.startsWith("mb:") }?.key
                    ?: parts.maxWith(compareBy<Map.Entry<String, List<Track>>>({ it.value.size }, { it.key })).key
                key to parts.flatMap { it.value }
            }
        }

        /** Where a song is: its folder (relative to Music/, or the server's library) + album title. */
        private fun placeKey(t: Track): String? {
            val rel = if (t.path.startsWith("server:")) t.path.removePrefix("server:") else relativePath(t.path)
            val folder = rel.substringBeforeLast('/', "").lowercase(Locale.ROOT)
            return if (folder.isEmpty()) null else folder + "\u0000" + fold(t.album)
        }

        /** The album's artist: the most common, or "Various Artists" when none is most of it. */
        private fun albumArtistOf(group: List<Track>): String {
            val names = group.map { it.filedUnder }
            val counts = names.groupingBy { fold(it) }.eachCount()
            if (counts.size > 1 && counts.values.max() * 2 <= names.size) return VARIOUS_ARTISTS
            return mostCommon(names)
        }

        /** One album = one MusicBrainz release when tagged, else album artist + album title. */
        private fun albumKey(t: Track): String =
            if (t.musicBrainzAlbumId.isNotBlank()) "mb:" + t.musicBrainzAlbumId.lowercase(Locale.ROOT)
            else "tag:" + fold(t.filedUnder) + "\u0000" + fold(t.album)

        private fun mostCommon(values: List<String>): String =
            values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
    }
}

private val FOLD_SPACES = Regex("\\s+")

/**
 * Folds a name for grouping and matching: case, curly vs straight quotes, and
 * runs of whitespace don't distinguish two names.
 */
fun fold(s: String): String = s.trim().lowercase(Locale.ROOT)
    .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
    .replace(FOLD_SPACES, " ")

object SortKey {
    /** Sort ignoring case, quotes and a leading "The ". */
    fun of(s: String): String = fold(s).removePrefix("the ").trimStart('\'', '"', '(', '[')
}

fun shortHash(s: String): String {
    val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
    return d.take(8).joinToString("") { "%02x".format(it) }
}
