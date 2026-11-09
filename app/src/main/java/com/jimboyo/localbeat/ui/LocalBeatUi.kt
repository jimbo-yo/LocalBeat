@file:OptIn(ExperimentalMaterial3Api::class)

package com.jimboyo.localbeat.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.jimboyo.localbeat.LocalBeatApp
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.playback.BrowseTree
import com.jimboyo.localbeat.playlists.Playlist
import kotlinx.coroutines.delay

sealed interface Screen {
    data class AlbumPage(val id: String) : Screen
    data class ArtistPage(val id: String) : Screen
    data class GenrePage(val id: String) : Screen
    data class DecadePage(val id: String) : Screen
    data class PlaylistPage(val id: String) : Screen
    data object NowPlaying : Screen
    data object Equalizer : Screen
}

/** The bottom navigation bar's destinations. */
enum class Dest(val label: String, val icon: ImageVector) {
    ALBUMS("Albums", Icons.Default.Album),
    SEARCH("Search", Icons.Default.Search),
    ARTISTS("Artists", Icons.Default.Person),
    PLAYLISTS("Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    BROWSE("Browse", Icons.Default.Explore),
    SETTINGS("Settings", Icons.Default.Settings),
}

/** The lists inside Browse. */
enum class BrowseTab(val label: String) { GENRES("Genres"), DECADES("Decades"), SONGS("Songs"), RECENT("Recent") }

/** Navigation and dialog state, plus the things screens need. */
class Ui(
    val app: LocalBeatApp,
    val player: PlayerConnection,
    val importPlaylist: () -> Unit,
    val sharePlaylist: (Playlist) -> Unit,
) {
    /** Pages opened on top of the current destination (album, artist, Now Playing…). */
    val stack = mutableStateListOf<Screen>()
    var dest by mutableStateOf(Dest.ALBUMS)
    var browseTab by mutableStateOf(BrowseTab.GENRES)
    /** Songs waiting for the "add to playlist" dialog. */
    var addToPlaylist by mutableStateOf<List<Track>?>(null)
    /** What's typed in the Search tab (kept while browsing results). */
    var searchQuery by mutableStateOf("")

    fun open(s: Screen) {
        if (stack.lastOrNull() != s) stack.add(s)
    }

    fun back() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        else if (dest != Dest.ALBUMS) dest = Dest.ALBUMS
    }

    /** Bottom bar tap: that destination's main list (tapping it again also comes back to it). */
    fun go(d: Dest) {
        stack.clear()
        dest = d
    }

    fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
}

