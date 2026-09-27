package com.cardprice.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.MarketProduct
import com.cardprice.app.data.market.PriceChartingPrice
import com.cardprice.app.data.market.ProductKind
import com.cardprice.app.data.cardsPerPackFor
import com.cardprice.app.data.presetsFor

/** Cards in one unit of [kind] for this set, based on the calculator's pack counts. */
fun cardsPerProduct(set: PokemonSet, kind: ProductKind): Int {
    val preset = presetsFor(set).firstOrNull { it.label == kind.presetLabel } ?: return 0
    return preset.packs * cardsPerPackFor(set, preset)
}

@Composable
fun MarketSection(
    set: PokemonSet,
    market: MarketState,
    onRefresh: () -> Unit,
    onOpenHistory: (MarketProduct, Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Market prices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Tap a product for its price & sales graphs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (set.language.tcgProductLine != null) {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh prices") }
                }
            }

            if (set.language.tcgProductLine == null) {
                Text(
                    "TCGplayer and PriceCharting don't track ${set.language.label} products, so market prices " +
                        "and sales graphs aren't available for this set. The calculator still works.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }

            when (val products = market.products) {
                Load.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                }
                is Load.Failed -> Column(Modifier.padding(16.dp)) {
                    Text("Couldn't reach TCGplayer. ${products.message}")
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
                is Load.Ready -> ProductList(set, products.value, market, onOpenHistory)
            }

            PriceChartingStatus(market.priceCharting)
        }
    }
}

@Composable
private fun ProductList(
    set: PokemonSet,
    products: List<MarketProduct>,
    market: MarketState,
    onOpenHistory: (MarketProduct, Int) -> Unit,
) {
    val presetLabels = presetsFor(set).map { it.label }
    val matched = market.matched.filterKeys { it.presetLabel in presetLabels }
    val pc = (market.priceCharting as? Load.Ready)?.value.orEmpty()

    if (matched.isEmpty()) {
        Text(
            if (products.isEmpty()) "TCGplayer doesn't list sealed products for this set."
            else "No standard packs or boxes found; see all products below.",
            modifier = Modifier.padding(16.dp),
        )
    }
    ProductKind.entries.forEach { kind ->
        val product = matched[kind] ?: return@forEach
        val cards = cardsPerProduct(set, kind)
        MarketRow(
            title = kind.presetLabel,
            subtitle = product.name,
            price = product.marketPrice,
            perCard = product.marketPrice?.takeIf { cards > 0 }?.div(cards),
            priceCharting = pc[kind],
            onClick = { onOpenHistory(product, cards) },
        )
    }

    val others = products.filter { it !in matched.values }
    if (others.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        HorizontalDivider(Modifier.padding(top = 4.dp))
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Other sealed products (${others.size})", modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        if (expanded) {
            others.forEach { product ->
                MarketRow(
                    title = product.name,
                    subtitle = null,
                    price = product.marketPrice,
                    perCard = null,
                    priceCharting = null,
                    onClick = { onOpenHistory(product, 0) },
                )
            }
        }
    }
}

@Composable
private fun MarketRow(
    title: String,
    subtitle: String?,
    price: Double?,
    perCard: Double?,
    priceCharting: PriceChartingPrice?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(price?.money() ?: "No price", fontWeight = FontWeight.Bold)
            if (perCard != null) Text("${perCard.moneyPerCard()}/card", style = MaterialTheme.typography.bodySmall)
            priceCharting?.price?.let {
                Text(
                    "PriceCharting ${it.money()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun PriceChartingStatus(status: Load<Map<ProductKind, PriceChartingPrice?>>?) {
    val text = when (status) {
        null -> "Add a PriceCharting API token in Settings (⚙ on the Home tab) to compare its prices too."
        Load.Loading -> "Loading PriceCharting prices…"
        is Load.Failed -> "PriceCharting: ${status.message}"
        is Load.Ready -> if (status.value.values.all { it?.price == null }) "PriceCharting has no matching prices for this set." else null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
