package com.cardprice.app.data.scan

import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.Variants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTextTest {
    private fun card(id: String, number: String, name: String) =
        CollectionCard(id, number, name, null, null, listOf(Variants.of("holo", null, emptyList(), null)))

    // Text as ML Kit returns it from the bottom of a Pitch Black card ("PBL EN 111/084") plus the name at the top.
    private val pitchBlackLines = listOf("Stage 2", "Mega Gengar ex", "HP 350", "Weakness", "Illus. 5ban Graphics", "I PBL EN 111/084")

    @Test
    fun readsCodeAndNumberFromModernCard() {
        val clues = CardTextParser.parse(pitchBlackLines)
        assertEquals("111", clues.number)
        assertEquals(84, clues.total)
        assertEquals(listOf("me05"), clues.codes.map { it.setId })
        assertTrue(clues.englishMarker)
        assertEquals("PBL 111/084", clues.summary)
    }

    @Test
    fun fixesCommonOcrDigitMistakes() {
        assertEquals("111/084", CardTextParser.fixDigits("1l1/O84"))
        assertEquals("111", CardTextParser.parse(listOf("PBL EN 1l1/O84")).number)
    }

    @Test
    fun codeAndCountPickTheSet() {
        val clues = CardTextParser.parse(pitchBlackLines)
        val candidates = ScanMatcher.candidateSets(clues)
        assertEquals("me05", candidates.first().set.setId)
    }

    @Test
    fun matchesTheCardByNumberAndName() {
        val clues = CardTextParser.parse(pitchBlackLines)
        val candidates = ScanMatcher.candidateSets(clues)
        val pitchBlack = listOf(card("me05-110", "110", "Other"), card("me05-111", "111", "Mega Gengar ex"))
        val matches = ScanMatcher.match(clues, candidates.take(1).map { it to pitchBlack })
        assertEquals("me05-111", matches.single().card.id)
        assertTrue(ScanMatcher.isConfident(matches))
    }

    @Test
    fun olderCardsWithoutCodeUseCountAndName() {
        // Sword & Shield cards only print "215/203"; Evolving Skies has 203 official cards.
        val clues = CardTextParser.parse(listOf("VMAX", "Umbreon VMAX", "HP 310", "215/203"))
        assertTrue(clues.codes.isEmpty())
        val candidates = ScanMatcher.candidateSets(clues)
        assertTrue(candidates.any { it.set.setId == "swsh7" })
        val lists = candidates.map { c ->
            c to if (c.set.setId == "swsh7") listOf(card("swsh7-215", "215", "Umbreon VMAX")) else listOf(card("x-215", "215", "Something Else"))
        }
        val matches = ScanMatcher.match(clues, lists)
        assertEquals("swsh7-215", matches.first().card.id)
        assertTrue(ScanMatcher.isConfident(matches))
    }

    @Test
    fun japaneseCodeIsRecognised() {
        val clues = CardTextParser.parse(listOf("ピカチュウ", "SV2a 025/165"))
        assertEquals("025", clues.number)
        assertTrue(clues.codes.any { it.language == Language.JAPANESE && it.setId == "SV2a" })
        assertEquals("SV2a", ScanMatcher.candidateSets(clues).first().set.setId)
    }

    @Test
    fun numbersCompareWithoutLeadingZerosButKeepPrefixes() {
        assertTrue(ScanMatcher.sameNumber("001", "1"))
        assertTrue(ScanMatcher.sameNumber("TG05", "TG5"))
        assertFalse(ScanMatcher.sameNumber("TG05", "5"))
    }

    @Test
    fun nothingToGoOnMeansNoGuess() {
        val clues = CardTextParser.parse(listOf("Pikachu", "HP 60"))
        assertFalse(clues.usable)
        assertTrue(ScanMatcher.candidateSets(clues).isEmpty())
    }

    @Test
    fun ambiguousNamesAreNotConfident() {
        val set = ScanIndex.sets.first { it.setId == "me05" }
        val matches = listOf(
            ScanMatch(set, card("a", "1", "A"), 15),
            ScanMatch(set, card("b", "1", "B"), 14),
        )
        assertFalse(ScanMatcher.isConfident(matches))
    }

    @Test
    fun regulationMarkJoinedToCodeStillReadsTheSet() {
        // What ML Kit actually returned for Misty's Vitality (PBL 111/084): "JPBL EN" plus the number.
        val clues = CardTextParser.parse(listOf("Misty's Vitality", "JPBL EN", "111/084"))
        assertEquals(listOf("me05"), clues.codes.map { it.setId })
        assertEquals("111", clues.number)
    }

    // Bottom of the Mega Gengar ex Black Star promo: no "/total", just the promo code and number.
    private val megaGengarPromoLines = listOf("Mega Gengar ex", "HP 350", "Weakness", "Illus. 5ban Graphics", "I MEP EN O73")

    @Test
    fun readsMegaEvolutionPromoNumber() {
        val clues = CardTextParser.parse(megaGengarPromoLines)
        assertEquals("073", clues.number)
        assertEquals(null, clues.total)
        assertEquals(listOf("mep"), clues.codes.map { it.setId })
        assertTrue(clues.promo)
        assertTrue(clues.usable)
        assertEquals("MEP 073", clues.summary)
    }

    @Test
    fun promoWithMatchingNameIsSure() {
        val clues = CardTextParser.parse(megaGengarPromoLines)
        val candidates = ScanMatcher.candidateSets(clues)
        assertEquals(listOf("mep"), candidates.map { it.set.setId })
        val matches = ScanMatcher.match(clues, candidates.map { it to listOf(card("mep-073", "073", "Mega Gengar ex")) })
        assertEquals("mep-073", matches.first().card.id)
        assertEquals(ScanConfidence.HIGH, ScanMatcher.confidence(matches))
    }

    @Test
    fun promoWhoseNameDoesntAgreeIsNotAutoAdded() {
        val clues = CardTextParser.parse(listOf("MEP EN 073"))
        val candidates = ScanMatcher.candidateSets(clues)
        val matches = ScanMatcher.match(clues, candidates.map { it to listOf(card("mep-073", "073", "Mega Gengar ex")) })
        assertEquals("mep-073", matches.first().card.id)
        assertFalse(ScanMatcher.confidence(matches) == ScanConfidence.HIGH)
    }

    @Test
    fun readsOlderAndJapanesePromoNumbers() {
        fun read(line: String) = CardTextParser.parse(listOf("Pikachu", line)).let { it.codes.map { s -> s.setId } to it.number }
        assertEquals(listOf("svp") to "085", read("SVP EN 085"))
        assertEquals(listOf("swshp") to "SWSH291", read("SWSH291"))
        assertEquals(listOf("smp") to "SM213", read("SM213"))
        assertEquals(listOf("xyp") to "XY121", read("XY121"))
        assertEquals(listOf("bwp") to "BW82", read("BW82"))
        assertEquals(listOf("SV-P") to "001", read("001/SV-P"))
        // A regular number still wins over anything promo-like.
        assertEquals("111", CardTextParser.parse(listOf("SM213", "PBL EN 111/084")).number)
    }

    @Test
    fun promoSetWithoutItsOwnCodeOnlyMatchesItself() {
        val clues = CardTextParser.parse(listOf("Pikachu", "SWSH020"))
        assertEquals(listOf("swshp"), ScanMatcher.candidateSets(clues).map { it.set.setId })
    }

    @Test
    fun readsSimplifiedChineseSetCode() {
        // Chinese names aren't read by the Latin recognizer; the boxed code and number are.
        val clues = CardTextParser.parse(listOf("HP 60", "G CSV8C", "024/207"))
        assertEquals("024", clues.number)
        assertEquals(207, clues.total)
        assertEquals(listOf("CSV8C"), clues.codes.map { it.setId })
        assertEquals(listOf("CSV8C", "cs4ac"), listOf(
            CardTextParser.parse(listOf("csv8C 024/207")).codes.single().setId,
            CardTextParser.parse(listOf("CS4aC 001/132")).codes.single().setId.lowercase(),
        ))
        assertEquals("CSV9.5C", CardTextParser.parse(listOf("CSV9.5C 100/208")).codes.single().setId)
    }

    @Test
    fun chineseCardWithCodeIsSureEvenWithAnyLanguage() {
        val clues = CardTextParser.parse(listOf("G CSV8C 024/207"))
        val candidates = ScanMatcher.candidateSets(clues)
        assertEquals("CSV8C", candidates.first().set.setId)
        val matches = ScanMatcher.match(clues, candidates.map { it to listOf(card("${it.set.setId}-024", "024", "Card 024")) })
        assertEquals("CSV8C-024", matches.first().card.id)
        assertEquals(ScanConfidence.HIGH, ScanMatcher.confidence(matches))
    }

    @Test
    fun countAloneOnlyPointsToChineseSetsWhenScanningChinese() {
        val clues = CardTextParser.parse(listOf("024/207"))
        assertTrue(ScanMatcher.candidateSets(clues).none { it.set.language == Language.CHINESE_SIMPLIFIED })
        val chinese = ScanMatcher.candidateSets(clues, setup = ScanSetup(language = Language.CHINESE_SIMPLIFIED))
        assertEquals(listOf("CSV8C"), chinese.map { it.set.setId })
    }
}
