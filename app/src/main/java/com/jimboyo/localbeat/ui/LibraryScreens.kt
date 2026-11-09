package com.jimboyo.localbeat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jimboyo.localbeat.library.Album
import com.jimboyo.localbeat.library.AlbumGroup
import com.jimboyo.localbeat.library.Artist
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.playback.BrowseTree

internal fun albumActions(ui: Ui, album: Album, withArtist: Boolean = true) =
    queueActions(ui, BrowseTree.ALBUM + album.id) { album.tracks } +
        (if (withArtist) listOf(Action("Go to artist") { ui.open(Screen.ArtistPage(album.artistId)) }) else emptyList())

/** Actions for a song row: queue it, add it to a playlist, jump to its album or artist. */
fun trackActions(ui: Ui, lib: Library, t: Track, extra: List<Action> = emptyList()): List<Action> {
    val album = lib.albumOf(t)
    return extra + queueActions(ui, BrowseTree.TRACK + t.id) { listOf(t) } + listOfNotNull(
        album?.let { a -> Action("Go to album") { ui.open(Screen.AlbumPage(a.id)) } },
        album?.let { a -> Action("Go to artist") { ui.open(Screen.ArtistPage(a.artistId)) } },
    )
}

/** Two big covers per row. */
@Composable
fun AlbumGrid(
    ui: Ui, albums: List<Album>, header: (@Composable () -> Unit)? = null,
    /** The A–Z strip, for lists sorted by title. */
    fastScroll: Boolean = false,
    subtitle: (Album) -> String,
) {
    val state = rememberLazyGridState()
    Box {
        val padding = if (fastScroll) PaddingValues(start = 10.dp, end = 10.dp + FastScrollWidth, top = 8.dp, bottom = 16.dp) else listPadding(horizontal = 10)
        LazyVerticalGrid(GridCells.Fixed(2), state = state, contentPadding = padding) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(albums, key = { it.id }) { album ->
                AlbumTile(album, subtitle(album), albumActions(ui, album)) { ui.open(Screen.AlbumPage(album.id)) }
            }
        }
        if (fastScroll) FastScroll(albums.map { it.title }) { i -> state.scrollToItem(i + if (header != null) 1 else 0) }
    }
}

