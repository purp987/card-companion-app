package com.cardprice.app.data.collection

import com.cardprice.app.data.Language

/** TCGdex language code for each app language. */
val Language.tcgdexCode: String
    get() = when (this) {
        Language.ENGLISH -> "en"
        Language.JAPANESE -> "ja"
        Language.CHINESE_SIMPLIFIED -> "zh-cn"
    }

data class CardSeries(val id: String, val name: String, val sets: List<CardSet>)

data class CardSet(
    val id: String,
    val name: String,
    /** Numbered cards (the "/165" on the card). */
    val officialCount: Int,
    /** Including secret rares above the official count. */
    val totalCount: Int,
    val logo: String?,
    val symbol: String?,
)

data class CollectionCard(
    val id: String,
    val number: String,
    val name: String,
    val rarity: String?,
    /** TCGdex image base URL; append "/low.webp" or "/high.webp". */
    val image: String?,
    val variants: List<CardVariant>,
    /** True when TCGdex only knows the set's card count, so this entry is just a numbered slot. */
    val placeholder: Boolean = false,
    /** TCGplayer market price (USD) when the card list came from TCGplayer rather than TCGdex. */
    val marketPrice: Double? = null,
) {
    /**
     * Image URL at "low" (list) or "high" (detail) quality, for either image source; for cards with no
     * picture of their own, one you took ([CardPhotos]).
     */
    fun imageUrl(quality: String): String? = image?.let {
        if (it.startsWith(TCGPLAYER_IMAGE_PREFIX)) "${it}_${if (quality == "high") "400" else "200"}w.jpg" else "$it/$quality.webp"
    } ?: CardPhotos.uriFor(id)

    /** True when the picture is one you took rather than one from the card databases. */
    val usesOwnPhoto: Boolean get() = image == null && CardPhotos.has(id)
}

/** One collectible printing of a card, e.g. a Poké Ball reverse holo. [key] is stable for storage. */
data class CardVariant(val key: String, val label: String)

/** A price for one variant. [currency] is "USD" (TCGplayer) or "EUR" (Cardmarket). */
data class VariantPrice(val amount: Double, val currency: String, val source: String)

data class CardPrices(val byVariant: Map<String, VariantPrice>)

/** Progress through one set, kept so the home screen can show it without reloading the set. */
data class SetProgress(
    val language: Language,
    val setId: String,
    val setName: String,
    val totalCards: Int,
    val totalVariants: Int,
    val ownedCards: Int,
    val ownedVariants: Int,
    val updatedAt: Long,
    /** What the owned copies are worth, from the last time the set's prices were checked. */
    val value: SetValue = SetValue.NONE,
    /** The set's picture URLs (same as the collection picker), so other screens can show it. */
    val art: List<String> = emptyList(),
) {
    val cardFraction: Float get() = if (totalCards > 0) ownedCards.toFloat() / totalCards else 0f
    val variantFraction: Float get() = if (totalVariants > 0) ownedVariants.toFloat() / totalVariants else 0f
}

object Variants {
    private val FOILS = mapOf("pokeball" to "Poké Ball", "masterball" to "Master Ball", "cosmos" to "Cosmos")
    private val STAMPS = mapOf("1st-edition" to "1st Edition", "set-logo" to "Set Logo Stamp")
    private val TYPES = mapOf("normal" to "Normal", "reverse" to "Reverse Holo", "holo" to "Holo")

    /** Builds a variant from TCGdex's detailed variant fields. */
    fun of(type: String, subtype: String?, stamps: List<String>, foil: String?): CardVariant {
        val key = buildList {
            add(type)
            subtype?.let { add("sub=$it") }
            stamps.sorted().forEach { add("stamp=$it") }
            foil?.let { add("foil=$it") }
        }.joinToString(":")
        val main = listOfNotNull(subtype?.let(::titleCase), foil?.let { FOILS[it] ?: titleCase(it) }, TYPES[type] ?: titleCase(type))
            .joinToString(" ")
        val stampText = stamps.joinToString(", ") { STAMPS[it] ?: titleCase(it) }
        return CardVariant(key, if (stampText.isEmpty()) main else "$main · $stampText")
    }

    /** Fallback when only TCGdex's yes/no variant flags are available. */
    fun fromFlags(normal: Boolean, reverse: Boolean, holo: Boolean, firstEdition: Boolean): List<CardVariant> =
        buildList {
            if (normal) add(of("normal", null, emptyList(), null))
            if (holo) add(of("holo", null, emptyList(), null))
            if (reverse) add(of("reverse", null, emptyList(), null))
            if (firstEdition) add(of(if (holo) "holo" else "normal", null, listOf("1st-edition"), null))
        }.distinctBy { it.key }.ifEmpty { listOf(of("normal", null, emptyList(), null)) }

    private fun titleCase(s: String) = s.split('-', '_', ' ').filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

/**
 * Value of owned copies. TCGplayer prices are in USD; variants only Cardmarket prices (common for
 * Japanese cards) are totalled separately in EUR rather than converted at a guessed rate.
 */
data class SetValue(val usd: Double, val eur: Double, val pricedCopies: Int, val unpricedCopies: Int) {
    val isEmpty: Boolean get() = usd == 0.0 && eur == 0.0

    operator fun plus(other: SetValue) =
        SetValue(usd + other.usd, eur + other.eur, pricedCopies + other.pricedCopies, unpricedCopies + other.unpricedCopies)

    companion object {
        val NONE = SetValue(0.0, 0.0, 0, 0)

        /** Adds up count × price for every owned variant that has a price. */
        fun of(cards: List<CollectionCard>, count: (cardId: String, variantKey: String) -> Int, prices: Map<String, CardPrices>): SetValue {
            var usd = 0.0
            var eur = 0.0
            var priced = 0
            var unpriced = 0
            for (card in cards) for (variant in card.variants) {
                val n = count(card.id, variant.key)
                if (n == 0) continue
                val price = prices[card.id]?.byVariant?.get(variant.key)
                when (price?.currency) {
                    "USD" -> { usd += n * price!!.amount; priced += n }
                    "EUR" -> { eur += n * price!!.amount; priced += n }
                    else -> unpriced += n
                }
            }
            return SetValue(usd, eur, priced, unpriced)
        }
    }
}
