package com.jimboyo.localbeat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jimboyo.localbeat.server.InviteLink
import com.jimboyo.localbeat.server.MusicServer
import kotlinx.coroutines.launch

/**
 * First-run Welcome screen for the Connected edition (docs/onboarding.md).
 * One tap to paste an invite from clipboard (or scan a QR code with the
 * phone's camera / scanner), plus a quiet "Use without a server" option.
 */
@Composable
fun WelcomeScreen(
    server: MusicServer,
    prefill: String,
    onDone: () -> Unit,
    onLocalOnly: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var input by remember(prefill) { mutableStateOf(prefill) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val parsed = remember(input) {
        if (input.isBlank()) null else InviteLink.parse(input)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Welcome to LocalBeat", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Scan an invite QR code with your camera, or paste your invite link below to connect to your music server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Connecting to your music server…", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        val clip = clipboard.getText()?.text.orEmpty()
                        if (clip.isNotBlank()) {
                            input = clip
                            error = null
                        } else {
                            error = "Clipboard is empty"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Paste invite from clipboard")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("Invite link") },
                    placeholder = { Text("localbeat://join/… (or paste the whole message)") },
                )
                Spacer(Modifier.height(8.dp))
                when {
                    parsed?.isSuccess == true -> Text(
                        "Invite ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    parsed?.isFailure == true -> Text(
                        parsed.exceptionOrNull()?.message ?: "Invalid invite link",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val res = server.claimInvite(input)
                            busy = false
                            res.onSuccess { onDone() }
                                .onFailure { error = it.message ?: "Couldn't claim invite" }
                        }
                    },
                    enabled = parsed?.isSuccess == true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connect")
                }
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = onLocalOnly) {
                    Text("Use without a server", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Dialog for claiming a `localbeat://join/...` invite on an existing install
 * (includes an already-joined confirmation guard before replacing).
 */
@Composable
fun JoinInviteDialog(
    server: MusicServer,
    initialText: String,
    alreadyConnectedAs: String?,
    onDismiss: () -> Unit,
    onClaimed: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var input by remember(initialText) { mutableStateOf(initialText) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = remember(input) { if (input.isBlank()) null else InviteLink.parse(input) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(if (alreadyConnectedAs != null) "Replace music server?" else "Join with an invite")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alreadyConnectedAs != null) {
                    Text(
                        "This phone is already connected as $alreadyConnectedAs. Claiming this invite will replace that connection.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Connecting to your music server…")
                    }
                } else {
                    if (initialText.isBlank()) {
                        OutlinedButton(
                            onClick = {
                                val clip = clipboard.getText()?.text.orEmpty()
                                if (clip.isNotBlank()) { input = clip; error = null }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Paste from clipboard") }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text("Invite link") },
                    )
                    parsed?.exceptionOrNull()?.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && parsed?.isSuccess == true,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val res = server.claimInvite(input)
                        busy = false
                        res.onSuccess { onClaimed(it) }
                            .onFailure { error = it.message ?: "Couldn't claim invite" }
                    }
                },
            ) { Text(if (alreadyConnectedAs != null) "Replace" else "Connect") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