@Composable
fun ArtistList(ui: Ui, lib: Library) {
    val state = rememberLazyListState()
    Box {
    LazyColumn(state = state, contentPadding = PaddingValues(end = FastScrollWidth, top = 8.dp, bottom = 16.dp)) {
        items(lib.artists, key = { it.id }) { artist ->
            Row(Modifier.fillMaxWidth().clickable { ui.open(Screen.ArtistPage(artist.id)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(coverAlbum(artist.albums), Modifier.size(64.dp), maxPx = 200)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(artist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${plural(artist.albums.size, "album")} · ${plural(artist.tracks.size, "song")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MoreButton(queueActions(ui, BrowseTree.ARTIST_ALL + artist.id) { artist.tracks })
            }
        }
    }
    FastScroll(lib.artists.map { it.name }) { state.scrollToItem(it) }
    }
}

/** Genres or decades. */
@Composable
fun GroupList(ui: Ui, groups: List<AlbumGroup>, emptyText: String, onOpen: (AlbumGroup) -> Unit) {
    if (groups.isEmpty()) {
        Text(emptyText, Modifier.fillMaxSize().padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(contentPadding = listPadding()) {
        items(groups, key = { it.id }) { g ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(g) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(coverAlbum(g.albums), Modifier.size(64.dp), maxPx = 200)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium)
                    Text(plural(g.albums.size, "album"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun GroupPage(ui: Ui, g: AlbumGroup, mediaId: String) {
    AlbumGrid(ui, g.albums, header = {
        PlayButtons(
            onPlay = { ui.player.play(mediaId) }, onShuffle = { ui.player.play(mediaId, shuffled = true) },
            more = queueActions(ui, mediaId) { g.albums.flatMap { it.tracks } },
        )
    }) { a -> if (a.year > 0) "${a.artist} · ${a.year}" else a.artist }
}

@Composable
fun SongList(ui: Ui, lib: Library) {
    val state = rememberLazyListState()
    Box {
    LazyColumn(state = state, contentPadding = PaddingValues(end = FastScrollWidth, top = 8.dp, bottom = 16.dp)) {
        itemsIndexedKeyed(lib.tracks) { i, t ->
            TrackRow(
                t, playing = ui.player.currentTrackId == t.id,
                subtitle = listOf(t.artist.ifBlank { t.filedUnder }, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                actions = trackActions(ui, lib, t),
                leading = { Thumb(lib, t) },
            ) { ui.player.play(BrowseTree.inList(BrowseTree.SONGS, i, t.id)) }
        }
    }
    FastScroll(lib.tracks.map { it.title }) { state.scrollToItem(it) }
    }
}

@Composable
fun AlbumPage(ui: Ui, lib: Library, album: Album) {
    val albumId = BrowseTree.ALBUM + album.id
    LazyColumn(contentPadding = listPadding()) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AlbumArt(album, Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f), maxPx = 1024)
                Text(album.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                Text(
                    album.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { ui.open(Screen.ArtistPage(album.artistId)) }.padding(4.dp),
                )
                Text(
                    listOfNotNull(album.year.takeIf { it > 0 }?.toString(), album.genres.firstOrNull(), plural(album.tracks.size, "song"), duration(album.durationMs)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PlayButtons(
                onPlay = { ui.player.play(albumId) }, onShuffle = { ui.player.play(albumId, shuffled = true) },
                more = albumActions(ui, album),
            )
        }
        var lastDisc = 0
        album.tracks.forEachIndexed { i, t ->
            if (album.discCount > 1 && t.discNumber != lastDisc) {
                lastDisc = t.discNumber
                item(key = "disc${t.discNumber}") {
                    Text("Disc ${t.discNumber}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                }
            }
            item(key = t.id) {
                TrackRow(
                    t, playing = ui.player.currentTrackId == t.id,
                    subtitle = if (t.artist.isNotBlank() && t.artist != album.artist) t.artist else null,
                    actions = trackActions(ui, lib, t).filter { it.label != "Go to album" },
                    leading = { TrackNumber(t) },
                ) { ui.player.play(BrowseTree.inList(albumId, i, t.id)) }
            }
        }
    }
}

@Composable
fun ArtistPage(ui: Ui, lib: Library, artist: Artist) {
    val allId = BrowseTree.ARTIST_ALL + artist.id
    LazyVerticalGrid(GridCells.Fixed(2), contentPadding = listPadding(horizontal = 10)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    "${plural(artist.albums.size, "album")} · ${plural(artist.tracks.size, "song")}",
                    modifier = Modifier.padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayButtons(
                    onPlay = { ui.player.play(allId) }, onShuffle = { ui.player.play(allId, shuffled = true) },
                    more = queueActions(ui, allId) { artist.tracks },
                )
            }
        }
        items(artist.albums, key = { it.id }) { album ->
            AlbumTile(album, if (album.year > 0) "${album.year}" else "", albumActions(ui, album, withArtist = false)) { ui.open(Screen.AlbumPage(album.id)) }
        }
        if (artist.looseTracks.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp, top = 16.dp))
        }
        artist.looseTracks.forEachIndexed { i, t ->
            item(key = t.id, span = { GridItemSpan(maxLineSpan) }) {
                TrackRow(t, playing = ui.player.currentTrackId == t.id, subtitle = null, actions = trackActions(ui, lib, t), leading = { Thumb(lib, t) }) {
                    ui.player.play(BrowseTree.inList(allId, artist.albums.sumOf { it.tracks.size } + i, t.id))
                }
            }
        }
    }
}

/** The first album in a list that has a cover, for list thumbnails. */
fun coverAlbum(albums: List<Album>): Album? = albums.firstOrNull { a -> a.tracks.any { it.hasEmbeddedArt } } ?: albums.firstOrNull()

private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedKeyed(list: List<T>, content: @Composable (Int, T) -> Unit) where T : Track {
    items(list.size, key = { list[it].id }) { i -> content(i, list[i]) }
}
