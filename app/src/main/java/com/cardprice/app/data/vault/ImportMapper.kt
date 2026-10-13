package com.cardprice.app.data.vault

import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.SetProgress
import com.cardprice.app.data.inventory.InventoryItem
import com.cardprice.app.data.inventory.InventoryKind
import com.cardprice.app.data.inventory.InventoryStatus
import java.time.Instant
import java.time.ZoneId

/**
 * Turns the phone's old local data into `/api/app/import` items. The local files are only read,
 * never changed. Refs are stable, so running the import again updates instead of duplicating.
 */
object ImportMapper {
    /** Portfolio names the contract asks for. */
    const val COLLECTION = "Collection"
    const val INVENTORY = "Inventory"

    /**
     * One item per owned card version. [owned] is the collection file's map
     * ("LANGUAGE|setId|cardId|variantKey" → copies); [cards] returns a set's cached card list, if any.
     */
    fun collection(
        owned: Map<String, Int>,
        progress: List<SetProgress>,
        cards: (Language, String) -> List<CollectionCard>?,
    ): List<ImportItem> {
        val setNames = progress.associate { "${it.language.name}|${it.setId}" to it.setName }
        val cardCache = HashMap<String, Map<String, CollectionCard>>()
        return owned.entries.filter { it.value > 0 }.sortedBy { it.key }.mapNotNull { (key, count) ->
            val parts = key.split('|')
            if (parts.size < 4) return@mapNotNull null
            val language = Language.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            val setId = parts[1]
            val cardId = parts[2]
            val variantKey = parts.subList(3, parts.size).joinToString("|")
            val byId = cardCache.getOrPut("${language.name}|$setId") {
                cards(language, setId).orEmpty().associateBy { it.id }
            }
            val card = byId[cardId]
            ImportItem(
                ref = key,
                lang = VariantMap.langCode(language),
                setCode = setId,
                setName = setNames["${language.name}|$setId"],
                number = card?.number ?: numberFromCardId(cardId),
                name = card?.name,
                variant = VariantMap.subTypeFor(variantKey),
                qty = count,
                condition = null,
                grade = null,
                purchasePrice = null,
                addedOn = null,
                notes = null,
                sealed = false,
                productName = null,
            )
        }
    }

    /**
     * Inventory items still held. Sold records stay on the phone, and items mirrored from the
     * collection are left out (the collection import already covers them).
     */
    fun inventory(items: List<InventoryItem>, zone: ZoneId = ZoneId.systemDefault()): List<ImportItem> =
        items.filter { it.collectionKey == null && it.status != InventoryStatus.SOLD && it.quantity > 0 }.map { i ->
            val sealed = i.kind == InventoryKind.SEALED
            ImportItem(
                ref = "inventory:${i.id}",
                lang = VariantMap.langCode(i.language),
                setCode = null,
                setName = i.setName,
                number = i.number,
                name = i.name,
                variant = if (sealed) null else VariantMap.subTypeForLabel(i.finish),
                qty = i.quantity,
                condition = if (sealed) null else i.condition?.label,
                grade = i.grade,
                purchasePrice = i.costEach,
                addedOn = Instant.ofEpochMilli(i.addedAt).atZone(zone).toLocalDate().toString(),
                notes = listOfNotNull(
                    i.location?.let { "Location: $it" },
                    i.askingPrice?.let { "Asking %.2f USD".format(java.util.Locale.US, it) },
                    i.status.takeIf { it == InventoryStatus.LISTED }?.let { "Listed for sale" },
                    i.notes,
                ).joinToString(" · ").ifBlank { null },
                sealed = sealed,
                productName = if (sealed) i.name else null,
            )
        }

    /** "sv03.5-199" → "199"; "SV2a-025" → "025". */
    internal fun numberFromCardId(cardId: String): String = cardId.substringAfterLast('-')
}
