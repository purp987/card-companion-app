package com.cardprice.app.data.market

import com.cardprice.app.data.Language
import com.cardprice.app.data.PokemonSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * PriceCharting lookups (the owner's own API token) with a short-lived in-memory cache. Everything else
 * the app shows comes from TCG Vault.
 */
object MarketRepository {
    private const val TTL_MS = 15 * 60 * 1000L

    private class Entry<T>(val value: T, val at: Long = System.currentTimeMillis()) {
        val fresh get() = System.currentTimeMillis() - at < TTL_MS
    }

    private val priceCharting = ConcurrentHashMap<Pair<String, ProductKind>, Entry<PriceChartingPrice?>>()

    suspend fun priceCharting(token: String, set: PokemonSet, kind: ProductKind, force: Boolean = false) =
        cached(priceCharting, set.id to kind, force) { PriceChartingApi.sealedPrice(token, set, kind) }

    /** A single product (card or sealed) by its name, for the product screen. */
    suspend fun priceChartingProduct(token: String, query: String, sealed: Boolean, language: Language = Language.ENGLISH): PriceChartingProduct? =
        cached(products, "$query|$sealed|$language", false) { PriceChartingApi.productPrices(token, query, sealed, language) }

    private val products = ConcurrentHashMap<String, Entry<PriceChartingProduct?>>()

    fun clearPriceCharting() {
        priceCharting.clear()
        products.clear()
    }

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
    is com.cardprice.app.data.vault.VaultException -> message ?: "TCG Vault error."
    is HttpException -> if (code >= 500) "The server is having trouble right now ($code). Try again in a moment."
        else "The server returned an error ($code)."
    is org.json.JSONException -> "The server sent data this app couldn't read."
    is java.io.IOException -> "Couldn't reach the server. Check your connection."
    // Raw messages can contain a request's address (with an API token in it), so they're never shown.
    else -> message?.takeIf { "://" !in it && it.length < 120 } ?: "Something went wrong."
}
