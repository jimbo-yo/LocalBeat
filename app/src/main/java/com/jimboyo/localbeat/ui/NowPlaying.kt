package com.jimboyo.localbeat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jimboyo.localbeat.library.Library

/** Below the expanded controls: the big cover, then the queue (drag to reorder). */
@Composable
fun NowPlayingBody(ui: Ui, lib: Library, modifier: Modifier) {
    val p = ui.player
    val track = p.currentTrackId?.let(lib::track)
    val album = track?.let(lib::albumOf)
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(listState, p.queue, headerCount = 2, keyOf = { it.key }, onMove = { a, b -> p.move(a, b) })
    val queue = reorder.items
    val remainingMs = queue.drop(p.currentIndex + 1).sumOf { lib.track(it.trackId)?.durationMs ?: 0 }

    LazyColumn(modifier, state = listState, contentPadding = listPadding()) {
        item(key = "art") {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AlbumArt(album, Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1f), maxPx = 1200)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (album != null) {
                        TextButton(onClick = { ui.open(Screen.AlbumPage(album.id)) }) { Text("Go to album") }
                        TextButton(onClick = { ui.open(Screen.ArtistPage(album.artistId)) }) { Text("Go to artist") }
                    }
                    if (track != null) TextButton(onClick = { ui.addToPlaylist = listOf(track) }) { Text("Add to playlist") }
                }
            }
        }
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Queue", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${plural(queue.size, "song")} · ${duration(remainingMs)} left" + if (p.shuffle) " · shuffled" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { ui.addToPlaylist = queue.mapNotNull { lib.track(it.trackId) } }) { Text("Save") }
                TextButton(onClick = p::clearUpcoming, enabled = queue.size > 1) { Text("Clear") }
            }
        }
        itemsIndexed(queue, key = { _, e -> e.key }) { i, entry ->
            val t = lib.track(entry.trackId)
            val current = i == p.currentIndex && reorder.draggingKey == null
            val bg = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background
            Row(
                reorder.rowModifier(entry.key).then(if (reorder.draggingKey == null) Modifier.animateItem() else Modifier)
                    .fillMaxWidth().background(bg).clickable { p.skipTo(i) }.padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.DragHandle, "Drag to reorder",
                    reorder.handleModifier(entry.key).padding(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    AlbumArt(t?.let(lib::albumOf), Modifier.size(44.dp), maxPx = 160)
                    if (current) PlayingOverlay(Modifier.matchParentSize())
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        t?.title ?: "Missing song", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (current) FontWeight.Bold else null,
                        color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(t?.let { it.artist.ifBlank { it.filedUnder } }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(duration(t?.durationMs ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { p.removeAt(i) }, enabled = i != p.currentIndex && reorder.draggingKey == null) { Icon(Icons.Default.Close, "Remove from queue") }
            }
        }
    }
}
