package com.cardprice.app.data.collection

import com.cardprice.app.data.Language
import com.cardprice.app.data.market.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sets TCGdex doesn't have (or doesn't split the way collectors do), built from TCGplayer's list of
 * single cards instead. These replace any TCGdex set with the same id.
 */
data class ExtraSet(
    val language: Language,
    /** TCGdex series the set belongs under (e.g. "M" for Japanese Mega Evolution). */
    val seriesId: String,
    val id: String,
    val name: String,
    val tcgSlug: String,
    /** Which of the TCGplayer set's cards belong here. */
    val includes: (rarity: String?) -> Boolean,
)

object ExtraSets {
    private const val CLASSIC = "Classic Collection"

    val all: List<ExtraSet> = listOf(
        // Japanese 30th Celebration (M6a). TCGplayer numbers its Classic Collection cards inside the
        // main set (141/103 and up); they're kept as a separate set, like the English release.
        ExtraSet(Language.JAPANESE, "M", "M6a", "MEGA Expansion 30th Celebration", "m6a-mega-expansion-30th-celebration") { it != CLASSIC },
        ExtraSet(Language.JAPANESE, "M", "M6a-C", "MEGA Expansion 30th Celebration: Classic Collection", "m6a-mega-expansion-30th-celebration") { it == CLASSIC },
    )

    fun find(language: Language, setId: String): ExtraSet? = all.firstOrNull { it.language == language && it.id == setId }

    /** Adds this language's extra sets to the top of their series, replacing any TCGdex set with the same id. */
    fun inject(language: Language, series: List<CardSeries>): List<CardSeries> {
        val extras = all.filter { it.language == language }
        if (extras.isEmpty()) return series
        val ids = extras.map { it.id }.toSet()
        return series.map { s ->
            val mine = extras.filter { it.seriesId == s.id }
                .map { CardSet(it.id, it.name, officialCount = 0, totalCount = 0, logo = null, symbol = null) }
            s.copy(sets = mine + s.sets.filterNot { it.id in ids })
        }
    }

    suspend fun cards(extra: ExtraSet): List<CollectionCard> = withContext(Dispatchers.IO) {
        val productLine = extra.language.tcgProductLine ?: return@withContext emptyList()
        val cards = mutableListOf<CollectionCard>()
        var from = 0
        while (true) {
            val page = TcgPlayerSingles.parse(Http.postJson(TcgPlayerSingles.url(), TcgPlayerSingles.body(productLine, extra.tcgSlug, from), TcgPlayerSingles.headers))
            cards += page.cards.filter { extra.includes(it.rarity) }.map { it.copy(id = "${extra.id}-${it.number}") }
            from += TcgPlayerSingles.PAGE
            if (from >= page.total || page.cards.isEmpty()) break
        }
        cards.distinctBy { it.id }.sortedWith(compareBy({ it.number.toIntOrNull() == null }, { it.number.toIntOrNull() ?: 0 }, { it.number }))
    }
}

/** Reads single cards from TCGplayer's search (the same endpoint used for sealed products). */
internal object TcgPlayerSingles {
    const val PAGE = 50
    val headers = mapOf("Origin" to "https://www.tcgplayer.com", "Referer" to "https://www.tcgplayer.com/")

    fun url() = "https://mp-search-api.tcgplayer.com/v1/search/request?q=&isList=false"

    fun body(productLine: String, slug: String, from: Int): String {
        val terms = JSONObject()
            .put("productLineName", JSONArray().put(productLine))
            .put("setName", JSONArray().put(slug))
            .put("productTypeName", JSONArray().put("Cards"))
        return JSONObject()
            .put("algorithm", "sales_synonym_v2")
            .put("from", from)
            .put("size", PAGE)
            .put("filters", JSONObject().put("term", terms))
            .put("listingSearch", JSONObject().put("context", JSONObject().put("cart", JSONObject())))
            .put("context", JSONObject().put("cart", JSONObject()).put("shippingCountry", "US"))
            .toString()
    }

    data class Page(val total: Int, val cards: List<CollectionCard>)

    /** Cards come back as "Pikachu - 017/103"; the number is also in customAttributes. */
    fun parse(json: String): Page {
        val result = JSONObject(json).getJSONArray("results").getJSONObject(0)
        val items = result.getJSONArray("results")
        val cards = (0 until items.length()).map { items.getJSONObject(it) }.map { p ->
            val id = p.getDouble("productId").toLong()
            val fullNumber = p.optJSONObject("customAttributes")?.optString("number").orEmpty()
            val number = fullNumber.substringBefore('/').ifBlank { id.toString() }
            val rarity = p.optString("rarityName").takeIf { it.isNotBlank() && it != "None" && !p.isNull("rarityName") }
            val price = if (p.isNull("marketPrice")) null else p.optDouble("marketPrice").takeIf { it > 0 }
            CollectionCard(
                id = id.toString(),
                number = number,
                name = p.getString("productName").substringBefore(" - $fullNumber").trim(),
                rarity = rarity,
                image = "${TCGPLAYER_IMAGE_PREFIX}$id",
                variants = Variants.fromFlags(normal = true, reverse = false, holo = false, firstEdition = false),
                marketPrice = price,
            )
        }
        return Page(result.optInt("totalResults", cards.size), cards)
    }
}

/** Card images from TCGplayer are stored with this prefix; the UI appends a size suffix. */
const val TCGPLAYER_IMAGE_PREFIX = "https://tcgplayer-cdn.tcgplayer.com/product/"
