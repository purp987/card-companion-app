package com.cardprice.app.data

/**
 * Card language. [nativeName] labels the language switch; [label] is used in sentences.
 * [tcgProductLine] is TCGplayer's product line, or null when TCGplayer doesn't sell that language.
 */
enum class Language(val label: String, val nativeName: String, val tcgProductLine: String?) {
    ENGLISH("English", "English", "pokemon"),
    JAPANESE("Japanese", "日本語", "pokemon-japan"),
    CHINESE_SIMPLIFIED("Simplified Chinese", "简体中文", null),
}

/** Display order of the groups on the home screen; the user's own sets come first. */
enum class Series(val title: String, val shortTitle: String) {
    CUSTOM("My Sets", "Mine"),
    MEGA_EVOLUTION("Mega Evolution", "ME"),
    SCARLET_VIOLET("Scarlet & Violet", "SV"),
    GEM_PACK("Gem Packs", "GEM"),
    SWORD_SHIELD("Sword & Shield", "SWSH"),
    SUN_MOON("Sun & Moon", "SM"),
    XY("XY", "XY"),
}

data class PokemonSet(
    val id: String,
    val name: String,
    val series: Series,
    val year: Int,
    /** Cards in a standard pack; a product can override it (e.g. Chinese jumbo packs). */
    val cardsPerPack: Int = 10,
    /** Special/holiday sets that were never sold in 36-pack booster boxes. */
    val special: Boolean = false,
    /** TCGplayer set slug; null for user-added sets, which are looked up by name instead. */
    val tcgSlug: String? = null,
    val language: Language = Language.ENGLISH,
    /** Printed set code, e.g. "SV11B" or "CSV10C". */
    val code: String? = null,
    /** Official name in the set's own language, e.g. "补充包 共逐荣光". */
    val localName: String? = null,
    /** Products this set was sold in; null uses the English defaults. "Custom" is always added. */
    val products: List<ProductPreset>? = null,
)

/** [cardsPerPack] overrides the set's default when this product's packs differ (e.g. 20-card jumbo packs). */
data class ProductPreset(val label: String, val packs: Int, val cardsPerPack: Int? = null)

data class Purchase(
    val id: Long,
    val setId: String,
    val product: String,
    val quantity: Int,
    val packsPerProduct: Int,
    val cardsPerPack: Int,
    val totalPrice: Double,
    val timestamp: Long,
) {
    val totalPacks: Int get() = quantity * packsPerProduct
    val totalCards: Int get() = totalPacks * cardsPerPack
    val pricePerCard: Double get() = PriceMath.perUnit(totalPrice, totalCards)
}

object PriceMath {
    fun withTax(price: Double, taxPercent: Double): Double = price * (1 + taxPercent / 100.0)

    fun perUnit(total: Double, units: Int): Double = if (units > 0) total / units else 0.0
}

const val CUSTOM_PRESET = "Custom"

/** Products available for a set, always ending with "Custom". */
fun presetsFor(set: PokemonSet): List<ProductPreset> {
    val products = set.products ?: englishProducts(set)
    return products + ProductPreset(CUSTOM_PRESET, 1)
}

/** Cards in one pack of [preset] for this set. */
fun cardsPerPackFor(set: PokemonSet, preset: ProductPreset): Int = preset.cardsPerPack ?: set.cardsPerPack

/** Scarlet & Violet onward ETBs hold 9 packs; older ones hold 8. */
private fun englishProducts(set: PokemonSet): List<ProductPreset> {
    val etbPacks = if (set.series == Series.SCARLET_VIOLET || set.series == Series.MEGA_EVOLUTION) 9 else 8
    return buildList {
        add(ProductPreset("Single Pack", 1))
        add(ProductPreset("3-Pack Blister", 3))
        add(ProductPreset("Booster Bundle", 6))
        add(ProductPreset("Elite Trainer Box", etbPacks))
        if (!set.special) add(ProductPreset("Booster Box", 36))
    }
}

/** Products for a user-added set, based on how that language is usually sold. */
fun customSetProducts(language: Language): List<ProductPreset>? = when (language) {
    Language.ENGLISH -> null
    Language.JAPANESE -> listOf(ProductPreset("Single Pack", 1), ProductPreset("Booster Box", 30))
    Language.CHINESE_SIMPLIFIED -> ChineseSets.boosterProducts.map {
        // The card count entered for the set applies to regular packs; jumbo packs stay at 20.
        if (it.cardsPerPack == 5) it.copy(cardsPerPack = null) else it
    }
}
