package com.jimboyo.localbeat.ui

import android.Manifest
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jimboyo.localbeat.LocalBeatApp
import com.jimboyo.localbeat.ThemeMode
import com.jimboyo.localbeat.playlists.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = application as LocalBeatApp
    private lateinit var player: PlayerConnection
    private lateinit var ui: Ui
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
        player = PlayerConnection(this)
        ui = Ui(app, player, importPlaylist = { pickPlaylist.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/octet-stream", "text/plain")) }, sharePlaylist = ::share)
        hasPermission = checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        app.library.start()
        if (!hasPermission) askPermissions()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val mode by app.settings.theme.collectAsStateWithLifecycle()
            val font by app.settings.font.collectAsStateWithLifecycle()
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
            LocalBeatTheme(mode, font) {
                LocalBeatUi(ui, hasPermission, onGrant = ::askPermissions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A playlist file opened or shared from another app (Files, KDE Connect…). */
    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        importPlaylist(uri)
    }

    private fun importPlaylist(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                    app.playlists.import(text, name, app.library.awaitLibrary())
                }
            }
            result.onSuccess { (playlist, missing) ->
                ui.toast(if (missing == 0) "Imported “${playlist.name}”" else "Imported “${playlist.name}”: $missing songs aren't on this phone")
                ui.stack.clear()
                ui.open(Screen.PlaylistPage(playlist.id))
            }.onFailure { ui.toast("Couldn't read that playlist") }
        }
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
    }

    override fun onStop() {
        player.release()
        super.onStop()
    }
}
