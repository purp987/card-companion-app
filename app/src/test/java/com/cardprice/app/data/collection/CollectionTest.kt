package com.cardprice.app.data.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionTest {
    @Test
    fun variantLabelsReadNaturally() {
        assertEquals("Normal", Variants.of("normal", null, emptyList(), null).label)
        assertEquals("Reverse Holo", Variants.of("reverse", null, emptyList(), null).label)
        assertEquals("Poké Ball Reverse Holo", Variants.of("reverse", null, emptyList(), "pokeball").label)
        assertEquals("Master Ball Reverse Holo", Variants.of("reverse", null, emptyList(), "masterball").label)
        assertEquals("Cosmos Holo", Variants.of("holo", null, emptyList(), "cosmos").label)
        assertEquals("Shadowless Holo · 1st Edition", Variants.of("holo", "shadowless", listOf("1st-edition"), null).label)
        assertEquals("Normal · Set Logo Stamp", Variants.of("normal", null, listOf("set-logo"), null).label)
    }

    @Test
    fun variantKeysAreDistinctAndStable() {
        val a = Variants.of("reverse", null, emptyList(), "pokeball")
        val b = Variants.of("reverse", null, emptyList(), null)
        assertEquals("reverse:foil=pokeball", a.key)
        assertEquals("reverse", b.key)
        // Stamp order doesn't change the key.
        assertEquals(
            Variants.of("holo", null, listOf("b", "a"), null).key,
            Variants.of("holo", null, listOf("a", "b"), null).key,
        )
    }

    @Test
    fun parsesGraphqlCardsWithDetailedVariants() {
        val json = """{"data":{"cards":[{"id":"sv08.5-001","localId":"001","name":"Exeggcute","rarity":"Common",
            "image":"https://assets.tcgdex.net/en/sv/sv08.5/001",
            "variants":{"normal":true,"reverse":true,"holo":false,"firstEdition":false},
            "variants_detailed":[{"type":"normal","subtype":null,"stamp":null,"foil":null},
              {"type":"reverse","subtype":null,"stamp":null,"foil":null},
              {"type":"reverse","subtype":null,"stamp":null,"foil":"pokeball"},
              {"type":"reverse","subtype":null,"stamp":null,"foil":"masterball"}]}]}}"""
        val card = TcgdexApi.parseGraphqlCards(json).single()
        assertEquals("001", card.number)
        assertEquals(
            listOf("Normal", "Reverse Holo", "Poké Ball Reverse Holo", "Master Ball Reverse Holo"),
            card.variants.map { it.label },
        )
    }

    @Test
    fun fallsBackToVariantFlags() {
        val json = """{"data":{"cards":[{"id":"x-1","localId":"1","name":"A","rarity":null,"image":null,
            "variants":{"normal":false,"reverse":true,"holo":true,"firstEdition":false},"variants_detailed":[]}]}}"""
        val card = TcgdexApi.parseGraphqlCards(json).single()
        assertEquals(listOf("Holo", "Reverse Holo"), card.variants.map { it.label })
        assertNull(card.rarity)
        assertNull(card.image)
    }

    @Test
    fun seriesListNewestSetFirst() {
        val json = """{"id":"sv","name":"Scarlet & Violet","sets":[
            {"id":"sv01","name":"Scarlet & Violet","cardCount":{"official":198,"total":258}},
            {"id":"sv02","name":"Paldea Evolved","cardCount":{"official":193,"total":279}}]}"""
        val series = TcgdexApi.parseSeries(json)
        assertEquals(listOf("sv02", "sv01"), series.sets.map { it.id })
        assertEquals(279, series.sets.first().totalCount)
    }

    @Test
    fun japaneseSetsSortNewestByCode() {
        val ids = listOf("SV1V", "SV2a", "SV-P", "SV11B", "SV9", "SV1a", "SV10", "SV9a", "SV1S")
        val sorted = ids.map { CardSet(it, it, 0, 0, null, null) }.sortedWith(TcgdexApi.newestByCode).map { it.id }
        assertEquals(listOf("SV11B", "SV10", "SV9a", "SV9", "SV2a", "SV1a", "SV1V", "SV1S", "SV-P"), sorted)
    }

    @Test
    fun mainlandChineseCatalogSkipsTraditionalAndDuplicates() {
        val json = """[
            {"id":"CSV1C","name":"亘古开来","cardCount":{"official":127,"total":127}},
            {"id":"CSV1C","name":"宝石包 第一卷","cardCount":{"official":9,"total":9}},
            {"id":"CSV9.5C","name":"太晶盛聚","cardCount":{"official":208,"total":208}},
            {"id":"CBB2C","name":"宝石包Vol.2","cardCount":{"official":15,"total":15}},
            {"id":"CS1aC","name":"横空出世 赫","cardCount":{"official":135,"total":135}},
            {"id":"csm1a","name":"风暴涌现","cardCount":{"official":211,"total":211}},
            {"id":"SV9","name":"對戰搭檔","cardCount":{"official":100,"total":100}}]"""
        val series = TcgdexApi.mainlandChineseSeries(json)
        assertEquals(listOf("SV", "S", "SM"), series.map { it.id })
        assertEquals(listOf("CSV9.5C", "CBB2C", "CSV1C"), series[0].sets.map { it.id })
        assertEquals("亘古开来", series[0].sets.last().name)
        assertEquals(listOf("csm1a"), series[2].sets.map { it.id })
    }

    @Test
    fun pricesPreferTcgplayerThenCardmarket() {
        val json = """{"id":"sv08.5-001","pricing":{"tcgplayer":{"unit":"USD","normal":{"marketPrice":0.12},"reverse-holofoil":{"marketPrice":0.4}}},
            "variants_detailed":[
              {"type":"normal","pricing":{"tcgplayer":{"marketPrice":null},"cardmarket":{"trend":0.03}}},
              {"type":"reverse","foil":"pokeball","pricing":{"tcgplayer":{"marketPrice":null},"cardmarket":{"trend":0.25}}},
              {"type":"reverse","foil":"masterball","pricing":{"tcgplayer":{"marketPrice":3.5},"cardmarket":{"trend":2}}}]}"""
        val prices = TcgdexApi.parseCardPrices(json).byVariant
        assertEquals(VariantPrice(0.12, "USD", "TCGplayer"), prices["normal"])
        assertEquals(VariantPrice(0.25, "EUR", "Cardmarket"), prices["reverse:foil=pokeball"])
        assertEquals(VariantPrice(3.5, "USD", "TCGplayer"), prices["reverse:foil=masterball"])
    }

    @Test
    fun cardCacheRoundTrips() {
        val cards = listOf(
            CollectionCard("sv03.5-199", "199", "Charizard ex", "Special illustration rare", "https://x/199",
                listOf(Variants.of("holo", null, emptyList(), null))),
            CollectionCard("SV2a-001", "001", "フシギダネ", null, null, Variants.fromFlags(true, true, false, false)),
        )
        assertEquals(cards, CollectionStore.cardsFromJson(CollectionStore.cardsToJson(cards)))
        val series = listOf(CardSeries("sv", "Scarlet & Violet", listOf(CardSet("sv03.5", "151", 165, 207, null, "https://s"))))
        assertEquals(series, CollectionStore.seriesFromJson(CollectionStore.seriesToJson(series)))
    }
}
