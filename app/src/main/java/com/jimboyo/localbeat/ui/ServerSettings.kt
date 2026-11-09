package com.jimboyo.localbeat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimboyo.localbeat.server.ServerStatus

/** Settings → Music server (Connected edition only). Off by default; on reveals the rest. */
@Composable
fun ServerSection(ui: Ui) {
    val server = ui.app.server ?: return
    val settings by server.settings.collectAsStateWithLifecycle()
    val status by server.status.collectAsStateWithLifecycle()
    var address by remember(settings) { mutableStateOf(settings.address) }
    var username by remember(settings) { mutableStateOf(settings.username) }
    var password by remember(settings) { mutableStateOf(settings.password) }

    Section("Music server")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Use music server", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Your own server (Navidrome or LocalBeat's), usually through WireGuard",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(settings.enabled, { on -> server.update(settings.copy(enabled = on, address = address, username = username, password = password)) })
    }
    if (!settings.enabled) return

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            address, { address = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Address") }, placeholder = { Text("http://10.8.0.1:4533") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username") })
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use mobile data", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Off: music from the server plays on Wi-Fi only. The album list and covers always load.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(settings.useMobileData, { on -> server.update(settings.copy(useMobileData = on)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { server.update(settings.copy(address = address, username = username, password = password)) }) { Text("Connect") }
            OutlinedButton(onClick = server::refresh, enabled = status is ServerStatus.Connected || status is ServerStatus.Failed) { Text("Refresh") }
        }
        Text(
            when (val s = status) {
                ServerStatus.Off -> "Not connected"
                ServerStatus.Connecting -> "Connecting…"
                is ServerStatus.Connected -> "Connected: ${s.name} ${s.version}, ${plural(s.songs, "song")}" +
                    if (s.extensions.isEmpty()) "" else " · ${s.extensions.size} OpenSubsonic extensions"
                is ServerStatus.Failed -> s.message
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (status is ServerStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
