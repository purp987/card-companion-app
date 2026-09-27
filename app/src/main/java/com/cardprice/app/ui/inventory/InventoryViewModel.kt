package com.cardprice.app.ui.inventory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardprice.app.data.Language
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.inventory.InventoryItem
import com.cardprice.app.data.inventory.InventoryOps
import com.cardprice.app.data.inventory.InventoryStatus
import com.cardprice.app.data.inventory.InventoryStore
import com.cardprice.app.data.market.HistoryRange
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.MarketProduct
import com.cardprice.app.data.market.TcgPlayerApi
import com.cardprice.app.data.search.CardHit
import com.cardprice.app.data.search.CardSearch
import com.cardprice.app.data.search.SearchSort
import com.cardprice.app.data.market.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.UUID

/** The inventory, plus the lookups used when adding to it (card search, a set's sealed products). */
class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = InventoryStore(application.filesDir)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val saves = Dispatchers.IO.limitedParallelism(1)

    private val _items = MutableStateFlow<List<InventoryItem>>(emptyList())
    val items: StateFlow<List<InventoryItem>> = _items.asStateFlow()

    /** Price refresh progress (done, total), or null when idle. */
    private val _refreshing = MutableStateFlow<Pair<Int, Int>?>(null)
    val refreshing: StateFlow<Pair<Int, Int>?> = _refreshing.asStateFlow()

    private val _cardResults = MutableStateFlow<Load<List<CardHit>>?>(null)
    val cardResults: StateFlow<Load<List<CardHit>>?> = _cardResults.asStateFlow()
    private var cardSearch: Job? = null

    private val _sealedResults = MutableStateFlow<Load<List<MarketProduct>>?>(null)
    val sealedResults: StateFlow<Load<List<MarketProduct>>?> = _sealedResults.asStateFlow()
    private var sealedSearch: Job? = null

    init {
        viewModelScope.launch { _items.value = withContext(saves) { store.load() } }
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** Adds a new item or replaces the one with the same id. */
    fun save(item: InventoryItem) = change { list ->
        if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it } else listOf(item) + list
    }

    fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

    /** Replaces the whole inventory (restoring a backup). The replaced version is kept as a backup file. */
    fun replaceAll(items: List<InventoryItem>) = change { items }

    fun sell(id: String, count: Int, priceEach: Double?) = change { list ->
        list.flatMap { if (it.id == id) InventoryOps.sell(it, count, priceEach, System.currentTimeMillis(), newId()) else listOf(it) }
    }

    private fun change(transform: (List<InventoryItem>) -> List<InventoryItem>) {
        _items.update(transform)
        val snapshot = _items.value
        viewModelScope.launch(saves) { store.save(snapshot) }
    }

    /** Updates the market price of every held item that's linked to a TCGplayer product. */
    fun refreshPrices() {
        if (_refreshing.value != null) return
        val targets = _items.value.filter { it.productId != null && it.status != InventoryStatus.SOLD }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _refreshing.value = 0 to targets.size
            val semaphore = Semaphore(4)
            var done = 0
            val prices = targets.map { item ->
                async {
                    semaphore.withPermit {
                        val price = runCatching { latestMarketPrice(item.productId!!) }.getOrNull()
                        _refreshing.value = ++done to targets.size
                        item.id to price
                    }
                }
            }.awaitAll().toMap()
            val now = System.currentTimeMillis()
            change { list ->
                list.map { item -> prices[item.id]?.let { item.copy(marketPrice = it, priceUpdatedAt = now) } ?: item }
            }
            _refreshing.value = null
        }
    }

    private suspend fun latestMarketPrice(productId: Long): Double? = withContext(Dispatchers.IO) {
        TcgPlayerApi.history(productId, HistoryRange.MONTH).lastOrNull { it.marketPrice != null }?.marketPrice
    }

    /** Searches TCGplayer's cards as you type (after a short pause). */
    fun searchCards(query: String, language: Language) {
        cardSearch?.cancel()
        if (query.isBlank()) {
            _cardResults.value = null
            return
        }
        cardSearch = viewModelScope.launch {
            delay(350)
            _cardResults.value = Load.Loading
            _cardResults.value = try {
                Load.Ready(CardSearch.search(query.trim(), language, SearchSort.POPULAR))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }

    /** A set's sealed products (packs, bundles, boxes…) with market prices. */
    fun loadSealed(set: PokemonSet?) {
        sealedSearch?.cancel()
        if (set == null) {
            _sealedResults.value = null
            return
        }
        sealedSearch = viewModelScope.launch {
            _sealedResults.value = Load.Loading
            _sealedResults.value = try {
                Load.Ready(withContext(Dispatchers.IO) { TcgPlayerApi.sealedProducts(set) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }
}
