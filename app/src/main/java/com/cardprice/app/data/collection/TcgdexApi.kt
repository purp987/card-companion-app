package com.cardprice.app.data.collection

import com.cardprice.app.data.Language
import com.cardprice.app.data.market.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCGdex (tcgdex.dev), an open-source (MIT) Pokémon TCG database. English card lists come from its
 * GraphQL endpoint in bulk; other languages only have REST, so variants are fetched card by card.
 */
object TcgdexApi {
    private const val BASE = "https://api.tcgdex.net/v2"

    /** Digital-only or non-collectible series. */
    private val SKIPPED_SERIES = setOf("tcgp")

    suspend fun series(language: Language): List<CardSeries> = withContext(Dispatchers.IO) {
        val lang = language.tcgdexCode
        if (language == Language.CHINESE_SIMPLIFIED) return@withContext mainlandChineseSeries(Http.get("$BASE/$lang/sets"))
        val ids = JSONArray(Http.get("$BASE/$lang/series"))
        val seriesIds = (0 until ids.length()).map { ids.getJSONObject(it).getString("id") }
            .filter { it !in SKIPPED_SERIES }
        val semaphore = Semaphore(6)
        coroutineScope {
            seriesIds.map { id ->
                async { semaphore.withPermit { parseSeries(Http.get("$BASE/$lang/series/${enc(id)}")) } }
            }.awaitAll()
        }
            .filter { it.sets.isNotEmpty() }
            // English sets come in release order already; Japanese ones don't, so order them by set number.
            .map { if (language == Language.ENGLISH) it else it.copy(sets = it.sets.sortedWith(newestByCode)) }
            // TCGdex lists series oldest first; collectors mostly want the newest.
            .reversed()
            // Japanese TCGdex data lists the Mega era (M) before Scarlet & Violet, though it's newer.
            .sortedBy { if (it.id == "M") 0 else 1 }
    }

    /**
     * TCGdex's zh-cn series only list Traditional Chinese releases (SV9, SV10…), while the mainland
     * Simplified sets (CSV1C, CBB2C, CS1aC…) are only in its full set list, so group those by code prefix.
     */
    internal fun mainlandChineseSeries(setsJson: String): List<CardSeries> {
        val all = JSONArray(setsJson)
        val sets = (0 until all.length()).map { parseSetSummary(all.getJSONObject(it)) }
            .filter { it.id.startsWith("C", ignoreCase = true) }
            .distinctBy { it.id }
        fun era(id: String) = when {
            id.startsWith("CSV", true) || id.startsWith("CBB", true) -> "SV" to "朱&紫 Scarlet & Violet"
            id.startsWith("CSM", true) -> "SM" to "太阳&月亮 Sun & Moon"
            else -> "S" to "剑&盾 Sword & Shield"
        }
        return listOf("SV", "S", "SM").mapNotNull { eraId ->
            val inEra = sets.filter { era(it.id).first == eraId }
            if (inEra.isEmpty()) null else CardSeries(eraId, era(inEra.first().id).second, inEra.sortedWith(newestByCode))
        }
    }

    /** Newest first by the number in the set code: SV11B, SV10, SV9a, SV9 … SV1a, SV1V; codes without a number last. */
    internal val newestByCode: Comparator<CardSet> = compareBy<CardSet> { codeNumber(it.id) == null }
        .thenByDescending { codeNumber(it.id) ?: 0.0 }
        .thenByDescending { it.id.substringAfterLast(codeNumberText(it.id) ?: "") }

    private val CODE_NUMBER = Regex("""\d+(\.\d+)?""")
    private fun codeNumberText(id: String) = CODE_NUMBER.find(id)?.value
    private fun codeNumber(id: String) = codeNumberText(id)?.toDoubleOrNull()

    suspend fun setCards(language: Language, setId: String, onProgress: (done: Int, total: Int) -> Unit): List<CollectionCard> =
        withContext(Dispatchers.IO) {
            if (language == Language.ENGLISH) englishSetCards(setId) else restSetCards(language, setId, onProgress)
        }

    /** A set's name and basic card list (number, name, image) in one request. */
    suspend fun setSummary(language: Language, setId: String): Pair<String, List<CollectionCard>> = withContext(Dispatchers.IO) {
        val json = Http.get("$BASE/${language.tcgdexCode}/sets/${enc(setId)}")
        JSONObject(json).optString("name", setId) to parseSetBriefs(json)
    }

