@file:OptIn(ExperimentalLayoutApi::class)

package com.jimboyo.localbeat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jimboyo.localbeat.library.Album
import com.jimboyo.localbeat.library.AlbumGroup
import com.jimboyo.localbeat.library.Artist
import com.jimboyo.localbeat.library.BroadGenreGroup
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.SortKey
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.library.fold
import com.jimboyo.localbeat.playback.BrowseTree

internal fun albumActions(ui: Ui, album: Album, withArtist: Boolean = true) = menu(
    queueActions(ui, BrowseTree.ALBUM + album.id) { album.tracks } +
        (if (withArtist) listOf(Action("Go to artist") { ui.open(Screen.ArtistPage(album.artistId)) }) else emptyList()),
    keepActions(ui) { album.tracks },
)

/** Actions for a song row: queue it, add it to a playlist, jump to its album or artist; then [extra]. */
fun trackActions(ui: Ui, lib: Library, t: Track, extra: List<Action> = emptyList()): List<Action> {
    val album = lib.albumOf(t)
    return menu(
        queueActions(ui, BrowseTree.TRACK + t.id) { listOf(t) } + listOfNotNull(
            album?.let { a -> Action("Go to album") { ui.open(Screen.AlbumPage(a.id)) } },
            album?.let { a -> Action("Go to artist") { ui.open(Screen.ArtistPage(a.artistId)) } },
        ),
        extra + keepActions(ui) { listOf(t) },
    )
}

/** Sort options for the main Albums tab. */
enum class AlbumSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    YEAR("Year"),
    RECENT("Recently added"),
}

/** Library → Albums, with a compact sort bar and A–Z fast scroll. */
@Composable
fun LibraryAlbumsScreen(ui: Ui, lib: Library) {
    var sort by rememberSaveable { mutableStateOf(AlbumSort.TITLE) }
    val sorted = remember(lib, sort) {
        when (sort) {
            AlbumSort.TITLE -> lib.albums
            AlbumSort.ARTIST -> lib.albums.sortedWith(compareBy({ SortKey.of(it.artist) }, { it.year }, { SortKey.of(it.title) }))
            AlbumSort.YEAR -> lib.albums.sortedWith(compareByDescending<Album> { it.year }.thenBy { SortKey.of(it.title) })
            AlbumSort.RECENT -> lib.recentlyAdded
        }
    }
    val fastScrollKeys = when (sort) {
        AlbumSort.TITLE -> sorted.map { it.title }
        AlbumSort.ARTIST -> sorted.map { it.artist }
        AlbumSort.YEAR, AlbumSort.RECENT -> null
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(AlbumSort.entries) { s ->
                FilterChip(
                    selected = sort == s,
                    onClick = { sort = s },
                    label = { Text(s.label) },
                )
            }
        }
        AlbumGrid(
            ui = ui,
            albums = sorted,
            fastScrollKeys = fastScrollKeys,
            modifier = Modifier.weight(1f),
        ) { a ->
            when (sort) {
                AlbumSort.YEAR -> if (a.year > 0) "${a.year} · ${a.artist}" else a.artist
                else -> a.artist
            }
        }
    }
}

/** Two big covers per row. */
@Composable
fun AlbumGrid(
    ui: Ui,
    albums: List<Album>,
    header: (@Composable () -> Unit)? = null,
    /** The A–Z strip, for lists sorted by title. */
    fastScroll: Boolean = false,
    /** Custom A–Z strip keys (e.g. artist names when sorted by artist). */
    fastScrollKeys: List<String>? = if (fastScroll) albums.map { it.title } else null,
    modifier: Modifier = Modifier,
    subtitle: (Album) -> String,
) {
    val state = rememberLazyGridState()
    val useFastScroll = fastScrollKeys != null && fastScrollKeys.size >= 2
    Box(modifier) {
        val padding = if (useFastScroll) PaddingValues(start = 10.dp, end = 10.dp + FastScrollWidth, top = 8.dp, bottom = 16.dp) else listPadding(horizontal = 10)
        LazyVerticalGrid(GridCells.Fixed(2), state = state, contentPadding = padding) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(albums, key = { it.id }) { album ->
                AlbumTile(album, subtitle(album), albumActions(ui, album)) { ui.open(Screen.AlbumPage(album.id)) }
            }
        }
        if (useFastScroll) FastScroll(fastScrollKeys!!) { i -> state.scrollToItem(i + if (header != null) 1 else 0) }
    }
}

