package com.jimboyo.localbeat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jimboyo.localbeat.library.Album
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.playlists.Playlist

/** A menu entry. */
class Action(val label: String, val run: () -> Unit)

/** The standard actions for anything playable: a song, album, artist, playlist… */
fun queueActions(ui: Ui, mediaId: String, tracks: () -> List<Track>): List<Action> = listOf(
    Action("Play next") { ui.player.playNext(mediaId); ui.toast("Playing next") },
    Action("Add to queue") { ui.player.addToQueue(mediaId); ui.toast("Added to queue") },
    Action("Add to playlist…") { ui.addToPlaylist = tracks() },
) + keepActions(ui, tracks)

/** "Keep on phone" / "Remove from phone" for whatever of these is on the music server. */
fun keepActions(ui: Ui, tracks: () -> List<Track>): List<Action> {
    val server = ui.app.server ?: return emptyList()
    val list = tracks().filter { it.onServer }
    if (list.isEmpty()) return emptyList()
    val pinned = ui.offline.pinned
    return listOfNotNull(
        Action("Keep on phone") { keep(ui, list) }.takeIf { list.any { it.id !in pinned } },
        Action("Remove from phone") {
            server.unpin(list.map { it.id })
            ui.toast("Removed from phone")
        }.takeIf { list.any { it.id in pinned } },
    )
}

private fun keep(ui: Ui, list: List<Track>) {
    val server = ui.app.server ?: return
    val new = list.count { it.id !in ui.offline.pinned }
    server.pin(list)
    ui.toast(if (new == 1) "Keeping 1 song on this phone" else "Keeping $new songs on this phone")
}

/**
 * For songs on the music server: a cloud; tap it and they download and stay on
 * the phone, shown by a pin. Tap the pin to remove them again (asks first for more
 * than one song). Nothing for songs that are on the phone anyway.
 */
@Composable
fun KeepButton(tracks: List<Track>, iconSize: Dp = 18.dp) {
    val ui = LocalUi.current ?: return
    val server = ui.app.server ?: return
    val list = tracks.filter { it.onServer }
    if (list.isEmpty()) return
    val off = ui.offline
    val ready = list.count { it.id in off.ready }
    val pinned = list.count { it.id in off.pinned }
    var confirm by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            when {
                pinned < list.size -> keep(ui, list)
                list.size > 1 -> confirm = true
                else -> { server.unpin(list.map { it.id }); ui.toast(if (ready == 1) "Removed from phone" else "Download cancelled") }
            }
        },
        modifier = Modifier.size(iconSize + 18.dp),
    ) {
        when {
            ready == list.size -> Icon(Icons.Filled.PushPin, "Kept on this phone", Modifier.size(iconSize), tint = MaterialTheme.colorScheme.primary)
            pinned == list.size -> {
                val current = if (list.any { it.id == off.downloading }) off.progress else 0f
                val progress = (ready + current) / list.size
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress }, Modifier.size(iconSize), strokeWidth = 2.dp, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            else -> Icon(Icons.Outlined.Cloud, "On the music server: tap to keep on this phone", Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirm) ConfirmDialog(
        "Remove from phone?", "The ${list.size} songs stay on the music server; they just won't play without it.", "Remove",
        onDismiss = { confirm = false },
    ) {
        server.unpin(list.map { it.id })
        ui.toast("Removed from phone")
    }
}

@Composable
fun MoreButton(actions: List<Action>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        ActionMenu(open, actions) { open = false }
    }
}

@Composable
fun ActionMenu(open: Boolean, actions: List<Action>, onDismiss: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        actions.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { onDismiss(); a.run() }) }
    }
}

/** Standard list padding (the bottom bar or Now Playing handles the gesture-bar inset). */
fun listPadding(horizontal: Int = 0) = PaddingValues(start = horizontal.dp, end = horizontal.dp, top = 8.dp, bottom = 16.dp)

/**
 * Play and Shuffle for an album, artist, playlist… While the song playing is one of
 * [tracks], Play becomes Pause (and, paused, Play carries on rather than restarting).
 * [tracks] also gets the keep-on-phone button when some are on the music server.
 */
@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit, more: List<Action> = emptyList(), tracks: List<Track> = emptyList()) {
    val player = LocalPlayer.current
    val current = player?.currentTrackId
    val here = player != null && current != null && tracks.any { it.id == current }
    val pause = here && player?.isPlaying == true
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PrismPill(onClick = { if (here) player?.togglePlay() else onPlay() }) {
            Icon(if (pause) Icons.Default.Pause else Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text(if (pause) "Pause" else "Play", style = MaterialTheme.typography.labelLarge)
        }
        FilledTonalButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
        Spacer(Modifier.weight(1f))
        KeepButton(tracks, iconSize = 24.dp)
        if (more.isNotEmpty()) MoreButton(more)
    }
}

/** A big cover tile for album grids. Long-press for the action menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumTile(album: Album, subtitle: String, actions: List<Action>, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(6.dp)) {
            AlbumArt(album, Modifier.fillMaxWidth().aspectRatio(1f), maxPx = 600)
            Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeepMark(album.tracks, Modifier.padding(end = 4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ActionMenu(menu, actions) { menu = false }
    }
}

@Composable
fun TrackRow(
    t: Track, playing: Boolean, subtitle: String?, actions: List<Action>,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    val color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Column(Modifier.weight(1f).padding(start = 16.dp, top = 6.dp, bottom = 6.dp)) {
            Text(t.title, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = if (playing) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.onServer) Box(Modifier.padding(start = 4.dp)) { KeepButton(listOf(t)) }
        Text(duration(t.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        MoreButton(actions)
        trailing()
    }
}

/**
 * A small mark for an album that's on the music server: a cloud, or a pin once
 * it's all kept on the phone. Nothing for albums on the phone, or partly so.
 */
@Composable
fun KeepMark(tracks: List<Track>, modifier: Modifier = Modifier) {
    if (tracks.isEmpty() || !tracks.all { it.onServer }) return
    val kept = LocalUi.current?.offline?.ready?.let { ready -> tracks.all { it.id in ready } } == true
    if (kept) Icon(Icons.Filled.PushPin, "Kept on this phone", modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
    else Icon(Icons.Outlined.Cloud, "On the music server", modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun TrackNumber(t: Track) {
    if (isCurrent(t.id)) Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) { PlayingBars(size = 18.dp) }
    else Text(if (t.trackNumber > 0) "${t.trackNumber}" else "", Modifier.width(28.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Thumb(lib: Library, t: Track, size: Int = 48) {
    Box {
        AlbumArt(lib.albumOf(t), Modifier.size(size.dp), maxPx = 160)
        if (isCurrent(t.id)) PlayingOverlay(Modifier.matchParentSize())
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a playlist (or make a new one) to add songs to. */
@Composable
fun AddToPlaylistDialog(playlists: List<Playlist>, count: Int, onDismiss: () -> Unit, onNew: () -> Unit, onPick: (Playlist) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Add 1 song to…" else "Add $count songs to…") },
        text = {
            LazyColumn {
                item {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onNew).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null)
                        Text("New playlist", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
                items(playlists, key = { "${if (it.onServer) "s" else "p"}:${it.id}" }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null)
                        Text(p.name, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
