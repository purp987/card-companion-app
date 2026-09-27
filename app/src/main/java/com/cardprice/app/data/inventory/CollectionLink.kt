package com.cardprice.app.data.inventory

import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CardPrices
import com.cardprice.app.data.collection.CollectionCard

/**
 * How the inventory takes cards from the collection.
 *
 * The plan: the collection is what you keep, the inventory is everything else. For now the inventory
 * mirrors the whole collection ([ALL]); [EXTRAS] is the next step, where the collection keeps
 * [keepPerVersion] copies of each version and every copy past that is bulk to sell or trade.
 */
enum class CollectionMode(val label: String, val keepPerVersion: Int?) {
    ALL("Whole collection", null),
    EXTRAS("Extras only (bulk)", 1),
    OFF("Off", null),
}

/**
 * Inventory items that come from the collection. Their quantity always follows the collection; what
 * the collection doesn't know (cost, condition, asking price, where it's kept, status) is saved as an
 * inventory item with the same [InventoryItem.collectionKey] and laid over the top.
 */
object CollectionLink {
    const val ID_PREFIX = "col:"

    fun idFor(collectionKey: String) = ID_PREFIX + collectionKey

    /** "ENGLISH|me05|me05-001|reverse" → its parts, or null if it isn't a collection key. */
    fun parseKey(key: String): Parts? {
        val p = key.split('|')
        if (p.size != 4) return null
        val language = runCatching { Language.valueOf(p[0]) }.getOrNull() ?: return null
        return Parts(language, p[1], p[2], p[3])
    }

    data class Parts(val language: Language, val setId: String, val cardId: String, val variantKey: String)

    /**
     * Items for every owned card version: all copies ([CollectionMode.ALL]) or those past
     * [CollectionMode.keepPerVersion] ([CollectionMode.EXTRAS]).
     *
     * [cards] finds a card's details (null while its set is still loading: the item then shows its
     * number only), [setNames] gives set names, [prices] per-card prices, and [saved] the details the
     * user added, by collection key.
     */
    fun derive(
        owned: Map<String, Int>,
        mode: CollectionMode,
        cards: (Language, String, String) -> CollectionCard?,
        setNames: (Language, String) -> String?,
        prices: (Language, String) -> CardPrices?,
        saved: Map<String, InventoryItem>,
    ): List<InventoryItem> {
        if (mode == CollectionMode.OFF) return emptyList()
        return owned.entries.mapNotNull { (key, count) ->
            val parts = parseKey(key) ?: return@mapNotNull null
            val quantity = count - (mode.keepPerVersion ?: 0)
            if (quantity <= 0) return@mapNotNull null
            val card = cards(parts.language, parts.setId, parts.cardId)
            val variant = card?.variants?.firstOrNull { it.key == parts.variantKey }
            val price = prices(parts.language, parts.cardId)?.byVariant?.get(parts.variantKey)
            val base = InventoryItem(
                id = idFor(key),
                kind = InventoryKind.CARD,
                name = card?.name ?: "#${parts.cardId.substringAfterLast('-')}",
                setName = setNames(parts.language, parts.setId) ?: parts.setId,
                language = parts.language,
                number = card?.number ?: parts.cardId.substringAfterLast('-'),
                image = card?.takeIf { it.image != null }?.imageUrl("low"),
                finish = variant?.label ?: parts.variantKey,
                condition = CardCondition.NM,
                quantity = quantity,
                // Only USD prices count towards value; Cardmarket (EUR) ones would mix currencies.
                marketPrice = price?.takeIf { it.currency == "USD" }?.amount,
                collectionKey = key,
                addedAt = 0,
            )
            val extra = saved[key] ?: return@mapNotNull base
            base.copy(
                condition = extra.condition ?: base.condition,
                grade = extra.grade,
                costEach = extra.costEach,
                askingPrice = extra.askingPrice,
                status = if (extra.status == InventoryStatus.SOLD) InventoryStatus.IN_STOCK else extra.status,
                location = extra.location,
                notes = extra.notes,
                addedAt = extra.addedAt,
            )
        }.sortedWith(compareBy({ it.setName }, { it.number?.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }, { it.finish }))
    }
}
