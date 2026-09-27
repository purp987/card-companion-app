package com.cardprice.app.data

import com.cardprice.app.data.collection.ExtraSets
import com.cardprice.app.data.scan.ScanIndex

/**
 * Finds sets by name, local name (日本語/简体中文) or printed code, for the home screen's search.
 * Every word typed has to appear somewhere in the set's name, local name or code, so "pitch" finds
 * Pitch Black and "sv2a" or "151" find Pokémon Card 151. Best matches come first: an exact name or
 * code, then names starting with the query, then the rest; newer sets first within each group.
 */
object SetSearch {
    fun find(sets: List<PokemonSet>, query: String, limit: Int = 20): List<PokemonSet> {
        val words = query.trim().lowercase().split(Regex("\\s+")).map(::normalize).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val whole = normalize(query)
        return sets.asSequence()
            .map { set -> set to fields(set) }
            .filter { (_, fields) -> words.all { w -> fields.any { it.contains(w) } } }
            .sortedWith(
                compareBy<Pair<PokemonSet, List<String>>> { (_, fields) ->
                    when {
                        fields.any { it == whole } -> 0
                        fields.any { it.startsWith(whole) } -> 1
                        else -> 2
                    }
                }.thenByDescending { (set, _) -> set.year },
            )
            .map { it.first }
            .distinctBy { it.id }
            .take(limit)
            .toList()
    }

    /**
     * The collection's (TCGdex) id for a calculator set, where its card list and card prices live;
     * null for sets added by hand.
     */
    fun collectionSetId(set: PokemonSet): String? {
        if (set.language == Language.ENGLISH) return SetArtwork.tcgdexArt(set.id)?.setId
        // Japanese and Chinese TCGdex ids are the printed codes (SV2a, CSV8C), give or take capitals
        // ("m1S" is "M1S"); some sets aren't on TCGdex at all (Chinese 151C), so they get no card list.
        val code = set.code ?: return null
        ExtraSets.find(set.language, code)?.let { return it.id }
        return ScanIndex.sets.firstOrNull { it.language == set.language && it.setId.equals(code, ignoreCase = true) }?.setId
    }

    private fun fields(set: PokemonSet) = listOfNotNull(set.name, set.localName, set.code).map(::normalize)

    /** Lower case, letters (any script) and digits only: "Scarlet & Violet—151" → "scarletviolet151". */
    internal fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
