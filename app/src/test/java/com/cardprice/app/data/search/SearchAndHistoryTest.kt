package com.cardprice.app.data.search

import com.cardprice.app.data.Language
import com.cardprice.app.data.scan.ScanAction
import com.cardprice.app.data.scan.ScanHistoryEntry
import com.cardprice.app.data.scan.ScanHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchAndHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun slugsMatchTcgplayerSetUrls() {
        assertEquals("swsh07-evolving-skies", CardSearch.slug("SWSH07 Evolving Skies"))
        assertEquals("sm-cosmic-eclipse", CardSearch.slug("SM Cosmic Eclipse"))
        assertEquals("me-30th-celebration-classic-collection", CardSearch.slug("ME 30th Celebration Classic Collection"))
        assertEquals("sv-scarlet-and-violet-151", CardSearch.slug("SV Scarlet & Violet 151"))
    }

    @Test
    fun parsesResultsAndMapsToCollectionSets() {
        val json = """{"results":[{"results":[
            {"productId":684332.0,"productName":"Poke Pad - 081/088","setName":"ME03: Perfect Order","setUrlName":"ME03 Perfect Order","rarityName":"Uncommon","marketPrice":0.31,"customAttributes":{"number":"081/088"}},
            {"productId":717831.0,"productName":"Code Card - 30th Celebration Booster Pack","setName":"ME: 30th Celebration","setUrlName":"ME 30th Celebration","rarityName":"Code Card","marketPrice":0.06,"customAttributes":{"number":""}},
            {"productId":1.0,"productName":"Pikachu","setName":"SWSH07: Evolving Skies","setUrlName":"SWSH07 Evolving Skies","rarityName":"Common","marketPrice":null,"customAttributes":{"number":"049/203"}},
            {"productId":2.0,"productName":"Pikachu","setName":"McDonald's Promos 2016","setUrlName":"McDonalds Promos 2016","rarityName":"Promo","marketPrice":1.0,"customAttributes":{"number":null}}]}]}"""
        val hits = CardSearch.parse(json, Language.ENGLISH)
        assertEquals(3, hits.size) // code card dropped
        assertEquals("Poke Pad", hits[0].name)
        assertEquals("081", hits[0].number)
        assertEquals("me03", hits[0].tcgdexSetId)
        assertEquals("swsh7", hits[1].tcgdexSetId)
        assertNull(hits[1].marketPrice)
        assertNull(hits[2].tcgdexSetId) // promos open on TCGplayer
        assertNull(hits[2].number)
    }

    @Test
    fun japaneseResultsMapToJapaneseSets() {
        assertEquals("SV2a", TcgSetMap.japanese["sv2a-pokemon-card-151"])
        assertEquals("M6a", TcgSetMap.japanese["m6a-mega-expansion-30th-celebration"])
    }

    @Test
    fun historyIsNewestFirstAndCapped() {
        val store = ScanHistoryStore(tmp.root)
        fun entry(i: Int) = ScanHistoryEntry(
            at = i.toLong(), action = ScanAction.AUTO_ADDED, language = Language.ENGLISH, setId = "me05", setName = "Pitch Black",
            cardId = "me05-111", number = "111", cardName = "Misty's Vitality", image = null,
            variantKey = "holo", variantLabel = "Holo", delta = 1,
        )
        repeat(ScanHistoryStore.MAX + 5) { store.append(entry(it)) }
        val loaded = ScanHistoryStore(tmp.root).load()
        assertEquals(ScanHistoryStore.MAX, loaded.size)
        assertEquals((ScanHistoryStore.MAX + 4).toLong(), loaded.first().at)
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun manyEntriesLoggedAtOnceAreAllKept() {
        val store = ScanHistoryStore(tmp.root)
        val threads = (1..50).map { i ->
            Thread {
                store.append(
                    ScanHistoryEntry(
                        at = i.toLong(), action = ScanAction.ADDED, language = Language.ENGLISH, setId = "me05", setName = "Pitch Black",
                        cardId = "me05-$i", number = "$i", cardName = "Card $i", image = null,
                        variantKey = "normal", variantLabel = "Normal", delta = 1,
                    ),
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(50, ScanHistoryStore(tmp.root).load().size)
    }
}
