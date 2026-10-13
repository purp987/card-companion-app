package com.cardprice.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.Repository
import com.cardprice.app.data.cardsPerPackFor
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.MarketProduct
import com.cardprice.app.data.market.MarketRepository
import com.cardprice.app.data.market.PriceChartingPrice
import com.cardprice.app.data.market.ProductKind
import com.cardprice.app.data.market.userMessage
import com.cardprice.app.data.presetsFor
import com.cardprice.app.data.vault.CalculatorSets
import com.cardprice.app.data.vault.SearchSort
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.data.vault.VaultSet
import com.cardprice.app.ui.theme.Palette
import com.cardprice.app.ui.vault.GlassCard
import com.cardprice.app.ui.vault.LoadingBox
import com.cardprice.app.ui.vault.SearchField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Market prices for the calculator: TCG Vault's sealed products for the set, plus PriceCharting when a token is set. */
data class MarketState(
    /** The TCG Vault set this calculator set is linked to (null = none found). */
    val serverSet: Load<VaultSet?> = Load.Loading,
    val products: Load<List<MarketProduct>> = Load.Loading,
    /** Null until a PriceCharting token is set. */
    val priceCharting: Load<Map<ProductKind, PriceChartingPrice?>>? = null,
    /** Every server set of this language, for picking a different one. */
    val choices: List<VaultSet> = emptyList(),
) {
    /** The sealed product that best matches each calculator preset (plainest name first). */
    val matched: Map<ProductKind, MarketProduct>
        get() {
            val list = (products as? Load.Ready)?.value?.sortedBy { it.name.length } ?: return emptyMap()
            return ProductKind.entries.mapNotNull { kind -> kind.pick(list)?.let { kind to it } }.toMap()
        }
}

/** Cards in one unit of [kind] for this set, based on the calculator's pack counts. */
fun cardsPerProduct(set: PokemonSet, kind: ProductKind): Int {
    val preset = presetsFor(set).firstOrNull { it.label == kind.presetLabel } ?: return 0
    return preset.packs * cardsPerPackFor(set, preset)
}

