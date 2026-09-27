package com.cardprice.app.data.collection

import com.cardprice.app.data.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFillTest {
    private val normal = listOf(Variants.of("holo", null, emptyList(), null))

    private fun tcgdex(n: String, name: String, image: String? = null) =
        CollectionCard("30th-c-$n", n, name, null, image, normal)

    private fun tcgplayer(id: Long, n: String, name: String, price: Double? = 10.0) =
        CollectionCard(id.toString(), n, name, null, "$TCGPLAYER_IMAGE_PREFIX$id", normal, marketPrice = price)

    @Test
    fun classicCollectionGetsTcgplayerPictures() {
        val cards = listOf(
            tcgdex("001", "Charizard"),
            tcgdex("015", "Metagross δ"),
            tcgdex("016", "Genesect-EX"),
            tcgdex("018", "Gengar"),
            tcgdex("019", "Darkrai & Cresselia LEGEND"),
            tcgdex("020", "Darkrai & Cresselia LEGEND"),
            tcgdex("022", "Palkia LV.X"),
        )
        val singles = listOf(
            tcgplayer(716200, "100", "Darkrai & Cresselia Legend (Bottom)"),
            tcgplayer(714372, "4", "Charizard", 250.0),
            tcgplayer(716157, "113", "Metagross (Delta Species)"),
            tcgplayer(716158, "RC25", "Genesect EX (Team Plasma)"),
            tcgplayer(716198, "94", "Gengar (Prime)"),
            tcgplayer(716199, "99", "Darkrai & Cresselia Legend (Top)"),
            tcgplayer(716203, "11", "Palkia LV.X"),
        )
        val filled = ImageFill.fill(cards, singles).associate { it.number to it.image?.removePrefix(TCGPLAYER_IMAGE_PREFIX) }
        assertEquals("714372", filled["001"])
        assertEquals("716157", filled["015"])
        assertEquals("716158", filled["016"])
        assertEquals("716198", filled["018"])
        assertEquals("716199", filled["019"]) // top half first
        assertEquals("716200", filled["020"])
        assertEquals("716203", filled["022"])
        assertEquals(250.0, ImageFill.fill(cards, singles).first().marketPrice!!, 0.0)
    }

    @Test
    fun cardsWithPicturesAndUnrelatedNamesAreLeftAlone() {
        val cards = listOf(tcgdex("001", "Pikachu", image = "https://assets.tcgdex.net/x"), tcgdex("002", "Mew"))
        val filled = ImageFill.fill(cards, listOf(tcgplayer(1, "1", "Pikachu"), tcgplayer(2, "2", "Mewtwo")))
        assertEquals("https://assets.tcgdex.net/x", filled[0].image)
        assertNull(filled[1].image) // "Mew" is too short to prefix-match "Mewtwo"
    }

    @Test
    fun classicCollectionMapsToItsTcgplayerSet() {
        assertTrue("me-30th-celebration-classic-collection" in ImageFill.tcgplayerSlugs(Language.ENGLISH, "30th-c"))
    }
}
