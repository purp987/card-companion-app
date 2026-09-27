package com.cardprice.app.data.collection

import com.cardprice.app.data.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtraSetsTest {
    private val page = """{"results":[{"totalResults":4,"results":[
        {"productId":716901.0,"productName":"Pikachu - 017/103","rarityName":"Pikachu Rare","marketPrice":3.25,"customAttributes":{"number":"017/103"}},
        {"productId":716950.0,"productName":"Pikachu ex - 047/103","rarityName":"Double Rare","marketPrice":7.99,"customAttributes":{"number":"047/103"}},
        {"productId":717001.0,"productName":"Shining Celebi","rarityName":"Classic Collection","marketPrice":null,"customAttributes":{"number":"141/103"}},
        {"productId":716999.0,"productName":"Lightning Energy","rarityName":"None","marketPrice":0.1,"customAttributes":{"number":"LIG"}}]}]}"""

    @Test
    fun parsesTcgplayerSingles() {
        val cards = TcgPlayerSingles.parse(page)
        assertEquals(4, TcgPlayerSingles.parse(page).total)
        assertEquals(listOf("017", "047", "141", "LIG"), cards.cards.map { it.number })
        assertEquals("Pikachu", cards.cards[0].name)
        assertEquals("Pikachu ex", cards.cards[1].name)
        assertEquals(3.25, cards.cards[0].marketPrice!!, 1e-9)
        assertNull(cards.cards[2].marketPrice)
        assertNull(cards.cards[3].rarity) // "None" means no rarity
        assertEquals("https://tcgplayer-cdn.tcgplayer.com/product/716901_200w.jpg", cards.cards[0].imageUrl("low"))
        assertEquals("https://tcgplayer-cdn.tcgplayer.com/product/716901_400w.jpg", cards.cards[0].imageUrl("high"))
    }

    @Test
    fun classicCollectionSplitsFromMainJapaneseSet() {
        val cards = TcgPlayerSingles.parse(page).cards
        val main = ExtraSets.find(Language.JAPANESE, "M6a")!!
        val classic = ExtraSets.find(Language.JAPANESE, "M6a-C")!!
        assertEquals(listOf("017", "047", "LIG"), cards.filter { main.includes(it.rarity) }.map { it.number })
        assertEquals(listOf("141"), cards.filter { classic.includes(it.rarity) }.map { it.number })
    }

    @Test
    fun japanesePikachuSubsetIs17To46() {
        val subset = SetSubsets.forSet(Language.JAPANESE, "M6a").single()
        val cards = TcgPlayerSingles.parse(page).cards
        assertEquals(listOf("017"), cards.filter(subset::contains).map { it.number })
        assertEquals(30, subset.numbers.count())
    }

    @Test
    fun injectedIntoJapaneseMegaSeriesFirst() {
        val series = listOf(CardSeries("M", "MEGA", listOf(CardSet("M6", "Storm Emeralda", 76, 113, null, null))))
        val injected = ExtraSets.inject(Language.JAPANESE, series).single()
        assertEquals(listOf("M6a", "M6a-C", "M6"), injected.sets.map { it.id })
        assertEquals(series, ExtraSets.inject(Language.ENGLISH, series))
    }

    @Test
    fun tcgdexImagesKeepTheirFormat() {
        val card = CollectionCard("sv03.5-001", "001", "Bulbasaur", null, "https://assets.tcgdex.net/en/sv/sv03.5/001", emptyList())
        assertEquals("https://assets.tcgdex.net/en/sv/sv03.5/001/low.webp", card.imageUrl("low"))
    }
}
