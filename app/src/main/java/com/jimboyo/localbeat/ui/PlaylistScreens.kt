package com.jimboyo.localbeat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.playback.BrowseTree
import com.jimboyo.localbeat.playlists.Playlist

/** The music server's playlists (Connected edition with a server; else none). */
@Composable
private fun serverPlaylists(ui: Ui): List<Playlist> {
    val flow = ui.app.server?.playlists ?: return emptyList()
    val lists by flow.collectAsStateWithLifecycle()
    return lists
}

@Composable
private fun PlaylistRow(ui: Ui, lib: Library, p: Playlist) {
    val tracks = p.tracks(lib)
    Row(Modifier.fillMaxWidth().clickable { ui.open(Screen.PlaylistPage(p.id)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val cover = tracks.firstNotNullOfOrNull { t -> lib.albumOf(t)?.takeIf { a -> a.tracks.any { it.hasEmbeddedArt } } }
        if (cover != null || tracks.isNotEmpty()) AlbumArt(cover, Modifier.size(64.dp), maxPx = 200)
        else Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(64.dp).padding(12.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${plural(tracks.size, "song")} · ${duration(tracks.sumOf { it.durationMs })}" + if (p.readOnly && p.owner.isNotEmpty()) " · from ${p.owner}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val share = if (p.onServer) emptyList() else listOf(Action("Share") { ui.sharePlaylist(p) })
        MoreButton(queueActions(ui, BrowseTree.PLAYLIST + p.id) { tracks } + share)
    }
}

@Composable
fun PlaylistList(ui: Ui, lib: Library) {
    val playlists by ui.app.playlists.playlists.collectAsStateWithLifecycle()
    val onServer = serverPlaylists(ui)
    var creating by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = listPadding()) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("New playlist") }
                OutlinedButton(onClick = ui.importPlaylist) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Import") }
            }
        }
        if (playlists.isEmpty() && onServer.isEmpty()) item {
            Text(
                "No playlists yet. Make one here, or use “Add to playlist” on any song, album or the queue.",
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(playlists, key = { it.id }) { p -> PlaylistRow(ui, lib, p) }
        if (onServer.isNotEmpty()) {
            item(key = "server-header") {
                Text(
                    "On the music server", Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            items(onServer, key = { "server:" + it.id }) { p -> PlaylistRow(ui, lib, p) }
        }
    }
    if (creating) NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
        creating = false
        ui.open(Screen.PlaylistPage(ui.app.playlists.create(name).id))
    }
}

/** One playlist entry; [key] is unique even when a song is in the playlist twice. */
private data class Entry(val key: String, val rel: String)

/**
 * A music server playlist: plays like a phone one; read only for now (editing
 * comes with LocalBeat docs/control.md step 5).
 */
@Composable
private fun ServerPlaylistPage(ui: Ui, lib: Library, playlist: Playlist) {
    val listId = BrowseTree.PLAYLIST + playlist.id
    val tracks = playlist.tracks(lib)
    val missing = playlist.entries.size - tracks.size
    LazyColumn(contentPadding = listPadding()) {
        item(key = "header") {
            Column {
                val notes = listOfNotNull(
                    "${plural(tracks.size, "song")} · ${duration(tracks.sumOf { it.durationMs })}",
                    if (missing > 0) "$missing not available now" else null,
                    if (playlist.readOnly && playlist.owner.isNotEmpty()) "from ${playlist.owner}" else null,
                )
                Text(notes.joinToString(" · "), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (playlist.comment.isNotBlank()) Text(
                    playlist.comment, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayButtons(
                    onPlay = { ui.player.play(listId) }, onShuffle = { ui.player.play(listId, shuffled = true) },
                    more = listOf(
                        Action("Play next") { ui.player.playNext(listId) },
                        Action("Add to queue") { ui.player.addToQueue(listId) },
                    ) + keepActions(ui) { tracks },
                    tracks = tracks,
                )
                if (tracks.isEmpty()) Text(
                    if (playlist.entries.isEmpty()) "Empty." else "None of its songs can play now: connect to the music server, or keep them on the phone.",
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Positions among the songs present, which is how the browse tree counts.
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
            TrackRow(
                t, playing = ui.player.currentTrackId == t.id,
                subtitle = t.artist.ifBlank { t.filedUnder },
                actions = trackActions(ui, lib, t),
                leading = { Thumb(lib, t) },
            ) { ui.player.play(BrowseTree.inList(listId, i, t.id)) }
        }
    }
}

@Composable
fun PlaylistPage(ui: Ui, lib: Library, id: String) {
    val playlists by ui.app.playlists.playlists.collectAsStateWithLifecycle()
    val onServer = serverPlaylists(ui)
    val playlist = playlists.firstOrNull { it.id == id }
        ?: return onServer.firstOrNull { it.id == id }?.let { ServerPlaylistPage(ui, lib, it) } ?: Unit
    val store = ui.app.playlists
    val listId = BrowseTree.PLAYLIST + id
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val entries = remember(playlist.entries) {
        val seen = HashMap<String, Int>()
        playlist.entries.map { rel -> Entry("$rel#${seen.merge(rel, 1, Int::plus)}", rel) }
    }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(listState, entries, headerCount = 1, keyOf = { it.key }, onMove = { a, b -> store.move(id, a, b) })
    val shown = reorder.items
    val tracks = playlist.tracks(lib)
    val missing = playlist.entries.size - tracks.size

    LazyColumn(state = listState, contentPadding = listPadding()) {
        item(key = "header") {
            Column {
                Text(
                    "${plural(tracks.size, "song")} · ${duration(tracks.sumOf { it.durationMs })}" +
                        if (missing > 0) " · $missing not on this phone" else "",
                    Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayButtons(
                    onPlay = { ui.player.play(listId) }, onShuffle = { ui.player.play(listId, shuffled = true) },
                    more = listOf(
                        Action("Play next") { ui.player.playNext(listId) },
                        Action("Add to queue") { ui.player.addToQueue(listId) },
                        Action("Share") { ui.sharePlaylist(playlist) },
                        Action("Rename") { renaming = true },
                        Action("Delete") { deleting = true },
                    ) + keepActions(ui) { tracks },
                    tracks = tracks,
                )
                if (entries.isEmpty()) Text(
                    "Empty. Use “Add to playlist” on songs, albums or the queue.",
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Positions among the songs that are present, which is how the browse tree counts.
        var present = 0
        val positions = shown.map { e -> if (lib.trackByRelativePath(e.rel) != null) present++ else -1 }
        itemsIndexed(shown, key = { _, e -> e.key }) { i, entry ->
            val t: Track? = lib.trackByRelativePath(entry.rel)
            val handle = @Composable {
                Icon(Icons.Default.DragHandle, "Drag to reorder", reorder.handleModifier(entry.key).padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val rowModifier = reorder.rowModifier(entry.key).then(if (reorder.draggingKey == null) Modifier.animateItem() else Modifier)
            val remove = Action("Remove from playlist") { store.removeAt(id, i) }
            if (t == null) {
                Row(rowModifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(entry.rel.substringAfterLast('/'), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Not on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    MoreButton(listOf(remove))
                    handle()
                }
            } else {
                TrackRow(
                    t, playing = ui.player.currentTrackId == t.id,
                    subtitle = t.artist.ifBlank { t.filedUnder },
                    actions = trackActions(ui, lib, t, extra = listOf(remove)),
                    modifier = rowModifier,
                    leading = { Thumb(lib, t) },
                    trailing = handle,
                ) { ui.player.play(BrowseTree.inList(listId, positions[i], t.id)) }
            }
        }
    }

    if (renaming) NameDialog("Rename playlist", playlist.name, "Rename", onDismiss = { renaming = false }) {
        renaming = false
        store.rename(id, it)
    }
    if (deleting) ConfirmDialog("Delete “${playlist.name}”?", "The songs stay in your library.", "Delete", onDismiss = { deleting = false }) {
        ui.back()
        store.delete(id)
    }
}

/** The "add to playlist" dialog, then a name prompt if a new playlist was chosen. */
@Composable
fun AddToPlaylistFlow(ui: Ui, tracks: List<Track>) {
    val playlists by ui.app.playlists.playlists.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    val rels = tracks.map { Library.relativePath(it.path) }
    fun done(name: String) {
        ui.addToPlaylist = null
        ui.toast(if (tracks.size == 1) "Added to $name" else "Added ${tracks.size} songs to $name")
    }
    if (naming) {
        NameDialog("New playlist", "", "Create", onDismiss = { ui.addToPlaylist = null }) { name ->
            val p = ui.app.playlists.create(name, rels)
            done(p.name)
        }
    } else {
        AddToPlaylistDialog(playlists, tracks.size, onDismiss = { ui.addToPlaylist = null }, onNew = { naming = true }) { p ->
            ui.app.playlists.add(p.id, rels)
            done(p.name)
        }
    }
}
