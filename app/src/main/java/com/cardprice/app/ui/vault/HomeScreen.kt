package com.cardprice.app.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.vault.Connection
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.ui.dayLabel
import com.cardprice.app.ui.theme.Palette

/** Where a web UI link from `/api/home` ("#/set/24722", "#/?sort=gain&minPrice=5") goes in the app. */
object VaultLinks {
    fun route(link: String?): String? {
        val l = link?.removePrefix("#")?.takeIf { it.isNotBlank() } ?: return null
        val path = l.substringBefore('?')
        val query = l.substringAfter('?', "")
        Regex("^/set/(\\d+)").find(path)?.let { return "set/${it.groupValues[1]}" }
        Regex("^/product/(\\d+)").find(path)?.let { return "product/${it.groupValues[1]}" }
        return when {
            path.startsWith("/sets") -> "sets"
            path.startsWith("/portfolio") -> "portfolio"
            path.startsWith("/watchlist") -> "watchlists"
            path == "/" || path.isEmpty() || path.startsWith("/explore") -> exploreRoute(query)
            else -> null
        }
    }

    /** "sort=gain&minPrice=5" → "explore?sort=gain&minPrice=5" (only the parameters Explore understands). */
    private fun exploreRoute(query: String): String {
        val params = query.split('&').mapNotNull { p ->
            val k = p.substringBefore('=')
            val v = p.substringAfter('=', "")
            if (k in setOf("q", "sort", "type", "minPrice", "cat") && v.isNotEmpty()) "$k=$v" else null
        }
        return "explore" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultHomeScreen(onNavigate: (String) -> Unit, onOpenProduct: (Long) -> Unit, onOpenSet: (Long) -> Unit) {
    val vm: HomeViewModel = viewModel()
    val state by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val connection by Vault.connection.collectAsState()
    val queue by Vault.queue.collectAsState()

    VaultBackground {
        Column(Modifier.fillMaxSize()) {
            VaultTopBar(
                center = { Wordmark() },
                actions = {
                    ConnectionDot(connection, Modifier.clickable { onNavigate("settings") })
                    IconButton(onClick = { onNavigate("settings") }) { Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Palette.text2) }
                },
            )
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh(); Vault.refresh() }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    item(key = "banner", contentType = "banner") { ConnectionBanner(connection, queue.size, onOpenSettings = { onNavigate("settings") }) }
                    when (val s = state) {
                        Load.Loading -> item { LoadingBox() }
                        is Load.Failed -> item {
                            MessageBox("Can't reach TCG Vault", s.message, "Try again", { vm.refresh(); Vault.refresh() })
                        }
                        is Load.Ready -> {
                            val feed = s.value
                            if (feed.window == null && feed.asOf != null) item {
                                Text(
                                    "Prices as of ${feed.asOf.dayLabel()}. Price movers show up after the second daily price update.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.text3,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            if (feed.sections.isEmpty()) item {
                                MessageBox("Nothing to show yet", "Pick the games you collect in TCG Vault's web Settings, or wait for the first price sync.")
                            }
                            items(feed.sections, key = { it.id }, contentType = { it.kind }) { sec ->
                                Column {
                                    val route = VaultLinks.route(sec.link)
                                    SectionHeader(sec.title, sec.subtitle, if (route != null) "See all" else null, route?.let { r -> { onNavigate(r) } })
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        if (sec.kind == "sets") {
                                            items(sec.sets, key = { it.id }, contentType = { "set" }) { set -> SetTile(set, { onOpenSet(set.id) }, Modifier.width(210.dp)) }
                                        } else {
                                            items(sec.products, key = { it.id }, contentType = { "product" }) { p -> ProductTile(p, { onOpenProduct(p.id) }, Modifier.width(152.dp)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionDot(connection: Connection, modifier: Modifier = Modifier) {
    val color = when (connection) {
        is Connection.Connected -> Palette.up
        is Connection.Unpaired, is Connection.OldServer -> Palette.accent3
        is Connection.Offline -> Palette.down
        else -> Palette.text3
    }
    Box(modifier.padding(8.dp).size(10.dp).clip(CircleShape).background(color))
}

/** Explains anything that stops the app working fully: offline, not paired, older server, queued scans. */
@Composable
fun ConnectionBanner(connection: Connection, queued: Int, onOpenSettings: () -> Unit) {
    val (title, body) = when (connection) {
        is Connection.Offline -> "Offline" to connection.message
        is Connection.Unpaired -> "Pair this phone" to "Browsing works, but scanning to your portfolio and importing need pairing: open TCG Vault on your computer → Settings → Phone app → Pair a phone, then enter the code here."
        is Connection.OldServer -> "TCG Vault needs an update" to connection.message
        else -> if (queued > 0) "Scans waiting" to "$queued scanned card${if (queued == 1) "" else "s"} will be added when TCG Vault is reachable." else return
    }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onOpenSettings) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectionDot(connection)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.text)
                Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.text2)
                if (queued > 0 && connection !is Connection.Connected) {
                    Text("$queued scan${if (queued == 1) "" else "s"} saved on the phone", style = MaterialTheme.typography.bodySmall, color = Palette.accent3)
                }
            }
            Text("Settings", style = MaterialTheme.typography.labelMedium, color = Palette.accent, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
