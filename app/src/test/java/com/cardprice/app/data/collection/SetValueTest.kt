package com.cardprice.app.data.collection

import org.junit.Assert.assertEquals
import org.junit.Test

class SetValueTest {
    private val normal = Variants.of("normal", null, emptyList(), null)
    private val reverse = Variants.of("reverse", null, emptyList(), null)
    private val pokeball = Variants.of("reverse", null, emptyList(), "pokeball")
    private val cards = listOf(
        CollectionCard("s-001", "001", "A", null, null, listOf(normal, reverse)),
        CollectionCard("s-002", "002", "B", null, null, listOf(normal, pokeball)),
        CollectionCard("s-003", "003", "C", null, null, listOf(normal)),
    )
    private val prices = mapOf(
        "s-001" to CardPrices(mapOf(normal.key to VariantPrice(0.25, "USD", "TCGplayer"), reverse.key to VariantPrice(1.0, "USD", "TCGplayer"))),
        "s-002" to CardPrices(mapOf(pokeball.key to VariantPrice(3.0, "EUR", "Cardmarket"))),
    )

    @Test
    fun multipliesCopiesByVariantPriceAndKeepsCurrenciesApart() {
        val owned = mapOf(
            ("s-001" to normal.key) to 4,
            ("s-001" to reverse.key) to 1,
            ("s-002" to pokeball.key) to 2,
            ("s-002" to normal.key) to 1, // no price
            ("s-003" to normal.key) to 1, // card not priced at all
        )
        val value = SetValue.of(cards, { id, key -> owned[id to key] ?: 0 }, prices)
        assertEquals(2.0, value.usd, 1e-9) // 4 × 0.25 + 1 × 1.00
        assertEquals(6.0, value.eur, 1e-9) // 2 × 3.00
        assertEquals(7, value.pricedCopies)
        assertEquals(2, value.unpricedCopies)
    }

    @Test
    fun nothingOwnedIsZero() {
        assertEquals(SetValue.NONE, SetValue.of(cards, { _, _ -> 0 }, prices))
    }

    @Test
    fun valuesAddUp() {
        val total = SetValue(2.0, 0.0, 3, 0) + SetValue(1.5, 4.0, 2, 1)
        assertEquals(SetValue(3.5, 4.0, 5, 1), total)
    }
}
