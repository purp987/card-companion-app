package com.cardprice.app.data.market

import com.cardprice.app.data.PokemonSet
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Reads TCGplayer's public website endpoints (the same ones its product pages use).
 * They're undocumented, so parsing is defensive and failures surface as errors in the UI.
 */
object TcgPlayerApi {
    private const val SEARCH_URL = "https://mp-search-api.tcgplayer.com/v1/search/request?q=%s&isList=false"
    private const val HISTORY_URL = "https://infinite-api.tcgplayer.com/price/history/%d?range=%s"
    private val SITE_HEADERS = mapOf(
        "Origin" to "https://www.tcgplayer.com",
        "Referer" to "https://www.tcgplayer.com/",
    )

    fun sealedProducts(set: PokemonSet): List<MarketProduct> {
        val productLine = set.language.tcgProductLine ?: return emptyList()
        val terms = JSONObject()
            .put("productLineName", JSONArray().put(productLine))
            .put("productTypeName", JSONArray().put("Sealed Products"))
        if (set.tcgSlug != null) terms.put("setName", JSONArray().put(set.tcgSlug))
        val body = JSONObject()
            .put("algorithm", "sales_synonym_v2")
            .put("from", 0)
            .put("size", 50)
            .put("filters", JSONObject().put("term", terms))
            .put("listingSearch", JSONObject().put("context", JSONObject().put("cart", JSONObject())))
            .put("context", JSONObject().put("cart", JSONObject()).put("shippingCountry", "US"))
        // User-added sets have no slug, so search by name and keep products whose set name matches.
        val query = if (set.tcgSlug == null) java.net.URLEncoder.encode(set.name, "UTF-8") else ""
        val json = Http.postJson(SEARCH_URL.format(query), body.toString(), SITE_HEADERS)
        return parseProducts(json, setNameFilter = if (set.tcgSlug == null) set.name else null)
    }

    fun history(productId: Long, range: HistoryRange): List<PricePoint> =
        parseHistory(Http.get(HISTORY_URL.format(productId, range.apiValue), SITE_HEADERS))

    internal fun parseProducts(json: String, setNameFilter: String? = null): List<MarketProduct> {
        val results = JSONObject(json).getJSONArray("results").getJSONObject(0).getJSONArray("results")
        return (0 until results.length()).map { results.getJSONObject(it) }
            .filter { setNameFilter == null || it.optString("setName").contains(setNameFilter, ignoreCase = true) }
            .map {
                MarketProduct(
                    id = it.getDouble("productId").toLong(),
                    name = it.getString("productName"),
                    marketPrice = it.optPositiveDouble("marketPrice"),
                    lowestPrice = it.optPositiveDouble("lowestPrice"),
                )
            }
    }

    internal fun parseHistory(json: String): List<PricePoint> {
        val result = JSONObject(json).getJSONArray("result")
        return (0 until result.length()).map { result.getJSONObject(it) }.mapNotNull { day ->
            val variants = day.optJSONArray("variants") ?: return@mapNotNull null
            if (variants.length() == 0) return@mapNotNull null
            // Sealed products normally have a single "Normal" variant.
            val v = (0 until variants.length()).map { variants.getJSONObject(it) }
                .firstOrNull { it.optString("variant") == "Normal" } ?: variants.getJSONObject(0)
            val quantity = v.optString("quantity").toDoubleOrNull()?.toInt() ?: 0
            PricePoint(
                date = LocalDate.parse(day.getString("date")),
                avgSalePrice = if (quantity > 0) v.optPositiveDouble("averageSalesPrice") else null,
                marketPrice = v.optPositiveDouble("marketPrice"),
                quantitySold = quantity,
            )
        }.sortedBy { it.date }.dropPartialTail()
    }

    /**
     * For weekly ranges TCGplayer appends a "today" entry whose sales are already counted in the
     * current week's bucket. It's spaced closer than the other buckets, which is how we spot it.
     */
    private fun List<PricePoint>.dropPartialTail(): List<PricePoint> {
        if (size < 3) return this
        val lastGap = this[lastIndex].date.toEpochDay() - this[lastIndex - 1].date.toEpochDay()
        val bucketGap = this[1].date.toEpochDay() - this[0].date.toEpochDay()
        return if (lastGap < bucketGap) dropLast(1) else this
    }
}

/** Reads a number that may be sent as a JSON number, a string, or null; zero means "no data". */
internal fun JSONObject.optPositiveDouble(key: String): Double? {
    if (isNull(key)) return null
    val value = opt(key)?.toString()?.toDoubleOrNull() ?: return null
    return value.takeIf { it > 0 }
}
