package com.jimboyo.localbeat.playback

import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.jimboyo.localbeat.art.ArtStore
import com.jimboyo.localbeat.library.Album
import com.jimboyo.localbeat.library.AlbumGroup
import com.jimboyo.localbeat.library.Artist
import com.jimboyo.localbeat.library.BroadGenreGroup
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Search
import com.jimboyo.localbeat.library.SortKey
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.library.fold
import com.jimboyo.localbeat.playlists.Playlist

/**
 * The browse tree shared by Android Auto, other media browsers and LocalBeat's own UI.
 *
 * Media IDs:
 * - `root`; the shared entry points as Android Auto's 4 tabs (docs/ui-refinement.md):
 *   `explore`, `library`, `playlists`, `moods` (search has Auto's own button)
 *   - `explore`: Shuffle all, Random album, Recently added (+ See all), Genres, Decades
 *   - `library`: `albums`, `artists`, `songs`
 *     (artists & albums automatically use A–Z letter buckets when > [AZ_THRESHOLD] items)
 *   - `playlists`, `moods`: one folder each
 *   - `genres`, `decades`, `recent`: the full lists
 * - `album:<id>`, `artist:<id>`, `playlist:<id>`, `genre:<id>`, `mood:<id>`, `decade:<id>`;
 *   every one but an album starts with a "Shuffle <name>" item
 * - `shuffleall`, `randomalbum`, `shuffleartist:<id>`, `shuffleplaylist:<id>`,
 *   `shufflemood:<id>`, `shufflegenre:<id>`, `shuffledecade:<id>`
 * - `artistall:<id>`: all an artist's songs in order (the list loose songs play from)
 * - `shuffled:<any list ID>`: that list's songs, mixed song by song (the Shuffle buttons)
 * - `browse`, `more`: old IDs, kept as aliases of `explore` and `library`
 * - `t:<id>`: one track (what the player's queue holds)
 * - `<list>/<position>|t:<id>`: a track tapped in a list; plays the list from there.
 */
