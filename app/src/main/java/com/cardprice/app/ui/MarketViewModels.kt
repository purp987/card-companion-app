package com.cardprice.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.presetsFor
import com.cardprice.app.data.market.HistoryRange
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.MarketProduct
import com.cardprice.app.data.market.MarketRepository
import com.cardprice.app.data.market.PriceChartingPrice
import com.cardprice.app.data.market.PricePoint
import com.cardprice.app.data.market.ProductKind
import com.cardprice.app.data.market.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MarketState(
    val products: Load<List<MarketProduct>> = Load.Loading,
    /** Null until a PriceCharting token is set. */
    val priceCharting: Load<Map<ProductKind, PriceChartingPrice?>>? = null,
) {
    /** The TCGplayer product that best matches each calculator preset. */
    val matched: Map<ProductKind, MarketProduct>
        get() {
            val list = (products as? Load.Ready)?.value ?: return emptyMap()
            return ProductKind.entries.mapNotNull { kind -> kind.pick(list)?.let { kind to it } }.toMap()
        }
}

class MarketViewModel(private val set: PokemonSet) : ViewModel() {
    private val _state = MutableStateFlow(MarketState())
    val state: StateFlow<MarketState> = _state.asStateFlow()

    private var token: String? = null
    private var pcJob: Job? = null

    private val hasMarketData = set.language.tcgProductLine != null

    init {
        if (hasMarketData) loadProducts(force = false)
    }

    fun refresh() {
        if (!hasMarketData) return
        loadProducts(force = true)
        loadPriceCharting(force = true)
    }

    fun setToken(newToken: String?) {
        if (newToken == token) return
        token = newToken
        loadPriceCharting(force = false)
    }

    private fun loadProducts(force: Boolean) {
        _state.update { it.copy(products = Load.Loading) }
        viewModelScope.launch {
            val result = try {
                Load.Ready(MarketRepository.sealedProducts(set, force))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            _state.update { it.copy(products = result) }
        }
    }

    private fun loadPriceCharting(force: Boolean) {
        pcJob?.cancel()
        val token = token
        if (token.isNullOrBlank() || !hasMarketData) {
            _state.update { it.copy(priceCharting = null) }
            return
        }
        _state.update { it.copy(priceCharting = Load.Loading) }
        pcJob = viewModelScope.launch {
            val labels = presetsFor(set).map { it.label }
            val kinds = ProductKind.entries.filter { it.presetLabel in labels }
            val result = try {
                val prices = kinds.map { kind ->
                    async { kind to MarketRepository.priceCharting(token, set, kind, force) }
                }.awaitAll()
                Load.Ready(prices.toMap())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            _state.update { it.copy(priceCharting = result) }
        }
    }
}

data class HistoryState(
    val range: HistoryRange = HistoryRange.MONTH,
    val points: Load<List<PricePoint>> = Load.Loading,
)

class HistoryViewModel(private val productId: Long) : ViewModel() {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        select(HistoryRange.MONTH)
    }

    fun select(range: HistoryRange) {
        job?.cancel()
        _state.update { it.copy(range = range, points = Load.Loading) }
        job = viewModelScope.launch {
            val result = try {
                Load.Ready(MarketRepository.history(productId, range))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            _state.update { it.copy(points = result) }
        }
    }

    fun retry() = select(_state.value.range)
}
