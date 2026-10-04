package com.cardprice.app.ui.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cardprice.app.data.Language
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.SetSearch
import com.cardprice.app.data.collection.TCGPLAYER_IMAGE_PREFIX
import com.cardprice.app.data.inventory.CardCondition
import com.cardprice.app.ui.collection.CollectionState
import com.cardprice.app.data.inventory.CollectionMode
import com.cardprice.app.data.inventory.InventoryItem
import com.cardprice.app.data.inventory.InventoryKind
import com.cardprice.app.data.inventory.InventoryOps
import com.cardprice.app.data.inventory.InventoryStatus
import com.cardprice.app.data.inventory.InventorySummary
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.search.CardHit
import com.cardprice.app.ui.AppIcons
import com.cardprice.app.ui.money
import com.cardprice.app.ui.shortDate
import com.cardprice.app.ui.showcase.CARD_ASPECT
import com.cardprice.app.ui.showcase.ShowcaseCard
import com.cardprice.app.ui.showcase.ShowcaseRow
import com.cardprice.app.ui.showcase.ShowcaseViewer
import com.cardprice.app.ui.showcase.rememberGalleryMode
import com.cardprice.app.ui.toAmount

/** Inventory tab: stock held to sell or trade, with cost, market value and profit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    vm: InventoryViewModel,
    allSets: List<PokemonSet>,
    collection: CollectionState,
    /** Copies of a collection item were sold: take them out of the collection. */
    onSoldFromCollection: (InventoryItem, count: Int) -> Unit,
) {
    val items by vm.items.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val mode by vm.mode.collectAsState()
    val linking by vm.linking.collectAsState()
    LaunchedEffect(collection.owned, collection.progress) { vm.syncCollection(collection.owned, collection.progress) }
    var source by rememberSaveable { mutableStateOf<Boolean?>(null) } // true: from collection, false: added here
    var status by rememberSaveable { mutableStateOf<InventoryStatus?>(null) }
    var kind by rememberSaveable { mutableStateOf<InventoryKind?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var gallery by rememberGalleryMode("inventory", default = false)
    var editing by remember { mutableStateOf<InventoryItem?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var sort by rememberSaveable { mutableStateOf(InventorySort.VALUE) }
    var menu by remember { mutableStateOf(false) }

    val shown = items
        .filter { status == null || it.status == status }
        .filter { kind == null || it.kind == kind }
        .filter { source == null || it.fromCollection == source }
        .filter { InventoryOps.matches(it, query) }
        .let(sort::apply)
    val summary = InventorySummary.of(items)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventory", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { gallery = !gallery }) {
                        Icon(if (gallery) AppIcons.ViewList else AppIcons.GridView, contentDescription = if (gallery) "Show as list" else "Show as gallery")
                    }
                    if (refreshing != null) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(onClick = vm::refreshPrices) { Icon(Icons.Filled.Refresh, contentDescription = "Update market prices") }
                    }
                    // Settings you change rarely live here, out of the way of the list.
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Sort and options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuLabel("Sort by")
                            InventorySort.entries.forEach { o -> MenuChoice(o.label, sort == o) { sort = o; menu = false } }
                            HorizontalDivider()
                            MenuLabel("From your collection")
                            CollectionMode.entries.forEach { m -> MenuChoice(m.label, mode == m) { vm.setMode(m); menu = false } }
                            if (mode != CollectionMode.OFF) {
                                HorizontalDivider()
                                MenuLabel("Show")
                                MenuChoice("Everything", source == null) { source = null; menu = false }
                                MenuChoice("From collection", source == true) { source = true; menu = false }
                                MenuChoice("Added here", source == false) { source = false; menu = false }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { SummaryCard(summary, refreshing ?: linking?.let { (d, t) -> d to t }, linking != null) }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("Search name, set, number or location") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") } },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }
            item {
                // One scrolling row: status, then kind.
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    item { FilterChip(selected = status == null, onClick = { status = null }, label = { Text("All") }) }
                    items(InventoryStatus.entries.toList()) { s ->
                        FilterChip(selected = status == s, onClick = { status = if (status == s) null else s }, label = { Text(s.label) })
                    }
                    item { VerticalDivider(Modifier.height(24.dp)) }
                    items(InventoryKind.entries.toList()) { k ->
                        FilterChip(selected = kind == k, onClick = { kind = if (kind == k) null else k }, label = { Text(if (k == InventoryKind.CARD) "Cards" else "Sealed") })
                    }
                }
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        "Nothing in stock yet. Tap Add to track cards and sealed products you're holding to sell or trade: " +
                            "what they cost, what they're worth now and what they sold for.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (shown.isEmpty()) {
                item { Text("Nothing matches these filters.", modifier = Modifier.padding(24.dp)) }
            }
            if (gallery) {
                items(shown.chunked(3), key = { row -> "row-" + row.first().id }) { row ->
                    ShowcaseRow(row.map(::showcaseItem), columns = 3, onOpen = { tile -> viewerIndex = shown.indexOfFirst { it.id == tile.key } })
                }
            } else {
                items(shown, key = { it.id }) { item -> ItemRow(item, Modifier.animateItem()) { editing = item } }
            }
        }
    }

    viewerIndex?.let { start ->
        ShowcaseViewer(shown.map(::showcaseItem), start, onDismiss = { viewerIndex = null }) { tile ->
            val item = shown.firstOrNull { it.id == tile.key } ?: return@ShowcaseViewer
            Text(moneyLine(item), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { viewerIndex = null; editing = item }) { Text("Edit item", color = Color.White) }
        }
    }
    if (adding) {
        AddItemSheet(vm, allSets, onDismiss = { adding = false }) { draft ->
            adding = false
            editing = draft
        }
    }
    editing?.let { item ->
        ItemEditor(
            item = item,
            isNew = items.none { it.id == item.id },
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onSell = { count, price ->
                if (item.fromCollection) {
                    vm.recordCollectionSale(item, count, price)
                    onSoldFromCollection(item, count)
                } else {
                    vm.sell(item.id, count, price)
                }
                editing = null
            },
            onDelete = { vm.delete(item.id); editing = null },
        )
    }
}

