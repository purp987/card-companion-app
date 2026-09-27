package com.cardprice.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingTest {
    private fun set(id: String) = SetCatalog.sets.first { it.id == id }

    @Test
    fun perUnit_dividesTotal() {
        assertEquals(0.5554, PriceMath.perUnit(49.99, 90), 0.0001)
    }

    @Test
    fun perUnit_zeroUnits_isZeroNotNaN() {
        assertEquals(0.0, PriceMath.perUnit(10.0, 0), 0.0)
    }

    @Test
    fun withTax_addsPercentage() {
        assertEquals(107.25, PriceMath.withTax(100.0, 7.25), 0.0001)
        assertEquals(100.0, PriceMath.withTax(100.0, 0.0), 0.0)
    }

    @Test
    fun purchase_derivesTotals() {
        val p = Purchase(1, "sv8", "Booster Box", quantity = 2, packsPerProduct = 36, cardsPerPack = 10,
            totalPrice = 288.0, timestamp = 0)
        assertEquals(72, p.totalPacks)
        assertEquals(720, p.totalCards)
        assertEquals(0.4, p.pricePerCard, 0.0001)
    }

    @Test
    fun etbPackCount_dependsOnEra() {
        fun etb(s: PokemonSet) = presetsFor(s).first { it.label == "Elite Trainer Box" }.packs
        assertEquals(9, etb(set("sv8")))
        assertEquals(9, etb(set("me1")))
        assertEquals(8, etb(set("swsh7")))
        assertEquals(8, etb(set("xy12")))
    }

    @Test
    fun specialSets_haveNoBoosterBox() {
        assertFalse(presetsFor(set("sv3pt5")).any { it.label == "Booster Box" })
        assertTrue(presetsFor(set("sv8")).any { it.label == "Booster Box" && it.packs == 36 })
    }

    @Test
    fun customIsAlwaysLastPreset() {
        // The calculator switches to the last preset when packs are edited by hand.
        SetCatalog.sets.forEach { assertEquals("Custom", presetsFor(it).last().label) }
    }

    @Test
    fun catalog_idsAreUnique_andCardCountsPositive() {
        val ids = SetCatalog.sets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(SetCatalog.sets.all { it.cardsPerPack > 0 })
        assertTrue(SetCatalog.sets.none { it.series == Series.CUSTOM })
    }
}
