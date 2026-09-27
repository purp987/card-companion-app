package com.cardprice.app.data.collection

import com.cardprice.app.data.Language
import com.cardprice.app.data.market.Http
import com.cardprice.app.data.scan.ScanMatcher
import com.cardprice.app.data.search.TcgSetMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Some TCGdex sets list cards without pictures (e.g. the 30th Celebration Classic Collection).
 * TCGplayer lists the same cards with photos, so missing pictures (and prices) are filled from there.
 */
object ImageFill {
    /** TCGplayer set slugs for a TCGdex set (a set can have several, e.g. a main set and its Trainer Gallery). */
    fun tcgplayerSlugs(language: Language, setId: String): List<String> = when (language) {
        Language.ENGLISH -> TcgSetMap.english.filterValues { it == setId }.keys.toList()
        Language.JAPANESE -> TcgSetMap.japanese.filterValues { it == setId }.keys.toList()
        Language.CHINESE_SIMPLIFIED -> emptyList()
    }

    /** Fills missing pictures from TCGplayer; returns the list unchanged if nothing is missing or TCGplayer can't be reached. */
    suspend fun fillMissing(language: Language, setId: String, cards: List<CollectionCard>): List<CollectionCard> {
        if (cards.none { it.image == null && !it.placeholder }) return cards
        val productLine = language.tcgProductLine ?: return cards
        val slugs = tcgplayerSlugs(language, setId).ifEmpty { return cards }
        val singles = withContext(Dispatchers.IO) {
            runCatching {
                slugs.flatMap { slug ->
                    val found = mutableListOf<CollectionCard>()
                    var from = 0
                    while (true) {
                        val page = TcgPlayerSingles.parse(Http.postJson(TcgPlayerSingles.url(), TcgPlayerSingles.body(productLine, slug, from), TcgPlayerSingles.headers))
                        found += page.cards
                        from += TcgPlayerSingles.PAGE
                        if (from >= page.total || page.cards.isEmpty()) break
                    }
                    found
                }.distinctBy { it.id }
            }.getOrDefault(emptyList())
        }
        return fill(cards, singles)
    }

    /**
     * Gives cards that have no picture the photo (and market price) of the matching TCGplayer card:
     * first by printed number and name, then by name alone (TCGplayer may add "(Prime)", "LV.X",
     * "(Top)"…), pairing cards that share a name in number order.
     */
    fun fill(cards: List<CollectionCard>, tcgplayer: List<CollectionCard>): List<CollectionCard> {
        val missing = cards.filter { it.image == null && !it.placeholder }
        if (missing.isEmpty() || tcgplayer.isEmpty()) return cards
        val used = mutableSetOf<String>()
        val assigned = mutableMapOf<String, CollectionCard>()

        // 1. Same number and same name: the numbering matches (e.g. a few cards missing from a regular set).
        for (card in missing) {
            val match = tcgplayer.firstOrNull { it.id !in used && ScanMatcher.sameNumber(it.number, card.number) && baseName(it.name) == baseName(card.name) }
            if (match != null) {
                assigned[card.id] = match
                used += match.id
            }
        }

        // 2. By name, pairing duplicates in order (reprint subsets keep their original numbers).
        missing.filter { it.id !in assigned }
            .groupBy { baseName(it.name) }
            .forEach { (name, group) ->
                val candidates = tcgplayer.filter { it.id !in used && nameMatches(baseName(it.name), name) }
                    .sortedWith(compareBy({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }))
                group.sortedBy { it.number }.zip(candidates).forEach { (card, match) ->
                    assigned[card.id] = match
                    used += match.id
                }
            }

        return cards.map { card ->
            val match = assigned[card.id] ?: return@map card
            card.copy(image = match.image, marketPrice = card.marketPrice ?: match.marketPrice)
        }
    }

    /** Lower-case letters and digits, without TCGplayer's bracketed labels ("Gengar (Prime)" → "gengar"). */
    internal fun baseName(name: String): String =
        name.replace(Regex("\\s*\\(.*?\\)"), "").lowercase().filter(Char::isLetterOrDigit)

    /** "palkia" matches "palkialvx"; very short names must match exactly. */
    private fun nameMatches(tcgplayer: String, tcgdex: String): Boolean =
        tcgplayer == tcgdex || (tcgdex.length >= 4 && (tcgplayer.startsWith(tcgdex) || tcgdex.startsWith(tcgplayer)))
}
