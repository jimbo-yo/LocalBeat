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
import androidx.compose.runtime.LaunchedEffect
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
import com.jimboyo.localbeat.playlists.MoveToServer
import com.jimboyo.localbeat.playlists.Playlist

// Playlists: the phone's own (PlaylistStore) and, with a music server, the
// user's playlists there plus ones shared with them (docs/control.md, steps
// 4-5). With a server set up, new playlists are made there; the phone's own
// can be moved there.

/** The music server's playlists (Connected edition with a server; else none). */
@Composable
private fun serverPlaylists(ui: Ui): List<Playlist> {
    val flow = ui.app.server?.playlists ?: return emptyList()
    val lists by flow.collectAsStateWithLifecycle()
    return lists
}

/** Changes to one playlist the user may change: the phone's own, or theirs on the server. */
private class Editor(
    val rename: (String) -> Unit,
    val removeAt: (Int) -> Unit,
    val move: (Int, Int) -> Unit,
    val delete: () -> Unit,
)

private fun editorFor(ui: Ui, p: Playlist): Editor? {
    if (!p.onServer) {
        val store = ui.app.playlists
        return Editor({ store.rename(p.id, it) }, { store.removeAt(p.id, it) }, { a, b -> store.move(p.id, a, b) }, { store.delete(p.id) })
    }
    val server = ui.app.server ?: return null
    if (p.readOnly) return null
    return Editor(
        rename = { server.renamePlaylist(p.id, it) },
        removeAt = { server.removeFromPlaylist(p.id, listOf(it)) },
        // The list as it is now (a drag handler may hold an older copy of the playlist).
        move = { from, to ->
            val now = server.playlists.value.firstOrNull { it.id == p.id }?.entries ?: p.entries
            if (from in now.indices && to in now.indices) server.setPlaylistSongs(p.id, now.toMutableList().apply { add(to, removeAt(from)) })
        },
        delete = { server.deletePlaylist(p.id) },
    )
}

/** Tells the user about playlist changes the server didn't keep. Shown once each. */
@Composable
fun PlaylistNotices(ui: Ui) {
    val server = ui.app.server ?: return
    val notice by server.playlistNotice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        notice?.let {
            ui.toast(it)
            server.clearPlaylistNotice()
        }
    }
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
        MoreButton(menu(queueActions(ui, BrowseTree.PLAYLIST + p.id) { tracks }, share + keepActions(ui) { tracks }))
    }
}

@Composable
private fun SectionTitle(text: String) = Text(
    text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
)

