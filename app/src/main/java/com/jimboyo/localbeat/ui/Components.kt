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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
)

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

@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit, more: List<Action> = emptyList()) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onPlay) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play") }
        FilledTonalButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
        Spacer(Modifier.weight(1f))
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
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(duration(t.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        MoreButton(actions)
        trailing()
    }
}

@Composable
fun TrackNumber(t: Track) {
    Text(if (t.trackNumber > 0) "${t.trackNumber}" else "", Modifier.width(28.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Thumb(lib: Library, t: Track) = AlbumArt(lib.albumOf(t), Modifier.size(48.dp), maxPx = 160)

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
                items(playlists, key = { it.id }) { p ->
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
