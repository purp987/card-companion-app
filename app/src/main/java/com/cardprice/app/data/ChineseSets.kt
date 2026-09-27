package com.cardprice.app.data

import com.cardprice.app.data.Series.GEM_PACK
import com.cardprice.app.data.Series.SCARLET_VIOLET

/**
 * Simplified Chinese releases from the Scarlet & Violet era onward, newest first.
 * Set list and dates: pikaqian.com/sets; Chinese names: pokemonlore.com;
 * box contents: retailer listings (japan2uk.com, pokeunlimited.com). TCGplayer doesn't sell
 * Simplified Chinese products, so these sets have no market prices.
 */
object ChineseSets {
    /** Regular boosters come as 5-card packs (15 per slim box) or 20-card jumbo packs (6 per jumbo box). */
    val boosterProducts = listOf(
        ProductPreset("Slim Pack", 1, cardsPerPack = 5),
        ProductPreset("Slim Box", 15, cardsPerPack = 5),
        ProductPreset("Jumbo Pack", 1, cardsPerPack = 20),
        ProductPreset("Jumbo Box", 6, cardsPerPack = 20),
    )

    private fun booster(code: String, name: String, localName: String, year: Int) = PokemonSet(
        id = "cn_" + code.lowercase(),
        name = name,
        series = SCARLET_VIOLET,
        year = year,
        cardsPerPack = 5,
        language = Language.CHINESE_SIMPLIFIED,
        code = code,
        localName = localName,
        products = boosterProducts,
    )

    /** Gem Packs: 4 all-foil cards per pack; Vol. 1–2 boxes hold 15 packs, later ones 18. */
    private fun gemPack(volume: Int, year: Int) = PokemonSet(
        id = "cn_cbb${volume}c",
        name = "Gem Pack Vol. $volume",
        series = GEM_PACK,
        year = year,
        cardsPerPack = 4,
        language = Language.CHINESE_SIMPLIFIED,
        code = "CBB${volume}C",
        localName = "宝石包 VOL.$volume",
        products = listOf(ProductPreset("Single Pack", 1), ProductPreset("Booster Box", if (volume <= 2) 15 else 18)),
    )

    val sets: List<PokemonSet> = listOf(
        PokemonSet(
            id = "cn_30thc", name = "30th Celebration", series = SCARLET_VIOLET, year = 2026, cardsPerPack = 6,
            language = Language.CHINESE_SIMPLIFIED, code = "30THC", localName = "补充包 30周年庆典",
            products = listOf(ProductPreset("Single Pack", 1), ProductPreset("Booster Box", 20)),
        ),
        booster("CSV10C", "Chasing Glory Together", "补充包 共逐荣光", 2026),
        // Sold in 10-pack boxes; cards per pack not confirmed, so the standard 5 is used (editable in the calculator).
        PokemonSet(
            id = "cn_csv9.5c", name = "Terastal Gathering", series = SCARLET_VIOLET, year = 2026, cardsPerPack = 5,
            language = Language.CHINESE_SIMPLIFIED, code = "CSV9.5C", localName = "补充包 太晶盛聚",
            products = listOf(ProductPreset("Single Pack", 1), ProductPreset("Booster Box", 10)),
        ),
        booster("CSV9C", "Stellar Crystal", "补充包 星彩晶璃", 2026),
        booster("CSV8C", "Sparkling Fantasy", "补充包 璀璨诡幻", 2026),
        booster("CSV7C", "Blade Awakened", "补充包 利刃猛醒", 2026),
        booster("CSV6C", "Arcane Truth", "补充包 真实玄虚", 2025),
        booster("151C", "Collect 151", "收集啦151", 2025),
        booster("CSV5C", "Dark Crystal Blaze", "补充包 黑晶炽诚", 2025),
        booster("CSV4C", "Bonus Round", "补充包 嘉奖回合", 2025),
        booster("CSV3C", "Fearless Terastal", "补充包 无畏太晶", 2025),
        booster("CSV2C", "Miracle Journey", "补充包 奇迹启程", 2025),
        booster("CSV1C", "Eternal Birth", "补充包 亘古开来", 2025),

        gemPack(6, 2026),
        gemPack(5, 2026),
        gemPack(4, 2026),
        gemPack(3, 2025),
        gemPack(2, 2025),
        gemPack(1, 2025),
    )
}
