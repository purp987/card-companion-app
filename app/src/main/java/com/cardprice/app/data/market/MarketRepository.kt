package com.cardprice.app.data.market

import com.cardprice.app.data.PokemonSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Network access plus a short-lived in-memory cache so moving between screens doesn't refetch. */
object MarketRepository {
    private const val TTL_MS = 15 * 60 * 1000L

    private class Entry<T>(val value: T, val at: Long = System.currentTimeMillis()) {
        val fresh get() = System.currentTimeMillis() - at < TTL_MS
    }

    private val products = ConcurrentHashMap<String, Entry<List<MarketProduct>>>()
    private val histories = ConcurrentHashMap<Pair<Long, HistoryRange>, Entry<List<PricePoint>>>()
    private val priceCharting = ConcurrentHashMap<Pair<String, ProductKind>, Entry<PriceChartingPrice?>>()

    suspend fun sealedProducts(set: PokemonSet, force: Boolean = false): List<MarketProduct> =
        cached(products, set.id, force) { TcgPlayerApi.sealedProducts(set) }

    suspend fun history(productId: Long, range: HistoryRange): List<PricePoint> =
        cached(histories, productId to range, false) { TcgPlayerApi.history(productId, range) }

    suspend fun priceCharting(token: String, set: PokemonSet, kind: ProductKind, force: Boolean = false) =
        cached(priceCharting, set.id to kind, force) { PriceChartingApi.sealedPrice(token, set, kind) }

    fun clearPriceCharting() = priceCharting.clear()

    private suspend fun <K, T> cached(
        map: ConcurrentHashMap<K, Entry<T>>,
        key: K,
        force: Boolean,
        load: () -> T,
    ): T {
        map[key]?.takeIf { it.fresh && !force }?.let { return it.value }
        val value = withContext(Dispatchers.IO) { load() }
        map[key] = Entry(value)
        return value
    }
}

fun Throwable.userMessage(): String = when (this) {
    is java.net.UnknownHostException -> "No internet connection."
    is java.net.SocketTimeoutException -> "The server took too long to respond."
    is HttpException -> if (code >= 500) "The server is having trouble right now ($code). Try again in a moment."
        else "The server returned an error ($code)."
    is org.json.JSONException -> "The server sent data this app couldn't read."
    else -> message ?: "Something went wrong."
}
