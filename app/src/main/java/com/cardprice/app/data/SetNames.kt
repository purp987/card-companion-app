package com.cardprice.app.data

/**
 * English names for Japanese and Simplified Chinese sets, from the calculator's set lists (matched by
 * printed code, e.g. "SV2a" → "Pokemon Card 151", "CSV8C" → "Sparkling Fantasy"). Null when the set
 * isn't in those lists (mostly older Japanese sets).
 */
object SetNames {
    private val japanese = JapaneseSets.sets.mapNotNull { s -> s.code?.let { it.uppercase() to s.name } }.toMap()
    private val chinese = ChineseSets.sets.mapNotNull { s -> s.code?.let { it.uppercase() to s.name } }.toMap()

    fun english(language: Language, setId: String): String? = when (language) {
        Language.ENGLISH -> null
        Language.JAPANESE -> japanese[setId.uppercase()]
        Language.CHINESE_SIMPLIFIED -> chinese[setId.uppercase()]
    }
}
