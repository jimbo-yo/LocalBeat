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
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.library.fold
import com.jimboyo.localbeat.playlists.Playlist

/**
 * The browse tree shared by Android Auto, other media browsers and LocalBeat's own UI.
 *
 * Media IDs:
 * - `root`; its children `albums`, `artists`, `playlists`, `more` (Auto shows these as tabs);
 *   `more` holds `genres`, `decades`, `songs`, `recent`
 * - `album:<id>`, `artist:<id>`, `playlist:<id>`, `genre:<id>`, `decade:<id>`: lists
 * - `artistall:<id>`: every song by an artist
 * - `t:<id>`: one track (what the player's queue holds)
 * - `<list>/<position>|t:<id>`: a track tapped in a list; plays the list from there.
 *   The position tells repeats in a playlist apart.
 */
class BrowseTree(
    private val art: ArtStore,
    private val playlists: () -> List<Playlist>,
    /** Where a music-server song streams from (Connected edition). */
    private val serverUri: (Track) -> android.net.Uri? = { null },
) {

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
                folder(ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, grid = true),
                folder(ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folder(PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(MORE, "Browse", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            )
            MORE -> listOf(
                folder(GENRES, "Genres", MediaMetadata.MEDIA_TYPE_FOLDER_GENRES),
                folder(DECADES, "Decades", MediaMetadata.MEDIA_TYPE_FOLDER_YEARS),
                folder(SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folder(RECENT, "Recently added", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, grid = true),
            )
            ALBUMS -> lib.albums.map { albumItem(it) }
            RECENT -> lib.recentlyAdded.take(RECENT_LIMIT).map { albumItem(it) }
            ARTISTS -> lib.artists.map { artistItem(it) }
            SONGS -> listItems(SONGS, lib.tracks, lib)
            GENRES -> lib.genres.map { groupItem(GENRE, it, MediaMetadata.MEDIA_TYPE_GENRE) }
            DECADES -> lib.decades.map { groupItem(DECADE, it, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS) }
            PLAYLISTS -> playlists().map { playlistItem(it, lib) }
            GENRE -> lib.genre(id)?.albums?.map { albumItem(it) }
            DECADE -> lib.decade(id)?.albums?.map { albumItem(it) }
            PLAYLIST -> playlists().firstOrNull { it.id == id }?.let { listItems(parentId, it.tracks(lib), lib) }
            ALBUM -> lib.album(id)?.let { album ->
                listItems(parentId, album.tracks, lib) { t -> if (album.discCount > 1) "Disc ${t.discNumber}" else null }
            }
            ARTIST -> lib.artist(id)?.let { artist ->
                listOf(playAllItem(artist)) + artist.albums.map { albumItem(it) } +
                    artist.looseTracks.map { t -> trackItem(t, lib, "$ARTIST_ALL$id|$TRACK${t.id}") }
            }
            else -> null
        }
    }

    fun item(mediaId: String, lib: Library): MediaItem? {
        val (kind, id) = split(mediaId)
        return when (kind) {
            ROOT -> root()
            ALBUM -> lib.album(id)?.let { albumItem(it) }
            ARTIST -> lib.artist(id)?.let { artistItem(it) }
            ARTIST_ALL -> lib.artist(id)?.let { playAllItem(it) }
            GENRE -> lib.genre(id)?.let { groupItem(GENRE, it, MediaMetadata.MEDIA_TYPE_GENRE) }
            DECADE -> lib.decade(id)?.let { groupItem(DECADE, it, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS) }
            PLAYLIST -> playlists().firstOrNull { it.id == id }?.let { playlistItem(it, lib) }
            else -> trackIdOf(mediaId)?.let(lib::track)?.let { trackItem(it, lib) }
                ?: (children(ROOT, lib)!! + children(MORE, lib)!!).firstOrNull { it.mediaId == mediaId }
        }
    }

    /** Search from Android Auto's search screen. */
    fun search(query: String, lib: Library): List<MediaItem> {
        val q = searchKey(query)
        if (q.isEmpty()) return emptyList()
        return ranked(lib.artists, q) { it.name }.take(SEARCH_LIMIT).map { artistItem(it) } +
            ranked(lib.albums, q) { it.title }.take(SEARCH_LIMIT).map { albumItem(it) } +
            ranked(playlists(), q) { it.name }.take(SEARCH_LIMIT).map { playlistItem(it, lib) } +
            ranked(lib.tracks, q) { it.title }.take(SEARCH_LIMIT).map { t -> trackItem(t, lib, inAlbumId(t, lib)) }
    }

    // ---- turning requests into a queue ----

    class Queue(val tracks: List<Track>, val startIndex: Int)

    /**
     * What to play for a requested item: a track in a list plays the list from that
     * track; an album, artist, playlist, genre or decade plays all its songs; a voice
     * request ("play Nightrain on LocalBeat") is searched.
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
            GENRE -> lib.genre(id)?.albums?.flatMap { it.tracks }
            DECADE -> lib.decade(id)?.albums?.flatMap { it.tracks }
            PLAYLIST -> playlists().firstOrNull { it.id == id }?.tracks(lib)
            SONGS -> lib.tracks
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
        val q = searchKey(query)

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
            ranked(lib.albums, albumName) { it.title }.firstOrNull()?.let { return Queue(it.tracks, 0) }
        }
        if (artist.isNotEmpty()) {
            ranked(lib.artists, artist) { it.name }.firstOrNull()?.let { return Queue(it.tracks, 0) }
        }
        if (genreName.isNotEmpty()) {
            ranked(lib.genres, genreName) { it.name }.firstOrNull()?.let { g -> return Queue(g.albums.flatMap { it.tracks }.shuffled(), 0) }
        }
        // Free text: the best match wins; on a tie playlists, then artists, albums, songs.
        val best = listOfNotNull(
            ranked(playlists(), q) { it.name }.firstOrNull()?.let { score(it.name, q) + 0.1 to Queue(it.tracks(lib), 0) },
            ranked(lib.artists, q) { it.name }.firstOrNull()?.let { score(it.name, q) to Queue(it.tracks, 0) },
            ranked(lib.albums, q) { it.title }.firstOrNull()?.let { score(it.title, q) - 0.1 to Queue(it.tracks, 0) },
            ranked(lib.tracks, q) { it.title }.firstOrNull()?.let { score(it.title, q) - 0.2 to ofTrack(it) },
        ).maxByOrNull { it.first }
        return best?.second ?: Queue(emptyList(), 0)
    }

    // ---- items ----

    /**
     * A playable queue entry for a track, with the URI ExoPlayer plays. Its
     * display lines (what Android Auto's playing screen shows) add where it came
     * from to the artist: "Artist · CD rip", "Artist · old MP3". Browse lists
     * keep the plain artist.
     */
    fun queueItem(track: Track, lib: Library): MediaItem {
        val item = trackItem(track, lib)
        val m = item.mediaMetadata
        val subtitle = listOfNotNull(m.artist?.toString()?.ifBlank { null }, track.sourceLabel.ifBlank { null }).joinToString(" · ")
        return item.buildUpon().setMediaMetadata(
            m.buildUpon()
                // Auto reads the display title/subtitle/description together, so set all three.
                .setDisplayTitle(track.title)
                .setSubtitle(subtitle)
                .setDescription(track.album.ifBlank { null })
                .build()
        ).build()
    }

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
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(if (track.onServer) serverUri(track) else ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.mediaStoreId))
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun albumItem(album: Album): MediaItem = browsable(
        ALBUM + album.id, album.title, if (album.year > 0) "${album.artist} · ${album.year}" else album.artist,
        MediaMetadata.MEDIA_TYPE_ALBUM, artUri(album),
    ) { setArtist(album.artist).setAlbumTitle(album.title).setRecordingYear(album.year.takeIf { it > 0 }) }

    private fun artistItem(artist: Artist): MediaItem = browsable(
        ARTIST + artist.id, artist.name, plural(artist.albums.size, "album"),
        MediaMetadata.MEDIA_TYPE_ARTIST, artist.albums.firstNotNullOfOrNull(::artUri), grid = true,
    ) { setArtist(artist.name) }

    private fun groupItem(kind: String, g: AlbumGroup, type: Int): MediaItem = browsable(
        kind + g.id, g.name, plural(g.albums.size, "album"), type, g.albums.firstNotNullOfOrNull(::artUri), grid = true,
    )

    private fun playlistItem(p: Playlist, lib: Library): MediaItem {
        val tracks = p.tracks(lib)
        return browsable(
            PLAYLIST + p.id, p.name, plural(tracks.size, "song"), MediaMetadata.MEDIA_TYPE_PLAYLIST,
            tracks.firstNotNullOfOrNull { t -> lib.albumOf(t)?.let(::artUri) },
        )
    }

    private fun playAllItem(artist: Artist): MediaItem = MediaItem.Builder()
        .setMediaId(ARTIST_ALL + artist.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("All songs")
                .setSubtitle(plural(artist.tracks.size, "song"))
                .setArtist(artist.name)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        ).build()

    private fun browsable(
        id: String, title: String, subtitle: String, type: Int, artwork: android.net.Uri?, grid: Boolean = false,
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
                .apply { if (grid) setExtras(gridChildren()) }
                .more()
                .build()
        ).build()

    private fun folder(id: String, title: String, type: Int, grid: Boolean = false) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setMediaType(type)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .apply { if (grid) setExtras(gridChildren()) }
                .build()
        ).build()

    /** Old rips have no embedded cover; give those no artwork URI rather than a broken one. */
    private fun artUri(album: Album) = if (album.tracks.any { it.hasEmbeddedArt }) art.uriFor(album.id) else null

    private fun gridChildren() = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
    }

    companion object {
        const val ROOT = "root"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        const val MORE = "more"
        const val GENRES = "genres"
        const val DECADES = "decades"
        const val SONGS = "songs"
        const val RECENT = "recent"
        const val ALBUM = "album:"
        const val ARTIST = "artist:"
        const val ARTIST_ALL = "artistall:"
        const val PLAYLIST = "playlist:"
        const val GENRE = "genre:"
        const val DECADE = "decade:"
        const val TRACK = "t:"

        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"
        private const val RECENT_LIMIT = 100
        private const val SEARCH_LIMIT = 50

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

        /** Looser than [fold]: punctuation is ignored, so "guns n roses" finds "Guns N’ Roses". */
        fun searchKey(s: String): String =
            fold(s).replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

        /** 3 = exact, 2 = starts with, 1 = a word starts with, 0.5 = contains, 0 = no match. */
        fun score(text: String, q: String): Double {
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
