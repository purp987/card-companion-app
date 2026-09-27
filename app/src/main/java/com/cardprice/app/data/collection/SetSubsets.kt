package com.cardprice.app.data.collection

import com.cardprice.app.data.Language

/** A named group of cards inside a set that collectors chase on its own, identified by card number. */
data class SetSubset(val label: String, val numbers: IntRange) {
    fun contains(card: CollectionCard): Boolean = card.number.toIntOrNull()?.let { it in numbers } == true
}

object SetSubsets {
    private val subsets: Map<Pair<Language, String>, List<SetSubset>> = mapOf(
        // 30th Celebration's 30 Pikachu cards are #023–052; the Pikachu ex cards (#053, #054, #149, #150) aren't part of it.
        // The Classic Collection is its own set on TCGdex ("30th-c") and is tracked separately.
        (Language.ENGLISH to "30th") to listOf(SetSubset("30 Pikachu", 23..52)),
        // The Japanese release (M6a) numbers its 30 Pikachu Rare cards #017–046; Pikachu ex #047/#048 aren't included.
        (Language.JAPANESE to "M6a") to listOf(SetSubset("30 Pikachu", 17..46)),
    )

    fun forSet(language: Language, setId: String): List<SetSubset> = subsets[language to setId].orEmpty()
}
