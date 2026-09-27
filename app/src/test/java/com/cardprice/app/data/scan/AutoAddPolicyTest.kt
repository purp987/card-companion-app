package com.cardprice.app.data.scan

import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.Variants
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoAddPolicyTest {
    private val holo = listOf("holo")
    private val normalAndReverse = listOf("normal", "reverse")

    private fun decide(
        variants: List<String> = holo,
        confidence: ScanConfidence = ScanConfidence.HIGH,
        fromPhoto: Boolean = false,
        auto: Boolean = true,
        previous: String? = "me05-111",
        lastAdded: String? = null,
    ) = AutoAddPolicy.decide("me05-111", variants, confidence, fromPhoto, auto, previous, lastAdded)

    @Test
    fun sureSingleVariantCardIsAddedAfterTwoMatchingReads() {
        assertEquals(ScanDecision.HoldSteady, decide(previous = null))
        assertEquals(ScanDecision.HoldSteady, decide(previous = "me05-110"))
        assertEquals(ScanDecision.AutoAdd("holo"), decide(previous = "me05-111"))
    }

    @Test
    fun photoNeedsOnlyOneRead() {
        assertEquals(ScanDecision.AutoAdd("holo"), decide(fromPhoto = true, previous = null))
    }

    @Test
    fun cardsWithSeveralVariantsAlwaysAsk() {
        assertEquals(ScanDecision.ChooseVariant, decide(variants = normalAndReverse))
        assertEquals(ScanDecision.ChooseVariant, decide(variants = normalAndReverse, fromPhoto = true))
    }

    @Test
    fun anythingLessThanHighConfidenceIsReviewed() {
        assertEquals(ScanDecision.Review, decide(confidence = ScanConfidence.MEDIUM))
        assertEquals(ScanDecision.Review, decide(confidence = ScanConfidence.LOW, variants = normalAndReverse))
    }

    @Test
    fun sameCardIsNotAddedTwiceInARow() {
        assertEquals(ScanDecision.AlreadyAdded, decide(lastAdded = "me05-111"))
    }

    @Test
    fun autoAddCanBeTurnedOff() {
        assertEquals(ScanDecision.Review, decide(auto = false))
    }

    @Test
    fun confidenceLevels() {
        val set = ScanIndex.sets.first { it.setId == "me05" }
        val card = CollectionCard("me05-111", "111", "Misty's Vitality", null, null, listOf(Variants.of("holo", null, emptyList(), null)))
        // Code + count match: high.
        assertEquals(ScanConfidence.HIGH, ScanMatcher.confidence(listOf(ScanMatch(set, card, 17, codeMatched = true, totalMatched = true))))
        // Count + clearly read name (older cards without a code): high.
        assertEquals(ScanConfidence.HIGH, ScanMatcher.confidence(listOf(ScanMatch(set, card, 13, totalMatched = true, nameScore = 8))))
        // Count only, name unreadable: medium, so the user checks it.
        assertEquals(ScanConfidence.MEDIUM, ScanMatcher.confidence(listOf(ScanMatch(set, card, 11, totalMatched = true))))
        // Two close candidates: low.
        assertEquals(
            ScanConfidence.LOW,
            ScanMatcher.confidence(listOf(ScanMatch(set, card, 17, true, true), ScanMatch(set, card, 15, true, true))),
        )
    }

    @Test
    fun realScanOfPitchBlackCardIsHighConfidence() {
        val clues = CardTextParser.parse(listOf("Misty's Vitality", "JPBL EN", "111/084"))
        val set = ScanMatcher.candidateSets(clues)
        val card = CollectionCard("me05-111", "111", "Misty's Vitality", null, null, listOf(Variants.of("holo", null, emptyList(), null)))
        val matches = ScanMatcher.match(clues, set.take(1).map { it to listOf(card) })
        assertEquals(ScanConfidence.HIGH, ScanMatcher.confidence(matches))
    }

    @Test
    fun chosenFinishLetsMultiVariantCardsAddInstantly() {
        fun decideWith(finish: ScanFinish, variants: List<String>) =
            AutoAddPolicy.decide(
                "me05-002", variants, ScanConfidence.HIGH, fromPhoto = true, autoAddEnabled = true,
                previousReadCardId = null, lastAutoAddedCardId = null, finish = finish,
            )
        assertEquals(ScanDecision.AutoAdd("reverse"), decideWith(ScanFinish.REVERSE, normalAndReverse))
        assertEquals(ScanDecision.AutoAdd("normal"), decideWith(ScanFinish.NORMAL, normalAndReverse))
        // Finish not available on this card: ask.
        assertEquals(ScanDecision.ChooseVariant, decideWith(ScanFinish.HOLO, normalAndReverse))
        // "Mixed" always asks when there's a choice.
        assertEquals(ScanDecision.ChooseVariant, decideWith(ScanFinish.MIXED, normalAndReverse))
        // A card with one version is added as that version whatever finish was chosen.
        assertEquals(ScanDecision.AutoAdd("holo"), decideWith(ScanFinish.REVERSE, holo))
    }

    @Test
    fun specialReversesAreNotTakenForPlainReverse() {
        // 151 and Prismatic cards also have Poke Ball / Master Ball reverses; "Reverse Holo" means the plain one.
        val keys = listOf("normal", "reverse", "reverse:foil=pokeball", "reverse:foil=masterball")
        assertEquals("reverse", AutoAddPolicy.variantFor(keys, ScanFinish.REVERSE))
        assertEquals(null, AutoAddPolicy.variantFor(listOf("normal", "reverse:foil=pokeball"), ScanFinish.REVERSE))
    }

    @Test
    fun chosenSetNeedsOnlyTheNumberAndFiltersOtherSets() {
        val setup = ScanSetup(ScanFinish.REVERSE, Language.ENGLISH, "me05", "Pitch Black")
        val justNumber = CardTextParser.parse(listOf("Grubbin")).copy(number = "002")
        assertEquals(false, justNumber.usable)
        assertEquals(true, justNumber.usableWith(setup))
        val candidates = ScanMatcher.candidateSets(justNumber, setup = setup)
        assertEquals(listOf("me05"), candidates.map { it.set.setId })
        assertEquals(true, candidates.single().codeMatched)
    }

    @Test
    fun languageFilterDropsOtherLanguages() {
        val clues = CardTextParser.parse(listOf("025/165")) // 165 cards: English 151 and Japanese SV2a
        val en = ScanMatcher.candidateSets(clues, setup = ScanSetup(language = Language.ENGLISH)).map { it.set.language }.toSet()
        val ja = ScanMatcher.candidateSets(clues, setup = ScanSetup(language = Language.JAPANESE)).map { it.set.language }.toSet()
        assertEquals(setOf(Language.ENGLISH), en)
        assertEquals(setOf(Language.JAPANESE), ja)
    }
}
