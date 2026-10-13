package com.cardprice.app.data.vault

import com.cardprice.app.data.Language
import com.cardprice.app.data.PokemonSet

/**
 * Links the calculator's own set list (pack counts per product) to TCG Vault sets, whose sealed
 * products carry the market prices.
 */
object CalculatorSets {
    /** TCG Vault category for a calculator language: English 3, Japanese 85, Chinese (Simplified) 900002. */
    fun categoryFor(language: Language): Long = when (language) {
        Language.ENGLISH -> 3
        Language.JAPANESE -> 85
        Language.CHINESE_SIMPLIFIED -> 900002
    }

    private fun norm(s: String) = s.lowercase().replace("&", "and").replace("é", "e").filter(Char::isLetterOrDigit)

    /** "SV: Scarlet & Violet 151" → "scarletandviolet151"; "ME05: Pitch Black" → "pitchblack". */
    private fun baseName(name: String) = norm(name.substringAfter(": ", name))

    /** The server set for a calculator set, or null when none fits well enough. */
    fun match(set: PokemonSet, sets: List<VaultSet>): VaultSet? {
        if (sets.isEmpty()) return null
        val withSealed = sets.sortedByDescending { it.sealedCount > 0 }
        // 1. Printed code (Japanese/Chinese sets): "SV2a" = abbreviation or "SV2a: …" prefix.
        set.code?.let { code ->
            withSealed.firstOrNull { it.abbreviation.equals(code, ignoreCase = true) || it.name.substringBefore(": ").equals(code, ignoreCase = true) }
                ?.let { return it }
        }
        val want = norm(set.name)
        if (want.isEmpty()) return null
        // 2. Same name once the series prefix is dropped.
        withSealed.firstOrNull { baseName(it.name) == want }?.let { return it }
        // 3. Server name ends with ours ("Scarlet & Violet 151" for "151"), shortest first so
        //    "Celebrations" doesn't pick "Celebrations: Classic Collection".
        withSealed.filter { baseName(it.name).endsWith(want) }.minByOrNull { it.name.length }?.let { return it }
        // 4. Server name contains ours, again preferring the shortest.
        if (want.length >= 5) withSealed.filter { want in baseName(it.name) }.minByOrNull { it.name.length }?.let { return it }
        return null
    }
}