@Composable
private fun SummaryCard(summary: InventorySummary, refreshing: Pair<Int, Int>?, loadingCollection: Boolean = false) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column {
            Column(Modifier.padding(16.dp)) {
                Text("Market value", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary.heldMarket.money(), style = MaterialTheme.typography.headlineMedium.tnum(), fontWeight = FontWeight.Bold)
                if (summary.unrealizedProfit != 0.0) {
                    Text("${signed(summary.unrealizedProfit)} vs. cost", style = MaterialTheme.typography.titleSmall.tnum(), color = profitColor(summary.unrealizedProfit))
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("${summary.heldUnits}", "in stock", Modifier.weight(1f))
                    Stat("${summary.soldUnits}", "sold", Modifier.weight(1f))
                    Stat(signed(summary.realizedProfit), "realized", Modifier.weight(1f), profitColor(summary.realizedProfit))
                }
                val note = when {
                    refreshing != null && loadingCollection -> "Loading collection cards and prices…"
                    refreshing != null -> "Updating prices…"
                    summary.unpricedUnits > 0 -> "${summary.unpricedUnits} without a market price"
                    else -> null
                }
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            }
            // Progress as a hairline along the bottom edge, not more text.
            if (refreshing != null && refreshing.second > 0) {
                LinearProgressIndicator(progress = { refreshing.first.toFloat() / refreshing.second }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun MenuChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = { if (selected) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
    )
}

/** Orders for the list; highest value first by default, like a holdings view. */
private enum class InventorySort(val label: String) {
    VALUE("Value, high to low"),
    PROFIT("Profit, high to low"),
    NAME("Name"),
    RECENT("Recently added");

    fun apply(list: List<InventoryItem>): List<InventoryItem> = when (this) {
        VALUE -> list.sortedByDescending { it.totalMarket ?: ((it.soldPriceEach ?: 0.0) * it.quantity) }
        PROFIT -> list.sortedByDescending { it.realized ?: it.unrealized ?: Double.NEGATIVE_INFINITY }
        NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        RECENT -> list.sortedByDescending { it.addedAt }
    }
}

/** Figures in columns line up when every digit has the same width. */
private fun androidx.compose.ui.text.TextStyle.tnum() = copy(fontFeatureSettings = "tnum")

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, color: Color = Color.Unspecified) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleSmall.tnum(), fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemRow(item: InventoryItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ItemPicture(item, Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.status != InventoryStatus.IN_STOCK) StatusTag(item.status)
                    Text(
                        listOfNotNull(item.setName, item.number?.let { "#$it" }, item.grade ?: item.condition?.short, "×${item.quantity}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val total = if (item.status == InventoryStatus.SOLD) item.soldPriceEach?.times(item.quantity) else item.totalMarket
                Text(total?.money() ?: "—", style = MaterialTheme.typography.titleSmall.tnum(), fontWeight = FontWeight.Bold)
                val profit = item.realized ?: item.unrealized
                val cost = item.totalCost
                if (profit != null) {
                    val pct = if (cost != null && cost > 0) " (${"%+.0f".format(profit / cost * 100)}%)" else ""
                    Text(signed(profit) + pct, style = MaterialTheme.typography.labelMedium.tnum(), color = profitColor(profit))
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** Only Listed and Sold get a tag; In stock is the normal state and needs none. */
@Composable
private fun StatusTag(status: InventoryStatus) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(status.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

@Composable
private fun StatusChip(status: InventoryStatus) {
    val color = when (status) {
        InventoryStatus.IN_STOCK -> MaterialTheme.colorScheme.secondaryContainer
        InventoryStatus.LISTED -> MaterialTheme.colorScheme.tertiaryContainer
        InventoryStatus.SOLD -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = RoundedCornerShape(6.dp)) {
        Text(status.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun ItemPicture(item: InventoryItem, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.aspectRatio(CARD_ASPECT).clip(shape), contentAlignment = Alignment.Center) {
        if (item.image != null) {
            AsyncImage(model = item.image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Inventory, contentDescription = null) }
            }
        }
    }
}

/** "Cost $40.00 · Market $55.00 · Asking $60.00" (or "Sold for $58.00 on …"), per copy. */
private fun moneyLine(item: InventoryItem): String = listOfNotNull(
    item.costEach?.let { "Cost ${it.money()}" },
    if (item.status == InventoryStatus.SOLD) item.soldPriceEach?.let { "Sold ${it.money()}" + (item.soldAt?.let { at -> " · ${at.shortDate()}" } ?: "") } else null,
    item.marketPrice?.takeIf { item.status != InventoryStatus.SOLD }?.let { "Market ${it.money()}" },
    item.askingPrice?.takeIf { item.status != InventoryStatus.SOLD }?.let { "Asking ${it.money()}" },
).joinToString(" · ").ifEmpty { "No prices yet" }

private fun signed(amount: Double): String = (if (amount > 0) "+" else if (amount < 0) "−" else "") + kotlin.math.abs(amount).money()

@Composable
private fun profitColor(amount: Double): Color = when {
    amount > 0 -> Color(0xFF2E9D57)
    amount < 0 -> MaterialTheme.colorScheme.error
    else -> Color.Unspecified
}

/** An inventory item for the gallery: sold items are dimmed; graded and pricey cards shine. */
private fun showcaseItem(item: InventoryItem) = ShowcaseCard(
    key = item.id,
    name = item.name,
    caption = item.details.ifEmpty { item.kind.label },
    image = item.image,
    imageLarge = item.image?.replace("_200w.jpg", "_400w.jpg"),
    owned = item.status != InventoryStatus.SOLD,
    shiny = item.status != InventoryStatus.SOLD && (item.grade != null || (item.marketPrice ?: 0.0) >= SHINY_VALUE),
    badge = if (item.status == InventoryStatus.SOLD) "Sold" else "×${item.quantity}",
    price = (if (item.status == InventoryStatus.SOLD) item.soldPriceEach else item.marketPrice)?.money(),
)

/** Market value per copy at which an inventory card gets the holo sheen in the gallery. */
private const val SHINY_VALUE = 20.0

/** Adding: find a card, pick a set's sealed product, or type anything in by hand. The choice opens the editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemSheet(vm: InventoryViewModel, allSets: List<PokemonSet>, onDismiss: () -> Unit, onPicked: (InventoryItem) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 400.dp).imePadding()) {
            Text("Add to inventory", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
            SecondaryTabRow(selectedTabIndex = tab) {
                listOf("Card", "Sealed", "By hand").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
            }
            when (tab) {
                0 -> CardPicker(vm) { hit ->
                    onPicked(
                        InventoryItem(
                            id = vm.newId(), kind = InventoryKind.CARD, name = hit.name, setName = hit.setName.substringAfter(": "),
                            language = hit.language, number = hit.number, productId = hit.productId, image = hit.imageUrl,
                            condition = CardCondition.NM, marketPrice = hit.marketPrice, priceUpdatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                1 -> SealedPicker(vm, allSets) { set, product ->
                    onPicked(
                        InventoryItem(
                            id = vm.newId(), kind = InventoryKind.SEALED, name = product.name, setName = set.name, language = set.language,
                            productId = product.id, image = "$TCGPLAYER_IMAGE_PREFIX${product.id}_200w.jpg",
                            marketPrice = product.marketPrice, priceUpdatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("For anything TCGplayer doesn't list, or to skip the search. You won't get market prices for it.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPicked(InventoryItem(id = vm.newId(), kind = InventoryKind.CARD, name = "", condition = CardCondition.NM)) }) { Text("A card") }
                        OutlinedButton(onClick = { onPicked(InventoryItem(id = vm.newId(), kind = InventoryKind.SEALED, name = "")) }) { Text("A sealed product") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardPicker(vm: InventoryViewModel, onPick: (CardHit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(Language.ENGLISH) }
    val results by vm.cardResults.collectAsState()
    LaunchedEffect(query, language) { vm.searchCards(query, language) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Card name or number, e.g. Charizard 199") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            listOf(Language.ENGLISH, Language.JAPANESE).forEach { l ->
                FilterChip(selected = language == l, onClick = { language = l }, label = { Text(l.nativeName) })
            }
        }
        when (val r = results) {
            null -> Text("Search TCGplayer's cards; the price comes along.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Load.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Load.Failed -> Text("Couldn't search right now. ${r.message}")
            is Load.Ready -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(r.value, key = { it.productId }) { hit ->
                    PickRow(hit.imageUrl, hit.name, listOfNotNull(hit.setName, hit.number?.let { "#$it" }, hit.rarity).joinToString(" · "), hit.marketPrice) { onPick(hit) }
                }
            }
        }
    }
}

@Composable
private fun SealedPicker(vm: InventoryViewModel, allSets: List<PokemonSet>, onPick: (PokemonSet, com.cardprice.app.data.market.MarketProduct) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var set by remember { mutableStateOf<PokemonSet?>(null) }
    val results by vm.sealedResults.collectAsState()
    LaunchedEffect(set) { vm.loadSealed(set) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        val chosen = set
        if (chosen == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Set, e.g. Pitch Black or SV2a") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(SetSearch.find(allSets.filter { it.language != Language.CHINESE_SIMPLIFIED }, query), key = { it.id }) { s ->
                    Text(
                        "${s.name} · ${s.language.nativeName} · ${s.year}",
                        modifier = Modifier.fillMaxWidth().clickable { set = s }.padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chosen.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { set = null }) { Text("Change set") }
            }
            when (val r = results) {
                null, Load.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Load.Failed -> Text("Couldn't load products. ${r.message}")
                is Load.Ready -> if (r.value.isEmpty()) {
                    Text("TCGplayer lists no sealed products for this set.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(r.value, key = { it.id }) { p ->
                            PickRow("$TCGPLAYER_IMAGE_PREFIX${p.id}_200w.jpg", p.name, chosen.name, p.marketPrice) { onPick(chosen, p) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(image: String, title: String, subtitle: String, price: Double?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(model = image, contentDescription = null, modifier = Modifier.width(44.dp).aspectRatio(CARD_ASPECT))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(price?.money() ?: "—", fontWeight = FontWeight.Bold)
    }
}

/** Details of one item: new ones get Save; existing ones can also be sold or deleted. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemEditor(
    item: InventoryItem,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (InventoryItem) -> Unit,
    onSell: (count: Int, priceEach: Double?) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    var setName by remember(item.id) { mutableStateOf(item.setName.orEmpty()) }
    var number by remember(item.id) { mutableStateOf(item.number.orEmpty()) }
    var finish by remember(item.id) { mutableStateOf(item.finish.orEmpty()) }
    var condition by remember(item.id) { mutableStateOf(item.condition) }
    var grade by remember(item.id) { mutableStateOf(item.grade.orEmpty()) }
    var quantity by remember(item.id) { mutableStateOf(item.quantity.toString()) }
    var cost by remember(item.id) { mutableStateOf(item.costEach?.let { "%.2f".format(it) }.orEmpty()) }
    var asking by remember(item.id) { mutableStateOf(item.askingPrice?.let { "%.2f".format(it) }.orEmpty()) }
    var market by remember(item.id) { mutableStateOf(item.marketPrice?.let { "%.2f".format(it) }.orEmpty()) }
    var status by remember(item.id) { mutableStateOf(item.status) }
    var location by remember(item.id) { mutableStateOf(item.location.orEmpty()) }
    var notes by remember(item.id) { mutableStateOf(item.notes.orEmpty()) }
    var selling by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun edited() = item.copy(
        name = name.trim(),
        setName = setName.trim().ifEmpty { null },
        number = number.trim().removePrefix("#").ifEmpty { null },
        finish = finish.trim().ifEmpty { null },
        condition = if (item.kind == InventoryKind.CARD) condition else null,
        grade = grade.trim().ifEmpty { null },
        quantity = quantity.toIntOrNull()?.coerceAtLeast(1) ?: item.quantity,
        costEach = cost.toAmount(),
        askingPrice = asking.toAmount(),
        // Items with a TCGplayer product keep their fetched price; hand-entered ones take what's typed.
        marketPrice = if (item.productId != null) item.marketPrice else market.toAmount(),
        status = if (item.status == InventoryStatus.SOLD) InventoryStatus.SOLD else status,
        location = location.trim().ifEmpty { null },
        notes = notes.trim().ifEmpty { null },
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ItemPicture(item, Modifier.width(64.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (isNew) "New ${item.kind.label.lowercase()}" else item.kind.label, style = MaterialTheme.typography.labelLarge)
                    Text(name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    item.marketPrice?.let { p ->
                        Text(
                            "Market ${p.money()}" + (item.priceUpdatedAt?.let { " · ${it.shortDate()}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (item.productId == null) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(setName, { setName = it }, label = { Text("Set") }, singleLine = true, modifier = Modifier.weight(2f))
                    if (item.kind == InventoryKind.CARD) {
                        OutlinedTextField(number, { number = it }, label = { Text("Number") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (item.kind == InventoryKind.CARD) {
                Text("Condition", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardCondition.entries.forEach { c ->
                        FilterChip(selected = condition == c, onClick = { condition = c }, label = { Text(c.short) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(finish, { finish = it }, label = { Text("Version") }, placeholder = { Text("e.g. Reverse Holo") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(grade, { grade = it }, label = { Text("Grade") }, placeholder = { Text("e.g. PSA 10") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            if (item.fromCollection) {
                Text(
                    "×${item.quantity} from your collection. The count follows the collection; details you add here are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!item.fromCollection) OutlinedTextField(
                    quantity, { quantity = it.filter(Char::isDigit) }, label = { Text("Quantity") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    cost, { cost = it }, label = { Text("Cost each") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    asking, { asking = it }, label = { Text("Asking each") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                if (item.productId == null) {
                    OutlinedTextField(
                        market, { market = it }, label = { Text("Value each") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                }
            }
            if (item.status != InventoryStatus.SOLD) {
                Text("Status", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(InventoryStatus.IN_STOCK, InventoryStatus.LISTED).forEach { s ->
                        FilterChip(selected = status == s, onClick = { status = s }, label = { Text(s.label) })
                    }
                }
            }
            OutlinedTextField(location, { location = it }, label = { Text("Kept in") }, placeholder = { Text("e.g. Binder 2, Shoebox A") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Button(onClick = { onSave(edited()) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (isNew) "Add to inventory" else "Save changes")
            }
            if (!isNew) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.status != InventoryStatus.SOLD) {
                        OutlinedButton(onClick = { selling = true }, modifier = Modifier.weight(1f)) { Text("Mark sold…") }
                    }
                    if (!item.fromCollection) {
                        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (selling) SellDialog(item, onDismiss = { selling = false }) { count, price -> selling = false; onSell(count, price) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this item?") },
            text = { Text("${item.name} (×${item.quantity}) is removed from your inventory. Your collection isn't affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SellDialog(item: InventoryItem, onDismiss: () -> Unit, onSell: (Int, Double?) -> Unit) {
    var count by remember { mutableStateOf(item.quantity.toString()) }
    var price by remember { mutableStateOf((item.askingPrice ?: item.marketPrice)?.let { "%.2f".format(it) }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark sold") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    count, { count = it.filter(Char::isDigit) }, label = { Text("How many (of ${item.quantity})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    price, { price = it }, label = { Text("Sold for, each") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    if (item.fromCollection) "Sold copies are taken out of your collection, and the sale is kept in inventory."
                    else "Selling part of a stack keeps the rest in stock.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSell(count.toIntOrNull()?.coerceIn(1, item.quantity) ?: item.quantity, price.toAmount()) }) { Text("Sold") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
