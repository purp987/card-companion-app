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
