package com.cardprice.app.ui.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardprice.app.data.Language
import com.cardprice.app.data.Repository
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.MarketRepository
import com.cardprice.app.data.market.PriceChartingProduct
import com.cardprice.app.data.market.userMessage
import com.cardprice.app.data.vault.HoldingInput
import com.cardprice.app.data.vault.HomeFeed
import com.cardprice.app.data.vault.Portfolio
import com.cardprice.app.data.vault.PortfolioView
import com.cardprice.app.data.vault.PriceHistory
import com.cardprice.app.data.vault.PricePoint
import com.cardprice.app.data.vault.PriceRange
import com.cardprice.app.data.vault.ProductDetail
import com.cardprice.app.data.vault.ProductItem
import com.cardprice.app.data.vault.SearchSort
import com.cardprice.app.data.vault.SetDetail
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.data.vault.VaultSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs a blocking server call off the main thread and wraps the outcome for the screen. */
internal suspend fun <T> load(block: () -> T): Load<T> = try {
    Load.Ready(withContext(Dispatchers.IO) { block() })
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Load.Failed(e.userMessage())
}

/** Runs a server change; returns an error message or null on success. */
internal suspend fun act(block: () -> Unit): String? = try {
    withContext(Dispatchers.IO) { block() }
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    e.userMessage()
}

// ---------------------------------------------------------------- home

class HomeViewModel : ViewModel() {
    private val _state = MutableStateFlow<Load<HomeFeed>>(Load.Loading)
    val state: StateFlow<Load<HomeFeed>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        refresh(initial = true)
        // Server-side changes (portfolio movers, watchlist movers, a new price day) reload the rows quietly.
        viewModelScope.launch {
            kotlinx.coroutines.flow.merge(Vault.holdingsVersion.drop(1), Vault.watchlistVersion.drop(1), Vault.pricesVersion.drop(1))
                .collect { refresh(initial = true) }
        }
    }

    /** [initial] = no pull-to-refresh spinner (first load and background updates). */
    fun refresh(initial: Boolean = false) {
        viewModelScope.launch {
            if (!initial) _refreshing.value = true
            val r = load { Vault.api.home() }
            // Keep showing the last feed if a refresh fails.
            if (r is Load.Ready || _state.value !is Load.Ready) _state.value = r
            _refreshing.value = false
        }
    }
}

// ---------------------------------------------------------------- explore / search grids

data class GridState(
    val items: List<ProductItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val window: String? = null,
) {
    val canLoadMore: Boolean get() = !loading && error == null && items.size < total
}

data class ExploreQuery(
    val q: String = "",
    val sort: SearchSort = SearchSort.BEST,
    val type: String? = null,
    val cat: Long? = null,
    val minPrice: Double? = null,
)

class ExploreViewModel : ViewModel() {
    private val _query = MutableStateFlow(ExploreQuery())
    val query: StateFlow<ExploreQuery> = _query.asStateFlow()
    private val _grid = MutableStateFlow(GridState())
    val grid: StateFlow<GridState> = _grid.asStateFlow()
    private var job: Job? = null

    init {
        reload()
    }

    fun set(query: ExploreQuery) {
        if (query == _query.value) return
        _query.value = query
        reload()
    }

    fun reload() = fetch(append = false)

    fun loadMore() {
        if (_grid.value.canLoadMore) fetch(append = true)
    }

    private fun fetch(append: Boolean) {
        job?.cancel()
        val q = _query.value
        val offset = if (append) _grid.value.items.size else 0
        _grid.update { if (append) it.copy(loading = true, error = null) else GridState(loading = true) }
        job = viewModelScope.launch {
            when (val r = load { Vault.api.search(q.q, q.cat, null, q.type, q.sort, q.minPrice, PAGE, offset) }) {
                is Load.Ready -> _grid.update {
                    GridState((if (append) it.items else emptyList()) + r.value.items, r.value.total, false, null, r.value.window)
                }
                is Load.Failed -> _grid.update { it.copy(loading = false, error = r.message) }
                Load.Loading -> Unit
            }
        }
    }

    private companion object { const val PAGE = 48 }
}

// ---------------------------------------------------------------- sets

class SetsViewModel : ViewModel() {
    private val _cat = MutableStateFlow<Long?>(null)
    val cat: StateFlow<Long?> = _cat.asStateFlow()
    private val _sets = MutableStateFlow<Load<List<VaultSet>>>(Load.Loading)
    val sets: StateFlow<Load<List<VaultSet>>> = _sets.asStateFlow()

    fun select(cat: Long?) {
        if (cat == _cat.value && _sets.value is Load.Ready) return
        _cat.value = cat
        _sets.value = Load.Loading
        viewModelScope.launch { _sets.value = load { Vault.api.sets(cat) } }
    }
}

