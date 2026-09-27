package com.cardprice.app.data.search

import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.TCGPLAYER_IMAGE_PREFIX
import com.cardprice.app.data.market.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

enum class SearchSort(val label: String) {
    /** TCGplayer's default ranking, weighted by recent sales. */
    POPULAR("Most popular"),
    PRICE("Highest price"),
}

/** A single card from TCGplayer's catalog. [tcgdexSetId] is set when the card's set is in the collection's catalog. */
data class CardHit(
    val productId: Long,
    val name: String,
    val setName: String,
    val setSlug: String,
    /** Printed number without the "/total" (e.g. "111", "TG23"); null for cards without one. */
    val number: String?,
    val rarity: String?,
    val marketPrice: Double?,
    val language: Language,
) {
    val imageUrl: String get() = "$TCGPLAYER_IMAGE_PREFIX${productId}_200w.jpg"
    val tcgplayerUrl: String get() = "https://www.tcgplayer.com/product/$productId"

    val tcgdexSetId: String?
        get() = when (language) {
            Language.ENGLISH -> TcgSetMap.english[setSlug]
            Language.JAPANESE -> TcgSetMap.japanese[setSlug]
            Language.CHINESE_SIMPLIFIED -> null
        }
}

/** Card search across every set, via TCGplayer's catalog search (ranked by sales by default). */
object CardSearch {
    private const val URL = "https://mp-search-api.tcgplayer.com/v1/search/request?q=%s&isList=false"
    private val HEADERS = mapOf("Origin" to "https://www.tcgplayer.com", "Referer" to "https://www.tcgplayer.com/")

    suspend fun search(query: String, language: Language, sort: SearchSort, size: Int = 40): List<CardHit> =
        withContext(Dispatchers.IO) {
            val productLine = language.tcgProductLine ?: return@withContext emptyList()
            val terms = JSONObject()
                .put("productLineName", JSONArray().put(productLine))
                .put("productTypeName", JSONArray().put("Cards"))
            val body = JSONObject()
                .put("algorithm", "sales_synonym_v2")
                .put("from", 0)
                .put("size", size)
                .put("filters", JSONObject().put("term", terms))
                .put("listingSearch", JSONObject().put("context", JSONObject().put("cart", JSONObject())))
                .put("context", JSONObject().put("cart", JSONObject()).put("shippingCountry", "US"))
            if (sort == SearchSort.PRICE) body.put("sort", JSONObject().put("field", "market-price").put("order", "desc"))
            val url = URL.format(URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20"))
            parse(Http.postJson(url, body.toString(), HEADERS), language)
        }

    internal fun parse(json: String, language: Language): List<CardHit> {
        val items = JSONObject(json).getJSONArray("results").getJSONObject(0).getJSONArray("results")
        return (0 until items.length()).map { items.getJSONObject(it) }.mapNotNull { p ->
            val rarity = p.optString("rarityName").takeIf { !p.isNull("rarityName") && it.isNotBlank() && it != "None" }
            if (rarity == "Code Card") return@mapNotNull null // codes for the online game, not cards
            val fullNumber = p.optJSONObject("customAttributes")?.let { a ->
                if (a.isNull("number")) null else a.optString("number").ifBlank { null }
            }
            CardHit(
                productId = p.getDouble("productId").toLong(),
                name = fullNumber?.let { p.getString("productName").substringBefore(" - $it") } ?: p.getString("productName"),
                setName = p.optString("setName"),
                setSlug = slug(p.optString("setUrlName")),
                number = fullNumber?.substringBefore('/'),
                rarity = rarity,
                marketPrice = if (p.isNull("marketPrice")) null else p.optDouble("marketPrice").takeIf { it > 0 },
                language = language,
            )
        }
    }

    /** "SWSH07 Evolving Skies" → "swsh07-evolving-skies", matching TCGplayer's set URL slugs. */
    internal fun slug(setUrlName: String): String = setUrlName.lowercase().replace("&", "and")
        .replace(Regex("[^a-z0-9 -]"), "")
        .replace(Regex("[\\s-]+"), "-")
        .trim('-')
}