@Composable
fun LocalBeatUi(ui: Ui, hasPermission: Boolean, onGrant: () -> Unit) {
    val library by ui.app.library.library.collectAsStateWithLifecycle()
    val scanning by ui.app.library.scanning.collectAsStateWithLifecycle()
    val lib = library ?: Library.EMPTY
    val top = ui.stack.lastOrNull()

    BackHandler(enabled = ui.stack.isNotEmpty() || ui.dest != Dest.ALBUMS) { ui.back() }

    CompositionLocalProvider(LocalPlayer provides ui.player) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The player controls are always at the top, whatever screen is showing.
            TopControls(ui, lib, expanded = top == Screen.NowPlaying)
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

            if (top == Screen.NowPlaying) {
                NowPlayingBody(ui, lib, Modifier.weight(1f).navigationBarsPadding())
                return@Column
            }
            // Only pages opened on top get a title row (they need a back button);
            // the main lists use the whole space.
            if (top != null) BackBar(ui, lib, top)
            if (top == null && ui.dest == Dest.BROWSE) {
                PrimaryTabRow(selectedTabIndex = ui.browseTab.ordinal) {
                    BrowseTab.entries.forEach { t ->
                        Tab(selected = ui.browseTab == t, onClick = { ui.browseTab = t }, text = { Text(t.label, style = MaterialTheme.typography.titleSmall) })
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    top == null && ui.dest == Dest.SETTINGS -> SettingsScreen(ui, lib)
                    top == Screen.Equalizer -> EqualizerScreen(ui)
                    !hasPermission -> Message("LocalBeat needs access to your music files.", "Allow access", onGrant)
                    library == null -> Unit
                    lib.tracks.isEmpty() -> Message("No music found in Internal Storage/Music.", "Rescan") { ui.app.library.rescan() }
                    top is Screen.AlbumPage -> lib.album(top.id)?.let { AlbumPage(ui, lib, it) }
                    top is Screen.ArtistPage -> lib.artist(top.id)?.let { ArtistPage(ui, lib, it) }
                    top is Screen.GenrePage -> lib.genre(top.id)?.let { GroupPage(ui, it, BrowseTree.GENRE + it.id) }
                    top is Screen.DecadePage -> lib.decade(top.id)?.let { GroupPage(ui, it, BrowseTree.DECADE + it.id) }
                    top is Screen.PlaylistPage -> PlaylistPage(ui, lib, top.id)
                    else -> when (ui.dest) {
                        Dest.ALBUMS -> AlbumGrid(ui, lib.albums, fastScroll = true) { it.artist }
                        Dest.SEARCH -> SearchScreen(ui, lib)
                        Dest.ARTISTS -> ArtistList(ui, lib)
                        Dest.PLAYLISTS -> PlaylistList(ui, lib)
                        Dest.SETTINGS -> Unit
                        Dest.BROWSE -> when (ui.browseTab) {
                            BrowseTab.GENRES -> GroupList(ui, lib.genres, emptyText = GENRE_HELP) { ui.open(Screen.GenrePage(it.id)) }
                            BrowseTab.DECADES -> GroupList(ui, lib.decades, emptyText = "No albums have a year tag.") { ui.open(Screen.DecadePage(it.id)) }
                            BrowseTab.SONGS -> SongList(ui, lib)
                            BrowseTab.RECENT -> AlbumGrid(ui, lib.recentlyAdded) { it.artist }
                        }
                    }
                }
            }
            NavigationBar {
                Dest.entries.forEach { d ->
                    NavigationBarItem(
                        selected = ui.dest == d,
                        onClick = { ui.go(d) },
                        icon = { Icon(d.icon, null) },
                        label = { Text(d.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
    }

    ui.addToPlaylist?.let { tracks -> AddToPlaylistFlow(ui, tracks) }
    }
}

private const val GENRE_HELP = "None of your albums have a genre tag yet. New rips get them from MusicBrainz."

@Composable
private fun BackBar(ui: Ui, lib: Library, top: Screen) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = ui::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(
            when (top) {
                is Screen.AlbumPage -> lib.album(top.id)?.title.orEmpty()
                is Screen.ArtistPage -> lib.artist(top.id)?.name.orEmpty()
                is Screen.GenrePage -> lib.genre(top.id)?.name.orEmpty()
                is Screen.DecadePage -> lib.decade(top.id)?.name.orEmpty()
                is Screen.PlaylistPage -> ui.app.playlists.get(top.id)?.name.orEmpty()
                Screen.NowPlaying -> ""
                Screen.Equalizer -> "Equalizer"
            },
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

// ---- the always-on-top player controls ----

@Composable
private fun TopControls(ui: Ui, lib: Library, expanded: Boolean) {
    val p = ui.player
    val track = p.currentTrackId?.let(lib::track)
    val album = track?.let(lib::albumOf)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        if (!expanded) Column {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable(enabled = track != null) { ui.open(Screen.NowPlaying) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArt(album, Modifier.size(60.dp), maxPx = 200)
                    Column(Modifier.padding(start = 12.dp, end = 4.dp)) {
                        Text(track?.title ?: "Nothing playing", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track?.let { it.artist.ifBlank { it.filedUnder } } ?: "Pick an album or song", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { p.previous() }, enabled = track != null) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(30.dp)) }
                FilledIconButton(onClick = p::togglePlay, enabled = track != null, modifier = Modifier.size(52.dp)) {
                    Icon(if (p.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", Modifier.size(32.dp))
                }
                IconButton(onClick = { p.next() }, enabled = track != null) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(30.dp)) }
            }
            Progress(p) { pos, dur ->
                LinearProgressIndicator(progress = { if (dur > 0) pos.toFloat() / dur else 0f }, modifier = Modifier.fillMaxWidth().height(3.dp), gapSize = 0.dp, drawStopIndicator = {})
            }
        } else Column(Modifier.padding(start = 4.dp, end = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ui::back) { Icon(Icons.Default.KeyboardArrowDown, "Close") }
                Column(Modifier.weight(1f).padding(start = 4.dp, top = 8.dp)) {
                    Text(track?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(track?.let { it.artist.ifBlank { it.filedUnder } }, track?.album?.ifBlank { null }, album?.year?.takeIf { it > 0 }?.toString()).joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                val eq by ui.app.equalizer.state.collectAsStateWithLifecycle()
                IconButton(onClick = { ui.open(Screen.Equalizer) }, Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Default.GraphicEq, "Equalizer", tint = if (eq.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(start = 12.dp)) {
                Progress(p) { pos, dur -> SeekBar(p, pos, dur) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Toggle(Icons.Default.Shuffle, "Shuffle", p.shuffle, p::toggleShuffle)
                    IconButton(onClick = { p.previous() }, Modifier.size(60.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(40.dp)) }
                    FilledIconButton(onClick = p::togglePlay, Modifier.size(76.dp)) {
                        Icon(if (p.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", Modifier.size(44.dp))
                    }
                    IconButton(onClick = { p.next() }, Modifier.size(60.dp)) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(40.dp)) }
                    Toggle(
                        if (p.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat",
                        p.repeatMode != Player.REPEAT_MODE_OFF, p::cycleRepeat,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeekBar(p: PlayerConnection, pos: Long, dur: Long) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Column {
        Slider(
            value = if (dragging) dragValue else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { p.seekTo((dragValue * dur).toLong()); dragging = false },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(duration(if (dragging) (dragValue * dur).toLong() else pos), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(duration(dur), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

/** Polls the playback position while playing. */
@Composable
private fun Progress(player: PlayerConnection, content: @Composable (positionMs: Long, durationMs: Long) -> Unit) {
    var pos by remember { mutableLongStateOf(player.positionMs) }
    LaunchedEffect(player.isPlaying, player.currentTrackId, player.controller) {
        pos = player.positionMs
        while (player.isPlaying) { delay(500); pos = player.positionMs }
    }
    content(pos, player.durationMs)
}
