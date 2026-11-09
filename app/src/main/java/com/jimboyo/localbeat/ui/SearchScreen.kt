package com.jimboyo.localbeat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Search
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.playback.BrowseTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The Search tab: artists, albums and songs, phone and server alike, as you type. */
@Composable
fun SearchScreen(ui: Ui, lib: Library) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val query = ui.searchQuery
    val results by produceState<Search.Results?>(null, query, lib) {
        delay(120) // let fast typing settle
        value = withContext(Dispatchers.Default) { Search.run(query, lib) }
    }
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { ui.searchQuery = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            singleLine = true,
            placeholder = { Text("Albums, artists, songs") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { ui.searchQuery = ""; focus.requestFocus() }) { Icon(Icons.Default.Clear, "Clear") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        )
        val r = results
        LazyColumn(contentPadding = listPadding()) {
            when {
                query.isBlank() -> item { Hint("Type part of a name: “zep”, “morning glory”, “springsteen thunder”.") }
                r == null -> Unit
                r.isEmpty -> item { Hint("Nothing found for “$query”.") }
            }
            if (query.isNotBlank() && r != null) {
                if (r.artists.isNotEmpty()) item(key = "h-artists") { Heading("Artists") }
                items(r.artists, key = { "ar-" + it.id }) { artist ->
                    Row(Modifier.fillMaxWidth().clickable { ui.open(Screen.ArtistPage(artist.id)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArt(coverAlbum(artist.albums), Modifier.size(48.dp), maxPx = 160)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(plural(artist.albums.size, "album"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (r.albums.isNotEmpty()) item(key = "h-albums") { Heading("Albums") }
                items(r.albums, key = { "al-" + it.id }) { album ->
                    Row(Modifier.fillMaxWidth().clickable { ui.open(Screen.AlbumPage(album.id)) }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArt(album, Modifier.size(48.dp), maxPx = 160)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(album.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                KeepMark(album.tracks, Modifier.padding(end = 4.dp))
                                Text(
                                    listOfNotNull(album.artist, album.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        MoreButton(albumActions(ui, album))
                    }
                }
                if (r.tracks.isNotEmpty()) item(key = "h-songs") { Heading("Songs") }
                items(r.tracks, key = { "t-" + it.id }) { t ->
                    TrackRow(
                        t, playing = ui.player.currentTrackId == t.id,
                        subtitle = listOf(t.artist.ifBlank { t.filedUnder }, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                        actions = trackActions(ui, lib, t),
                        leading = { Thumb(lib, t) },
                    ) {
                        keyboard?.hide()
                        ui.player.play(playId(lib, t))
                    }
                }
            }
        }
    }
}

/** A song tapped in the results plays its album from that song. */
private fun playId(lib: Library, t: Track): String {
    val album = lib.albumOf(t) ?: return BrowseTree.TRACK + t.id
    return BrowseTree.inList(BrowseTree.ALBUM + album.id, album.tracks.indexOf(t), t.id)
}

@Composable
private fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))

@Composable
private fun Hint(text: String) =
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