    /** One card with its full variant list. */
    suspend fun card(language: Language, cardId: String): CollectionCard = withContext(Dispatchers.IO) {
        parseCard(JSONObject(Http.get("$BASE/${language.tcgdexCode}/cards/${enc(cardId)}")))
    }

    suspend fun cardPrices(language: Language, cardId: String): CardPrices = withContext(Dispatchers.IO) {
        parseCardPrices(Http.get("$BASE/${language.tcgdexCode}/cards/${enc(cardId)}"))
    }

    private fun englishSetCards(setId: String): List<CollectionCard> {
        val cards = mutableListOf<CollectionCard>()
        var page = 1
        while (true) {
            // The id filter is a substring match, so "sv03.5-" selects exactly this set's cards.
            val query = """{ cards(filters: {id: "${setId.replace("\"", "")}-"}, pagination: {page: $page, count: 100}) {
                id localId name rarity image variants { normal reverse holo firstEdition }
                variants_detailed { type subtype stamp foil } } }"""
            val body = JSONObject().put("query", query).toString()
            val batch = parseGraphqlCards(Http.postJson("$BASE/graphql", body))
            cards += cardsOfSet(batch, setId)
            if (batch.size < 100) break
            page++
        }
        return cards.sortedWith(cardOrder)
    }

    private suspend fun restSetCards(
        language: Language,
        setId: String,
        onProgress: (Int, Int) -> Unit,
    ): List<CollectionCard> = coroutineScope {
        val lang = language.tcgdexCode
        val briefs = parseSetBriefs(Http.get("$BASE/$lang/sets/${enc(setId)}"))
        val done = AtomicInteger(0)
        onProgress(0, briefs.size)
        val semaphore = Semaphore(6)
        // Placeholder cards don't exist on TCGdex, so there are no details to fetch.
        if (briefs.all { it.placeholder }) return@coroutineScope briefs
        briefs.map { brief ->
            async {
                semaphore.withPermit {
                    val card = runCatching { parseCard(JSONObject(Http.get("$BASE/$lang/cards/${enc(brief.id)}"))) }
                        // If one card fails, keep it in the checklist with a plain variant.
                        .getOrElse { brief }
                    onProgress(done.incrementAndGet(), briefs.size)
                    card
                }
            }
        }.awaitAll().sortedWith(cardOrder)
    }

    /**
     * The GraphQL id filter matches substrings, so "30th-" also returns the Classic Collection's
     * "30th-c-001". A card belongs to the set only if its id is exactly "<setId>-<number>".
     */
    internal fun cardsOfSet(cards: List<CollectionCard>, setId: String) = cards.filter { it.id == "$setId-${it.number}" }

    // ---- Parsing (internal for tests) ----

    internal fun parseSeries(json: String): CardSeries {
        val o = JSONObject(json)
        val sets = o.optJSONArray("sets") ?: JSONArray()
        return CardSeries(
            id = o.getString("id"),
            name = o.getString("name"),
            // TCGdex occasionally reuses an id; duplicates would break list keys, so keep the first.
            sets = (0 until sets.length()).map { parseSetSummary(sets.getJSONObject(it)) }.distinctBy { it.id }.reversed(),
        )
    }

    private fun parseSetSummary(o: JSONObject): CardSet {
        val count = o.optJSONObject("cardCount")
        return CardSet(
            id = o.getString("id"),
            name = o.getString("name"),
            officialCount = count?.optInt("official") ?: 0,
            totalCount = count?.optInt("total") ?: 0,
            logo = o.optStringOrNull("logo"),
            symbol = o.optStringOrNull("symbol"),
        )
    }

    internal fun parseSetBriefs(json: String): List<CollectionCard> {
        val root = JSONObject(json)
        val cards = root.optJSONArray("cards")
        if (cards == null || cards.length() == 0) return numberedPlaceholders(root)
        return (0 until cards.length()).map { cards.getJSONObject(it) }.map {
            CollectionCard(
                id = it.getString("id"),
                number = it.optString("localId"),
                name = it.optString("name"),
                rarity = null,
                image = it.optStringOrNull("image"),
                variants = Variants.fromFlags(normal = true, reverse = false, holo = false, firstEdition = false),
            )
        }
    }