@Composable
fun ArtistList(ui: Ui, lib: Library) {
    ArtistListRows(ui, lib.artists, fastScroll = true)
}

@Composable
private fun ArtistListRows(
    ui: Ui,
    artists: List<Artist>,
    fastScroll: Boolean,
    header: (@Composable () -> Unit)? = null,
) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        val padding = if (fastScroll) PaddingValues(end = FastScrollWidth, top = 8.dp, bottom = 16.dp) else listPadding()
        LazyColumn(state = state, contentPadding = padding) {
            if (header != null) item(key = "header") { header() }
            items(artists, key = { it.id }) { artist ->
                Row(
                    Modifier.fillMaxWidth().clickable { ui.open(Screen.ArtistPage(artist.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArt(coverAlbum(artist.albums), Modifier.size(64.dp), maxPx = 200)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(artist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${plural(artist.albums.size, "album")} · ${plural(artist.tracks.size, "song")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MoreButton(menu(queueActions(ui, BrowseTree.ARTIST_ALL + artist.id) { artist.tracks }, keepActions(ui) { artist.tracks }))
                }
            }
        }
        if (fastScroll && artists.size >= 2) FastScroll(artists.map { it.name }) { i -> state.scrollToItem(i + if (header != null) 1 else 0) }
    }
}

// ---- Broad Genre / Genre / Mood / Decade pages with subgenre & cross-filtering ----

private enum class GroupViewMode { ALBUMS, ARTISTS }

private class FilterOption(val key: String, val label: String, val count: Int)

@Composable
fun BroadGenrePage(ui: Ui, lib: Library, broad: BroadGenreGroup) {
    FilteredGroupPage(
        ui = ui,
        lib = lib,
        baseAlbums = broad.albums,
        baseMediaId = BrowseTree.GENRE + broad.id,
        subgenres = broad.subgenres,
        crossFilterKind = CrossFilterKind.DECADE,
    )
}

@Composable
fun GenrePage(ui: Ui, lib: Library, group: AlbumGroup) {
    FilteredGroupPage(
        ui = ui,
        lib = lib,
        baseAlbums = group.albums,
        baseMediaId = BrowseTree.GENRE + group.id,
        subgenres = emptyList(),
        crossFilterKind = CrossFilterKind.DECADE,
    )
}

@Composable
fun MoodPage(ui: Ui, lib: Library, group: AlbumGroup) {
    FilteredGroupPage(
        ui = ui,
        lib = lib,
        baseAlbums = group.albums,
        baseMediaId = BrowseTree.MOOD + group.id,
        subgenres = emptyList(),
        crossFilterKind = CrossFilterKind.DECADE,
        secondaryKind = CrossFilterKind.BROAD_GENRE,
    )
}

@Composable
fun DecadePage(ui: Ui, lib: Library, group: AlbumGroup) {
    FilteredGroupPage(
        ui = ui,
        lib = lib,
        baseAlbums = group.albums,
        baseMediaId = BrowseTree.DECADE + group.id,
        subgenres = emptyList(),
        crossFilterKind = CrossFilterKind.MOOD,
        secondaryKind = CrossFilterKind.BROAD_GENRE,
    )
}

private enum class CrossFilterKind { NONE, DECADE, MOOD, BROAD_GENRE }

@Composable
private fun FilteredGroupPage(
    ui: Ui,
    lib: Library,
    baseAlbums: List<Album>,
    baseMediaId: String,
    subgenres: List<AlbumGroup>,
    crossFilterKind: CrossFilterKind,
    secondaryKind: CrossFilterKind = CrossFilterKind.NONE,
) {
    var selectedSubId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCross by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSecondary by rememberSaveable { mutableStateOf<String?>(null) }
    var viewMode by rememberSaveable { mutableStateOf(GroupViewMode.ALBUMS) }

    val subAlbums = remember(baseAlbums, subgenres, selectedSubId) {
        if (selectedSubId == null) baseAlbums
        else subgenres.firstOrNull { it.id == selectedSubId }?.albums ?: baseAlbums
    }

    fun optionsFor(albums: List<Album>, kind: CrossFilterKind): List<FilterOption> = when (kind) {
        CrossFilterKind.NONE -> emptyList()
        CrossFilterKind.DECADE -> albums.mapNotNull { it.decadeLabel }
            .groupingBy { it }.eachCount().entries
            .sortedBy { it.key }
            .map { FilterOption(it.key, it.key, it.value) }
        CrossFilterKind.MOOD -> albums.flatMap { it.moods }
            .groupingBy(::fold).eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (k, count) ->
                val display = lib.moodByName(k)?.name ?: k.replaceFirstChar { it.uppercase() }
                FilterOption(k, display, count)
            }
        CrossFilterKind.BROAD_GENRE -> albums.mapNotNull { it.genres.firstOrNull() }
            .groupingBy(::fold).eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (k, count) ->
                val display = lib.broadGenreByName(k)?.name ?: lib.genreByName(k)?.name ?: k.replaceFirstChar { it.uppercase() }
                FilterOption(k, display, count)
            }
    }

    fun matches(a: Album, kind: CrossFilterKind, key: String): Boolean = when (kind) {
        CrossFilterKind.NONE -> true
        CrossFilterKind.DECADE -> a.decadeLabel == key
        CrossFilterKind.MOOD -> a.moods.any { fold(it) == key }
        CrossFilterKind.BROAD_GENRE -> a.genres.firstOrNull()?.let(::fold) == key
    }

    val crossOptions = remember(subAlbums, crossFilterKind) { optionsFor(subAlbums, crossFilterKind) }
    val secondaryOptions = remember(subAlbums, secondaryKind) { optionsFor(subAlbums, secondaryKind) }

    val filteredAlbums = remember(subAlbums, selectedCross, selectedSecondary) {
        subAlbums.filter { a ->
            (selectedCross == null || matches(a, crossFilterKind, selectedCross!!)) &&
                (selectedSecondary == null || matches(a, secondaryKind, selectedSecondary!!))
        }
    }
    val filteredTracks = remember(filteredAlbums) { filteredAlbums.flatMap { it.tracks } }
    val filteredArtists = remember(filteredAlbums, lib) {
        filteredAlbums.groupBy { it.artistId }.entries.map { (artistId, groupAlbums) ->
            val full = lib.artist(artistId)
            Artist(
                id = artistId,
                name = full?.name ?: groupAlbums.first().artist,
                albums = groupAlbums.sortedWith(compareBy({ it.year }, { SortKey.of(it.title) })),
                looseTracks = emptyList(),
            )
        }.sortedBy { SortKey.of(it.name) }
    }

    val isFiltered = selectedSubId != null || selectedCross != null || selectedSecondary != null
    val activeMediaId = if (!isFiltered) baseMediaId else if (selectedCross == null && selectedSecondary == null && selectedSubId != null) BrowseTree.GENRE + selectedSubId else null

    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            if (subgenres.isNotEmpty()) {
                Text(
                    "Subgenres", Modifier.padding(start = 16.dp, top = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "sub-all") {
                        FilterChip(
                            selected = selectedSubId == null,
                            onClick = { selectedSubId = null; selectedCross = null },
                            label = { Text("All (${baseAlbums.size})") },
                        )
                    }
                    items(subgenres, key = { it.id }) { sub ->
                        FilterChip(
                            selected = selectedSubId == sub.id,
                            onClick = {
                                selectedSubId = if (selectedSubId == sub.id) null else sub.id
                                selectedCross = null
                            },
                            label = { Text("${sub.name} (${sub.albums.size})") },
                        )
                    }
                }
            }
            if (secondaryOptions.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(secondaryOptions, key = { "sec-${it.key}" }) { opt ->
                        FilterChip(
                            selected = selectedSecondary == opt.key,
                            onClick = { selectedSecondary = if (selectedSecondary == opt.key) null else opt.key },
                            label = { Text("${opt.label} (${opt.count})") },
                        )
                    }
                }
            }
            if (crossOptions.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(crossOptions, key = { "x-${it.key}" }) { opt ->
                        FilterChip(
                            selected = selectedCross == opt.key,
                            onClick = { selectedCross = if (selectedCross == opt.key) null else opt.key },
                            label = { Text("${opt.label} (${opt.count})") },
                        )
                    }
                }
            }
            PlayButtons(
                onPlay = {
                    if (activeMediaId != null) ui.player.play(activeMediaId)
                    else ui.player.playTracks(filteredTracks, shuffled = false)
                },
                onShuffle = {
                    if (activeMediaId != null) ui.player.play(activeMediaId, shuffled = true)
                    else ui.player.playTracks(filteredTracks, shuffled = true)
                },
                more = menu(
                    if (activeMediaId != null) queueActions(ui, activeMediaId) { filteredTracks } else listOf(
                        Action("Play next") { ui.player.playNextTracks(filteredTracks); ui.toast("Playing next") },
                        Action("Add to queue") { ui.player.addTracksToQueue(filteredTracks); ui.toast("Added to queue") },
                        Action("Add to playlist…") { ui.addToPlaylist = filteredTracks },
                    ),
                    keepActions(ui) { filteredTracks },
                ),
                tracks = filteredTracks,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${plural(filteredAlbums.size, "album")} · ${plural(filteredTracks.size, "song")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (filteredArtists.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactModePill("Albums", viewMode == GroupViewMode.ALBUMS) { viewMode = GroupViewMode.ALBUMS }
                        CompactModePill("Artists", viewMode == GroupViewMode.ARTISTS) { viewMode = GroupViewMode.ARTISTS }
                    }
                }
            }
        }
    }

    if (viewMode == GroupViewMode.ARTISTS && filteredArtists.size > 1) {
        ArtistListRows(ui, filteredArtists, fastScroll = filteredArtists.size > 15, header = header)
    } else {
        AlbumGrid(
            ui = ui,
            albums = filteredAlbums,
            header = header,
            fastScrollKeys = if (filteredAlbums.size > 20) filteredAlbums.map { it.artist } else null,
        ) { a -> if (a.year > 0) "${a.artist} · ${a.year}" else a.artist }
    }
}

