@file:OptIn(ExperimentalLayoutApi::class)

package com.jimboyo.localbeat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimboyo.localbeat.library.Album
import com.jimboyo.localbeat.library.AlbumGroup
import com.jimboyo.localbeat.library.BroadGenreGroup
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.SortKey
import com.jimboyo.localbeat.playback.BrowseTree

/**
 * Explore, the entry point for discovering music: Shuffle all, Random album,
 * Recently added, Moods, Genres (with subgenre previews) and Decades.
 */
@Composable
fun ExploreScreen(ui: Ui, lib: Library) {
    val onPhone by ui.app.library.onPhoneOnly.collectAsStateWithLifecycle()
    var genresByCount by rememberSaveable { mutableStateOf(true) }

    val recent = remember(lib) { lib.recentlyAdded.take(12) }
    val moodsByCount = remember(lib) {
        lib.moods.sortedWith(compareByDescending<AlbumGroup> { it.albums.size }.thenBy { SortKey.of(it.name) })
    }
    val genres = remember(lib, genresByCount) {
        if (genresByCount) {
            lib.broadGenres.sortedWith(compareByDescending<BroadGenreGroup> { it.albums.size }.thenBy { SortKey.of(it.name) })
        } else {
            lib.broadGenres
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
    ) {
        // ---- Library summary + Quick Play ----
        item(key = "overview") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val stats = buildString {
                    append("${plural(lib.albums.size, "album")} · ${plural(lib.artists.size, "artist")} · ${plural(lib.tracks.size, "song")}")
                    if (onPhone) append(" · On this phone")
                }
                Text(stats, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrismPill(onClick = { ui.player.play(BrowseTree.SHUFFLE_ALL) }) {
                        Icon(Icons.Default.Shuffle, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle all", style = MaterialTheme.typography.labelLarge)
                    }
                    if (lib.albums.isNotEmpty()) {
                        FilledTonalButton(onClick = {
                            lib.albums.randomOrNull()?.let { album ->
                                ui.player.play(BrowseTree.ALBUM + album.id)
                                ui.open(Screen.AlbumPage(album.id))
                            }
                        }) {
                            Icon(Icons.Default.Casino, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Random album")
                        }
                    }
                }
            }
        }

        // ---- Recently Added shelf ----
        if (recent.isNotEmpty()) {
            item(key = "recent-header") {
                ExploreSectionHeader(
                    title = "Recently added",
                    actionLabel = if (lib.albums.size > 4) "See all" else null,
                    onAction = { ui.open(Screen.RecentPage) },
                )
            }
            item(key = "recent-shelf") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(recent, key = { it.id }) { album ->
                        RecentAlbumCard(ui, album)
                    }
                }
            }
        }

        // ---- Moods ----
        item(key = "moods-header") {
            ExploreSectionHeader(title = "Moods")
        }
        item(key = "moods-body") {
            if (moodsByCount.isEmpty()) {
                Text(
                    "No mood tags yet. Use AI Genres & Moods in LocalBeat Studio to tag your albums by mood.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    moodsByCount.forEach { mood ->
                        MoodPill(mood) { ui.open(Screen.MoodPage(mood.id)) }
                    }
                }
            }
        }

        // ---- Broad Genres ----
        item(key = "genres-header") {
            ExploreSectionHeader(
                title = "Genres",
                actionLabel = if (genres.size > 1) { if (genresByCount) "Sort: A–Z" else "Sort: Size" } else null,
                onAction = { genresByCount = !genresByCount },
            )
        }
        if (genres.isEmpty()) {
            item(key = "genres-empty") {
                Text(
                    "None of your albums have a genre tag yet. New rips get them from MusicBrainz, or enrich them in LocalBeat Studio.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(genres, key = { "bg-" + it.id }) { bg ->
                BroadGenreRow(bg) {
                    if (bg.subgenres.isNotEmpty()) ui.open(Screen.BroadGenrePage(bg.id))
                    else ui.open(Screen.GenrePage(bg.id))
                }
            }
        }

        // ---- Decades ----
        if (lib.decades.isNotEmpty()) {
            item(key = "decades-header") {
                ExploreSectionHeader(title = "Decades")
            }
            item(key = "decades-shelf") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(lib.decades, key = { it.id }) { d ->
                        DecadeCard(d) { ui.open(Screen.DecadePage(d.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreSectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun RecentAlbumCard(ui: Ui, album: Album) {
    Column(modifier = Modifier.width(140.dp)) {
        AlbumTile(album, album.artist, albumActions(ui, album)) { ui.open(Screen.AlbumPage(album.id)) }
    }
}

@Composable
private fun MoodPill(mood: AlbumGroup, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(mood.name, style = MaterialTheme.typography.labelLarge)
            Text(
                "${mood.albums.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun BroadGenreRow(bg: BroadGenreGroup, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(coverAlbum(bg.albums), Modifier.size(56.dp), maxPx = 180)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bg.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        plural(bg.albums.size, "album"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (bg.subgenres.isNotEmpty()) {
                    val preview = bg.subgenres.take(3).joinToString(" · ") { it.name } +
                        if (bg.subgenres.size > 3) " +${bg.subgenres.size - 3}" else ""
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DecadeCard(d: AlbumGroup, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(coverAlbum(d.albums), Modifier.size(44.dp), maxPx = 150)
            Column(Modifier.padding(start = 10.dp, end = 6.dp)) {
                Text(d.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    plural(d.albums.size, "album"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
