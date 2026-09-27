package com.cardprice.app.data.collection

import com.cardprice.app.data.Language
import com.cardprice.app.data.SetArtwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetSubsetsTest {
    private fun card(number: String, name: String) = CollectionCard("30th-$number", number, name, null, null, emptyList())

    @Test
    fun thirtyPikachuAreNumbers23To52() {
        val pikachu = SetSubsets.forSet(Language.ENGLISH, "30th").single()
        assertEquals("30 Pikachu", pikachu.label)
        assertEquals(30, pikachu.numbers.count())
        assertTrue(pikachu.contains(card("023", "Pikachu")))
        assertTrue(pikachu.contains(card("052", "Pikachu")))
        // Pikachu ex cards sit outside the subset.
        assertTrue(!pikachu.contains(card("053", "Pikachu ex")))
        assertTrue(!pikachu.contains(card("149", "Pikachu ex")))
    }

    @Test
    fun classicCollectionCardsNeverLeakInto30thCelebration() {
        val fromFilter = listOf(
            CollectionCard("30th-001", "001", "Exeggcute", null, null, emptyList()),
            CollectionCard("30th-c-001", "001", "Charizard", null, null, emptyList()),
            CollectionCard("30th-c-030", "030", "Magikarp", null, null, emptyList()),
        )
        assertEquals(listOf("30th-001"), TcgdexApi.cardsOfSet(fromFilter, "30th").map { it.id })
        assertEquals(listOf("30th-c-001", "30th-c-030"), TcgdexApi.cardsOfSet(fromFilter, "30th-c").map { it.id })
    }

    @Test
    fun classicCollectionHasNoSubsetsAndOtherSetsNone() {
        assertTrue(SetSubsets.forSet(Language.ENGLISH, "30th-c").isEmpty())
        assertTrue(SetSubsets.forSet(Language.ENGLISH, "sv03.5").isEmpty())
    }

    @Test
    fun calculatorSetsHavePackArt() {
        assertNotNull(SetArtwork.packImage("me-30th-celebration"))
        assertNotNull(SetArtwork.packImage("sv2a-pokemon-card-151"))
        assertEquals(null, SetArtwork.packImage(null))
    }
}
