package com.jimboyo.localbeat.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jimboyo.localbeat.LocalBeatApp
import com.jimboyo.localbeat.ThemeMode
import com.jimboyo.localbeat.playback.VoiceRequest
import com.jimboyo.localbeat.playlists.Playlist
import com.jimboyo.localbeat.server.ConnectLink
import com.jimboyo.localbeat.server.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Survives configuration changes (rotation, split-screen, theme/font changes) so navigation and dialogs stay put. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val player = PlayerConnection(application)
    val ui = Ui(application as LocalBeatApp, player)
    /** A `localbeat://connect` link waiting for the user to confirm it. */
    var connectOffer by mutableStateOf<ConnectLink?>(null)
}

class MainActivity : ComponentActivity() {
    private val app get() = application as LocalBeatApp
    private val vm: MainViewModel by viewModels()
    private val player get() = vm.player
    private val ui get() = vm.ui
    private var hasPermission by mutableStateOf(false)

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_MEDIA_AUDIO] == true) {
            hasPermission = true
            app.library.rescan()
        }
    }

    private val pickPlaylist = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPlaylist(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.importPlaylist = {
            pickPlaylist.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/octet-stream", "text/plain"))
        }
        ui.sharePlaylist = ::share
        hasPermission = checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        app.library.start()
        if (!hasPermission) askPermissions()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val mode by app.settings.theme.collectAsStateWithLifecycle()
            val font by app.settings.font.collectAsStateWithLifecycle()
            val colors by app.settings.colors.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's light/dark choice, not just the system's.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            LocalBeatTheme(mode, colors, font) {
                LocalBeatUi(ui, hasPermission, onGrant = ::askPermissions)
                vm.connectOffer?.let { link ->
                    ConnectDialog(link, app.server?.settings?.value, onUse = { useServer(link) }, onDismiss = { vm.connectOffer = null })
                }
            }
        }
    }

    override fun onDestroy() {
        ui.importPlaylist = {}
        ui.sharePlaylist = {}
        ui.savedStates = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * A music server link (`localbeat://connect`, the camera opening the server's
     * QR code; declared by the Connected edition only), or a playlist file opened
     * or shared from another app (Files, KDE Connect…), or a voice request
     * ("Hey Google, play … on LocalBeat").
     */
    private fun handleIntent(intent: Intent?) {
        if (VoiceRequest.isVoice(intent)) return player.playItem(VoiceRequest.item(intent!!))
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme.equals("localbeat", ignoreCase = true)) {
            if (app.server == null) return // the offline edition: no server
            val link = ConnectLink.parse(data.toString())
            if (link == null) ui.toast("That isn't a valid LocalBeat server link") else vm.connectOffer = link
            return
        }
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        importPlaylist(uri)
    }

    /** Confirmed: the link's server, user and secret, and the server turned on. */
    private fun useServer(link: ConnectLink) {
        vm.connectOffer = null
        val server = app.server ?: return
        server.update(server.settings.value.copy(enabled = true, address = link.address, username = link.username, password = link.password))
        ui.toast("Music server set: ${link.username} at ${link.address}")
    }

    private fun importPlaylist(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri) ?: throw IOException("Can't open playlist")
                    val text = stream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_PLAYLIST_BYTES) throw IOException("Playlist file is too large")
                            out.write(buf, 0, n)
                        }
                        String(out.toByteArray(), Charsets.UTF_8)
                    }
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                    app.playlists.import(text, name, app.library.awaitLibrary())
                }
            }
            result.onSuccess { (playlist, missing) ->
                ui.toast(if (missing == 0) "Imported “${playlist.name}”" else "Imported “${playlist.name}”: $missing songs aren't on this phone")
                ui.clearStack()
                ui.open(Screen.PlaylistPage(playlist.id))
            }.onFailure { ui.toast("Couldn't read that playlist") }
        }
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    }

    private fun share(p: Playlist) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { app.playlists.exportFile(p, app.library.awaitLibrary()) }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/x-mpegurl"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, p.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share “${p.name}”"))
        }
    }

    private fun askPermissions() = requestPermissions.launch(
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    )

    override fun onStart() {
        super.onStart()
        player.connect()
        app.server?.setInUse("ui", true)
    }

    override fun onStop() {
        app.server?.setInUse("ui", false)
        player.release()
        super.onStop()
    }
}

/** Asks before a link changes the music server settings (a stray link must not). */
@Composable
private fun ConnectDialog(link: ConnectLink, current: ServerSettings?, onUse: () -> Unit, onDismiss: () -> Unit) {
    val replacing = current != null && current.address.isNotBlank() &&
        (current.address.trimEnd('/') != link.address || current.username != link.username)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use this music server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Address: ${link.address}\nUser: ${link.username}")
                if (replacing) {
                    Text(
                        "Replaces ${current!!.username.ifBlank { "the login" }} at ${current.address}. Songs kept on this " +
                            "phone from the old server or user are tied to it and won't be used.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Only use codes from your own server's setup page.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onUse) { Text("Use it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
