package com.cardprice.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.PriceMath
import com.cardprice.app.data.Purchase
import com.cardprice.app.data.market.Deal
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.MarketProduct
import com.cardprice.app.data.market.ProductKind
import com.cardprice.app.data.CUSTOM_PRESET
import com.cardprice.app.data.cardsPerPackFor
import com.cardprice.app.data.presetsFor
import com.cardprice.app.ui.theme.dealColors
import com.cardprice.app.ui.theme.seriesColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalculatorScreen(
    set: PokemonSet,
    purchases: List<Purchase>,
    onBack: () -> Unit,
    onSavePurchase: (Purchase) -> Unit,
    onDeletePurchase: (Long) -> Unit,
    market: MarketState,
    onRefreshMarket: () -> Unit,
    onOpenHistory: (MarketProduct, Int) -> Unit,
    /** Opened to check prices (from search): market prices come first, above the calculator. */
    pricesFirst: Boolean = false,
) {
    val presets = remember(set) { presetsFor(set) }
    var presetIndex by rememberSaveable { mutableIntStateOf(0) }
    var priceText by rememberSaveable { mutableStateOf("") }
    var quantityText by rememberSaveable { mutableStateOf("1") }
    var packsText by rememberSaveable { mutableStateOf(presets[0].packs.toString()) }
    var cardsText by rememberSaveable { mutableStateOf(cardsPerPackFor(set, presets[0]).toString()) }
    var taxText by rememberSaveable { mutableStateOf("") }

    val price = priceText.toAmount()
    val quantity = quantityText.toIntOrNull()
    val packsPerProduct = packsText.toIntOrNull()
    val cardsPerPack = cardsText.toIntOrNull()
    val tax = taxText.toAmount() ?: 0.0

    val valid = price != null && price > 0 &&
        quantity != null && quantity > 0 &&
        packsPerProduct != null && packsPerProduct > 0 &&
        cardsPerPack != null && cardsPerPack > 0

    val totalCost = if (valid) PriceMath.withTax(price!! * quantity!!, tax) else 0.0
    val totalPacks = if (valid) quantity!! * packsPerProduct!! else 0
    val totalCards = if (valid) totalPacks * cardsPerPack!! else 0

    // Compare against market only when the pack count still matches the chosen product.
    val kind = ProductKind.forPreset(presets[presetIndex].label)
        ?.takeIf { packsPerProduct == presets[presetIndex].packs }
    val comparison = kind?.let { k ->
        MarketComparison(
            tcgPlayer = market.matched[k]?.marketPrice,
            priceCharting = ((market.priceCharting as? Load.Ready)?.value?.get(k))?.price,
        ).takeIf { it.tcgPlayer != null || it.priceCharting != null }
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SetArt(
                            urls = calculatorSetArt(set),
                            label = set.code ?: set.series.shortTitle,
                            color = seriesColor(set.series),
                            // Light backing so dark logos stay readable on the colored bar.
                            modifier = Modifier.size(width = 64.dp, height = 44.dp)
                                .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                                .padding(3.dp),
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(set.name, fontWeight = FontWeight.Bold)
                            Text(set.series.title, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = seriesColor(set.series),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (pricesFirst) item { MarketSection(set, market, onRefreshMarket, onOpenHistory) }
            item {
                SectionTitle("What did you buy?")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    presets.forEachIndexed { index, preset ->
                        FilterChip(
                            selected = presetIndex == index,
                            onClick = {
                                presetIndex = index
                                packsText = preset.packs.toString()
                                // "Custom" keeps whatever card count is already entered.
                                if (preset.label != CUSTOM_PRESET) cardsText = cardsPerPackFor(set, preset).toString()
                            },
                            label = {
                                Text(if (preset.label == CUSTOM_PRESET) CUSTOM_PRESET else "${preset.label} (${preset.packs})")
                            },
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = "Price paid (each)",
                        prefix = "$",
                        decimal = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(
                            value = quantityText,
                            onValueChange = { quantityText = it },
                            label = "Quantity",
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            value = taxText,
                            onValueChange = { taxText = it },
                            label = "Tax % (optional)",
                            decimal = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(
                            value = packsText,
                            onValueChange = {
                                packsText = it
                                presetIndex = presets.lastIndex // edited by hand → Custom
                            },
                            label = "Packs each",
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            value = cardsText,
                            onValueChange = { cardsText = it },
                            label = "Cards per pack",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item {
                ResultsCard(
                    valid = valid,
                    totalCost = totalCost,
                    totalPacks = totalPacks,
                    totalCards = totalCards,
                    paidEach = price ?: 0.0,
                    comparison = comparison,
                )
            }

            item {
                Button(
                    enabled = valid,
                    onClick = {
                        onSavePurchase(
                            Purchase(
                                id = System.currentTimeMillis(),
                                setId = set.id,
                                product = presets[presetIndex].label,
                                quantity = quantity!!,
                                packsPerProduct = packsPerProduct!!,
                                cardsPerPack = cardsPerPack!!,
                                totalPrice = totalCost,
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                        priceText = ""
                        focus.clearFocus()
                        scope.launch { snackbar.showSnackbar("Purchase saved") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Text("Save purchase", modifier = Modifier.padding(start = 8.dp))
                }
            }

            if (!pricesFirst) item { MarketSection(set, market, onRefreshMarket, onOpenHistory) }

            if (purchases.isNotEmpty()) {
                item { HistorySummary(purchases) }
                items(purchases, key = { it.id }) { p ->
                    PurchaseRow(p, onDelete = { onDeletePurchase(p.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    prefix: String? = null,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val cleaned = if (decimal) input.filter { it.isDigit() || it == '.' || it == ',' } else input.filter(Char::isDigit)
            onValueChange(cleaned.take(9))
        },
        label = { Text(label) },
        prefix = prefix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        modifier = modifier,
    )
}

@Composable
private fun ResultsCard(
    valid: Boolean,
    totalCost: Double,
    totalPacks: Int,
    totalCards: Int,
    paidEach: Double,
    comparison: MarketComparison?,
) {
    val deal = comparison?.reference?.takeIf { valid }?.let { Deal.rate(paidEach, it) }
    val colors = dealColors(deal)
    val container by animateColorAsState(colors.container, label = "dealContainer")
    val content by animateColorAsState(colors.content, label = "dealContent")

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (deal != null) DealBadge(deal, content)
            Text("Price per card", style = MaterialTheme.typography.labelLarge)
            Text(
                if (valid) PriceMath.perUnit(totalCost, totalCards).moneyPerCard() else "—",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Per pack", if (valid) PriceMath.perUnit(totalCost, totalPacks).money() else "—")
                Stat("Total", if (valid) totalCost.money() else "—")
                Stat("Packs", if (valid) "$totalPacks" else "—")
                Stat("Cards", if (valid) "$totalCards" else "—")
            }
            if (valid && comparison != null) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                MarketComparisonText(paidEach, comparison)
            }
            if (!valid) {
                Text(
                    "Enter the price you paid to see the breakdown.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HistorySummary(purchases: List<Purchase>) {
    val spent = purchases.sumOf { it.totalPrice }
    val cards = purchases.sumOf { it.totalCards }
    val packs = purchases.sumOf { it.totalPacks }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Saved purchases")
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Stat("Spent", spent.money())
                Stat("Packs", "$packs")
                Stat("Cards", "$cards")
                Stat("Avg / card", PriceMath.perUnit(spent, cards).moneyPerCard())
            }
        }
    }
}

@Composable
private fun PurchaseRow(purchase: Purchase, onDelete: () -> Unit) {
    ListItem(
        headlineContent = {
            val qty = if (purchase.quantity > 1) " ×${purchase.quantity}" else ""
            Text("${purchase.product}$qty")
        },
        supportingContent = {
            Text(
                "${purchase.timestamp.shortDate()} · ${purchase.totalPacks} packs · " +
                    "${purchase.totalPrice.money()} total"
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${purchase.pricePerCard.moneyPerCard()}/card",
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete purchase")
                }
            }
        },
    )
}

private data class MarketComparison(val tcgPlayer: Double?, val priceCharting: Double?) {
    /** TCGplayer is preferred since it reflects recent sales; PriceCharting is the fallback. */
    val reference: Double? get() = tcgPlayer ?: priceCharting
}

@Composable
private fun DealBadge(deal: Deal, color: Color) {
    val icon = when (deal) {
        Deal.GOOD -> Icons.Filled.ThumbUp
        Deal.FAIR -> Icons.Filled.CheckCircle
        Deal.OVERPAID -> Icons.Filled.Warning
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                deal.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun MarketComparisonText(paidEach: Double, comparison: MarketComparison) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        comparison.tcgPlayer?.let { Text("TCGplayer market: ${it.money()} each") }
        comparison.priceCharting?.let { Text("PriceCharting: ${it.money()} each") }
        val reference = comparison.reference ?: return@Column
        val diff = Deal.percentVsMarket(paidEach, reference)
        val verdict = when {
            diff <= -1 -> "You paid %.0f%% below market".format(-diff)
            diff >= 1 -> "You paid %.0f%% above market".format(diff)
            else -> "You paid about market price"
        }
        Text(verdict, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}
