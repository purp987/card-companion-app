package com.cardprice.app.ui.collection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CardPrices
import com.cardprice.app.data.collection.CardSeries
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.CollectionStore
import com.cardprice.app.data.collection.ExtraSets
import com.cardprice.app.data.collection.ImageFill
import com.cardprice.app.data.collection.VariantPrice
import com.cardprice.app.data.collection.SetProgress
import com.cardprice.app.data.collection.SetValue
import com.cardprice.app.data.collection.TcgdexApi
import com.cardprice.app.data.collection.ownedKey
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class CollectionState(
    val owned: Map<String, Int> = emptyMap(),
    val progress: List<SetProgress> = emptyList(),
    /** When the collection file was missing or damaged at startup: the time of the backup used instead. */
    val restoredFrom: Long? = null,
) {
    fun count(language: Language, setId: String, cardId: String, variantKey: String): Int =
        owned[ownedKey(language, setId, cardId, variantKey)] ?: 0

    fun progressFor(language: Language, setId: String): SetProgress? =
        progress.firstOrNull { it.language == language && it.setId == setId }

    /** Combined value of every set, from each set's last price check. */
    val totalValue: SetValue get() = progress.fold(SetValue.NONE) { acc, p -> acc + p.value }

    /** Sets with at least one card, most recently changed first. */
    val inProgress: List<SetProgress> get() = progress.filter { it.ownedVariants > 0 }.sortedByDescending { it.updatedAt }

    val totalCopies: Int get() = owned.values.sum()
}

