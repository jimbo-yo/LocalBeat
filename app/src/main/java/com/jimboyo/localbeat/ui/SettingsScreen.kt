package com.jimboyo.localbeat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimboyo.localbeat.AppFont
import com.jimboyo.localbeat.BuildConfig
import com.jimboyo.localbeat.ThemeMode
import com.jimboyo.localbeat.library.Library

@Composable
fun SettingsScreen(ui: Ui, lib: Library) {
    val settings = ui.app.settings
    val theme by settings.theme.collectAsStateWithLifecycle()
    val font by settings.font.collectAsStateWithLifecycle()
    val scanning by ui.app.library.scanning.collectAsStateWithLifecycle()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Section("Appearance")
        ThemeMode.entries.forEach { m -> Choice(m.label, theme == m) { settings.setTheme(m) } }

        Section("Font")
        AppFont.entries.forEach { f ->
            val family = remember(f) { fontFamily(f) }
            Choice(f.label, font == f, sample = "Sittin’ on Top of the World", style = TextStyle(fontFamily = family, fontSize = 18.sp)) { settings.setFont(f) }
        }

        Section("Sound")
        val eq by ui.app.equalizer.state.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxWidth().clickable { ui.open(Screen.Equalizer) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null, tint = if (eq.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.padding(start = 16.dp)) {
                Text("Equalizer", style = MaterialTheme.typography.bodyLarge)
                Text(eqSummary(ui, eq.enabled, eq.gains), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        ServerSection(ui)

        Section("Library")
        Text(
            "${plural(lib.tracks.size, "song")}, ${plural(lib.albums.size, "album")}, ${plural(lib.artists.size, "artist")} in Internal Storage/Music.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { ui.app.library.rescan() }, enabled = !scanning, modifier = Modifier.padding(16.dp)) {
            Text(if (scanning) "Scanning…" else "Rescan library")
        }

        Section("About")
        Text(
            "LocalBeat ${BuildConfig.VERSION_NAME}. Plays music stored on this phone. " +
                (if (BuildConfig.CONNECTED) "Connected edition: it can also use network features that talk only to " +
                    "your own devices. No accounts with anyone else, no tracking.\n\n"
                else "No internet access, no accounts, nothing leaves the phone.\n\n") +
                "Fonts: Figtree, Inter, Nunito Sans and Atkinson Hyperlegible Next, under the SIL Open Font License.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, sample: String? = null, style: TextStyle? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = style ?: MaterialTheme.typography.bodyLarge)
            if (sample != null) Text(sample, style = (style ?: MaterialTheme.typography.bodyMedium).copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
