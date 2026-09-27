package com.cardprice.app.ui.inventory

import android.app.Application
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.coroutineScope
import com.cardprice.app.ui.collection.PRICE_MAX_AGE_MS
import com.cardprice.app.data.inventory.CollectionMode
import com.cardprice.app.data.inventory.CollectionLink
import com.cardprice.app.data.collection.VariantPrice
import com.cardprice.app.data.collection.TcgdexApi
import com.cardprice.app.data.collection.SetProgress
import com.cardprice.app.data.collection.ExtraSets
import com.cardprice.app.data.collection.CollectionStore
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.CardPrices
import android.content.Context
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

    /** What's saved: items added here, plus the extra details of collection items (by collection key). */
    private val _items = MutableStateFlow<List<InventoryItem>>(emptyList())

    /** Items taken from the collection (see [CollectionLink]); recalculated as the collection changes. */
    private val _linked = MutableStateFlow<List<InventoryItem>>(emptyList())

    /** Everything the inventory shows: collection items first, then items added here. */
    val items: StateFlow<List<InventoryItem>> = combine(_items, _linked) { saved, linked ->
        linked + saved.filter { !it.fromCollection }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val prefs = application.getSharedPreferences("inventory", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(
        CollectionMode.entries.firstOrNull { it.name == prefs.getString("collection_mode", null) } ?: CollectionMode.ALL,
    )
    val mode: StateFlow<CollectionMode> = _mode.asStateFlow()

    private val collectionStore = CollectionStore(application)
    private var owned: Map<String, Int> = emptyMap()
    private var setNames: Map<Pair<Language, String>, String> = emptyMap()
    private val cardLists = ConcurrentHashMap<Pair<Language, String>, Map<String, CollectionCard>>()
    private val cardPrices = ConcurrentHashMap<String, CardPrices>()
    private var linkJob: Job? = null

    /** Collection cards still being looked up (done, total), or null. */
    private val _linking = MutableStateFlow<Pair<Int, Int>?>(null)
    val linking: StateFlow<Pair<Int, Int>?> = _linking.asStateFlow()

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
        viewModelScope.launch {
            _items.value = withContext(saves) { store.load() }
            relink()
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun setMode(mode: CollectionMode) {
        _mode.value = mode
        prefs.edit().putString("collection_mode", mode.name).apply()
        relink()
    }

    /**
     * The collection changed (or the screen opened): update the items taken from it. Card details and
     * prices for sets not seen before are looked up in the background.
     */
    fun syncCollection(owned: Map<String, Int>, progress: List<SetProgress>) {
        this.owned = owned
        setNames = progress.associate { (it.language to it.setId) to it.setName }
        relink()
        val keys = owned.keys.mapNotNull(CollectionLink::parseKey)
        val missingSets = keys.map { it.language to it.setId }.distinct().filter { !cardLists.containsKey(it) }
        val missingPrices = keys.filter { !cardPrices.containsKey(it.cardId) }.distinctBy { it.cardId }
        if (_mode.value == CollectionMode.OFF || (missingSets.isEmpty() && missingPrices.isEmpty())) return
        if (linkJob?.isActive == true) return
        linkJob = viewModelScope.launch { lookUp(missingSets, missingPrices) }
    }

    private suspend fun lookUp(sets: List<Pair<Language, String>>, cards: List<CollectionLink.Parts>) = withContext(Dispatchers.IO) {
        val total = sets.size + cards.size
        var done = 0
        _linking.value = 0 to total
        val semaphore = Semaphore(6)
        coroutineScope {
            sets.map { (language, setId) ->
                async {
                    semaphore.withPermit {
                        val list = collectionStore.cachedCards(language, setId)
                            ?: ExtraSets.find(language, setId)?.let { runCatching { ExtraSets.cards(it) }.getOrNull() }
                            ?: runCatching { TcgdexApi.setCards(language, setId) { _, _ -> } }.getOrNull()
                                ?.also { if (it.any { c -> !c.placeholder }) collectionStore.cacheCards(language, setId, it) }
                        if (list != null) cardLists[language to setId] = list.associateBy { it.id }
                        // Cards from TCGplayer carry their own price.
                        list?.forEach { c -> c.marketPrice?.let { p -> cardPrices[c.id] = CardPrices(c.variants.associate { v -> v.key to VariantPrice(p, "USD", "TCGplayer") }) } }
                        _linking.value = ++done to total
                        relink()
                    }
                }
            }.awaitAll()
            // Prices: the collection's day-old cache first, then TCGdex for the rest.
            cards.map { it.language }.distinct().forEach { language ->
                cardPrices.putAll(collectionStore.cachedPrices(language, PRICE_MAX_AGE_MS))
            }
            val fetched = ConcurrentHashMap<String, CardPrices>()
            cards.filter { !cardPrices.containsKey(it.cardId) }.map { part ->
                async {
                    semaphore.withPermit {
                        runCatching { TcgdexApi.cardPrices(part.language, part.cardId) }.getOrNull()?.let {
                            cardPrices[part.cardId] = it
                            fetched[part.cardId] = it
                        }
                    }
                }
            }.awaitAll()
            cards.groupBy { it.language }.forEach { (language, parts) ->
                val mine = parts.mapNotNull { p -> fetched[p.cardId]?.let { p.cardId to it } }.toMap()
                if (mine.isNotEmpty()) collectionStore.cachePrices(language, mine)
            }
        }
        _linking.value = null
        relink()
    }

    private fun relink() {
        val saved = _items.value.filter { it.fromCollection }.associateBy { it.collectionKey!! }
        _linked.value = CollectionLink.derive(
            owned = owned,
            mode = _mode.value,
            cards = { language, setId, cardId -> cardLists[language to setId]?.get(cardId) },
            setNames = { language, setId -> setNames[language to setId] },
            prices = { _, cardId -> cardPrices[cardId] },
            saved = saved,
        )
    }

    /**
     * Adds a new item or replaces the one with the same id. For collection items only the details the
     * collection doesn't hold are saved (the quantity keeps following the collection).
     */
    fun save(item: InventoryItem) {
        val toSave = if (item.fromCollection) item.copy(quantity = 1, marketPrice = null, image = null) else item
        change { list ->
            if (list.any { it.id == toSave.id }) list.map { if (it.id == toSave.id) toSave else it } else listOf(toSave) + list
        }
        if (item.fromCollection) relink()
    }

    fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

    /** Replaces the whole inventory (restoring a backup). The replaced version is kept as a backup file. */
    fun replaceAll(items: List<InventoryItem>) = change { items }

    fun sell(id: String, count: Int, priceEach: Double?) = change { list ->
        list.flatMap { if (it.id == id) InventoryOps.sell(it, count, priceEach, System.currentTimeMillis(), newId()) else listOf(it) }
    }

    /**
     * Records the sale of [count] copies of a collection item. The caller takes those copies out of the
     * collection, which then shrinks this item by itself.
     */
    fun recordCollectionSale(item: InventoryItem, count: Int, priceEach: Double?) {
        val sold = InventoryOps.sell(item, count, priceEach, System.currentTimeMillis(), newId()).single()
        change { list -> listOf(sold) + list }
    }

    private fun change(transform: (List<InventoryItem>) -> List<InventoryItem>) {
        _items.update(transform)
        val snapshot = _items.value
        viewModelScope.launch(saves) { store.save(snapshot) }
    }

    /** Updates the market price of every held item that's linked to a TCGplayer product, and collection cards' prices. */
    fun refreshPrices() {
        if (_refreshing.value != null) return
        // Collection cards: forget their prices and look them up again.
        val collectionCards = owned.keys.mapNotNull(CollectionLink::parseKey).distinctBy { it.cardId }
        if (collectionCards.isNotEmpty() && linkJob?.isActive != true) {
            collectionCards.forEach { cardPrices.remove(it.cardId) }
            linkJob = viewModelScope.launch { lookUp(emptyList(), collectionCards.filter { part -> cardLists[part.language to part.setId]?.get(part.cardId)?.marketPrice == null }) }
        }
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