/** Owned cards and per-set progress. One instance for the whole app. */
class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CollectionStore(application)
    private val _state = MutableStateFlow(store.load().let { CollectionState(it.owned, it.progress, it.restoredFrom?.takenAt) })
    val state: StateFlow<CollectionState> = _state.asStateFlow()

    // Saves run one at a time, in order, off the main thread.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun dismissRestoreNotice() = _state.update { it.copy(restoredFrom = null) }

    /** Restore points first (pinned), then automatic backups; each group newest first. */
    suspend fun backups(): List<CollectionStore.Backup> = withContext(saveDispatcher) { store.restorePoints() + store.backups() }

    /** Saves the current collection as a named restore point that's kept until deleted. */
    suspend fun saveRestorePoint(name: String): CollectionStore.Backup? = withContext(saveDispatcher) { store.saveRestorePoint(name) }

    /** Swaps in a backup. The collection being replaced is kept as a backup, so this can be undone. */
    fun restore(backup: CollectionStore.Backup) {
        viewModelScope.launch(saveDispatcher) {
            val saved = store.restore(backup) ?: return@launch
            _state.value = CollectionState(saved.owned, saved.progress)
        }
    }

    /** Stores a set's latest value and artwork (value comes from the set screen once prices load). */
    fun updateSetDetails(language: Language, setId: String, value: SetValue?, art: List<String>?) {
        val existing = _state.value.progressFor(language, setId) ?: return
        val updated = existing.copy(value = value ?: existing.value, art = art?.takeIf { it.isNotEmpty() } ?: existing.art)
        if (updated == existing) return
        _state.update { s -> s.copy(progress = s.progress.map { if (it.language == language && it.setId == setId) updated else it }) }
        persist()
    }

    /** Adds (or with a negative [delta], removes) copies, based on the current count. */
    fun addCopies(language: Language, setId: String, setName: String, cards: List<CollectionCard>, cardId: String, variantKey: String, delta: Int) {
        val current = _state.value.count(language, setId, cardId, variantKey)
        setCount(language, setId, setName, cards, cardId, variantKey, (current + delta).coerceAtLeast(0))
    }

    fun setCount(language: Language, setId: String, setName: String, cards: List<CollectionCard>, cardId: String, variantKey: String, count: Int) {
        _state.update { s ->
            val key = ownedKey(language, setId, cardId, variantKey)
            val owned = if (count > 0) s.owned + (key to count) else s.owned - key
            s.copy(owned = owned, progress = upsertProgress(s.progress, owned, language, setId, setName, cards))
        }
        persist()
    }

    /**
     * Takes [count] copies of one version out of the collection (e.g. sold from inventory) without
     * needing the set's card list: the set's owned counts are worked out from what's left.
     */
    fun removeCopies(language: Language, setId: String, cardId: String, variantKey: String, count: Int) {
        if (count <= 0) return
        _state.update { s ->
            val key = ownedKey(language, setId, cardId, variantKey)
            val left = ((s.owned[key] ?: 0) - count).coerceAtLeast(0)
            val owned = if (left > 0) s.owned + (key to left) else s.owned - key
            val prefix = "${language.name}|$setId|"
            val inSet = owned.filter { (k, v) -> k.startsWith(prefix) && v > 0 }.keys
            val progress = s.progress.map { p ->
                if (p.language != language || p.setId != setId) p
                else p.copy(
                    ownedVariants = inSet.size,
                    ownedCards = inSet.map { it.removePrefix(prefix).substringBefore('|') }.distinct().size,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            s.copy(owned = owned, progress = progress)
        }
        persist()
    }

    /** Refreshes a set's totals after its card list loads (card counts can change as TCGdex adds cards). */
    fun onSetLoaded(language: Language, setId: String, setName: String, cards: List<CollectionCard>) {
        val existing = _state.value.progressFor(language, setId) ?: return
        _state.update { s ->
            s.copy(progress = upsertProgress(s.progress, s.owned, language, setId, setName, cards, existing.updatedAt))
        }
        persist()
    }

    private fun upsertProgress(
        progress: List<SetProgress>,
        owned: Map<String, Int>,
        language: Language,
        setId: String,
        setName: String,
        cards: List<CollectionCard>,
        updatedAt: Long = System.currentTimeMillis(),
    ): List<SetProgress> {
        fun has(cardId: String, variantKey: String) = (owned[ownedKey(language, setId, cardId, variantKey)] ?: 0) > 0
        val previous = progress.firstOrNull { it.language == language && it.setId == setId }
        val entry = SetProgress(
            language = language,
            setId = setId,
            setName = setName,
            totalCards = cards.size,
            totalVariants = cards.sumOf { it.variants.size },
            ownedCards = cards.count { c -> c.variants.any { has(c.id, it.key) } },
            ownedVariants = cards.sumOf { c -> c.variants.count { has(c.id, it.key) } },
            updatedAt = updatedAt,
            value = previous?.value ?: SetValue.NONE,
            art = previous?.art.orEmpty(),
        )
        return progress.filterNot { it.language == language && it.setId == setId } + entry
    }

    private fun persist() {
        val snapshot = _state.value
        viewModelScope.launch(saveDispatcher) { store.save(snapshot.owned, snapshot.progress) }
    }
}

/** TCGdex set catalogs per language, cached on the device for a week. */
class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CollectionStore(application)
    private val _state = MutableStateFlow<Map<Language, Load<List<CardSeries>>>>(emptyMap())
    val state: StateFlow<Map<Language, Load<List<CardSeries>>>> = _state.asStateFlow()
    private val jobs = mutableMapOf<Language, Job>()

    fun ensureLoaded(language: Language) {
        if (_state.value[language] is Load.Ready || jobs[language]?.isActive == true) return
        load(language, force = false)
    }

    fun refresh(language: Language) = load(language, force = true)

    private fun load(language: Language, force: Boolean) {
        jobs[language]?.cancel()
        _state.update { it + (language to Load.Loading) }
        jobs[language] = viewModelScope.launch {
            val result = try {
                val cached = if (force) null else withContext(Dispatchers.IO) { store.cachedSeries(language, WEEK_MS) }
                val series = cached ?: TcgdexApi.series(language).also {
                    withContext(Dispatchers.IO) { store.cacheSeries(language, it) }
                }
                Load.Ready(ExtraSets.inject(language, series))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            _state.update { it + (language to result) }
        }
    }

    private companion object {
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}

data class SetCardsState(
    val cards: Load<List<CollectionCard>> = Load.Loading,
    /** Cards whose variants have been fetched, for languages that load card by card. */
    val progress: Pair<Int, Int>? = null,
    /** Prices for cards that have been looked up (owned cards, and any card opened). */
    val prices: Map<String, CardPrices> = emptyMap(),
    /** Price lookups in flight: done / total. */
    val pricing: Pair<Int, Int>? = null,
)

class SetCardsViewModel(application: Application, private val language: Language, private val setId: String) :
    AndroidViewModel(application) {
    private val store = CollectionStore(application)
    private val _state = MutableStateFlow(SetCardsState())
    val state: StateFlow<SetCardsState> = _state.asStateFlow()
    private var job: Job? = null
    private val requested = mutableSetOf<String>()

    init {
        load(force = false)
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { store.cachedPrices(language, PRICE_MAX_AGE_MS) }
            _state.update { it.copy(prices = cached + it.prices) }
        }
    }

    fun refresh() = load(force = true)

    /** Cards whose rows came into view, waiting to be priced together. */
    private val pendingRows = mutableSetOf<String>()
    private var rowBatch: Job? = null

    /**
     * A card's row is on screen: price it (quietly, without the header's progress line). Rows that
     * appear together while scrolling are looked up as one batch.
     */
    fun requestPrice(cardId: String) {
        if (cardId in _state.value.prices || cardId in requested) return
        pendingRows += cardId
        if (rowBatch?.isActive == true) return
        rowBatch = viewModelScope.launch {
            delay(ROW_BATCH_MS)
            val batch = pendingRows.toList()
            pendingRows.clear()
            fetchPrices(batch, showProgress = false)
        }
    }

    /** Looks up prices for these cards unless they're already known (prices are cached for a day). */
    fun ensurePrices(cardIds: Collection<String>) = fetchPrices(cardIds, showProgress = true)

    private fun fetchPrices(cardIds: Collection<String>, showProgress: Boolean) {
        // TCGplayer-built sets get their prices with the card list; TCGdex has nothing for them.
        if (ExtraSets.find(language, setId) != null) return
        val missing = cardIds.filter { it !in _state.value.prices && requested.add(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            if (showProgress) _state.update { it.copy(pricing = 0 to missing.size) }
            val semaphore = Semaphore(6)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val fetched = coroutineScope {
                missing.map { id ->
                    async {
                        semaphore.withPermit {
                            val prices = runCatching { TcgdexApi.cardPrices(language, id) }.getOrNull()
                            val finished = done.incrementAndGet()
                            _state.update { s ->
                                s.copy(
                                    prices = if (prices != null) s.prices + (id to prices) else s.prices,
                                    pricing = when {
                                        !showProgress -> s.pricing
                                        finished < missing.size -> finished to missing.size
                                        else -> null
                                    },
                                )
                            }
                            if (prices == null) requested.remove(id)
                            prices?.let { id to it }
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            if (fetched.isNotEmpty()) withContext(Dispatchers.IO) { store.cachePrices(language, fetched) }
        }
    }

    private fun load(force: Boolean) {
        job?.cancel()
        _state.update { SetCardsState(prices = it.prices) }
        job = viewModelScope.launch {
            val result = try {
                val cached = if (force) null else withContext(Dispatchers.IO) { store.cachedCards(language, setId) }
                val extra = ExtraSets.find(language, setId)
                val loaded = cached ?: if (extra != null) ExtraSets.cards(extra) else TcgdexApi.setCards(language, setId) { done, total ->
                    _state.update { it.copy(progress = done to total) }
                }
                // Some TCGdex sets (e.g. the 30th Celebration Classic Collection) have no pictures; TCGplayer does.
                val cards = ImageFill.fillMissing(language, setId, loaded)
                // Numbered placeholders aren't cached, so real card data shows up as soon as TCGdex adds it.
                if ((cached == null || cards != cached) && cards.any { !it.placeholder }) {
                    withContext(Dispatchers.IO) { store.cacheCards(language, setId, cards) }
                }
                Load.Ready(cards)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            // Cards from TCGplayer carry their own market price, so they need no separate lookup.
            val listPrices = (result as? Load.Ready)?.value.orEmpty()
                .mapNotNull { c -> c.marketPrice?.let { c.id to CardPrices(c.variants.associate { v -> v.key to VariantPrice(it, "USD", "TCGplayer") }) } }
                .toMap()
            _state.update { SetCardsState(cards = result, prices = it.prices + listPrices) }
        }
    }
}

internal const val PRICE_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** How long to collect rows scrolling into view before pricing them together. */
private const val ROW_BATCH_MS = 300L
