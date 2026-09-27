package com.cardprice.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetSearchTest {
    private val all = SetCatalog.sets + JapaneseSets.sets + ChineseSets.sets

    @Test
    fun findsSetsByNameCodeAndLocalName() {
        assertEquals("me5", SetSearch.find(all, "pitch").first().id)
        assertEquals("me5", SetSearch.find(all, "Pitch Black").first().id)
        assertEquals("SV2a", SetSearch.find(all, "sv2a").first().code)
        assertEquals("CSV8C", SetSearch.find(all, "csv8c").first().code)
        assertEquals("CSV8C", SetSearch.find(all, "璀璨诡幻").first().code)
    }

    @Test
    fun exactMatchesComeFirstAndEveryWordMustMatch() {
        // "151" is the English set's whole name; Japanese "Pokemon Card 151" follows it.
        val results = SetSearch.find(all, "151")
        assertEquals("sv3pt5", results.first().id)
        assertTrue(results.any { it.code == "SV2a" })
        assertTrue(SetSearch.find(all, "pitch white").isEmpty())
        assertTrue(SetSearch.find(all, "   ").isEmpty())
    }

    @Test
    fun setsLinkToTheirCollectionCardLists() {
        assertEquals("me05", SetSearch.collectionSetId(all.first { it.id == "me5" }))
        assertEquals("sv03.5", SetSearch.collectionSetId(all.first { it.id == "sv3pt5" }))
        assertEquals("SV2a", SetSearch.collectionSetId(all.first { it.code == "SV2a" }))
        assertEquals("CSV8C", SetSearch.collectionSetId(all.first { it.code == "CSV8C" }))
        assertEquals("M1S", SetSearch.collectionSetId(all.first { it.code == "m1S" }))
        assertEquals("M6a", SetSearch.collectionSetId(all.first { it.code == "M6a" }))
        // Not on TCGdex: no card list to open.
        assertEquals(null, SetSearch.collectionSetId(all.first { it.code == "151C" }))
    }
}
