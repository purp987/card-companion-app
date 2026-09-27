package com.cardprice.app.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.cloud.ServerBackup
import java.text.DateFormat
import java.util.Date

/** Settings → Cloud backup: sign in to the backup server, back up now, and restore or delete server backups. */
@Composable
fun CloudBackupDialog(state: CloudState, vm: CloudViewModel, onRestore: (RestoreData) -> Unit, onDismiss: () -> Unit) {
    var confirmRestore by remember { mutableStateOf<ServerBackup?>(null) }
    var confirmDelete by remember { mutableStateOf<ServerBackup?>(null) }
    LaunchedEffect(state.signedIn) { if (state.signedIn && state.backups == null) vm.loadBackups() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cloud backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.beta) {
                    TrustedDevice(state, vm)
                    HorizontalDivider()
                }
                if (state.signedIn) {
                    SignedIn(state, vm, onRestoreRequest = { confirmRestore = it }, onDeleteRequest = { confirmDelete = it })
                } else {
                    SignInForm(state, vm)
                }
                state.busy?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(it)
                    }
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    confirmRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Your collection and inventory are replaced with the ones from ${backup.createdAt.dateTime()}. " +
                        "Your current collection is kept as a local backup (⚙ Settings → Restore a backup…), so this can be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmRestore = null; vm.restore(backup, onRestore) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this backup?") },
            text = { Text("The backup from ${backup.createdAt.dateTime()} is deleted from the server. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; vm.delete(backup) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

/** Beta: pairing with the server as a trusted device, and the live viewer switch. */
@Composable
private fun TrustedDevice(state: CloudState, vm: CloudViewModel) {
    Text("This phone (beta)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    if (state.deviceName == null) {
        var server by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl.orEmpty()) }
        var code by remember { mutableStateOf("") }
        Text(
            "Pair this phone so the server trusts it: in the server's control panel open Devices → Make a pairing code, then enter it here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            server, { server = it }, label = { Text("Server") }, placeholder = { Text("cards.example.com") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) }, label = { Text("Pairing code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.pair(server, code) }, enabled = server.isNotBlank() && code.length >= 6 && state.busy == null, modifier = Modifier.fillMaxWidth()) {
            Text("Pair this phone")
        }
    } else {
        Text("Paired as ${state.deviceName}. Every request is signed with this phone's key.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stream to live viewer", modifier = Modifier.weight(1f))
            Switch(checked = state.liveStreaming, onCheckedChange = vm::setLiveStreaming)
        }
        Text(
            "Sends screens, scanner activity and totals to the server's control panel while the app is open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = vm::unpair) { Text("Unpair") }
    }
}

@Composable
private fun SignInForm(state: CloudState, vm: CloudViewModel) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(state.email.orEmpty()) }
    // Passwords and invite codes are never saved; they're gone when the dialog closes.
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Text(
        "Back up your collection, inventory and scanner learning to your own backup server, and restore them on this " +
            "or another phone.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !creating, onClick = { creating = false }, label = { Text("Sign in") })
        FilterChip(selected = creating, onClick = { creating = true }, label = { Text("Create account") })
    }
    OutlinedTextField(
        server, { server = it }, label = { Text("Server") }, placeholder = { Text("cards.example.com") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        email, { email = it }, label = { Text("Email") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        password, { password = it }, label = { Text("Password") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        supportingText = if (creating) ({ Text("At least 10 characters.") }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
    if (creating) {
        OutlinedTextField(
            code, { code = it }, label = { Text("Invite code") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val ready = server.isNotBlank() && email.isNotBlank() && password.isNotEmpty() && (!creating || (code.isNotBlank() && password.length >= 10))
    Button(
        onClick = { if (creating) vm.register(server, email, password, code) else vm.signIn(server, email, password) },
        enabled = ready && state.busy == null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (creating) "Create account" else "Sign in") }
}

@Composable
private fun SignedIn(state: CloudState, vm: CloudViewModel, onRestoreRequest: (ServerBackup) -> Unit, onDeleteRequest: (ServerBackup) -> Unit) {
    Text("Signed in as ${state.email}", fontWeight = FontWeight.Bold)
    Text(state.serverUrl.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        state.lastBackupAt?.let { "Last backup: ${it.dateTime()}" } ?: "Not backed up from this phone yet.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = vm::backUpNow, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Backups on the server", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::loadBackups, enabled = state.busy == null) { Text("Refresh") }
    }
    val backups = state.backups
    when {
        backups == null -> Text("…")
        backups.isEmpty() -> Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> backups.forEach { b ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(b.createdAt.dateTime(), fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(
                        b.summary["cards"]?.let { "$it cards" },
                        b.summary["sets"]?.let { "$it sets" },
                        b.summary["inventory"]?.takeIf { it > 0 }?.let { "$it inventory items" },
                        b.device,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onRestoreRequest(b) }, enabled = state.busy == null) { Text("Restore") }
                    TextButton(onClick = { onDeleteRequest(b) }, enabled = state.busy == null) { Text("Delete") }
                }
            }
        }
    }
    HorizontalDivider()
    TextButton(onClick = vm::signOut) { Text("Sign out") }
}

private fun Long.dateTime(): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(this))
