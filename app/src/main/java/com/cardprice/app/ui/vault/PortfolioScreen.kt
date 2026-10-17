package com.cardprice.app.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.vault.Holding
import com.cardprice.app.data.vault.PortfolioView
import com.cardprice.app.data.vault.PricePoint
import com.cardprice.app.data.vault.PriceRange
import com.cardprice.app.ui.theme.Palette
import com.cardprice.app.ui.theme.PriceStyle
import com.cardprice.app.ui.usd
import com.cardprice.app.ui.usdSigned

private enum class HoldingSort(val label: String) { VALUE("Value"), RECENT("Recently added"), NAME("Name"), GAIN("Gain") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(onOpenProduct: (Long) -> Unit) {
    val vm: PortfolioViewModel = viewModel()
    val portfolios by vm.portfolios.collectAsState()
    val pid by vm.pid.collectAsState()
    val view by vm.view.collectAsState()
    val range by vm.range.collectAsState()
    val history by vm.history.collectAsState()
    val extras by vm.extras.collectAsState()
    val message by vm.message.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val loadedAt by vm.loadedAt.collectAsState()
    val stale by vm.stale.collectAsState()
    var tab by rememberSaveable { mutableStateOf("overview") }
    var naming by remember { mutableStateOf<Pair<Long?, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(HoldingSort.VALUE) }
    var editing by remember { mutableStateOf<Holding?>(null) }
    var deleting by remember { mutableStateOf<Holding?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var confetti by remember { mutableStateOf(0) }
    var celebratedSets by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    VaultBackground {
        Column(Modifier.fillMaxSize()) {
            VaultTopBar(title = "Portfolio", actions = {
                IconButton(onClick = { naming = null to "" }) { Icon(Icons.Filled.Add, "New portfolio", tint = Palette.text2) }
                if (pid != null) {
                    IconButton(onClick = { naming = pid to (portfolios.firstOrNull { it.id == pid }?.name ?: "") }) { Icon(Icons.Filled.Edit, "Rename", tint = Palette.text2) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete portfolio", tint = Palette.text2) }
                }
            })
            val sortedHoldings = remember(view, sort) { (view as? Load.Ready)?.value?.holdings?.let { sortHoldings(it, sort) }.orEmpty() }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item(key = "picker", contentType = "picker") { Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PortfolioPicker(portfolios.map { it.id to "${it.name} (${it.items})" }, pid, vm::select)
                        Segmented(listOf("overview", "holdings"), tab, { if (it == "overview") "Overview" else "Products" }, { tab = it })
                    }
                    loadedAt?.let { at ->
                        val time = remember(at) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at)) }
                        Text(
                            if (stale) "Offline: showing data from $time" else "Updated $time",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (stale) Palette.accent3 else Palette.text3,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                } }
                when (val v = view) {
                    Load.Loading -> item { LoadingBox() }
                    is Load.Failed -> item { MessageBox("Couldn't load your portfolio", v.message, "Try again", { vm.reload() }) }
                    is Load.Ready -> {
                        val data = v.value
                        if (data.holdings.isEmpty()) {
                            item { MessageBox("Your collection is empty", "Scan cards, or find a card or sealed product and press Add to collection.") }
                        } else if (tab == "overview") {
                            item { ValueCard(data, portfolios.firstOrNull { it.id == pid }?.name ?: "All portfolios", history, range, vm::setRange, extras?.dayChange, extras?.dayChangePct) }
                            item { VaultBreakdown(data) }
                            item { CrownJewels(data.holdings, onOpenProduct) }
                            extras?.takeIf { it.movers.isNotEmpty() }?.let { f -> item { MoversStrip(f.movers, onOpenProduct) } }
                            extras?.takeIf { it.sets.isNotEmpty() }?.let { f -> item { SetCompletionCard(f.sets, onOpenProduct) } }
                        } else {
                            item(key = "sort", contentType = "sort") {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    HoldingSort.entries.forEach { s -> Pill(s.label, sort == s, { sort = s }) }
                                }
                            }
                            // One lazy row per holding, so long portfolios scroll smoothly.
                            items(sortedHoldings, key = { it.id }, contentType = { "holding" }) { h ->
                                GlassCard(Modifier.fillMaxWidth(), padding = 8.dp) {
                                    HoldingRow(h, { onOpenProduct(h.productId) }) {
                                        IconButton(onClick = { editing = h }) { Icon(Icons.Filled.Edit, "Edit", tint = Palette.text3) }
                                        IconButton(onClick = { deleting = h }) { Icon(Icons.Filled.Delete, "Remove", tint = Palette.text3) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        ConfettiOverlay(confetti)
    }

    // Celebrate a new all-time-high or a newly completed set (once each).
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(extras) {
        val f = extras ?: return@LaunchedEffect
        val seen = celebratedSets
        var fire = f.milestones.athIsNew
        val newDone = f.milestones.completedSets.filter { it.groupId !in seen }
        if (newDone.isNotEmpty()) { fire = true; celebratedSets = seen + newDone.map { it.groupId } }
        if (fire) {
            confetti++
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            vm.celebrate(if (f.milestones.athIsNew) "All-time high! ${f.milestones.ath.usd()} 🎉" else "Set complete: ${newDone.first().setName}! 🎉")
        }
    }

    editing?.let { h ->
        HoldingEditor(h.productId, h.name.orEmpty(), h.isSealed, null, h.variantId, h, { editing = null }) { input ->
            vm.saveHolding(h.id, input)
            editing = null
        }
    }
    deleting?.let { h ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Palette.surfaceSolid,
            title = { Text("Remove from collection?") },
            text = { Text("${h.qty}× ${h.name.orEmpty()}") },
            confirmButton = { TextButton(onClick = { vm.deleteHolding(h.id); deleting = null }) { Text("Remove", color = Palette.down) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
    naming?.let { (id, start) ->
        var name by remember { mutableStateOf(start) }
        AlertDialog(
            onDismissRequest = { naming = null },
            containerColor = Palette.surfaceSolid,
            title = { Text(if (id == null) "New portfolio" else "Rename portfolio") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, colors = vaultFieldColors(), label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (id == null) vm.create(name.trim()) else vm.rename(id, name.trim())
                    naming = null
                }) { Text("Save", color = Palette.accent) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }
    if (confirmDelete && pid != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Palette.surfaceSolid,
            title = { Text("Delete this portfolio?") },
            text = { Text("Everything in it is removed from TCG Vault.") },
            confirmButton = { TextButton(onClick = { vm.delete(pid!!); confirmDelete = false }) { Text("Delete", color = Palette.down) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PortfolioPicker(options: List<Pair<Long, String>>, selected: Long?, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill((options.firstOrNull { it.first == selected }?.second ?: "All portfolios") + " ▾", true, { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All portfolios") }, onClick = { open = false; onSelect(null) })
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(id) }) }
        }
    }
}

@Composable
private fun ValueCard(data: PortfolioView, name: String, history: Load<List<PricePoint>>, range: PriceRange, onRange: (PriceRange) -> Unit, day: Double? = null, dayPct: Double? = null) {
    val s = data.summary
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text("$name · ${s.items} item${if (s.items == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge, color = Palette.text2, modifier = Modifier.weight(1f))
            if (day != null && kotlin.math.abs(day) >= 0.005) DeltaChip(day, dayPct)
        }
        // The hero value counts up on first show.
        val shown = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(s.value) { shown.animateTo(s.value.toFloat(), androidx.compose.animation.core.tween(900)) }
        Text(shown.value.toDouble().usd(), style = PriceStyle, fontSize = 36.sp, color = Palette.text)
        val series = (history as? Load.Ready)?.value.orEmpty()
        if (series.size > 1) {
            val diff = series.last().value - series.first().value
            val pct = if (series.first().value != 0.0) diff / series.first().value * 100 else null
            Text(
                "${diff.usdSigned()}${pct?.let { " (${if (it >= 0) "+" else "-"}${"%.2f".format(kotlin.math.abs(it))}%)" } ?: ""} · ${range.label}",
                color = if (diff > 0) Palette.up else if (diff < 0) Palette.down else Palette.text2,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        s.gain?.let { g ->
            Text(
                "Cost basis ${s.cost.usd()} · ${g.usdSigned()} unrealized",
                style = MaterialTheme.typography.bodySmall,
                color = if (g >= 0) Palette.up else Palette.down,
            )
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            when {
                history is Load.Loading -> LoadingBox(Modifier.fillMaxWidth().height(180.dp))
                series.size > 1 -> LineChart(series, height = 180.dp)
                else -> Text(
                    "Portfolio history starts today. A new point is added each day prices update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.text3,
                )
            }
        }
        RangeSelector(range, onRange)
    }
}

@Composable
private fun VaultBreakdown(data: PortfolioView) {
    val kinds = listOf(Triple("Cards", data.cards, Palette.accent), Triple("Sealed", data.sealed, Palette.chartSealed), Triple("Graded", data.graded, Palette.chartGraded))
    val total = data.summary.value
    GlassCard(Modifier.fillMaxWidth()) {
        CardTitle("What's in the Vault")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Donut(kinds.map { it.second.value to it.third }, Modifier.size(130.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${data.summary.items}", style = PriceStyle, fontSize = 22.sp, color = Palette.text)
                    Text("items", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
                }
            }
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                kinds.forEach { (label, b, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text("$label ${if (total > 0) "(${(b.value / total * 100).toInt()}%)" else ""}", style = MaterialTheme.typography.labelLarge, color = Palette.text)
                            Text("${b.count} item${if (b.count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
                        }
                        Text(b.value.usd(), style = PriceStyle, color = Palette.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeltaChip(day: Double, pct: Double?) {
    val up = day > 0
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (up) Palette.up.copy(alpha = 0.14f) else Palette.down.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            "${if (up) "▲" else "▼"} ${kotlin.math.abs(day).usd()}${pct?.let { " (${"%.1f".format(it * 100)}%)" } ?: ""} today",
            style = MaterialTheme.typography.labelMedium,
            color = if (up) Palette.up else Palette.down,
        )
    }
}

@Composable
private fun CrownJewels(holdings: List<Holding>, onOpenProduct: (Long) -> Unit) {
    val top = remember(holdings) { holdings.sortedByDescending { it.value ?: 0.0 }.take(4) }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Crown Jewels")
            Text("  tap to flip", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            top.forEach { h ->
                HoloCard(h.productId, h.imageUrl, h.isSealed, h.rarity, Modifier.weight(1f), back = {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        Text(h.name.orEmpty(), style = MaterialTheme.typography.labelMedium, color = Palette.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(h.setName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((h.value ?: 0.0).usd(), style = MaterialTheme.typography.labelLarge, color = Palette.accent, modifier = Modifier.padding(top = 2.dp))
                        TextButton(onClick = { onOpenProduct(h.productId) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("View", color = Palette.accent, style = MaterialTheme.typography.bodySmall) }
                    }
                })
            }
            repeat(4 - top.size) { Box(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun MoversStrip(movers: List<com.cardprice.app.data.vault.Mover>, onOpenProduct: (Long) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Today's Highlights")
            Text("  biggest movers", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            movers.forEach { m ->
                Column(Modifier.width(92.dp).clickable { onOpenProduct(m.productId) }) {
                    HoloCard(m.productId, m.imageUrl, m.isSealed, m.rarity, Modifier.fillMaxWidth())
                    Text(m.name, style = MaterialTheme.typography.bodySmall, color = Palette.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text("${if (m.changeAbs >= 0) "+" else "-"}${kotlin.math.abs(m.changeAbs).usd()}", style = MaterialTheme.typography.labelMedium, color = if (m.changeAbs >= 0) Palette.up else Palette.down)
                }
            }
        }
    }
}

@Composable
private fun SetCompletionCard(sets: List<com.cardprice.app.data.vault.SetCompletion>, onOpenProduct: (Long) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Set Completion")
            val done = sets.count { it.pct >= 1.0 }
            if (done > 0) Text("  $done complete", style = MaterialTheme.typography.bodySmall, color = Palette.accent)
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sets.forEach { x ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (x.pct >= 1.0) Palette.accentSoft else Palette.surface2)
                        .clickable(enabled = x.next != null) { x.next?.let { onOpenProduct(it.id) } }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompletionRing(x.pct)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(x.setName, style = MaterialTheme.typography.labelLarge, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${x.owned} / ${x.total} cards", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
                        if (x.pct >= 1.0) Text("✓ Complete!", style = MaterialTheme.typography.bodySmall, color = Palette.accent)
                        else x.next?.let { Text("Next: ${it.name}", style = MaterialTheme.typography.bodySmall, color = Palette.text2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletionRing(pct: Double) {
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(pct) { anim.animateTo(pct.toFloat().coerceIn(0f, 1f), androidx.compose.animation.core.tween(900)) }
    val color = if (pct >= 1.0) Palette.accent else Palette.accent2
    Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(50.dp)) {
            val sw = 5.dp.toPx()
            drawArc(Palette.borderStrong, -90f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawArc(color, -90f, 360f * anim.value, false, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text("${(pct * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Palette.text)
    }
}

@Composable
private fun HoldingRow(h: Holding, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(46.dp).aspectRatio(5f / 7f).clip(RoundedCornerShape(6.dp)).background(if (h.isSealed) Palette.showcase else Palette.cardImageBg),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(VaultImages.product(h.productId, h.imageUrl, h.isSealed), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(h.name.orEmpty(), style = MaterialTheme.typography.labelLarge, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(h.setName, "${h.qty}×", h.subType?.takeIf { it != "Normal" }, h.grade ?: h.condition?.takeIf { it != "Near Mint" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(h.value.usd(), style = PriceStyle, color = Palette.text)
            if (h.cost != null && h.value != null) {
                val g = h.value - h.cost
                Text(g.usdSigned(), fontSize = 11.sp, color = if (g >= 0) Palette.up else Palette.down)
            }
        }
        trailing()
    }
}

/** Holdings sorted for the Products tab (done once per data/sort change, not per row). */
private fun sortHoldings(holdings: List<Holding>, sort: HoldingSort): List<Holding> = when (sort) {
    HoldingSort.VALUE -> holdings.sortedByDescending { it.value ?: 0.0 }
    HoldingSort.RECENT -> holdings.sortedWith(compareByDescending<Holding> { it.addedOn }.thenByDescending { it.id })
    HoldingSort.NAME -> holdings.sortedBy { it.name.orEmpty().lowercase() }
    HoldingSort.GAIN -> holdings.sortedByDescending { if (it.cost != null && it.value != null) it.value - it.cost else Double.NEGATIVE_INFINITY }
}