class SetDetailViewModel(private val setId: Long) : ViewModel() {
    private val _detail = MutableStateFlow<Load<SetDetail>>(Load.Loading)
    val detail: StateFlow<Load<SetDetail>> = _detail.asStateFlow()
    private val _type = MutableStateFlow("cards")
    val type: StateFlow<String> = _type.asStateFlow()
    private val _sort = MutableStateFlow(SearchSort.PRICE_DESC)
    val sort: StateFlow<SearchSort> = _sort.asStateFlow()
    private val _q = MutableStateFlow("")
    val q: StateFlow<String> = _q.asStateFlow()
    private val _grid = MutableStateFlow(GridState())
    val grid: StateFlow<GridState> = _grid.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val d = load { Vault.api.set(setId) }
            _detail.value = d
            if (d is Load.Ready && d.value.set.cardCount == 0) _type.value = "sealed"
            fetch(false)
        }
    }

    fun setType(t: String) { _type.value = t; fetch(false) }
    fun setSort(s: SearchSort) { _sort.value = s; fetch(false) }
    fun setQuery(q: String) { _q.value = q; fetch(false) }
    fun loadMore() { if (_grid.value.canLoadMore) fetch(true) }

    private fun fetch(append: Boolean) {
        job?.cancel()
        val offset = if (append) _grid.value.items.size else 0
        _grid.update { if (append) it.copy(loading = true) else GridState(loading = true) }
        job = viewModelScope.launch {
            val sort = _sort.value
            val minPrice = if (sort == SearchSort.GAIN || sort == SearchSort.LOSS) 0.01 else null
            when (val r = load { Vault.api.search(_q.value, null, setId, _type.value, sort, minPrice, 60, offset) }) {
                is Load.Ready -> _grid.update { GridState((if (append) it.items else emptyList()) + r.value.items, r.value.total, false) }
                is Load.Failed -> _grid.update { it.copy(loading = false, error = r.message) }
                Load.Loading -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------- product

class ProductViewModel(application: Application, private val productId: Long) : AndroidViewModel(application) {
    private val _detail = MutableStateFlow<Load<ProductDetail>>(Load.Loading)
    val detail: StateFlow<Load<ProductDetail>> = _detail.asStateFlow()
    private val _range = MutableStateFlow(PriceRange.M1)
    val range: StateFlow<PriceRange> = _range.asStateFlow()
    private val _variant = MutableStateFlow<Long?>(null)
    val variant: StateFlow<Long?> = _variant.asStateFlow()
    private val _history = MutableStateFlow<Load<PriceHistory>>(Load.Loading)
    val history: StateFlow<Load<PriceHistory>> = _history.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    /** PriceCharting (owner's own token) for this product; null when no token is set. */
    private val _priceCharting = MutableStateFlow<Load<PriceChartingProduct?>?>(null)
    val priceCharting: StateFlow<Load<PriceChartingProduct?>?> = _priceCharting.asStateFlow()
    private var historyJob: Job? = null

    init {
        reload()
        // Holdings or the star changed on the server (web UI, the other app…): refresh them in place.
        viewModelScope.launch {
            kotlinx.coroutines.flow.merge(Vault.holdingsVersion.drop(1), Vault.watchlistVersion.drop(1)).collect { refreshHoldings() }
        }
    }

    fun reload() {
        viewModelScope.launch {
            val d = load { Vault.api.product(productId) }
            _detail.value = d
            if (d is Load.Ready) {
                if (_variant.value == null) _variant.value = d.value.primaryVariantId ?: d.value.variants.firstOrNull()?.id
                loadHistory()
                loadPriceCharting(d.value)
            }
        }
    }

    private fun loadPriceCharting(p: ProductDetail) {
        if (_priceCharting.value != null) return
        val token = Repository(getApplication()).loadPriceChartingToken() ?: return
        _priceCharting.value = Load.Loading
        val language = when (p.product.categoryId) {
            85L -> Language.JAPANESE
            900002L, 900001L -> Language.CHINESE_SIMPLIFIED
            else -> Language.ENGLISH
        }
        // PriceCharting names cards "Charizard ex #199" in a set console; search by base name + number + set.
        val base = p.product.name.replace(Regex("\\s+-\\s+.*$"), "").replace(Regex("\\s*\\([^)]*\\)"), "")
        val number = p.product.number?.substringBefore('/')?.trimStart('0')?.ifEmpty { "0" }
        val setName = p.product.setName.substringAfter(": ")
        val q = listOfNotNull(base, number, setName.takeIf { !p.product.isSealed }).joinToString(" ")
        viewModelScope.launch {
            _priceCharting.value = try {
                Load.Ready(MarketRepository.priceChartingProduct(token, q, p.product.isSealed, language))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
        }
    }

    fun setRange(r: PriceRange) { _range.value = r; loadHistory() }
    fun setVariant(id: Long) { _variant.value = id; loadHistory() }

    private fun loadHistory() {
        historyJob?.cancel()
        _history.value = Load.Loading
        historyJob = viewModelScope.launch { _history.value = load { Vault.api.history(productId, _range.value, _variant.value) } }
    }

    fun toggleWatch() {
        val d = (_detail.value as? Load.Ready)?.value ?: return
        val now = !d.watched
        _detail.value = Load.Ready(d.copy(watched = now))
        viewModelScope.launch {
            val err = act { Vault.api.setWatched(productId, now) }
            if (err != null) {
                _detail.value = Load.Ready(d)
                _message.value = err
            } else {
                val list = if (d.product.isSealed) "Shrink Wrap Watch" else "Chase List"
                _message.value = if (now) "Added to your $list" else "Removed from your $list"
                Vault.changedOnServer()
            }
        }
    }

    fun saveHolding(id: Long?, input: HoldingInput) {
        viewModelScope.launch {
            val sealed = (_detail.value as? Load.Ready)?.value?.product?.isSealed == true
            val err = act { if (id == null) Vault.api.addHolding(input) else Vault.api.updateHolding(id, input) }
            if (err != null) {
                _message.value = err
                return@launch
            }
            Vault.holdingsChanged()
            // Owning it takes it off the Chase List / Shrink Wrap Watch on the server, so say so once the
            // re-read confirms the star went out (rather than two snackbars in a row).
            val unwatched = refreshHoldings()
            val list = if (sealed) "Shrink Wrap Watch" else "Chase List"
            _message.value = when {
                id != null -> "Saved"
                unwatched -> "Added to your collection · off your $list"
                else -> "Added to your collection"
            }
        }
    }

    fun deleteHolding(id: Long) {
        viewModelScope.launch {
            val err = act { Vault.api.deleteHolding(id) }
            _message.value = err ?: "Removed from your collection"
            if (err == null) { Vault.holdingsChanged(); refreshHoldings() }
        }
    }

    fun loadGraded() {
        viewModelScope.launch {
            _message.value = "Loading graded prices…"
            val err = act { Vault.api.loadGraded(productId) }
            _message.value = err
            reload()
        }
    }

    /** Re-reads the holdings and the star. True when the product just came off a watchlist. */
    private suspend fun refreshHoldings(): Boolean {
        val d = (_detail.value as? Load.Ready)?.value ?: return false
        val fresh = (load { Vault.api.product(productId) } as? Load.Ready)?.value ?: return false
        val current = (_detail.value as? Load.Ready)?.value ?: d
        _detail.value = Load.Ready(current.copy(holdings = fresh.holdings, watched = fresh.watched))
        return d.watched && !fresh.watched
    }

    fun clearMessage() { _message.value = null }
}

// ---------------------------------------------------------------- portfolio

class PortfolioViewModel : ViewModel() {
    private val _portfolios = MutableStateFlow<List<Portfolio>>(emptyList())
    val portfolios: StateFlow<List<Portfolio>> = _portfolios.asStateFlow()
    private val _pid = MutableStateFlow(Vault.settings.portfolioId)
    val pid: StateFlow<Long?> = _pid.asStateFlow()
    private val _view = MutableStateFlow<Load<PortfolioView>>(Load.Loading)
    val view: StateFlow<Load<PortfolioView>> = _view.asStateFlow()
    private val _range = MutableStateFlow(PriceRange.M1)
    val range: StateFlow<PriceRange> = _range.asStateFlow()
    private val _history = MutableStateFlow<Load<List<PricePoint>>>(Load.Loading)
    val history: StateFlow<Load<List<PricePoint>>> = _history.asStateFlow()
    /** Movers / set completion / milestones for the fun overview; null until loaded (older servers stay null). */
    private val _extras = MutableStateFlow<com.cardprice.app.data.vault.PortfolioFun?>(null)
    val extras: StateFlow<com.cardprice.app.data.vault.PortfolioFun?> = _extras.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    /** When the shown data was loaded (ms), and whether the last reload failed (showing older data). */
    private val _loadedAt = MutableStateFlow<Long?>(null)
    val loadedAt: StateFlow<Long?> = _loadedAt.asStateFlow()
    private val _stale = MutableStateFlow(false)
    val stale: StateFlow<Boolean> = _stale.asStateFlow()
    private var reloadJob: Job? = null

    init {
        reload()
        // Changes on the server from anywhere (web UI, the other app, imports, a new price day) reload quietly:
        // no spinner, the list keeps its scroll position.
        viewModelScope.launch {
            kotlinx.coroutines.flow.merge(Vault.holdingsVersion.drop(1), Vault.portfoliosVersion.drop(1)).collect { reload(quiet = true) }
        }
        viewModelScope.launch { Vault.syncOffline.collect { if (it) _stale.value = true } }
    }

    /** Pull to refresh. */
    fun refresh() {
        _refreshing.value = true
        reload(quiet = true)
    }

    fun reload(quiet: Boolean = false) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            val list = load { Vault.api.portfolios() }
            (list as? Load.Ready)?.value?.let { portfolios ->
                _portfolios.value = portfolios
                // The selected portfolio was deleted (here or elsewhere): show all.
                if (_pid.value != null && portfolios.none { it.id == _pid.value }) {
                    _pid.value = null
                    Vault.settings.portfolioId = null
                }
            }
            if (!quiet) _view.value = Load.Loading
            val v = load { Vault.api.portfolio(_pid.value) }
            if (v is Load.Ready || !quiet || _view.value !is Load.Ready) _view.value = v
            if (v is Load.Ready) {
                _loadedAt.value = System.currentTimeMillis()
                _stale.value = false
            } else {
                _stale.value = true
            }
            loadHistory(quiet)
            // Fun extras are best-effort: an older server (no endpoint) just leaves the sections out.
            _extras.value = runCatching { withContext(Dispatchers.IO) { Vault.api.portfolioFun(_pid.value) } }.getOrNull()
            _refreshing.value = false
        }
    }

    fun select(pid: Long?) {
        _pid.value = pid
        Vault.settings.portfolioId = pid
        reload()
    }

    fun setRange(r: PriceRange) { _range.value = r; viewModelScope.launch { loadHistory(quiet = false) } }

    private suspend fun loadHistory(quiet: Boolean) {
        if (!quiet || _history.value !is Load.Ready) _history.value = Load.Loading
        val h = load { Vault.api.portfolioHistory(_pid.value, _range.value) }
        if (h is Load.Ready || !quiet) _history.value = h
    }

    fun create(name: String) = viewModelScope.launch {
        var id: Long? = null
        val err = act { id = Vault.api.createPortfolio(name) }
        _message.value = err
        if (err == null) select(id) else reload()
        Vault.changedOnServer()
    }

    fun rename(id: Long, name: String) = viewModelScope.launch {
        _message.value = act { Vault.api.renamePortfolio(id, name) }
        reload(quiet = true)
        Vault.changedOnServer()
    }

    fun delete(id: Long) = viewModelScope.launch {
        val err = act { Vault.api.deletePortfolio(id) }
        _message.value = err
        if (err == null) {
            select(null)
            Vault.changedOnServer()
        }
    }

    fun saveHolding(id: Long, input: HoldingInput) = viewModelScope.launch {
        val err = act { Vault.api.updateHolding(id, input) }
        _message.value = err ?: "Saved"
        if (err == null) Vault.holdingsChanged()
    }

    fun deleteHolding(id: Long) = viewModelScope.launch {
        val err = act { Vault.api.deleteHolding(id) }
        _message.value = err ?: "Removed"
        if (err == null) Vault.holdingsChanged()
    }

    fun clearMessage() { _message.value = null }
    /** Shows a celebratory message (snackbar) for a milestone. */
    fun celebrate(msg: String) { _message.value = msg }
}

// ---------------------------------------------------------------- watchlists

class WatchlistViewModel : ViewModel() {
    private val _items = MutableStateFlow<Load<List<ProductItem>>>(Load.Loading)
    val items: StateFlow<Load<List<ProductItem>>> = _items.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        reload()
        // Stars toggled elsewhere and new prices show up without leaving the screen.
        viewModelScope.launch {
            kotlinx.coroutines.flow.merge(Vault.watchlistVersion.drop(1), Vault.pricesVersion.drop(1)).collect { reload(quiet = true) }
        }
    }

    /** Pull to refresh. */
    fun refresh() {
        _refreshing.value = true
        reload(quiet = true)
    }

    fun reload(quiet: Boolean = false) {
        viewModelScope.launch {
            val r = load { Vault.api.watchlist() }
            if (r is Load.Ready || !quiet || _items.value !is Load.Ready) _items.value = r
            _refreshing.value = false
        }
    }

    fun remove(id: Long) {
        val current = (_items.value as? Load.Ready)?.value ?: return
        _items.value = Load.Ready(current.filterNot { it.id == id })
        viewModelScope.launch {
            if (act { Vault.api.setWatched(id, false) } != null) reload() else Vault.changedOnServer()
        }
    }
}
