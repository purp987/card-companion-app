package com.cardprice.app.data

import com.cardprice.app.data.market.PriceChartingApi
import com.cardprice.app.data.market.ProductKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageSetsTest {
    private val all = SetCatalog.sets + JapaneseSets.sets + ChineseSets.sets

    @Test
    fun idsAreUniqueAcrossLanguages() {
        val ids = all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everySetEndsWithCustomAndHasPositiveCounts() {
        all.forEach { set ->
            val presets = presetsFor(set)
            assertEquals(CUSTOM_PRESET, presets.last().label)
            presets.forEach { assertTrue("${set.id} ${it.label}", it.packs > 0 && cardsPerPackFor(set, it) > 0) }
        }
    }

    @Test
    fun japaneseSetsUseJapaneseProductLine() {
        assertEquals(98, JapaneseSets.sets.size)
        JapaneseSets.sets.forEach {
            assertEquals(Language.JAPANESE, it.language)
            assertTrue(it.id, it.tcgSlug != null && it.code != null)
            assertEquals(listOf("Single Pack", "Booster Box", CUSTOM_PRESET), presetsFor(it).map { p -> p.label })
        }
        assertEquals("pokemon-japan", Language.JAPANESE.tcgProductLine)
    }

    @Test
    fun japaneseBoxContentsMatchKnownFormats() {
        fun box(code: String) = JapaneseSets.sets.first { it.code == code }.let { set ->
            presetsFor(set).first { it.label == "Booster Box" }.packs to set.cardsPerPack
        }
        assertEquals(30 to 5, box("SV9a"))    // standard expansion
        assertEquals(10 to 10, box("SV4a"))   // High Class pack
        assertEquals(20 to 7, box("SV2a"))    // Pokémon Card 151
        assertEquals(20 to 6, box("S9a"))     // corrected from TCGplayer's description
    }

    @Test
    fun chineseBoostersOfferSlimAndJumbo() {
        val set = ChineseSets.sets.first { it.code == "CSV10C" }
        val cards = presetsFor(set).dropLast(1).associate { it.label to (it.packs * cardsPerPackFor(set, it)) }
        assertEquals(mapOf("Slim Pack" to 5, "Slim Box" to 75, "Jumbo Pack" to 20, "Jumbo Box" to 120), cards)
        assertEquals("补充包 共逐荣光", set.localName)
        assertNull(Language.CHINESE_SIMPLIFIED.tcgProductLine)
    }

    @Test
    fun gemPackBoxSizeChangesAfterVolumeTwo() {
        fun boxPacks(vol: Int) = presetsFor(ChineseSets.sets.first { it.code == "CBB${vol}C" })
            .first { it.label == "Booster Box" }.packs
        assertEquals(15, boxPacks(2))
        assertEquals(18, boxPacks(3))
    }

    @Test
    fun customChineseSetUsesEnteredCardsForRegularPacks() {
        val set = PokemonSet("custom_1", "Promo", Series.CUSTOM, 2026, cardsPerPack = 6).withLanguage(Language.CHINESE_SIMPLIFIED)
        val presets = presetsFor(set)
        assertEquals(6, cardsPerPackFor(set, presets.first { it.label == "Slim Pack" }))
        assertEquals(20, cardsPerPackFor(set, presets.first { it.label == "Jumbo Pack" }))
    }

    @Test
    fun priceChartingKeepsLanguagesApart() {
        val json = """{"status":"success","products":[
            {"product-name":"Booster Box","console-name":"Pokemon Japanese Black Bolt","loose-price":15000},
            {"product-name":"Booster Box","console-name":"Pokemon Black Bolt","loose-price":9000}]}"""
        assertEquals(90.0, PriceChartingApi.parseBestMatch(json, "Black Bolt", ProductKind.BOOSTER_BOX)!!.price!!, 0.001)
        assertEquals(
            150.0,
            PriceChartingApi.parseBestMatch(json, "Black Bolt", ProductKind.BOOSTER_BOX, Language.JAPANESE)!!.price!!,
            0.001,
        )
    }
}
