package com.cardprice.app.data.market

/** Maps the calculator's product presets to how sealed products are named on TCGplayer and PriceCharting. */
enum class ProductKind(val presetLabel: String, val keyword: String) {
    BOOSTER_PACK("Single Pack", "booster pack"),
    BLISTER("3-Pack Blister", "3 pack blister"),
    BUNDLE("Booster Bundle", "booster bundle"),
    ETB("Elite Trainer Box", "elite trainer box"),
    BOOSTER_BOX("Booster Box", "booster box");

    fun matches(productName: String): Boolean {
        val name = productName.lowercase().replace('-', ' ')
        if (keyword !in name) return false
        if (EXCLUDED.any { it in name }) return false
        return when (this) {
            BOOSTER_PACK -> "sleeved" !in name && "bundle" !in name
            BOOSTER_BOX -> "half" !in name
            else -> true
        }
    }

    /** First match wins; TCGplayer's search already ranks by sales, so that's the mainstream version. */
    fun pick(products: List<MarketProduct>): MarketProduct? {
        val matches = products.filter { matches(it.name) }
        return matches.firstOrNull { it.marketPrice != null } ?: matches.firstOrNull()
    }

    companion object {
        // Cases, store exclusives and multi-item lots aren't what the calculator presets describe.
        private val EXCLUDED = listOf(
            "case", "display", "pokemon center", "exclusive", "set of", "art bundle",
            "dollar general", "sam's club", "+", "deluxe",
        )

        fun forPreset(label: String): ProductKind? = entries.firstOrNull { it.presetLabel == label }
    }
}