    /**
     * Some sets (e.g. mainland Chinese ones) only have a card count on TCGdex. A numbered checklist still
     * lets collectors track them; names and images show up once TCGdex adds the cards and the set is reloaded.
     */
    private fun numberedPlaceholders(set: JSONObject): List<CollectionCard> {
        val count = set.optJSONObject("cardCount")?.let { c -> c.optInt("total").takeIf { it > 0 } ?: c.optInt("official") } ?: 0
        val setId = set.optString("id")
        val width = count.toString().length.coerceAtLeast(3)
        return (1..count).map { n ->
            val number = n.toString().padStart(width, '0')
            CollectionCard(
                id = "$setId-$number",
                number = number,
                name = "Card $number",
                rarity = null,
                image = null,
                variants = Variants.fromFlags(normal = true, reverse = false, holo = false, firstEdition = false),
                placeholder = true,
            )
        }
    }

    internal fun parseGraphqlCards(json: String): List<CollectionCard> {
        val root = JSONObject(json)
        root.optJSONArray("errors")?.let { if (it.length() > 0) error(it.getJSONObject(0).optString("message")) }
        val cards = root.getJSONObject("data").optJSONArray("cards") ?: return emptyList()
        return (0 until cards.length()).map { parseCard(cards.getJSONObject(it)) }
    }

    /** Works for both GraphQL cards and full REST card objects. */
    internal fun parseCard(o: JSONObject): CollectionCard {
        val detailed = o.optJSONArray("variants_detailed")
        val variants = if (detailed != null && detailed.length() > 0) {
            (0 until detailed.length()).map { detailed.getJSONObject(it) }.map { v ->
                Variants.of(
                    type = v.optString("type", "normal"),
                    subtype = v.optStringOrNull("subtype"),
                    stamps = v.optJSONArray("stamp")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
                    foil = v.optStringOrNull("foil"),
                )
            }.distinctBy { it.key }
        } else {
            val f = o.optJSONObject("variants")
            Variants.fromFlags(
                normal = f?.optBoolean("normal") ?: true,
                reverse = f?.optBoolean("reverse") ?: false,
                holo = f?.optBoolean("holo") ?: false,
                firstEdition = f?.optBoolean("firstEdition") ?: false,
            )
        }
        return CollectionCard(
            id = o.getString("id"),
            number = o.optString("localId"),
            name = o.optString("name"),
            rarity = o.optStringOrNull("rarity")?.takeIf { it != "None" },
            image = o.optStringOrNull("image"),
            variants = variants,
        )
    }

    /** Prefers TCGplayer's USD market price per variant, falling back to Cardmarket's EUR trend. */
    internal fun parseCardPrices(json: String): CardPrices {
        val o = JSONObject(json)
        val cardTcg = o.optJSONObject("pricing")?.optJSONObject("tcgplayer")
        val detailed = o.optJSONArray("variants_detailed") ?: JSONArray()
        val prices = mutableMapOf<String, VariantPrice>()
        for (i in 0 until detailed.length()) {
            val v = detailed.getJSONObject(i)
            val stamps = v.optJSONArray("stamp")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            val variant = Variants.of(v.optString("type", "normal"), v.optStringOrNull("subtype"), stamps, v.optStringOrNull("foil"))
            val pricing = v.optJSONObject("pricing")
            val plain = v.optStringOrNull("foil") == null && stamps.isEmpty() && v.optStringOrNull("subtype") == null
            val tcg = pricing?.optJSONObject("tcgplayer")?.positive("marketPrice")
                // Card-level TCGplayer prices are keyed by printing and only fit the plain variants.
                ?: if (plain) cardTcg?.optJSONObject(tcgplayerKey(variant.key))?.positive("marketPrice") else null
            val cardmarket = pricing?.optJSONObject("cardmarket")?.let { it.positive("trend") ?: it.positive("avg") }
            prices[variant.key] = when {
                tcg != null -> VariantPrice(tcg, "USD", "TCGplayer")
                cardmarket != null -> VariantPrice(cardmarket, "EUR", "Cardmarket")
                else -> continue
            }
        }
        return CardPrices(prices)
    }

    private fun tcgplayerKey(variantKey: String) = when (variantKey.substringBefore(':')) {
        "reverse" -> "reverse-holofoil"
        "holo" -> "holofoil"
        else -> "normal"
    }

    /** Numbered cards in order, then letter-prefixed ones (TG01, SV001…). */
    private val cardOrder = compareBy<CollectionCard>({ it.number.toIntOrNull() == null }, { it.number.toIntOrNull() ?: 0 }, { it.number })

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

private fun JSONObject.positive(key: String): Double? =
    if (isNull(key)) null else opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it > 0 }
