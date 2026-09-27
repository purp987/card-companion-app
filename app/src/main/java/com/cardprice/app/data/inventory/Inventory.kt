package com.cardprice.app.data.inventory

import com.cardprice.app.data.Language

/** What an inventory item is: a single card or a sealed product (pack, box, ETB…). */
enum class InventoryKind(val label: String) {
    CARD("Card"),
    SEALED("Sealed"),
}

/** Where an item stands: on hand, up for sale, or gone. */
enum class InventoryStatus(val label: String) {
    IN_STOCK("In stock"),
    LISTED("Listed"),
    SOLD("Sold"),
}

/** Raw card condition, TCGplayer's scale. */
enum class CardCondition(val short: String, val label: String) {
    NM("NM", "Near Mint"),
    LP("LP", "Lightly Played"),
    MP("MP", "Moderately Played"),
    HP("HP", "Heavily Played"),
    DMG("DMG", "Damaged"),
}

/**
 * Stock held to sell or trade (separate from the collection, which is what you keep). A stack of
 * identical copies is one item with a [quantity]; selling part of a stack splits off a SOLD item.
 * Money is per copy, in US dollars.
 */
data class InventoryItem(
    val id: String,
    val kind: InventoryKind,
    val name: String,
    val setName: String? = null,
    val language: Language = Language.ENGLISH,
    /** Printed card number, e.g. "111" or "TG05". */
    val number: String? = null,
    /** TCGplayer product, for pictures and market prices; null for items entered by hand. */
    val productId: Long? = null,
    val image: String? = null,
    /** Version or printing, e.g. "Reverse Holo" or "1st Edition". */
    val finish: String? = null,
    val condition: CardCondition? = null,
    /** Graded cards, e.g. "PSA 10". */
    val grade: String? = null,
    val quantity: Int = 1,
    /** What one copy cost you. */
    val costEach: Double? = null,
    /** TCGplayer market price of one copy, and when it was last updated. */
    val marketPrice: Double? = null,
    val priceUpdatedAt: Long? = null,
    /** What you're asking for one copy. */
    val askingPrice: Double? = null,
    val status: InventoryStatus = InventoryStatus.IN_STOCK,
    /** Where it's kept, e.g. "Binder 2" or "Shoebox A". */
    val location: String? = null,
    val notes: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** What one copy sold for, and when. */
    val soldPriceEach: Double? = null,
    val soldAt: Long? = null,
    /**
     * Set on items that come from the collection ("LANGUAGE|set|card|version"): their quantity
     * follows the collection (see [CollectionLink]); only the extra details are saved here.
     */
    val collectionKey: String? = null,
) {
    val fromCollection: Boolean get() = collectionKey != null

    val totalCost: Double? get() = costEach?.times(quantity)
    val totalMarket: Double? get() = marketPrice?.times(quantity)

    /** For items still held: market value minus cost (profit on paper). */
    val unrealized: Double? get() = if (status == InventoryStatus.SOLD || costEach == null || marketPrice == null) null else (marketPrice - costEach) * quantity

    /** For sold items: what they sold for minus what they cost. */
    val realized: Double? get() = if (status != InventoryStatus.SOLD || soldPriceEach == null) null else (soldPriceEach - (costEach ?: 0.0)) * quantity

    /** "Base Set · #4 · NM · Holo". */
    val details: String
        get() = listOfNotNull(setName, number?.let { "#$it" }, grade ?: condition?.short, finish).joinToString(" · ")
}

/** Totals across items: held stock at cost and at market, and what sold stock brought in. */
data class InventorySummary(
    val heldUnits: Int,
    val heldCost: Double,
    val heldMarket: Double,
    /** Held units that have no market price yet (left out of [heldMarket]). */
    val unpricedUnits: Int,
    val soldUnits: Int,
    val soldRevenue: Double,
    val realizedProfit: Double,
    /** Market value minus cost, only for held items that have both (collection cards usually have no cost). */
    val unrealizedProfit: Double,
) {
    companion object {
        fun of(items: List<InventoryItem>): InventorySummary {
            val held = items.filter { it.status != InventoryStatus.SOLD }
            val sold = items.filter { it.status == InventoryStatus.SOLD }
            val priced = held.filter { it.marketPrice != null }
            return InventorySummary(
                heldUnits = held.sumOf { it.quantity },
                heldCost = priced.sumOf { it.totalCost ?: 0.0 },
                heldMarket = priced.sumOf { it.totalMarket ?: 0.0 },
                unpricedUnits = held.filter { it.marketPrice == null }.sumOf { it.quantity },
                soldUnits = sold.sumOf { it.quantity },
                soldRevenue = sold.sumOf { (it.soldPriceEach ?: 0.0) * it.quantity },
                realizedProfit = sold.sumOf { it.realized ?: 0.0 },
                unrealizedProfit = held.sumOf { it.unrealized ?: 0.0 },
            )
        }
    }
}

object InventoryOps {
    /**
     * Sells [count] copies of [item] at [priceEach]. The whole stack becomes SOLD, or, when only part
     * of it sells, a SOLD item is split off ([newId]) and the rest stays as it was.
     * Returns the items that replace [item].
     */
    fun sell(item: InventoryItem, count: Int, priceEach: Double?, now: Long, newId: String): List<InventoryItem> {
        val n = count.coerceIn(1, item.quantity)
        // A sale is a record of its own, no longer tied to the collection.
        val sold = item.copy(status = InventoryStatus.SOLD, quantity = n, soldPriceEach = priceEach, soldAt = now, collectionKey = null)
        if (item.fromCollection) return listOf(sold.copy(id = newId))
        return if (n == item.quantity) listOf(sold) else listOf(item.copy(quantity = item.quantity - n), sold.copy(id = newId))
    }

    /** Items matching a search: name, set, number, finish, grade, location or notes. */
    fun matches(item: InventoryItem, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val text = listOfNotNull(item.name, item.setName, item.number, item.finish, item.grade, item.location, item.notes)
            .joinToString(" ").lowercase()
        return q.split(Regex("\\s+")).all { text.contains(it.removePrefix("#")) }
    }
}