class MarketViewModel(application: Application, private val set: PokemonSet) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MarketState())
    val state: StateFlow<MarketState> = _state.asStateFlow()
    private val links = application.getSharedPreferences("calculator_links", Context.MODE_PRIVATE)
    private var token: String? = Repository(application).loadPriceChartingToken()
    private var pcJob: Job? = null

    init {
        load(force = false)
        loadPriceCharting(force = false)
    }

    fun refresh() {
        load(force = true)
        loadPriceCharting(force = true)
    }

    fun setToken(newToken: String?) {
        if (newToken == token) return
        token = newToken
        loadPriceCharting(force = false)
    }

    /** Links this calculator set to another TCG Vault set (remembered). */
    fun choose(serverSet: VaultSet) {
        links.edit().putLong(set.id, serverSet.id).apply()
        _state.update { it.copy(serverSet = Load.Ready(serverSet)) }
        loadProducts(serverSet)
    }

    private fun load(force: Boolean) {
        _state.update { it.copy(serverSet = Load.Loading, products = Load.Loading) }
        viewModelScope.launch {
            val cat = CalculatorSets.categoryFor(set.language)
            val sets = try {
                withContext(Dispatchers.IO) { cachedSets[cat]?.takeIf { !force } ?: Vault.api.sets(cat).also { cachedSets[cat] = it } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(serverSet = Load.Failed(e.userMessage()), products = Load.Failed(e.userMessage())) }
                return@launch
            }
            val linked = links.getLong(set.id, -1).takeIf { it > 0 }?.let { id -> sets.firstOrNull { it.id == id } }
            val match = linked ?: CalculatorSets.match(set, sets)
            _state.update { it.copy(serverSet = Load.Ready(match), choices = sets) }
            if (match == null) _state.update { it.copy(products = Load.Ready(emptyList())) } else loadProducts(match)
        }
    }

    private fun loadProducts(serverSet: VaultSet) {
        _state.update { it.copy(products = Load.Loading) }
        viewModelScope.launch {
            val r = try {
                Load.Ready(
                    withContext(Dispatchers.IO) {
                        Vault.api.search(set = serverSet.id, type = "sealed", sort = SearchSort.PRICE_DESC, limit = 200).items
                            .map { MarketProduct(it.id, it.name, it.price, null) }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            _state.update { it.copy(products = r) }
        }
    }

    private fun loadPriceCharting(force: Boolean) {
        pcJob?.cancel()
        val token = token
        // PriceCharting has no Chinese sealed prices.
        if (token.isNullOrBlank() || set.language.tcgProductLine == null) {
            _state.update { it.copy(priceCharting = null) }
            return
        }
        _state.update { it.copy(priceCharting = Load.Loading) }
        pcJob = viewModelScope.launch {
            val labels = presetsFor(set).map { it.label }
            val kinds = ProductKind.entries.filter { it.presetLabel in labels }
            val result = try {
                Load.Ready(kinds.map { kind -> async { kind to MarketRepository.priceCharting(token, set, kind, force) } }.awaitAll().toMap())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            _state.update { it.copy(priceCharting = result) }
        }
    }

    private companion object {
        /** Server set lists per category, shared between calculator screens for this app run. */
        val cachedSets = java.util.concurrent.ConcurrentHashMap<Long, List<VaultSet>>()
    }
}

@Composable
fun MarketSection(
    set: PokemonSet,
    market: MarketState,
    onRefresh: () -> Unit,
    onOpenProduct: (MarketProduct) -> Unit,
    onChooseSet: (VaultSet) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Market prices", style = MaterialTheme.typography.titleMedium, color = Palette.text)
                    val linked = (market.serverSet as? Load.Ready)?.value
                    Text(
                        if (linked != null) "TCG Vault · ${linked.name}" else "From TCG Vault. Tap a product for its price chart.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh prices", tint = Palette.text2) }
            }
            when (val s = market.serverSet) {
                Load.Loading -> LoadingBox(Modifier.fillMaxWidth().padding(20.dp))
                is Load.Failed -> Column(Modifier.padding(16.dp)) {
                    Text("Couldn't reach TCG Vault. ${s.message}", color = Palette.text2)
                    TextButton(onClick = onRefresh) { Text("Try again", color = Palette.accent) }
                }
                is Load.Ready -> if (s.value == null) {
                    Text(
                        "TCG Vault has no matching ${set.language.label} set${if (market.choices.isEmpty()) " (is that language tracked on the server?)" else ""}. The calculator still works.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.text2,
                        modifier = Modifier.padding(16.dp),
                    )
                } else when (val products = market.products) {
                    Load.Loading -> LoadingBox(Modifier.fillMaxWidth().padding(20.dp))
                    is Load.Failed -> Text("Couldn't load prices. ${products.message}", color = Palette.text2, modifier = Modifier.padding(16.dp))
                    is Load.Ready -> ProductList(set, products.value, market, onOpenProduct)
                }
            }
            PriceChartingStatus(market.priceCharting)
            if (market.choices.isNotEmpty()) {
                TextButton(onClick = { picking = true }, modifier = Modifier.padding(start = 8.dp)) { Text("Not the right set? Choose another", color = Palette.accent) }
            }
        }
    }
    if (picking) {
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = Palette.surfaceSolid,
            title = { Text("Link to a TCG Vault set") },
            text = {
                Column {
                    SearchField(q, { q = it }, "Search sets", onSearch = {})
                    LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                        items(market.choices.filter { q.isBlank() || it.name.contains(q.trim(), true) || it.abbreviation?.contains(q.trim(), true) == true }) { s ->
                            Text(
                                "${s.name}${if (s.sealedCount > 0) " · ${s.sealedCount} sealed" else ""}",
                                color = Palette.text,
                                modifier = Modifier.fillMaxWidth().clickable { onChooseSet(s); picking = false }.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ProductList(set: PokemonSet, products: List<MarketProduct>, market: MarketState, onOpenProduct: (MarketProduct) -> Unit) {
    val presetLabels = presetsFor(set).map { it.label }
    val matched = market.matched.filterKeys { it.presetLabel in presetLabels }
    val pc = (market.priceCharting as? Load.Ready)?.value.orEmpty()
    if (matched.isEmpty()) {
        Text(
            if (products.isEmpty()) "TCG Vault doesn't list sealed products for this set." else "No standard packs or boxes found; see all products below.",
            color = Palette.text2,
            modifier = Modifier.padding(16.dp),
        )
    }
    ProductKind.entries.forEach { kind ->
        val product = matched[kind] ?: return@forEach
        val cards = cardsPerProduct(set, kind)
        MarketRow(kind.presetLabel, product.name, product.marketPrice, product.marketPrice?.takeIf { cards > 0 }?.div(cards), pc[kind]) { onOpenProduct(product) }
    }
    val others = products.filter { it !in matched.values }
    if (others.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        HorizontalDivider(Modifier.padding(top = 4.dp), color = Palette.border)
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Other sealed products (${others.size})", color = Palette.text, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Palette.text2)
        }
        if (expanded) others.forEach { p -> MarketRow(p.name, null, p.marketPrice, null, null) { onOpenProduct(p) } }
    }
}

@Composable
private fun MarketRow(title: String, subtitle: String?, price: Double?, perCard: Double?, priceCharting: PriceChartingPrice?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(price?.let { CurrencyState.fromUsd(it).money() } ?: "No price", style = com.cardprice.app.ui.theme.PriceStyle, color = Palette.text)
            if (perCard != null) Text("${CurrencyState.fromUsd(perCard).moneyPerCard()}/card", style = MaterialTheme.typography.bodySmall, color = Palette.text2)
            priceCharting?.price?.let { Text("PriceCharting ${CurrencyState.fromUsd(it).money()}", style = MaterialTheme.typography.bodySmall, color = Palette.accent2) }
        }
    }
}

@Composable
private fun PriceChartingStatus(status: Load<Map<ProductKind, PriceChartingPrice?>>?) {
    val text = when (status) {
        null -> "Add a PriceCharting API token in Settings to compare its prices too."
        Load.Loading -> "Loading PriceCharting prices…"
        is Load.Failed -> "PriceCharting: ${status.message}"
        is Load.Ready -> if (status.value.values.all { it?.price == null }) "PriceCharting has no matching prices for this set." else null
    } ?: return
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.text3)
    }
}
