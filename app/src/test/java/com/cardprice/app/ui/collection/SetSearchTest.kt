package com.cardprice.app.ui.collection

import com.cardprice.app.data.collection.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Test

class SetSearchTest {
    private val cards = listOf(
        CollectionCard("me05-002", "002", "Grubbin", null, null, emptyList()),
        CollectionCard("me05-111", "111", "Misty's Vitality", null, null, emptyList()),
        CollectionCard("me05-080", "080", "Misty's Vitality", null, null, emptyList()),
        CollectionCard("swsh9-TG05", "TG05", "Pikachu VMAX", null, null, emptyList()),
    )

    private fun find(q: String) = cards.filter { it.matchesQuery(q) }.map { it.id }

    @Test
    fun numbersInAnyPrintedForm() {
        assertEquals(listOf("me05-111"), find("111"))
        assertEquals(listOf("me05-111"), find("#111"))
        assertEquals(listOf("me05-111"), find("111/084"))
        assertEquals(listOf("me05-002"), find("2"))
        assertEquals(listOf("swsh9-TG05"), find("TG05"))
    }

    @Test
    fun namesMatchAnyPart() {
        assertEquals(listOf("me05-111", "me05-080"), find("misty"))
        assertEquals(listOf("swsh9-TG05"), find("pikachu vmax"))
    }

    @Test
    fun emptyQueryShowsEverything() {
        assertEquals(4, find("  ").size)
    }
}
