package com.cardprice.app.ui.collection

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CardPrices
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.PokeCottage
import com.cardprice.app.data.collection.SetSubsets
import com.cardprice.app.data.collection.SetValue
import com.cardprice.app.data.scan.ScanMatcher
import com.cardprice.app.ui.SetArt
import com.cardprice.app.ui.display
import com.cardprice.app.ui.formatCurrency
import com.cardprice.app.data.market.Load

private enum class CardFilter(val label: String) { ALL("All"), MISSING("Missing"), OWNED("Owned") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionSetScreen(
    language: Language,
    setId: String,
    setName: String,
    art: List<String>,
    cardsState: SetCardsState,
    collection: CollectionState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onSetCount: (cards: List<CollectionCard>, cardId: String, variantKey: String, count: Int) -> Unit,
    onCardsLoaded: (List<CollectionCard>) -> Unit,
    onEnsurePrices: (Collection<String>) -> Unit,
    /** A card's row came into view; look up its price if it isn't known yet. */
    onRowPrice: (String) -> Unit,
    onValueChanged: (SetValue) -> Unit,
    /** Card to open when the screen appears (from search or scan history): printed number, with name as a fallback. */
    focusNumber: String? = null,
    focusName: String? = null,
) {
    var filter by rememberSaveable { mutableStateOf(CardFilter.ALL) }
    // Master-set mode counts every variant; otherwise any variant of a card counts as owning it.
    var masterSet by rememberSaveable { mutableStateOf(false) }
    var openCardId by rememberSaveable { mutableStateOf<String?>(null) }
    // Card id + variant key awaiting "remove all copies" confirmation.
    var pendingRemoval by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    val subsets = remember(language, setId) { SetSubsets.forSet(language, setId) }
    var subsetLabel by rememberSaveable { mutableStateOf<String?>(null) }
    val subset = subsets.firstOrNull { it.label == subsetLabel }
    var query by rememberSaveable { mutableStateOf("") }
    var focusHandled by rememberSaveable { mutableStateOf(false) }
    val cards = (cardsState.cards as? Load.Ready)?.value
    LaunchedEffect(cards) { cards?.let(onCardsLoaded) }
    LaunchedEffect(cards) {
        if (focusHandled || cards == null || (focusNumber == null && focusName == null)) return@LaunchedEffect
        focusHandled = true
        val card = focusNumber?.let { n -> cards.firstOrNull { ScanMatcher.sameNumber(it.number, n) } }
            ?.takeIf { focusName == null || it.name.equals(focusName, ignoreCase = true) || it.name.contains(focusName, true) }
            ?: focusName?.let { n -> cards.firstOrNull { it.name.equals(n, ignoreCase = true) } }
            ?: focusNumber?.let { n -> cards.firstOrNull { ScanMatcher.sameNumber(it.number, n) } }
        card?.let { openCardId = it.id }
    }

    // Price every owned card so the set's value is complete; new cards get priced as they're added.
    val ownedIds = cards.orEmpty().filter { c -> !c.placeholder && c.variants.any { collection.count(language, setId, c.id, it.key) > 0 } }
        .map { it.id }.toSet()
    LaunchedEffect(ownedIds) { if (ownedIds.isNotEmpty()) onEnsurePrices(ownedIds) }
    val value = cards?.let { SetValue.of(it, { id, key -> collection.count(language, setId, id, key) }, cardsState.prices) }
    LaunchedEffect(value) { value?.let(onValueChanged) }

    fun count(cardId: String, variantKey: String) = collection.count(language, setId, cardId, variantKey)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(setName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(setId, style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Reload card list") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val load = cardsState.cards) {
                Load.Loading -> LoadingCards(cardsState.progress)
                is Load.Failed -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Couldn't load this set's cards.", fontWeight = FontWeight.Bold)
                    Text(load.message)
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
                is Load.Ready -> {
                    val all = load.value
                    fun owned(card: CollectionCard) = card.variants.any { count(card.id, it.key) > 0 }
                    fun complete(card: CollectionCard) = card.variants.all { count(card.id, it.key) > 0 }
                    val inScope = (if (subset == null) all else all.filter(subset::contains)).filter { it.matchesQuery(query) }
                    val shown = when (filter) {
                        CardFilter.ALL -> inScope
                        CardFilter.MISSING -> inScope.filter { if (masterSet) !complete(it) else !owned(it) }
                        CardFilter.OWNED -> inScope.filter(::owned)
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item {
                            SetHeader(
                                language, setId, setName,
                                art + listOfNotNull(all.firstOrNull { it.image != null }?.imageUrl("low")),
                                value = value ?: SetValue.NONE,
                                pricing = cardsState.pricing,
                                ownedCards = all.count(::owned),
                                totalCards = all.size,
                                ownedVariants = all.sumOf { c -> c.variants.count { count(c.id, it.key) > 0 } },
                                totalVariants = all.sumOf { it.variants.size },
                                subsets = subsets.map { s ->
                                    val cards = all.filter(s::contains)
                                    Triple(s.label, cards.count(::owned), cards.size)
                                },
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                placeholder = { Text("Find a card by number or name") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(28.dp),
                            )
                        }
                        if (subsets.isNotEmpty()) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Show", style = MaterialTheme.typography.labelLarge)
                                    FilterChip(
                                        selected = subset == null,
                                        onClick = { subsetLabel = null },
                                        label = { Text("Whole set") },
                                    )
                                    subsets.forEach { s ->
                                        FilterChip(
                                            selected = subset == s,
                                            onClick = { subsetLabel = s.label },
                                            label = { Text(s.label) },
                                        )
                                    }
                                }
                            }
                        }
                        if (all.isNotEmpty() && all.all { it.placeholder }) {
                            item {
                                Text(
                                    "TCGdex doesn't have card names or images for this set yet, so it's shown as a " +
                                        "numbered checklist. Tick cards off by the number printed on them.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CardFilter.entries.forEach { f ->
                                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                                }
                                FilterChip(
                                    selected = masterSet,
                                    onClick = { masterSet = !masterSet },
                                    label = { Text("Master set") },
                                )
                            }
                            Text(
                                "Tap a variant to add a copy · press and hold to remove it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        if (shown.isEmpty()) {
                            item {
                                Text(
                                    when {
                                        query.isNotBlank() -> "No card matches \"$query\" here."
                                    else -> when (filter) {
                                        CardFilter.MISSING -> "Nothing missing. Set complete! 🎉"
                                        CardFilter.OWNED -> "No cards marked yet. Tap a variant to add a copy."
                                        CardFilter.ALL -> "This set has no cards listed yet."
                                    }
                                    },
                                    modifier = Modifier.padding(32.dp),
                                )
                            }
                        }
                        items(shown, key = { it.id }) { card ->
                            if (!card.placeholder) LaunchedEffect(card.id) { onRowPrice(card.id) }
                            CardRow(
                                card = card,
                                prices = cardsState.prices[card.id],
                                count = { count(card.id, it) },
                                onAdd = { key -> onSetCount(all, card.id, key, count(card.id, key) + 1) },
                                onRequestRemove = { key -> if (count(card.id, key) > 0) pendingRemoval = card.id to key },
                                onOpen = { openCardId = card.id },
                            )
                        }
                    }
                    pendingRemoval?.let { (cardId, key) ->
                        val card = all.firstOrNull { it.id == cardId }
                        val variant = card?.variants?.firstOrNull { it.key == key }
                        val n = count(cardId, key)
                        if (card == null || variant == null || n == 0) {
                            pendingRemoval = null
                        } else {
                            AlertDialog(
                                onDismissRequest = { pendingRemoval = null },
                                title = { Text("Remove from collection?") },
                                text = {
                                    Text(
                                        "Remove ${if (n == 1) "your copy" else "all $n copies"} of #${card.number} ${card.name} " +
                                            "(${variant.label})?",
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        onSetCount(all, cardId, key, 0)
                                        pendingRemoval = null
                                    }) { Text("Remove") }
                                },
                                dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Keep") } },
                            )
                        }
                    }
                    openCardId?.let { id -> all.firstOrNull { it.id == id } }?.let { card ->
                        CardDetailSheet(
                            card = card,
                            prices = cardsState.prices[card.id],
                            onNeedPrices = { onEnsurePrices(listOf(card.id)) },
                            count = { count(card.id, it) },
                            onSetCount = { key, n -> onSetCount(all, card.id, key, n) },
                            onDismiss = { openCardId = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingCards(progress: Pair<Int, Int>?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (progress == null || progress.second == 0) {
            CircularProgressIndicator()
            Text("Loading cards…", modifier = Modifier.padding(top = 16.dp))
        } else {
            LinearProgressIndicator(
                progress = { progress.first.toFloat() / progress.second },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Loading card variants ${progress.first} of ${progress.second}…", modifier = Modifier.padding(top = 16.dp))
            Text(
                "This happens once per set; after that it opens instantly, even offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetHeader(
    language: Language,
    setId: String,
    setName: String,
    art: List<String>,
    value: SetValue,
    pricing: Pair<Int, Int>?,
    ownedCards: Int,
    totalCards: Int,
    ownedVariants: Int,
    totalVariants: Int,
    subsets: List<Triple<String, Int, Int>>,
) {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SetArt(
                    urls = art,
                    label = setId,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.width(120.dp).height(72.dp),
                )
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text("Collection value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        value.display() ?: if (ownedCards == 0) "—" else "No prices yet",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val note = when {
                        pricing != null -> "Updating prices ${pricing.first} of ${pricing.second}…"
                        value.unpricedCopies > 0 -> "${value.unpricedCopies} ${copies(value.unpricedCopies)} without a recent price"
                        value.pricedCopies > 0 -> "Market prices for ${value.pricedCopies} ${copies(value.pricedCopies)}"
                        else -> "Add cards to see what they're worth"
                    }
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ProgressLine("Cards", ownedCards, totalCards)
            ProgressLine("Master set (every variant)", ownedVariants, totalVariants)
            subsets.forEach { (label, owned, total) -> ProgressLine(label, owned, total) }

            val links = if (language == Language.ENGLISH) PokeCottage.forSet(setId) else null
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                links?.cardList?.let { OutlinedButton(onClick = { uriHandler.openUri(it) }) { Text("PokeCottage card list") } }
                links?.masterSetGuide?.let { OutlinedButton(onClick = { uriHandler.openUri(it) }) { Text("Master set guide") } }
                if (language == Language.CHINESE_SIMPLIFIED) {
                    OutlinedButton(onClick = { uriHandler.openUri(PokeCottage.CHINESE_LIBRARY) }) { Text("PokeCottage Chinese sets") }
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(label: String, owned: Int, total: Int) {
    val fraction = if (total > 0) owned.toFloat() / total else 0f
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("$owned / $total · ${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardRow(
    card: CollectionCard,
    prices: CardPrices?,
    count: (String) -> Int,
    onAdd: (String) -> Unit,
    onRequestRemove: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardImage(card.imageUrl("low"), Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text("#${card.number} · ${card.name}", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.rarity?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rowPriceText(card, prices)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                card.variants.forEach { variant ->
                    VariantButton(
                        label = variant.label,
                        count = count(variant.key),
                        onTap = { onAdd(variant.key) },
                        onLongPress = { onRequestRemove(variant.key) },
                    )
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 84.dp))
}

/**
 * Market price per version for a card row: "$1.20" for a single version, "Normal $0.10 · Reverse Holo $0.35"
 * for several. Null while loading or for numbered placeholders (no price data).
 */
internal fun rowPriceText(card: CollectionCard, prices: CardPrices?): String? {
    if (card.placeholder || prices == null) return null
    val priced = card.variants.mapNotNull { v -> prices.byVariant[v.key]?.let { v to it } }
    return when {
        priced.isEmpty() -> "No recent price"
        card.variants.size == 1 -> formatCurrency(priced.single().second.amount, priced.single().second.currency)
        else -> priced.joinToString(" · ") { (v, p) -> "${v.label} ${formatCurrency(p.amount, p.currency)}" }
    }
}

/** Tap adds a copy; press-and-hold asks to remove every copy of this variant. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VariantButton(label: String, count: Int, onTap: () -> Unit, onLongPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val owned = count > 0
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = if (owned) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (owned) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (owned) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .clip(shape)
            .combinedClickable(
                onClickLabel = "Add a copy",
                onLongClickLabel = "Remove all copies",
                onLongClick = {
                    if (owned) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                },
                onClick = onTap,
            )
            .semantics { contentDescription = if (owned) "$label, $count owned" else "$label, not owned" },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (owned) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (count > 1) {
                Text(
                    "×$count",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CardImage(url: String?, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.aspectRatio(0.716f).clip(shape), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Card(Modifier.fillMaxSize(), shape = shape) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No image", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardDetailSheet(
    card: CollectionCard,
    prices: CardPrices?,
    onNeedPrices: () -> Unit,
    count: (String) -> Int,
    onSetCount: (variantKey: String, count: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(card.id) { if (!card.placeholder) onNeedPrices() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CardImage(card.imageUrl("high"), Modifier.height(320.dp))
                }
                Text(card.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                Text(
                    listOfNotNull("#${card.number}", card.rarity).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Your copies", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            }
            items(card.variants, key = { it.key }) { variant ->
                val n = count(variant.key)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(variant.label, fontWeight = FontWeight.Medium)
                        val priceText = when {
                            card.placeholder -> "No price data"
                            prices == null -> "Loading price…"
                            else -> prices.byVariant[variant.key]?.let { "${formatCurrency(it.amount, it.currency)} · ${it.source}" }
                                ?: "No recent price"
                        }
                        Text(priceText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalIconButton(onClick = { onSetCount(variant.key, (n - 1).coerceAtLeast(0)) }, enabled = n > 0) {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                    }
                    Text("$n", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
                    FilledTonalIconButton(onClick = { onSetCount(variant.key, n + 1) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add a ${variant.label}")
                    }
                }
            }
        }
    }
}


private fun copies(n: Int) = if (n == 1) "copy" else "copies"

/**
 * Quick per-set search: a number ("111", "#111", "TG05", "111/084") matches the printed number;
 * anything else matches part of the card's name.
 */
internal fun CollectionCard.matchesQuery(query: String): Boolean {
    val q = query.trim().removePrefix("#").substringBefore('/').trim()
    if (q.isEmpty()) return true
    val looksLikeNumber = q.any(Char::isDigit) && q.length <= 5 && q.none { it == ' ' }
    if (looksLikeNumber && (ScanMatcher.sameNumber(number, q) || number.trimStart('0').startsWith(q.trimStart('0')))) return true
    return name.contains(q, ignoreCase = true)
}
