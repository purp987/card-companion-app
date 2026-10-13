package com.cardprice.app.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.ui.theme.Palette

/** The "More" tab: everything that isn't on the bottom bar. */
@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val connection by Vault.connection.collectAsState()
    val queue by Vault.queue.collectAsState()
    VaultBackground {
        Column(Modifier.fillMaxSize()) {
            VaultTopBar(center = { Wordmark(small = true) })
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ConnectionBanner(connection, queue.size) { onNavigate("settings") }
                Entry("Watchlists", "Chase List and Shrink Wrap Watch") { onNavigate("watchlists") }
                Entry("Sets", "Browse every set by language") { onNavigate("sets") }
                Entry("Pack Calculator", "Price per card and pack, with TCG Vault market prices") { onNavigate("calc") }
                Entry("Scan history", "Everything the scanner added, removed or undid") { onNavigate("scan-history") }
                Entry("Settings", "Server, pairing, currency, import") { onNavigate("settings") }
            }
        }
    }
}

@Composable
private fun Entry(title: String, subtitle: String, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.text)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.text2)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = Palette.accent)
        }
    }
}
