package com.cardprice.app.data.inventory

import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CardPrices
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.VariantPrice
import com.cardprice.app.data.collection.Variants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionLinkTest {
    private val grubbin = CollectionCard(
        "me05-002", "002", "Grubbin", "Common", "https://assets.tcgdex.net/en/me/me05/002",
        listOf(Variants.of("normal", null, emptyList(), null), Variants.of("reverse", null, emptyList(), null)),
    )
    private val owned = mapOf(
        "ENGLISH|me05|me05-002|normal" to 11,
        "ENGLISH|me05|me05-002|reverse" to 1,
        "ENGLISH|me05|me05-099|holo" to 2, // card details not loaded yet
    )
    private val prices = CardPrices(mapOf("normal" to VariantPrice(0.04, "USD", "TCGplayer"), "reverse" to VariantPrice(0.18, "EUR", "Cardmarket")))

    private fun derive(mode: CollectionMode, saved: Map<String, InventoryItem> = emptyMap()) = CollectionLink.derive(
        owned, mode,
        cards = { _, _, id -> grubbin.takeIf { it.id == id } },
        setNames = { _, _ -> "Pitch Black" },
        prices = { _, id -> prices.takeIf { id == "me05-002" } },
        saved = saved,
    )

    @Test
    fun wholeCollectionListsEveryCopy() {
        val items = derive(CollectionMode.ALL)
        assertEquals(3, items.size)
        val normal = items.first { it.collectionKey == "ENGLISH|me05|me05-002|normal" }
        assertEquals("col:ENGLISH|me05|me05-002|normal", normal.id)
        assertEquals("Grubbin", normal.name)
        assertEquals("Pitch Black", normal.setName)
        assertEquals(11, normal.quantity)
        assertEquals(0.04, normal.marketPrice!!, 0.0)
        assertTrue(normal.fromCollection)
        // Euro prices aren't mixed into the dollar value.
        assertNull(items.first { it.finish == "Reverse Holo" }.marketPrice)
        // A card whose set hasn't loaded yet still shows, by number.
        assertEquals("099", items.first { it.collectionKey!!.endsWith("holo") }.number)
    }

    @Test
    fun extrasOnlyKeepsOneOfEachVersionInTheCollection() {
        val items = derive(CollectionMode.EXTRAS)
        // Normal: 11 owned → 10 extra. Reverse: 1 owned → none. Holo: 2 → 1.
        assertEquals(mapOf("ENGLISH|me05|me05-002|normal" to 10, "ENGLISH|me05|me05-099|holo" to 1), items.associate { it.collectionKey!! to it.quantity })
        assertTrue(derive(CollectionMode.OFF).isEmpty())
    }

    @Test
    fun detailsAddedInInventoryAreKeptButTheCountFollowsTheCollection() {
        val key = "ENGLISH|me05|me05-002|normal"
        val saved = InventoryItem(
            id = CollectionLink.idFor(key), kind = InventoryKind.CARD, name = "old name", quantity = 1, collectionKey = key,
            costEach = 0.02, location = "Bulk box A", status = InventoryStatus.LISTED, condition = CardCondition.LP,
        )
        val item = derive(CollectionMode.ALL, mapOf(key to saved)).first { it.collectionKey == key }
        assertEquals(11, item.quantity)
        assertEquals("Grubbin", item.name)
        assertEquals("Bulk box A", item.location)
        assertEquals(InventoryStatus.LISTED, item.status)
        assertEquals(CardCondition.LP, item.condition)
        assertEquals((0.04 - 0.02) * 11, item.unrealized!!, 1e-9)
    }

    @Test
    fun sellingFromTheCollectionMakesAStandaloneSaleRecord() {
        val item = derive(CollectionMode.ALL).first { it.quantity == 11 }
        val sold = InventoryOps.sell(item, 4, 0.10, now = 5, newId = "sale-1").single()
        assertEquals("sale-1", sold.id)
        assertEquals(4, sold.quantity)
        assertEquals(InventoryStatus.SOLD, sold.status)
        assertNull(sold.collectionKey)
    }

    @Test
    fun summaryProfitOnlyCountsItemsWithACost() {
        val summary = InventorySummary.of(derive(CollectionMode.ALL))
        assertEquals(14, summary.heldUnits)
        assertEquals(0.44, summary.heldMarket, 1e-9)
        assertEquals(0.0, summary.unrealizedProfit, 0.0)
    }
}
