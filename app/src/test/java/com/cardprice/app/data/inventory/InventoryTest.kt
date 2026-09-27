package com.cardprice.app.data.inventory

import com.cardprice.app.data.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InventoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val charizard = InventoryItem(
        id = "a", kind = InventoryKind.CARD, name = "Charizard ex", setName = "151", number = "199", productId = 504467,
        condition = CardCondition.NM, quantity = 3, costEach = 40.0, marketPrice = 55.0, location = "Binder 2",
    )
    private val box = InventoryItem(id = "b", kind = InventoryKind.SEALED, name = "Pitch Black Booster Box", quantity = 2, costEach = 150.0, marketPrice = null)

    @Test
    fun summaryCountsHeldAndSoldStock() {
        val sold = InventoryOps.sell(charizard, 1, 60.0, now = 10, newId = "c")
        val summary = InventorySummary.of(sold + box)
        assertEquals(4, summary.heldUnits) // 2 Charizards + 2 boxes
        assertEquals(2, summary.unpricedUnits) // the boxes have no price yet
        assertEquals(110.0, summary.heldMarket, 0.001)
        assertEquals(80.0, summary.heldCost, 0.001) // only priced items count towards paper profit
        assertEquals(30.0, summary.unrealizedProfit, 0.001)
        assertEquals(1, summary.soldUnits)
        assertEquals(60.0, summary.soldRevenue, 0.001)
        assertEquals(20.0, summary.realizedProfit, 0.001)
    }

    @Test
    fun sellingPartOfAStackSplitsOffASoldItem() {
        val result = InventoryOps.sell(charizard, 2, 58.0, now = 99, newId = "new")
        assertEquals(2, result.size)
        val (rest, sold) = result
        assertEquals("a", rest.id)
        assertEquals(1, rest.quantity)
        assertEquals(InventoryStatus.IN_STOCK, rest.status)
        assertEquals("new", sold.id)
        assertEquals(2, sold.quantity)
        assertEquals(InventoryStatus.SOLD, sold.status)
        assertEquals(99L, sold.soldAt)
        assertEquals(36.0, sold.realized!!, 0.001)
        assertNull(sold.unrealized)
        // Selling everything just changes the item's status.
        val all = InventoryOps.sell(charizard, 5, 50.0, now = 1, newId = "x").single()
        assertEquals("a", all.id)
        assertEquals(3, all.quantity)
    }

    @Test
    fun searchLooksAtNameSetNumberAndLocation() {
        assertTrue(InventoryOps.matches(charizard, "char 199"))
        assertTrue(InventoryOps.matches(charizard, "#199"))
        assertTrue(InventoryOps.matches(charizard, "binder"))
        assertFalse(InventoryOps.matches(charizard, "pikachu"))
        assertTrue(InventoryOps.matches(charizard, "  "))
    }

    @Test
    fun savedInventoryLoadsBackTheSame() {
        val items = listOf(charizard.copy(language = Language.JAPANESE, grade = "PSA 10", notes = "trade bait"), box)
        val store = InventoryStore(tmp.root)
        store.save(items)
        assertEquals(items, InventoryStore(tmp.root).load())
    }

    @Test
    fun damagedFileFallsBackToTheNewestBackup() {
        val store = InventoryStore(tmp.root)
        store.save(listOf(charizard), now = 1_000)
        store.save(listOf(charizard, box), now = 2_000) // backs up the first version
        File(tmp.root, "inventory/inventory.json").writeText("{ not json")
        assertEquals(listOf(charizard), InventoryStore(tmp.root).load())
        assertTrue(File(tmp.root, "inventory").listFiles()!!.any { it.name.startsWith("inventory.damaged-") })
    }
}
