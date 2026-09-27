package com.cardprice.app.data.market

import com.cardprice.app.data.Language
import com.cardprice.app.data.PokemonSet
import org.json.JSONObject
import java.net.URLEncoder

/** PriceCharting's official API. Requires the subscriber's API token (pricecharting.com/api-documentation). */
object PriceChartingApi {
    private const val PRODUCTS_URL = "https://www.pricecharting.com/api/products?t=%s&q=%s"

    fun sealedPrice(token: String, set: PokemonSet, kind: ProductKind): PriceChartingPrice? {
        val language = if (set.language == Language.JAPANESE) "japanese " else ""
        val query = "pokemon $language${set.name} ${kind.keyword}"
        val url = PRODUCTS_URL.format(URLEncoder.encode(token, "UTF-8"), URLEncoder.encode(query, "UTF-8"))
        val json = try {
            Http.get(url)
        } catch (e: HttpException) {
            // PriceCharting reports bad tokens etc. in the body.
            throw IllegalStateException(errorMessage(e.body) ?: "PriceCharting error ${e.code}")
        }
        return parseBestMatch(json, set.name, kind, set.language)
    }

    internal fun errorMessage(json: String): String? = runCatching {
        JSONObject(json).optString("error-message").ifBlank { null }
    }.getOrNull()

    internal fun parseBestMatch(
        json: String,
        setName: String,
        kind: ProductKind,
        language: Language = Language.ENGLISH,
    ): PriceChartingPrice? {
        val root = JSONObject(json)
        if (root.optString("status") == "error") {
            throw IllegalStateException(root.optString("error-message", "PriceCharting error"))
        }
        val products = root.optJSONArray("products") ?: return null
        val candidates = (0 until products.length()).map { products.getJSONObject(it) }
            .filter { kind.matches(it.optString("product-name")) }
        val set = normalize(setName)
        // PriceCharting names foreign sets like "Pokemon Japanese Black Bolt"; keep languages apart.
        val best = candidates.firstOrNull { product ->
            val console = normalize(product.optString("console-name"))
            val isJapanese = "japanese" in console
            val isOtherLanguage = "chinese" in console || "korean" in console
            console.contains(set) && !isOtherLanguage && isJapanese == (language == Language.JAPANESE)
        }
            ?: return null
        return PriceChartingPrice(
            productName = best.optString("product-name"),
            consoleName = best.optString("console-name"),
            // Prices are returned in pennies.
            price = best.optPositiveDouble("loose-price")?.div(100.0),
        )
    }

    private fun normalize(s: String) = s.lowercase().replace("&", "and").filter(Char::isLetterOrDigit)
}
