package com.cardprice.app.ui.vault

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.PriceChartingProduct
import com.cardprice.app.data.vault.Holding
import com.cardprice.app.data.vault.PriceHistory
import com.cardprice.app.data.vault.ProductDetail
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.ui.dayLabel
import com.cardprice.app.ui.theme.Palette
import com.cardprice.app.ui.theme.PriceStyle
import com.cardprice.app.ui.usd
import com.cardprice.app.ui.usdSigned
import java.net.URLEncoder

private val CHINESE = setOf(900001L, 900002L)

@Composable
fun ProductScreen(productId: Long, onOpenProduct: (Long) -> Unit, onOpenSet: (Long) -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: ProductViewModel = viewModel(key = "product-$productId", factory = viewModelFactory { initializer { ProductViewModel(app, productId) } })
    val detail by vm.detail.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    VaultBackground {
        Column(Modifier.fillMaxSize()) {
            val p = (detail as? Load.Ready)?.value
            VaultTopBar(
                title = p?.product?.setName ?: "",
                onBack = onBack,
                actions = {
                    if (p != null) {
                        IconButton(onClick = vm::toggleWatch) {
                            Icon(Icons.Filled.Star, contentDescription = if (p.watched) "Unwatch" else "Watch", tint = if (p.watched) Palette.accent else Palette.text3)
                        }
                    }
                },
            )
            when (val d = detail) {
                Load.Loading -> LoadingBox()
                is Load.Failed -> MessageBox("Couldn't load this product", d.message, "Try again", vm::reload)
                is Load.Ready -> ProductBody(d.value, vm, onOpenProduct, onOpenSet)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ProductBody(p: ProductDetail, vm: ProductViewModel, onOpenProduct: (Long) -> Unit, onOpenSet: (Long) -> Unit) {
    val range by vm.range.collectAsState()
    val variantId by vm.variant.collectAsState()
    val history by vm.history.collectAsState()
    val pc by vm.priceCharting.collectAsState()
    val item = p.product
    val isZh = item.categoryId in CHINESE
    val variant = p.variants.firstOrNull { it.id == variantId }
    var editing by remember { mutableStateOf<Holding?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Holding?>(null) }

    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- hero
        item {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (item.isSealed) Palette.showcase else Palette.cardImageBg),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = VaultImages.product(item.id, item.imageUrl, item.isSealed, 400),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                }
                Text(item.name, style = MaterialTheme.typography.headlineMedium, color = Palette.text, modifier = Modifier.padding(top = 12.dp))
                Text(
                    item.setName,
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.accent,
                    modifier = Modifier.clickable { onOpenSet(item.groupId) }.padding(vertical = 2.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LangBadge(item.categoryId, Modifier.padding(end = 6.dp))
                    Text(
                        if (item.isSealed) "Sealed Product" else listOfNotNull(item.rarity, item.number).joinToString("  •  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.text2,
                    )
                }
            }
        }
        // ---- price + watch
        item { PriceHero(p, history, variant?.market, range.label, isZh, vm::toggleWatch) }
        // ---- chart
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                CardTitle(if (item.isSealed) "Price History" else "Ungraded Price History")
                RangeSelector(range, vm::setRange)
                if (p.variants.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.variants.forEach { v -> Pill(v.subType, v.id == variantId, { vm.setVariant(v.id) }) }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    when (val h = history) {
                        Load.Loading -> LoadingBox(Modifier.fillMaxWidth().height(200.dp))
                        is Load.Failed -> Text(h.message, color = Palette.down, style = MaterialTheme.typography.bodySmall)
                        is Load.Ready -> {
                            val series = h.value.series
                            val cm = h.value.cardmarket.takeIf { variantId == p.primaryVariantId && it.size > 1 }
                            when {
                                series.isEmpty() && isZh -> ChartNote(
                                    "No daily price chart for Chinese cards",
                                    "TCGplayer doesn't sell them. This card is valued from recent eBay comps (see Recent eBay Comps below).",
                                )
                                series.isEmpty() -> ChartNote("No market price recorded for this printing yet.", null)
                                series.size == 1 -> ChartNote(
                                    "${series[0].value.usd()} on ${series[0].day.dayLabel()}",
                                    "Price history builds up daily. Come back tomorrow for the first trend line.",
                                )
                                else -> Column {
                                    LineChart(series, compare = cm)
                                    Text(
                                        if (cm != null) "━ TCGplayer market   ┅ Cardmarket trend (EU)" else "Daily TCGplayer market price",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Palette.text3,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // ---- current prices
        if (variant != null) item {
            GlassCard(Modifier.fillMaxWidth()) {
                CardTitle("Current prices")
                listOf("Market" to variant.market, "Low" to variant.low, "Mid" to variant.mid, "High" to variant.high, "Direct low" to variant.directLow)
                    .filter { it.second != null }
                    .ifEmpty { listOf("No current listings" to null) }
                    .forEach { (k, v) -> PriceRow(k, v?.usd() ?: "") }
            }
        }
        // ---- collection
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                CardTitle("Collection")
                Text("Track this ${if (item.isSealed) "product" else "card"} in your collection", style = MaterialTheme.typography.bodySmall, color = Palette.text2)
                p.holdings.forEach { h ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${h.qty}× ${h.subType.orEmpty()}${h.grade?.let { " · $it" } ?: h.condition?.let { " · $it" } ?: ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.text,
                            )
                            Text("${h.portfolioName.orEmpty()} · ${h.addedOn?.dayLabel().orEmpty()}", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
                        }
                        IconButton(onClick = { editing = h }) { Icon(Icons.Filled.Edit, "Edit", tint = Palette.text2) }
                        IconButton(onClick = { deleting = h }) { Icon(Icons.Filled.Delete, "Remove", tint = Palette.text2) }
                    }
                }
                AccentButton("+  Add to collection", { adding = true }, Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        }
        // ---- PriceCharting (owner's token)
        pc?.let { state -> item { PriceChartingCard(state, item.isSealed) } }
        // ---- slab prices / across the pond / japanese twin
        if (isZh) {
            item { JapaneseTwin(p, onOpenProduct) }
        } else if (!item.isSealed) {
            item { SlabPrices(p, vm::loadGraded) }
            item { AcrossThePond(p) }
        }
        // ---- eBay comps
        item { EbayComps(p) }
        // ---- shop
        item { ShopLinks(p, isZh, variant?.low) }
        // ---- details
        if (p.ext.isNotEmpty()) item {
            GlassCard(Modifier.fillMaxWidth()) {
                CardTitle("Details")
                p.ext.forEach { e -> PriceRow(e.name, e.value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), ""), mono = false) }
            }
        }
    }

    if (adding || editing != null) {
        HoldingEditor(
            productId = item.id,
            productName = item.name,
            isSealed = item.isSealed,
            variants = p.variants,
            primaryVariantId = p.primaryVariantId,
            holding = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { input ->
                vm.saveHolding(editing?.id, input)
                adding = false
                editing = null
            },
        )
    }
    deleting?.let { h ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Palette.surfaceSolid,
            title = { Text("Remove from collection?") },
            text = { Text("${h.qty}× ${item.name} (${h.portfolioName.orEmpty()})") },
            confirmButton = { TextButton(onClick = { vm.deleteHolding(h.id); deleting = null }) { Text("Remove", color = Palette.down) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun PriceHero(p: ProductDetail, history: Load<PriceHistory>, market: Double?, rangeLabel: String, isZh: Boolean, onWatch: () -> Unit) {
    val series = (history as? Load.Ready)?.value?.series.orEmpty()
    val now = market ?: series.lastOrNull()?.value ?: p.product.price
    val first = series.firstOrNull()?.value
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BigPrice(now, up = if (series.size > 1 && now != null && first != null && now != first) now > first else null)
                when {
                    series.size > 1 && now != null && first != null -> {
                        val diff = now - first
                        Text(
                            "${diff.usdSigned()} (${if (diff >= 0) "+" else "-"}${"%.2f".format(kotlin.math.abs(diff / first * 100))}%) · $rangeLabel",
                            color = if (diff > 0) Palette.up else if (diff < 0) Palette.down else Palette.text2,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    series.isEmpty() && isZh -> {
                        val comps = p.ebaySales.count { it.kind == "raw" || it.kind == null }
                        Text(if (p.product.price != null) "Median of recent eBay comps ($comps raw)" else "No eBay comps yet", style = MaterialTheme.typography.bodySmall, color = Palette.text2)
                    }
                    series.isNotEmpty() -> Text("Tracking since ${series.first().day.dayLabel()}", style = MaterialTheme.typography.bodySmall, color = Palette.text2)
                }
            }
        }
        val list = if (p.product.isSealed) "Shrink Wrap Watch" else "Chase List"
        Pill(if (p.watched) "★ On your $list" else "☆ Add to $list", p.watched, onWatch, Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun ChartNote(title: String, body: String?) {
    Column(Modifier.fillMaxWidth().height(140.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.text)
        if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.text3, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun PriceRow(label: String, value: String, mono: Boolean = true, valueColor: androidx.compose.ui.graphics.Color = Palette.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.text2, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (mono) PriceStyle.copy(fontSize = 15.sp) else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.padding(start = 12.dp).then(if (mono) Modifier else Modifier.weight(1.4f)),
        )
    }
}

@Composable
private fun SlabPrices(p: ProductDetail, onLoad: () -> Unit) {
    var all by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        CardTitle("Slab Prices")
        val rows = p.graded
        if (rows.isEmpty()) {
            Text(
                if (p.gradedFetchedAt != null) "No graded sales found for this card." else "Graded sale prices (PSA, BGS, CGC…) aren't loaded for this card yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.text2,
            )
            if (p.gradedFetchedAt == null) {
                GhostButton("Load graded prices", onLoad, Modifier.padding(top = 8.dp), enabled = (p.creditsRemaining ?: 2) >= 2)
                Text("Uses 2 of ${p.creditsRemaining ?: "?"} API credits left today. Cards you own or watch load automatically.", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
            }
            return@GlassCard
        }
        val graded = rows.filter { it.gradeKey != "ungraded" }
        val list = if (all) rows else graded.take(6)
        list.forEach { r ->
            val trend = when (r.trend) { "up" -> "▲ "; "down" -> "▼ "; else -> "" }
            PriceRow("${r.label} · ${r.sales} sale${if (r.sales == 1) "" else "s"}", trend + r.price.usd(),
                valueColor = when (r.trend) { "up" -> Palette.up; "down" -> Palette.down; else -> Palette.text })
        }
        if (rows.size > list.size || all) {
            TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else "Show all ${rows.size} grades", color = Palette.accent) }
        }
        p.gradedFetchedAt?.let {
            Text("eBay + Fanatics sales via PokemonPriceTracker · updated ${java.text.DateFormat.getDateInstance().format(java.util.Date(it))}", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
        }
    }
}

@Composable
private fun AcrossThePond(p: ProductDetail) {
    val rates by Vault.rates.collectAsState()
    val eurPerUsd = rates["EUR"]
    fun eur(v: Double?): String = when {
        v == null -> "—"
        eurPerUsd == null -> "€%.2f".format(v)
        else -> "${(v / eurPerUsd).usd()}  ·  €%.2f".format(v)
    }
    GlassCard(Modifier.fillMaxWidth()) {
        CardTitle("Across the Pond", badge = "Cardmarket EU")
        val c = p.cardmarket
        if (c == null) {
            Text("No Cardmarket data for this card yet. Cards worth \$5+ and anything you own or watch are refreshed daily.", style = MaterialTheme.typography.bodyMedium, color = Palette.text2)
            return@GlassCard
        }
        PriceRow("Trend", eur(c.trend))
        PriceRow("1-day avg", eur(c.avg1))
        PriceRow("7-day avg", eur(c.avg7))
        PriceRow("30-day avg", eur(c.avg30))
        PriceRow("Lowest listing", eur(c.low))
        c.trendHolo?.let { PriceRow("Holo / reverse trend", eur(it)) }
        val tp = p.variants.firstOrNull { it.id == p.primaryVariantId }?.market
        val usd = if (c.trend != null && eurPerUsd != null) c.trend / eurPerUsd else null
        if (tp != null && usd != null && (tp / usd > 3 || usd / tp > 3)) {
            Text("⚠ TCGplayer and Cardmarket disagree by more than 3×. Check recent sales before trusting either.", color = Palette.down, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Via TCGdex · updated ${c.day?.dayLabel().orEmpty()}${if (c.matchStatus == "unverified") " · matched by set & card number" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.text3,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun JapaneseTwin(p: ProductDetail, onOpenProduct: (Long) -> Unit) {
    val t = p.jpTwin
    GlassCard(Modifier.fillMaxWidth()) {
        CardTitle("Japanese Twin")
        Text(
            "Chinese cards aren't sold on TCGplayer, so this card is valued from eBay comps. " +
                if (t != null) "The same card's Japanese edition is a useful reference point:" else "No Japanese edition of this card is in the catalog.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.text2,
        )
        if (t != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface2).clickable { onOpenProduct(t.id) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Badge("JP", color = Palette.accent2)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(t.name, style = MaterialTheme.typography.labelLarge, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.setName, style = MaterialTheme.typography.bodySmall, color = Palette.text3, maxLines = 1)
                }
                Text(t.price.usd(), style = PriceStyle, color = Palette.text)
            }
        }
    }
}

@Composable
private fun PriceChartingCard(state: Load<PriceChartingProduct?>, sealed: Boolean) {
    GlassCard(Modifier.fillMaxWidth()) {
        CardTitle("PriceCharting", badge = "Your API token")
        when (state) {
            Load.Loading -> Text("Looking it up on PriceCharting…", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
            is Load.Failed -> Text("PriceCharting: ${state.message}", style = MaterialTheme.typography.bodySmall, color = Palette.down)
            is Load.Ready -> {
                val pc = state.value
                if (pc == null || !pc.hasPrices) {
                    Text("PriceCharting has no matching product.", style = MaterialTheme.typography.bodyMedium, color = Palette.text2)
                } else {
                    if (sealed) PriceRow("Sealed", pc.sealedPrice.usd()) else {
                        pc.ungraded?.let { PriceRow("Ungraded", it.usd()) }
                        pc.grade9?.let { PriceRow("Grade 9", it.usd()) }
                        pc.psa10?.let { PriceRow("PSA 10", it.usd()) }
                        pc.bgs10?.let { PriceRow("BGS 10", it.usd()) }
                    }
                    Text("PriceCharting: ${pc.productName} · ${pc.consoleName}", style = MaterialTheme.typography.bodySmall, color = Palette.text3, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun EbayComps(p: ProductDetail) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf<String?>(null) }
    val sales = p.ebaySales
    GlassCard(Modifier.fillMaxWidth()) {
        CardTitle("Recent eBay Comps", badge = if (sales.isNotEmpty()) "${sales.size} sale${if (sales.size == 1) "" else "s"}" else null)
        if (sales.isEmpty()) {
            Text(
                "No eBay sales captured for this ${if (p.product.isSealed) "product" else "card"} yet. Import eBay sold listings in TCG Vault's web Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.text2,
            )
            return@GlassCard
        }
        val groups = sales.groupBy { it.label }.toList().sortedByDescending { it.second.size }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("All", filter == null, { filter = null })
            groups.forEach { (k, list) -> Pill("$k ${median(list.map { it.unitPrice }).usd()} · ${list.size}", filter == k, { filter = k }) }
        }
        sales.filter { filter == null || it.label == filter }.take(40).forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !s.id.startsWith("t:")) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ebay.com/itm/${URLEncoder.encode(s.id, "UTF-8")}")))
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.soldDate.dayLabel(false), style = MaterialTheme.typography.bodySmall, color = Palette.text3, modifier = Modifier.width(52.dp))
                Column(Modifier.weight(1f)) {
                    Badge(s.label)
                    Text(
                        s.title + (if (s.quantity > 1) " · ${s.quantity} units" else "") + (if (s.priceIsRange) " · lowest price" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.text2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(s.price.usd(), style = PriceStyle, color = Palette.text, modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = Palette.border)
        }
    }
}

private fun median(xs: List<Double>): Double? {
    if (xs.isEmpty()) return null
    val v = xs.sorted()
    return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
}

@Composable
private fun ShopLinks(p: ProductDetail, isZh: Boolean, low: Double?) {
    val context = LocalContext.current
    val item = p.product
    val enName = item.name.replace(Regex("\\s*\\([^)]*[\\u3400-\\u9fff][^)]*\\)"), "").replace(Regex("\\s+-\\s+.*$"), "")
    val q = URLEncoder.encode(
        (if (isZh) "$enName ${item.number.orEmpty()} chinese pokemon" else "${item.name} ${item.number.orEmpty()} ${item.setName}").replace(Regex("\\s+"), " ").trim(),
        "UTF-8",
    )
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    GlassCard(Modifier.fillMaxWidth()) {
        CardTitle("Shop")
        p.url?.let { url -> ShopRow("TCGplayer", "View all listings", low?.usd()) { open(url) } }
        ShopRow("eBay", "Check out the latest listings", null) { open("https://www.ebay.com/sch/i.html?_nkw=$q") }
        ShopRow("eBay", "Recently sold prices", null) { open("https://www.ebay.com/sch/i.html?_nkw=$q&LH_Sold=1&LH_Complete=1") }
    }
}

@Composable
private fun ShopRow(source: String, label: String, amount: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Palette.surface2.copy(alpha = 0.6f)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(source, style = MaterialTheme.typography.labelLarge, color = Palette.text, modifier = Modifier.width(84.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.text2, modifier = Modifier.weight(1f))
        if (amount != null) Text(amount, style = PriceStyle, color = Palette.text)
    }
}