class BrowseTree(
    private val art: ArtStore?,
    private val playlists: () -> List<Playlist>,
    /** Where a music-server song streams from (Connected edition). */
    private val serverUri: (Track) -> android.net.Uri? = { null },
    /** Resolves temporary offline-created playlist IDs to their synced server IDs. */
    private val resolvePlaylistId: (String) -> String = { it },
) {

    private fun findPlaylist(id: String): Playlist? {
        val resolved = resolvePlaylistId(id)
        return playlists().firstOrNull { it.id == resolved || it.id == id }
    }

    // ---- browsing ----

    fun root(): MediaItem = folder(ROOT, "LocalBeat", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** Defaults for the whole tree, plus the flag that turns on search in Android Auto. */
    fun rootExtras() = Bundle().apply {
        putBoolean(SEARCH_SUPPORTED, true)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    fun children(parentId: String, lib: Library): List<MediaItem>? {
        val (kind, id) = split(parentId)
        return when (kind) {
            ROOT -> listOf(
                folder(EXPLORE, "Explore", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, grid = true),
                folder(LIBRARY, "Library", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folder(PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(MOODS, "Moods", MediaMetadata.MEDIA_TYPE_FOLDER_GENRES),
            )
            EXPLORE, BROWSE -> exploreChildren(lib)
            LIBRARY, MORE -> listOf(
                folder(ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, subtitle = plural(lib.albums.size, "album"), grid = lib.albums.size <= AZ_THRESHOLD),
                folder(ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, subtitle = plural(lib.artists.size, "artist")),
                folder(SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, subtitle = plural(lib.tracks.size, "song")),
            )
            MOODS -> lib.moods
                .sortedWith(compareByDescending<AlbumGroup> { it.albums.size }.thenBy { SortKey.of(it.name) })
                .map { groupItem(MOOD, it, MediaMetadata.MEDIA_TYPE_GENRE) }
            GENRES -> sortedBroadGenres(lib).map { broadGenreItem(it) }
            DECADES -> lib.decades.map { groupItem(DECADE, it, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS) }
            PLAYLISTS -> playlists().map { playlistItem(it, lib) }
            RECENT -> lib.recentlyAdded.take(RECENT_LIMIT).map { albumItem(it) }
            SONGS -> listItems(SONGS, lib.tracks, lib)
            ARTISTS -> if (lib.artists.size > AZ_THRESHOLD) artistBuckets(lib.artists) else lib.artists.map { artistItem(it) }
            ARTISTS_LETTER -> lib.artists.filter { Search.indexLetter(it.name).toString() == id }.map { artistItem(it) }
            ALBUMS -> if (lib.albums.size > AZ_THRESHOLD) albumBuckets(ALBUMS_LETTER, lib.albums) else lib.albums.map { albumItem(it) }
            ALBUMS_LETTER -> lib.albums.filter { Search.indexLetter(it.title).toString() == id }.map { albumItem(it) }
            MOOD -> lib.mood(id)?.let { m ->
                listOf(shuffleItem(SHUFFLE_MOOD + m.id, m.name, m.albums.sumOf { it.tracks.size })) + m.albums.map { albumItem(it) }
            }
            GENRE -> genreChildren(id, lib)
            GENRE_ALBUMS -> lib.broadGenre(id)?.albums?.let { albums ->
                if (albums.size > AZ_THRESHOLD) albumBuckets(GENRE_ALBUMS_LETTER + "${id}_", albums)
                else albums.map { albumItem(it) }
            }
            GENRE_ALBUMS_LETTER -> {
                val genreId = id.substringBefore('_')
                val letter = id.substringAfter('_', "")
                lib.broadGenre(genreId)?.albums
                    ?.filter { Search.indexLetter(it.title).toString() == letter }
                    ?.map { albumItem(it) }
            }
            DECADE -> lib.decade(id)?.let { d ->
                listOf(shuffleItem(SHUFFLE_DECADE + d.id, d.name, d.albums.sumOf { it.tracks.size })) + d.albums.map { albumItem(it) }
            }
            PLAYLIST -> findPlaylist(id)?.let { p ->
                val tracks = p.tracks(lib)
                listOfNotNull(shuffleItem(SHUFFLE_PLAYLIST + id, p.name, tracks.size).takeIf { tracks.isNotEmpty() }) +
                    listItems(parentId, tracks, lib)
            }
            ALBUM -> lib.album(id)?.let { album ->
                listItems(parentId, album.tracks, lib) { t -> if (album.discCount > 1) "Disc ${t.discNumber}" else null }
            }
            ARTIST -> lib.artist(id)?.let { artist ->
                listOf(shuffleItem(SHUFFLE_ARTIST + id, artist.name, artist.tracks.size)) + artist.albums.map { albumItem(it) } +
                    artist.looseTracks.map { t -> trackItem(t, lib, "$ARTIST_ALL$id|$TRACK${t.id}") }
            }
            else -> null
        }
    }

    /** Explore, in the phone's order: Shuffle all, Random album, Recently added, Genres, Decades. */
    private fun exploreChildren(lib: Library): List<MediaItem> = buildList {
        if (lib.tracks.isNotEmpty()) {
            add(playableAction(SHUFFLE_ALL, "Shuffle all", plural(lib.tracks.size, "song")))
        }
        if (lib.albums.isNotEmpty()) {
            add(playableAction(RANDOM_ALBUM, "Random album", "Pick a surprise album"))
        }
        lib.recentlyAdded.take(EXPLORE_RECENT_LIMIT).forEach { add(albumItem(it, group = "Recently added")) }
        if (lib.albums.size > EXPLORE_RECENT_LIMIT) {
            add(folder(RECENT, "See all", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, grid = true, group = "Recently added"))
        }
        sortedBroadGenres(lib).forEach { add(broadGenreItem(it, group = "Genres")) }
        lib.decades.forEach { add(groupItem(DECADE, it, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, group = "Decades")) }
    }

    private fun sortedBroadGenres(lib: Library) =
        lib.broadGenres.sortedWith(compareByDescending<BroadGenreGroup> { it.albums.size }.thenBy { SortKey.of(it.name) })

    private fun genreChildren(id: String, lib: Library): List<MediaItem>? {
        val broad = lib.broadGenre(id)
        if (broad != null && broad.subgenres.isNotEmpty()) {
            return buildList {
                add(shuffleItem(SHUFFLE_GENRE + broad.id, broad.name, broad.albums.sumOf { it.tracks.size }))
                broad.subgenres.forEach { sub ->
                    add(groupItem(GENRE, sub, MediaMetadata.MEDIA_TYPE_GENRE, group = "Subgenres"))
                }
                if (broad.albums.size <= 12) {
                    broad.albums.forEach { add(albumItem(it, group = "Albums")) }
                } else {
                    add(
                        folder(
                            GENRE_ALBUMS + broad.id, "All ${broad.name} albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                            subtitle = plural(broad.albums.size, "album"), grid = broad.albums.size <= AZ_THRESHOLD, group = "Albums",
                        )
                    )
                }
            }
        }
        val group = broad?.asGroup() ?: lib.genre(id) ?: return null
        return listOf(shuffleItem(SHUFFLE_GENRE + group.id, group.name, group.albums.sumOf { it.tracks.size })) +
            group.albums.map { albumItem(it) }
    }

    private fun artistBuckets(artists: List<Artist>): List<MediaItem> =
        artists.groupBy { Search.indexLetter(it.name) }
            .entries
            .sortedBy { if (it.key == '#') Char.MAX_VALUE else it.key }
            .map { (letter, list) ->
                browsable(
                    ARTISTS_LETTER + letter, letter.toString(), plural(list.size, "artist"),
                    MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                    list.firstNotNullOfOrNull { a -> a.albums.firstNotNullOfOrNull(::artUri) },
                )
            }

    private fun albumBuckets(prefix: String, albums: List<Album>): List<MediaItem> =
        albums.groupBy { Search.indexLetter(it.title) }
            .entries
            .sortedBy { if (it.key == '#') Char.MAX_VALUE else it.key }
            .map { (letter, list) ->
                browsable(
                    prefix + letter, letter.toString(), plural(list.size, "album"),
                    MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                    list.firstNotNullOfOrNull(::artUri),
                    grid = true,
                )
            }

    fun item(mediaId: String, lib: Library): MediaItem? {
        val (kind, id) = split(mediaId)
        return when (kind) {
            ROOT -> root()
            SHUFFLE_ALL -> playableAction(SHUFFLE_ALL, "Shuffle all", plural(lib.tracks.size, "song"))
            RANDOM_ALBUM -> playableAction(RANDOM_ALBUM, "Random album", "Pick a surprise album")
            ALBUM -> lib.album(id)?.let { albumItem(it) }
            ARTIST -> lib.artist(id)?.let { artistItem(it) }
            ARTIST_ALL -> lib.artist(id)?.let { playableAction(ARTIST_ALL + it.id, it.name, plural(it.tracks.size, "song")) }
            SHUFFLE_ARTIST -> lib.artist(id)?.let { shuffleItem(SHUFFLE_ARTIST + it.id, it.name, it.tracks.size) }
            MOOD -> lib.mood(id)?.let { groupItem(MOOD, it, MediaMetadata.MEDIA_TYPE_GENRE) }
            SHUFFLE_MOOD -> lib.mood(id)?.let { shuffleItem(SHUFFLE_MOOD + it.id, it.name, it.albums.sumOf { a -> a.tracks.size }) }
            GENRE -> lib.broadGenre(id)?.let { broadGenreItem(it) } ?: lib.genre(id)?.let { groupItem(GENRE, it, MediaMetadata.MEDIA_TYPE_GENRE) }
            SHUFFLE_GENRE -> (lib.broadGenre(id)?.asGroup() ?: lib.genre(id))?.let { shuffleItem(SHUFFLE_GENRE + it.id, it.name, it.albums.sumOf { a -> a.tracks.size }) }
            DECADE -> lib.decade(id)?.let { groupItem(DECADE, it, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS) }
            SHUFFLE_DECADE -> lib.decade(id)?.let { shuffleItem(SHUFFLE_DECADE + it.id, it.name, it.albums.sumOf { a -> a.tracks.size }) }
            PLAYLIST -> findPlaylist(id)?.let { playlistItem(it, lib) }
            SHUFFLE_PLAYLIST -> findPlaylist(id)?.let { p -> shuffleItem(SHUFFLE_PLAYLIST + id, p.name, p.tracks(lib).size) }
            else -> trackIdOf(mediaId)?.let(lib::track)?.let { trackItem(it, lib) }
                ?: (children(ROOT, lib)!! + children(LIBRARY, lib)!!).firstOrNull { it.mediaId == mediaId }
        }
    }

    /** Search from Android Auto's search screen. */
    fun search(query: String, lib: Library): List<MediaItem> =
        searchMediaIds(query, lib).mapNotNull { id ->
            when {
                id.startsWith(MOOD) -> lib.mood(id.removePrefix(MOOD))?.let { groupItem(MOOD, it, MediaMetadata.MEDIA_TYPE_GENRE) }
                id.startsWith(GENRE) -> lib.genre(id.removePrefix(GENRE))?.let { groupItem(GENRE, it, MediaMetadata.MEDIA_TYPE_GENRE) }
                id.startsWith(DECADE) -> lib.decade(id.removePrefix(DECADE))?.let { groupItem(DECADE, it, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS) }
                id.startsWith(PLAYLIST) -> findPlaylist(id.removePrefix(PLAYLIST))?.let { playlistItem(it, lib) }
                id.startsWith(ARTIST) -> lib.artist(id.removePrefix(ARTIST))?.let { artistItem(it) }
                id.startsWith(ALBUM) -> lib.album(id.removePrefix(ALBUM))?.let { albumItem(it) }
                else -> trackIdOf(id)?.let(lib::track)?.let { t -> trackItem(t, lib, inAlbumId(t, lib)) }
            }
        }

    /** Ordered media IDs for Android Auto search (Moods, Genres, Decades, Playlists, Artists, Albums, Songs). */
    internal fun searchMediaIds(query: String, lib: Library): List<String> {
        val q = searchKey(query)
        if (q.isEmpty()) return emptyList()
        return ranked(lib.moods, q) { it.name }.take(SEARCH_LIMIT).map { MOOD + it.id } +
            ranked(lib.genres, q) { it.name }.take(SEARCH_LIMIT).map { GENRE + it.id } +
            ranked(lib.decades, q) { it.name }.take(SEARCH_LIMIT).map { DECADE + it.id } +
            ranked(playlists(), q) { it.name }.take(SEARCH_LIMIT).map { PLAYLIST + it.id } +
            ranked(lib.artists, q) { it.name }.take(SEARCH_LIMIT).map { ARTIST + it.id } +
            ranked(lib.albums, q) { it.title }.take(SEARCH_LIMIT).map { ALBUM + it.id } +
            ranked(lib.tracks, q) { it.title }.take(SEARCH_LIMIT).map { TRACK + it.id }
    }

    // ---- turning requests into a queue ----

    class Queue(val tracks: List<Track>, val startIndex: Int)

    /**
     * What to play for a requested item: a track in a list plays the list from that
     * track; an album, artist, playlist, genre, mood or decade plays all its songs;
     * a voice request ("play driving music on LocalBeat") is searched.
     */
    fun resolve(item: MediaItem, lib: Library): Queue {
        val mediaId = item.mediaId
        val query = item.requestMetadata.searchQuery
        if (mediaId.isBlank() && query != null) return voice(query, item.requestMetadata.extras, lib)

        if ('|' in mediaId) {
            val tid = trackIdOf(mediaId)
            val listPart = mediaId.substringBefore('|')
            val list = listPart.substringBeforeLast('/')
            val position = listPart.substringAfterLast('/', "").toIntOrNull()
            val tracks = tracksFor(list, lib) ?: listOfNotNull(tid?.let(lib::track))
            val start = position?.takeIf { tracks.getOrNull(it)?.id == tid } ?: tracks.indexOfFirst { it.id == tid }
            return Queue(tracks, start.coerceAtLeast(0))
        }
        return Queue(tracksFor(mediaId, lib).orEmpty(), 0)
    }

    /** All the songs a list-like media ID stands for. */
    fun tracksFor(mediaId: String, lib: Library): List<Track>? {
        val (kind, id) = split(mediaId)
        return when (kind) {
            TRACK -> listOfNotNull(lib.track(id))
            ALBUM -> lib.album(id)?.tracks
            ARTIST, ARTIST_ALL -> lib.artist(id)?.tracks
            SHUFFLE_ARTIST -> lib.artist(id)?.tracks?.shuffled()
            GENRE -> (lib.broadGenre(id)?.albums ?: lib.genre(id)?.albums)?.flatMap { it.tracks }
            SHUFFLE_GENRE -> (lib.broadGenre(id)?.albums ?: lib.genre(id)?.albums)?.flatMap { it.tracks }?.shuffled()
            MOOD -> lib.mood(id)?.albums?.flatMap { it.tracks }
            SHUFFLE_MOOD -> lib.mood(id)?.albums?.flatMap { it.tracks }?.shuffled()
            DECADE -> lib.decade(id)?.albums?.flatMap { it.tracks }
            SHUFFLE_DECADE -> lib.decade(id)?.albums?.flatMap { it.tracks }?.shuffled()
            PLAYLIST -> findPlaylist(id)?.tracks(lib)
            SHUFFLE_PLAYLIST -> findPlaylist(id)?.tracks(lib)?.shuffled()
            SHUFFLED -> tracksFor(id, lib)?.shuffled()
            SONGS -> lib.tracks
            SHUFFLE_ALL -> lib.tracks.shuffled()
            RANDOM_ALBUM -> lib.albums.randomOrNull()?.tracks.orEmpty()
            else -> null
        }
    }

    private fun voice(query: String, extras: Bundle?, lib: Library): Queue {
        // The assistant often splits the request into fields; use them when present.
        val title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)?.let(::searchKey).orEmpty()
        val artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)?.let(::searchKey).orEmpty()
        val albumName = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)?.let(::searchKey).orEmpty()
        val playlistName = extras?.getString(EXTRA_MEDIA_PLAYLIST)?.let(::searchKey).orEmpty()
        val genreName = extras?.getString(MediaStore.EXTRA_MEDIA_GENRE)?.let(::searchKey).orEmpty()
        val focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)
        val q = searchKey(query)
        val tagQ = stripVoiceFiller(q)

        fun ofTrack(t: Track): Queue {
            val album = lib.albumOf(t) ?: return Queue(listOf(t), 0)
            return Queue(album.tracks, album.tracks.indexOf(t))
        }

        if (listOf(q, title, artist, albumName, playlistName, genreName).all { it.isEmpty() }) {
            return Queue(lib.tracks.shuffled(), 0) // "play music on LocalBeat"
        }
        if (playlistName.isNotEmpty()) {
            ranked(playlists(), playlistName) { it.name }.firstOrNull()?.let { return Queue(it.tracks(lib), 0) }
        }
        if (title.isNotEmpty()) {
            val candidates = lib.tracks.filter { artist.isEmpty() || artist in searchKey(it.artist) || artist in searchKey(it.filedUnder) }
            ranked(candidates, title) { it.title }.firstOrNull()?.let { return ofTrack(it) }
        }
        if (albumName.isNotEmpty()) {
            val candidates = lib.albums.filter { artist.isEmpty() || artist in searchKey(it.artist) }
            ranked(candidates.ifEmpty { lib.albums }, albumName) { it.title }.firstOrNull()?.let { return Queue(it.tracks, 0) }
        }
        if (artist.isNotEmpty()) {
            ranked(lib.artists, artist) { it.name }.firstOrNull()?.let { return Queue(it.tracks, 0) }
        }
        if (genreName.isNotEmpty()) {
            val gq = stripVoiceFiller(genreName)
            matchMoodGenreOrDecade(gq, lib)?.let { return Queue(it.shuffled(), 0) }
        }
        if (q.isEmpty()) return Queue(emptyList(), 0)
        // Honour EXTRA_MEDIA_FOCUS if the assistant told us what kind of thing was requested.
        when (focus) {
            MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE ->
                ranked(playlists(), q) { it.name }.firstOrNull()?.let { return Queue(it.tracks(lib), 0) }
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE ->
                ranked(lib.artists, q) { it.name }.firstOrNull()?.let { return Queue(it.tracks, 0) }
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE ->
                ranked(lib.albums, q) { it.title }.firstOrNull()?.let { return Queue(it.tracks, 0) }
            MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE ->
                matchMoodGenreOrDecade(tagQ, lib)?.let { return Queue(it.shuffled(), 0) }
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE ->
                ranked(lib.tracks, q) { it.title }.firstOrNull()?.let { return ofTrack(it) }
        }
        // Free text: the best match wins across playlists, moods, genres, decades, artists, albums, songs.
        val moodHit = ranked(lib.moods, tagQ) { it.name }.firstOrNull()
        val genreHit = ranked(lib.genres, tagQ) { it.name }.firstOrNull()
        val decadeHit = matchDecade(tagQ, lib)
        val best = listOfNotNull(
            ranked(playlists(), q) { it.name }.firstOrNull()?.let { score(it.name, q) + 0.1 to Queue(it.tracks(lib), 0) },
            moodHit?.let { score(it.name, tagQ) + 0.05 to Queue(it.albums.flatMap { a -> a.tracks }.shuffled(), 0) },
            genreHit?.let { score(it.name, tagQ) + 0.04 to Queue((lib.broadGenre(it.id)?.albums ?: it.albums).flatMap { a -> a.tracks }.shuffled(), 0) },
            decadeHit?.let { 3.03 to Queue(it.albums.flatMap { a -> a.tracks }.shuffled(), 0) },
            ranked(lib.artists, q) { it.name }.firstOrNull()?.let { score(it.name, q) to Queue(it.tracks, 0) },
            ranked(lib.albums, q) { it.title }.firstOrNull()?.let { score(it.title, q) - 0.1 to Queue(it.tracks, 0) },
            ranked(lib.tracks, q) { it.title }.firstOrNull()?.let { score(it.title, q) - 0.2 to ofTrack(it) },
        ).maxByOrNull { it.first }
        return best?.second ?: Queue(emptyList(), 0)
    }

    private fun matchMoodGenreOrDecade(q: String, lib: Library): List<Track>? {
        if (q.isEmpty()) return null
        ranked(lib.moods, q) { it.name }.firstOrNull()?.let { m -> return m.albums.flatMap { it.tracks } }
        ranked(lib.genres, q) { it.name }.firstOrNull()?.let { g ->
            return (lib.broadGenre(g.id)?.albums ?: g.albums).flatMap { it.tracks }
        }
        matchDecade(q, lib)?.let { d -> return d.albums.flatMap { it.tracks } }
        return null
    }

    private fun matchDecade(q: String, lib: Library): AlbumGroup? {
        if (q.isEmpty()) return null
        val decadeNum = when (q) {
            "50s", "1950s", "fifties" -> 1950
            "60s", "1960s", "sixties" -> 1960
            "70s", "1970s", "seventies" -> 1970
            "80s", "1980s", "eighties" -> 1980
            "90s", "1990s", "nineties" -> 1990
            "00s", "2000s", "two thousands" -> 2000
            "10s", "2010s", "twenty tens" -> 2010
            "20s", "2020s", "twenty twenties" -> 2020
            else -> null
        } ?: return null
        return lib.decade("d$decadeNum")
    }

    /** "driving music" -> "driving", "80s songs" -> "80s", "chill vibes" -> "chill". */
    private fun stripVoiceFiller(q: String): String =
        q.removeSuffix(" music").removeSuffix(" songs").removeSuffix(" vibes").removeSuffix(" tracks").trim()

    // ---- items ----

    /** A playable queue entry for a track, with the URI ExoPlayer plays. */
    fun queueItem(track: Track, lib: Library): MediaItem = trackItem(track, lib)

    private fun inAlbumId(t: Track, lib: Library): String {
        val album = lib.albumOf(t) ?: return "$TRACK${t.id}"
        return "$ALBUM${album.id}/${album.tracks.indexOf(t)}|$TRACK${t.id}"
    }

    private fun listItems(listId: String, tracks: List<Track>, lib: Library, group: (Track) -> String? = { null }) =
        tracks.mapIndexed { i, t -> trackItem(t, lib, "$listId/$i|$TRACK${t.id}", group(t)) }

    private fun trackItem(track: Track, lib: Library, mediaId: String = "$TRACK${track.id}", group: String? = null): MediaItem {
        val album = lib.albumOf(track)
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifBlank { track.filedUnder })
            .setAlbumTitle(track.album.ifBlank { null })
            .setAlbumArtist(album?.artist)
            .setTrackNumber(track.trackNumber.takeIf { it > 0 })
            .setDiscNumber(track.discNumber)
            .setTotalDiscCount(album?.discCount)
            .setRecordingYear(track.year.takeIf { it > 0 })
            .setGenre(track.genre.ifBlank { null })
            .setDurationMs(track.durationMs.takeIf { it > 0 })
            .setArtworkUri(album?.let(::artUri))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        if (group != null) metadata.setExtras(Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group) })
        val uri = if (track.onServer) {
            serverUri(track) ?: android.net.Uri.parse("localbeat://unavailable/${track.id}")
        } else {
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.mediaStoreId)
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun albumItem(album: Album, group: String? = null): MediaItem = browsable(
        ALBUM + album.id, album.title, if (album.year > 0) "${album.artist} · ${album.year}" else album.artist,
        MediaMetadata.MEDIA_TYPE_ALBUM, artUri(album), group = group,
    ) { setArtist(album.artist).setAlbumTitle(album.title).setRecordingYear(album.year.takeIf { it > 0 }) }

    private fun artistItem(artist: Artist): MediaItem = browsable(
        ARTIST + artist.id, artist.name, plural(artist.albums.size, "album"),
        MediaMetadata.MEDIA_TYPE_ARTIST, artist.albums.firstNotNullOfOrNull(::artUri), grid = true,
    ) { setArtist(artist.name) }

    private fun groupItem(kind: String, g: AlbumGroup, type: Int, group: String? = null): MediaItem = browsable(
        kind + g.id, g.name, plural(g.albums.size, "album"), type, g.albums.firstNotNullOfOrNull(::artUri), grid = true, group = group,
    )

    private fun broadGenreItem(bg: BroadGenreGroup, group: String? = null): MediaItem = browsable(
        GENRE + bg.id, bg.name, plural(bg.albums.size, "album"), MediaMetadata.MEDIA_TYPE_GENRE,
        bg.albums.firstNotNullOfOrNull(::artUri), grid = bg.subgenres.isEmpty(), group = group,
    )

    private fun playlistItem(p: Playlist, lib: Library, group: String? = null): MediaItem {
        val tracks = p.tracks(lib)
        return browsable(
            PLAYLIST + p.id, p.name, plural(tracks.size, "song"), MediaMetadata.MEDIA_TYPE_PLAYLIST,
            tracks.firstNotNullOfOrNull { t -> lib.albumOf(t)?.let(::artUri) }, group = group,
        )
    }

    /** The first item of every group but an album: "Shuffle <name>". */
    private fun shuffleItem(mediaId: String, name: String, songs: Int): MediaItem =
        playableAction(mediaId, "Shuffle $name", plural(songs, "song"))

    private fun playableAction(id: String, title: String, subtitle: String, group: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .apply { if (group != null) setExtras(Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group) }) }
                    .build()
            ).build()

    private fun browsable(
        id: String, title: String, subtitle: String, type: Int, artwork: android.net.Uri?,
        grid: Boolean = false, group: String? = null,
        more: MediaMetadata.Builder.() -> MediaMetadata.Builder = { this },
    ) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artwork)
                .setMediaType(type)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .apply { itemExtras(grid, group)?.let(::setExtras) }
                .more()
                .build()
        ).build()

    private fun folder(
        id: String, title: String, type: Int, subtitle: String? = null,
        grid: Boolean = false, group: String? = null,
    ) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setMediaType(type)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .apply { itemExtras(grid, group)?.let(::setExtras) }
                .build()
        ).build()

    /** Old rips have no embedded cover; give those no artwork URI rather than a broken one. */
    private fun artUri(album: Album) = if (album.tracks.any { it.hasEmbeddedArt }) art?.uriFor(album.id) else null

    private fun itemExtras(grid: Boolean, group: String?): Bundle? {
        if (!grid && group == null) return null
        return Bundle().apply {
            if (grid) putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            if (group != null) putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }
    }

    companion object {
        const val ROOT = "root"
        const val EXPLORE = "explore"
        const val BROWSE = "browse"
        const val MOODS = "moods"
        const val GENRES = "genres"
        const val LIBRARY = "library"
        const val MORE = "more"
        const val ALBUMS = "albums"
        const val ALBUMS_LETTER = "albumsletter:"
        const val ARTISTS = "artists"
        const val ARTISTS_LETTER = "artistsletter:"
        const val PLAYLISTS = "playlists"
        const val DECADES = "decades"
        const val SONGS = "songs"
        const val RECENT = "recent"
        const val ALBUM = "album:"
        const val ARTIST = "artist:"
        const val ARTIST_ALL = "artistall:"
        const val PLAYLIST = "playlist:"
        const val GENRE = "genre:"
        const val GENRE_ALBUMS = "genrealbums:"
        const val GENRE_ALBUMS_LETTER = "genrealbumsletter:"
        const val MOOD = "mood:"
        const val DECADE = "decade:"
        const val SHUFFLE_ALL = "shuffleall"
        const val RANDOM_ALBUM = "randomalbum"
        const val SHUFFLE_GENRE = "shufflegenre:"
        const val SHUFFLE_ARTIST = "shuffleartist:"
        const val SHUFFLED = "shuffled:"
        const val SHUFFLE_PLAYLIST = "shuffleplaylist:"
        const val SHUFFLE_MOOD = "shufflemood:"
        const val SHUFFLE_DECADE = "shuffledecade:"
        const val TRACK = "t:"

        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"
        private const val RECENT_LIMIT = 100
        private const val EXPLORE_RECENT_LIMIT = 8
        private const val SEARCH_LIMIT = 50
        const val AZ_THRESHOLD = 40

        /** "album:abc" -> ("album:", "abc"); "albums" -> ("albums", ""). Ignores any "/position". */
        private fun split(mediaId: String): Pair<String, String> {
            val colon = mediaId.indexOf(':')
            if (colon < 0) return mediaId.substringBefore('/') to ""
            return mediaId.substring(0, colon + 1) to mediaId.substring(colon + 1).substringBefore('/').substringBefore('|')
        }

        /** The track ID in a `t:<id>` or `<list>|t:<id>` media ID. */
        fun trackIdOf(mediaId: String): String? =
            mediaId.substringAfterLast('|').takeIf { it.startsWith(TRACK) }?.removePrefix(TRACK)

        /** The media ID for tapping the track at [position] of the list [listId]. */
        fun inList(listId: String, position: Int, trackId: String) = "$listId/$position|$TRACK$trackId"

        private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

        private val NON_ALNUM = Regex("[^\\p{L}\\p{N} ]")
        private val SPACES = Regex("\\s+")

        /** Looser than [fold]: punctuation is ignored, so "guns n roses" finds "Guns N’ Roses". */
        fun searchKey(s: String): String =
            fold(s).replace(NON_ALNUM, "").replace(SPACES, " ").trim()

        /** 3 = exact, 2 = starts with, 1 = a word starts with, 0.5 = contains, 0 = no match. */
        fun score(text: String, q: String): Double {
            if (q.isEmpty()) return 0.0
            val t = searchKey(text)
            return when {
                t == q -> 3.0
                t.startsWith(q) -> 2.0
                t.contains(" $q") -> 1.0
                t.contains(q) -> 0.5
                else -> 0.0
            }
        }

        fun <T> ranked(items: List<T>, q: String, text: (T) -> String): List<T> =
            items.map { it to score(text(it), q) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }
}
