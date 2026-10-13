package com.cardprice.app.data.vault

import com.cardprice.app.data.Language
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.Series
import com.cardprice.app.data.SetCatalog
import com.cardprice.app.data.collection.CardVariant
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.SetProgress
import com.cardprice.app.data.inventory.CardCondition
import com.cardprice.app.data.inventory.InventoryItem
import com.cardprice.app.data.inventory.InventoryKind
import com.cardprice.app.data.inventory.InventoryStatus
import com.cardprice.app.ui.vault.VaultLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class MappingTest {
    @Test
    fun variantKeysMapToTcgplayerPrintings() {
        assertEquals("Normal", VariantMap.subTypeFor("normal"))
        assertEquals("Holofoil", VariantMap.subTypeFor("holo"))
        assertEquals("Reverse Holofoil", VariantMap.subTypeFor("reverse:foil=pokeball"))
        assertEquals("1st Edition Holofoil", VariantMap.subTypeFor("holo:stamp=1st-edition"))
        assertNull(VariantMap.subTypeFor(null))
        assertEquals("Reverse Holofoil", VariantMap.subTypeForLabel("Reverse Holo"))
        assertEquals("1st Edition Normal", VariantMap.subTypeForLabel("1st Edition"))
    }

    @Test
    fun pickFindsTheServerVariant() {
        val vs = listOf(Variant(1, "Unlimited Holofoil", 5.0), Variant(2, "1st Edition Holofoil", 50.0), Variant(3, "Reverse Holofoil", 1.0))
        assertEquals(2L, VariantMap.pick(vs, "holo:stamp=1st-edition")?.id)
        assertEquals(1L, VariantMap.pick(vs, "holo")?.id) // not the reverse
        assertEquals(3L, VariantMap.pick(vs, "reverse")?.id)
        assertNull(VariantMap.pick(vs, "normal"))
        assertEquals(9L, VariantMap.pick(listOf(Variant(9, "Normal", 1.0)), "holo")?.id) // only one printing
    }

    @Test
    fun collectionImportUsesCachedCardsAndStableRefs() {
        val owned = mapOf(
            "ENGLISH|sv03.5|sv03.5-199|holo" to 2,
            "ENGLISH|sv03.5|sv03.5-001|reverse:foil=pokeball" to 1,
            "JAPANESE|SV2a|SV2a-025|normal" to 1,
            "ENGLISH|sv03.5|sv03.5-002|normal" to 0, // removed copies aren't sent
        )
        val progress = listOf(SetProgress(Language.ENGLISH, "sv03.5", "151", 207, 300, 2, 2, 0))
        val cards = mapOf(
            "sv03.5" to listOf(CollectionCard("sv03.5-199", "199", "Charizard ex", "SIR", null, listOf(CardVariant("holo", "Holo")))),
        )
        val items = ImportMapper.collection(owned, progress) { _, set -> cards[set] }
        assertEquals(3, items.size)
        val zard = items.first { it.ref == "ENGLISH|sv03.5|sv03.5-199|holo" }
        assertEquals("en", zard.lang)
        assertEquals("sv03.5", zard.setCode)
        assertEquals("151", zard.setName)
        assertEquals("199", zard.number)
        assertEquals("Charizard ex", zard.name)
        assertEquals("Holofoil", zard.variant)
        assertEquals(2, zard.qty)
        val bulba = items.first { it.ref.contains("sv03.5-001") }
        assertEquals("001", bulba.number) // from the card id when the list isn't cached
        assertNull(bulba.name)
        assertEquals("Reverse Holofoil", bulba.variant)
        assertEquals("ja", items.first { it.ref.startsWith("JAPANESE") }.lang)
    }

    @Test
    fun inventoryImportSkipsSoldAndMirroredItems() {
        val items = listOf(
            InventoryItem("a", InventoryKind.CARD, "Charizard ex", setName = "151", number = "199", finish = "Reverse Holo", condition = CardCondition.LP,
                quantity = 2, costEach = 40.0, location = "Binder 2", addedAt = 1_760_000_000_000),
            InventoryItem("b", InventoryKind.SEALED, "Pitch Black Booster Box", setName = "Pitch Black", quantity = 1, costEach = 150.0, status = InventoryStatus.LISTED, addedAt = 0),
            InventoryItem("c", InventoryKind.CARD, "Sold one", status = InventoryStatus.SOLD),
            InventoryItem("d", InventoryKind.CARD, "Mirrored", collectionKey = "ENGLISH|x|y|normal"),
        )
        val out = ImportMapper.inventory(items, ZoneOffset.UTC)
        assertEquals(listOf("inventory:a", "inventory:b"), out.map { it.ref })
        val card = out[0]
        assertEquals("Reverse Holofoil", card.variant)
        assertEquals("Lightly Played", card.condition)
        assertEquals(40.0, card.purchasePrice!!, 0.001)
        assertEquals("2025-10-09", card.addedOn)
        assertEquals("Location: Binder 2", card.notes)
        val box = out[1]
        assertEquals(true, box.sealed)
        assertEquals("Pitch Black Booster Box", box.productName)
        assertNull(box.condition)
        assertEquals("Listed for sale", box.notes)
    }

    private fun vset(id: Long, name: String, abbr: String? = null, sealed: Int = 5) = VaultSet(id, name, abbr, null, null, null, 100, sealed)

    @Test
    fun calculatorSetsLinkToServerSets() {
        val server = listOf(
            vset(1, "SV: Scarlet & Violet 151", "MEW"),
            vset(2, "ME05: Pitch Black", "PBL"),
            vset(3, "Celebrations"),
            vset(4, "Celebrations: Classic Collection", sealed = 0),
            vset(5, "SV08: Surging Sparks", "SSP"),
        )
        val japanese = listOf(vset(6, "SV2a: Pokemon Card 151", "SV2a"), vset(7, "SV2D: Clay Burst", "SV2D"))
        fun calc(id: String) = SetCatalog.sets.first { it.id == id }
        assertEquals(1L, CalculatorSets.match(calc("sv3pt5"), server)?.id)
        assertEquals(2L, CalculatorSets.match(calc("me5"), server)?.id)
        assertEquals(3L, CalculatorSets.match(calc("cel25"), server)?.id)
        assertEquals(5L, CalculatorSets.match(calc("sv8"), server)?.id)
        val jp = PokemonSet("jp-sv2a", "Pokémon Card 151", Series.SCARLET_VIOLET, 2023, language = Language.JAPANESE, code = "SV2a")
        assertEquals(6L, CalculatorSets.match(jp, japanese)?.id)
        assertNull(CalculatorSets.match(calc("xy1"), server))
        assertEquals(900002L, CalculatorSets.categoryFor(Language.CHINESE_SIMPLIFIED))
    }

    @Test
    fun homeLinksMapToAppScreens() {
        assertEquals("set/24722", VaultLinks.route("#/set/24722"))
        assertEquals("explore?sort=gain&minPrice=5", VaultLinks.route("#/?sort=gain&minPrice=5"))
        assertEquals("explore?type=sealed&sort=newest&minPrice=15", VaultLinks.route("#/?type=sealed&sort=newest&minPrice=15"))
        assertEquals("portfolio", VaultLinks.route("#/portfolio/products"))
        assertEquals("watchlists", VaultLinks.route("#/watchlist"))
        assertEquals("sets", VaultLinks.route("#/sets"))
        assertNull(VaultLinks.route(null))
    }
}
