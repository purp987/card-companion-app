package com.cardprice.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.vault.AppRelease
import com.cardprice.app.ui.theme.Palette
import com.cardprice.app.ui.vault.AccentButton
import com.cardprice.app.ui.vault.GhostButton
import com.cardprice.app.ui.vault.GlassCard

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

/** Non-blocking update banner shown above the bottom bar when TCG Vault has a newer build. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val state by AppUpdater.state.collectAsState()
    var dismissed by remember { mutableStateOf(AppUpdater.dismissedVersion) }
    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.NeedsPermission -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Installing -> s.release
        is UpdateState.Failed -> s.release
        else -> null
    } ?: return
    if ((state is UpdateState.Available || state is UpdateState.NeedsPermission) && dismissed == release.versionCode) return
    GlassCard(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), padding = 14.dp) {
        UpdateContent(state, release, onDismiss = {
            AppUpdater.dismissedVersion = release.versionCode
            dismissed = release.versionCode
        })
    }
}

/** Shared body for the banner and Settings: version, notes, progress and the action. */
@Composable
fun UpdateContent(state: UpdateState, release: AppRelease, onDismiss: (() -> Unit)? = null) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("Update available: ${release.versionName}", style = MaterialTheme.typography.titleSmall, color = Palette.text)
            release.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.text2, maxLines = 4) }
        }
        if (onDismiss != null && (state is UpdateState.Available || state is UpdateState.NeedsPermission)) {
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Later", tint = Palette.text3) }
        }
    }
    when (state) {
        is UpdateState.Downloading -> Column(Modifier.padding(top = 8.dp)) {
            LinearProgressIndicator(progress = { state.fraction }, color = Palette.accent, trackColor = Palette.surface3, modifier = Modifier.fillMaxWidth())
            Text("Downloading… ${mb(state.done)} of ${mb(state.total)}", style = MaterialTheme.typography.bodySmall, color = Palette.text3, modifier = Modifier.padding(top = 4.dp))
        }
        is UpdateState.Installing -> Text("Installing… the app restarts when it's done.", style = MaterialTheme.typography.bodySmall, color = Palette.accent, modifier = Modifier.padding(top = 8.dp))
        is UpdateState.NeedsPermission -> Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "To update itself, Card Companion needs Android's \"Install unknown apps\" permission. Turn on \"Allow from this source\", then come back.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.accent3,
            )
            AccentButton("Open settings", { AppUpdater.openInstallPermission(context) })
        }
        is UpdateState.Failed -> Column(Modifier.padding(top = 8.dp)) {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = Palette.down)
            GhostButton("Try again", { AppUpdater.update(context, release) }, Modifier.padding(top = 6.dp))
        }
        else -> AccentButton("Update (${mb(release.size)})", { AppUpdater.update(context, release) }, Modifier.padding(top = 8.dp))
    }
}

/** Settings card: current version, "Check for updates", and the result. */
@Composable
fun UpdateSettingsCard() {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    GlassCard(Modifier.fillMaxWidth()) {
        Text("App updates", style = MaterialTheme.typography.titleMedium, color = Palette.text, modifier = Modifier.padding(bottom = 6.dp))
        Text(
            "This phone has ${AppUpdater.versionName(context)} (build ${AppUpdater.versionCode(context)}). Updates come from your TCG Vault server.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.text2,
        )
        when (val s = state) {
            UpdateState.Checking -> Text("Checking…", style = MaterialTheme.typography.bodySmall, color = Palette.text3, modifier = Modifier.padding(top = 6.dp))
            is UpdateState.UpToDate -> Text("You're up to date.", style = MaterialTheme.typography.bodySmall, color = Palette.up, modifier = Modifier.padding(top = 6.dp))
            is UpdateState.Failed -> if (s.release == null) Text(s.message, style = MaterialTheme.typography.bodySmall, color = Palette.down, modifier = Modifier.padding(top = 6.dp))
            else -> Unit
        }
        val release = when (val s = state) {
            is UpdateState.Available -> s.release
            is UpdateState.NeedsPermission -> s.release
            is UpdateState.Downloading -> s.release
            is UpdateState.Installing -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        }
        if (release != null) {
            Column(Modifier.padding(top = 8.dp)) { UpdateContent(state, release) }
        } else {
            GhostButton("Check for updates", { AppUpdater.check(context, manual = true) }, Modifier.padding(top = 8.dp), enabled = state != UpdateState.Checking)
        }
    }
}