@Composable
private fun CompactModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
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

/** "CD rip": every song of the album is from a verified CD rip. Album page only. */
@Composable
private fun CdRipBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text("CD rip", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Small tappable tag pill for Genre, Mood, or Decade metadata. */
@Composable
internal fun TagChip(label: String, isMood: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isMood) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isMood) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (isMood) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (album.cdRip) CdRipBadge()
                    Text(
                        listOfNotNull(album.year.takeIf { it > 0 }?.toString(), plural(album.tracks.size, "song"), duration(album.durationMs)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Tappable genre, mood, and decade chips
                val hasTags = album.genres.isNotEmpty() || album.moods.isNotEmpty() || album.decadeId != null
                if (hasTags) {
                    FlowRow(
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        album.genres.forEach { g ->
                            val group = lib.genreByName(g)
                            if (group != null) {
                                TagChip(group.name) { ui.openGenre(lib, group.id) }
                            }
                        }
                        album.moods.forEach { m ->
                            val group = lib.moodByName(m)
                            if (group != null) {
                                TagChip(group.name, isMood = true) { ui.open(Screen.MoodPage(group.id)) }
                            }
                        }
                        album.decadeId?.let { did ->
                            album.decadeLabel?.let { dlabel ->
                                TagChip(dlabel) { ui.open(Screen.DecadePage(did)) }
                            }
                        }
                    }
                }
            }
            PlayButtons(
                onPlay = { ui.player.play(albumId) }, onShuffle = { ui.player.play(albumId, shuffled = true) },
                more = albumActions(ui, album), tracks = album.tracks,
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
    val artistGenres = remember(artist, lib) { artist.genres.mapNotNull(lib::genreByName).take(4) }
    val artistMoods = remember(artist, lib) { artist.moods.mapNotNull(lib::moodByName).take(4) }
    LazyVerticalGrid(GridCells.Fixed(2), contentPadding = listPadding(horizontal = 10)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    "${plural(artist.albums.size, "album")} · ${plural(artist.tracks.size, "song")}",
                    modifier = Modifier.padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (artistGenres.isNotEmpty() || artistMoods.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        artistGenres.forEach { g -> TagChip(g.name) { ui.openGenre(lib, g.id) } }
                        artistMoods.forEach { m -> TagChip(m.name, isMood = true) { ui.open(Screen.MoodPage(m.id)) } }
                    }
                }
                val tracks = artist.tracks
                PlayButtons(
                    onPlay = { ui.player.play(allId) }, onShuffle = { ui.player.play(allId, shuffled = true) },
                    more = menu(queueActions(ui, allId) { tracks }, keepActions(ui) { tracks }), tracks = tracks,
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