@Composable
fun PlaylistList(ui: Ui, lib: Library) {
    val phone by ui.app.playlists.playlists.collectAsStateWithLifecycle()
    val onServer = serverPlaylists(ui)
    val server = ui.app.server
    val useServer = server?.playlistsOnServer == true
    val pending = server?.pendingEdits?.collectAsStateWithLifecycle()?.value ?: 0
    val mine = onServer.filter { !it.readOnly }
    val shared = onServer.filter { it.readOnly }
    var creating by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = listPadding()) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("New playlist") }
                OutlinedButton(onClick = ui.importPlaylist) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Import") }
            }
        }
        if (pending > 0) item {
            Text(
                "${plural(pending, "change")} waiting to be sent to the music server",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (phone.isEmpty() && onServer.isEmpty()) item {
            Text(
                "No playlists yet. Make one here, or use “Add to playlist” on any song, album or the queue.",
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (useServer) {
            // The user's playlists are the server's; the phone's own come last, to move there.
            items(mine, key = { "server:" + it.id }) { p -> PlaylistRow(ui, lib, p) }
            if (shared.isNotEmpty()) {
                item(key = "shared-header") { SectionTitle("Shared with you") }
                items(shared, key = { "server:" + it.id }) { p -> PlaylistRow(ui, lib, p) }
            }
            if (phone.isNotEmpty()) {
                item(key = "phone-header") {
                    Column {
                        SectionTitle("Only on this phone")
                        OutlinedButton(onClick = { moving = true }, Modifier.padding(horizontal = 16.dp)) { Text("Move to the music server") }
                    }
                }
                items(phone, key = { it.id }) { p -> PlaylistRow(ui, lib, p) }
            }
        } else {
            items(phone, key = { it.id }) { p -> PlaylistRow(ui, lib, p) }
            if (onServer.isNotEmpty()) {
                item(key = "server-header") { SectionTitle("On the music server") }
                items(onServer, key = { "server:" + it.id }) { p -> PlaylistRow(ui, lib, p) }
            }
        }
    }
    if (creating) NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
        creating = false
        val id = if (useServer) server!!.createPlaylist(name, emptyList()) else ui.app.playlists.create(name).id
        ui.open(Screen.PlaylistPage(id))
    }
    if (moving && server != null) ConfirmDialog(
        "Move ${plural(phone.size, "playlist")} to the music server?",
        "They'll be on all your devices and leave this phone's own list. Songs the music server doesn't have are left out.",
        "Move", onDismiss = { moving = false },
    ) {
        moving = false
        val all = ui.app.library.library.value ?: lib
        var left = 0
        for (p in phone) {
            val r = MoveToServer.serverSongs(p, all, server.tracks.value)
            server.createPlaylist(p.name, r.trackIds)
            ui.app.playlists.delete(p.id)
            left += r.notFound.size
        }
        ui.toast(
            "Moved ${plural(phone.size, "playlist")}" +
                if (left > 0) "; ${plural(left, "song")} the music server doesn't have were left out" else ""
        )
    }
}

/** One playlist entry; [key] is unique even when a song is in the playlist twice. */
private data class Entry(val key: String, val rel: String)

/** Someone else's playlist shared with the user (or public): play it, or copy it to make it theirs. */
@Composable
private fun SharedPlaylistPage(ui: Ui, lib: Library, playlist: Playlist) {
    val listId = BrowseTree.PLAYLIST + playlist.id
    val tracks = playlist.tracks(lib)
    val missing = playlist.entries.size - tracks.size
    val server = ui.app.server
    LazyColumn(contentPadding = listPadding()) {
        item(key = "header") {
            Column {
                val notes = listOfNotNull(
                    "${plural(tracks.size, "song")} · ${duration(tracks.sumOf { it.durationMs })}",
                    if (missing > 0) "$missing not available now" else null,
                    if (playlist.owner.isNotEmpty()) "from ${playlist.owner}" else null,
                )
                Text(notes.joinToString(" · "), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (playlist.comment.isNotBlank()) Text(
                    playlist.comment, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayButtons(
                    onPlay = { ui.player.play(listId) }, onShuffle = { ui.player.play(listId, shuffled = true) },
                    more = menu(
                        queueActions(ui, listId) { tracks },
                        listOfNotNull(server?.let { s ->
                            Action("Make a copy") {
                                ui.open(Screen.PlaylistPage(s.createPlaylist(playlist.name, playlist.entries)))
                                ui.toast("Copied: it's yours to change")
                            }
                        }) + keepActions(ui) { tracks },
                    ),
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

/** What to call an entry whose song can't play now. */
private fun missingName(ui: Ui, p: Playlist, entry: String): String =
    if (!p.onServer) entry.substringAfterLast('/')
    else ui.app.server?.tracks?.value?.firstOrNull { it.id == entry }?.let { "${it.artist.ifBlank { it.filedUnder }} – ${it.title}" } ?: "A song"

@Composable
fun PlaylistPage(ui: Ui, lib: Library, id: String) {
    val phone by ui.app.playlists.playlists.collectAsStateWithLifecycle()
    val onServer = serverPlaylists(ui)
    // A playlist made on the phone gets the server's ID once it has been sent.
    val serverId = ui.app.server?.playlistId(id) ?: id
    val playlist = phone.firstOrNull { it.id == id } ?: onServer.firstOrNull { it.id == serverId }
    if (playlist == null) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("This playlist isn't available right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.Button(onClick = ui::back) { Text("Back") }
        }
        return
    }
    val editor = editorFor(ui, playlist) ?: return SharedPlaylistPage(ui, lib, playlist)
    val listId = BrowseTree.PLAYLIST + playlist.id
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val entries = remember(playlist.entries) {
        val seen = HashMap<String, Int>()
        playlist.entries.map { e -> Entry("$e#${seen.merge(e, 1, Int::plus)}", e) }
    }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(listState, entries, headerCount = 1, keyOf = { it.key }, onMove = { a, b -> editor.move(a, b) })
    val shown = reorder.items
    val tracks = playlist.tracks(lib)
    val missing = playlist.entries.size - tracks.size

    LazyColumn(state = listState, contentPadding = listPadding()) {
        item(key = "header") {
            Column {
                Text(
                    "${plural(tracks.size, "song")} · ${duration(tracks.sumOf { it.durationMs })}" +
                        if (missing > 0) " · $missing ${if (playlist.onServer) "not available now" else "not on this phone"}" else "",
                    Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (playlist.comment.isNotBlank()) Text(
                    playlist.comment, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayButtons(
                    onPlay = { ui.player.play(listId) }, onShuffle = { ui.player.play(listId, shuffled = true) },
                    more = menu(
                        queueActions(ui, listId) { tracks },
                        (if (playlist.onServer) emptyList() else listOf(Action("Share") { ui.sharePlaylist(playlist) })) + listOf(
                            Action("Rename") { renaming = true },
                            Action("Delete") { deleting = true },
                        ) + keepActions(ui) { tracks },
                    ),
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
        val positions = shown.map { e -> if (playlist.resolve(e.rel, lib) != null) present++ else -1 }
        itemsIndexed(shown, key = { _, e -> e.key }) { i, entry ->
            val t: Track? = playlist.resolve(entry.rel, lib)
            val handle = @Composable {
                Icon(Icons.Default.DragHandle, "Drag to reorder", reorder.handleModifier(entry.key).padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val rowModifier = reorder.rowModifier(entry.key).then(if (reorder.draggingKey == null) Modifier.animateItem() else Modifier)
            val remove = Action("Remove from playlist") { editor.removeAt(i) }
            if (t == null) {
                Row(rowModifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(missingName(ui, playlist, entry.rel), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (playlist.onServer) "Not available now" else "Not on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
        editor.rename(it)
    }
    if (deleting) ConfirmDialog(
        "Delete “${playlist.name}”?",
        if (playlist.onServer) "From all your devices. The songs stay in your library." else "The songs stay in your library.",
        "Delete", onDismiss = { deleting = false },
    ) {
        ui.back()
        editor.delete()
    }
}

/** The "add to playlist" dialog, then a name prompt if a new playlist was chosen. */
@Composable
fun AddToPlaylistFlow(ui: Ui, tracks: List<Track>) {
    val phone by ui.app.playlists.playlists.collectAsStateWithLifecycle()
    val server = ui.app.server
    val useServer = server?.playlistsOnServer == true
    val mine = serverPlaylists(ui).filter { !it.readOnly }
    var naming by remember { mutableStateOf(false) }
    val rels = tracks.map { Library.relativePath(it.path) }
    // For server playlists: the server's song for each (a phone-only song can't go there).
    val all = ui.app.library.library.value
    val serverIds = tracks.mapNotNull { all?.serverId(it) }
    val skipped = tracks.size - serverIds.size
    fun done(name: String, added: Int, left: Int) {
        ui.addToPlaylist = null
        ui.toast(
            (if (added == 1) "Added to $name" else "Added $added songs to $name") +
                if (left > 0) "; ${plural(left, "song")} only on this phone left out" else ""
        )
    }
    if (naming) {
        NameDialog("New playlist", "", "Create", onDismiss = { ui.addToPlaylist = null }) { name ->
            if (useServer) {
                server!!.createPlaylist(name, serverIds)
                done(name, serverIds.size, skipped)
            } else {
                val p = ui.app.playlists.create(name, rels)
                done(p.name, tracks.size, 0)
            }
        }
    } else {
        // The user's server playlists first, then the phone's own.
        AddToPlaylistDialog(mine + phone, tracks.size, onDismiss = { ui.addToPlaylist = null }, onNew = { naming = true }) { p ->
            if (p.onServer) {
                server?.addToPlaylist(p.id, serverIds)
                done(p.name, serverIds.size, skipped)
            } else {
                ui.app.playlists.add(p.id, rels)
                done(p.name, tracks.size, 0)
            }
        }
    }
}
