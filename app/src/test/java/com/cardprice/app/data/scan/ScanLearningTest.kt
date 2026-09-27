package com.cardprice.app.data.scan

import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.Variants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanLearningTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun card(id: String, number: String, name: String) =
        CollectionCard(id, number, name, null, null, listOf(Variants.of("normal", null, emptyList(), null)))

    private val setup = ScanSetup()

    @Test
    fun confirmedReadingIsRememberedAcrossRestarts() {
        val clues = CardTextParser.parse(listOf("PBL EN 111/084"))
        val key = ScanLearning.readKey(clues, setup)
        val learning = ScanLearning(tmp.root)
        learning.confirm(key, LearnedCard(Language.ENGLISH, "me05", "me05-111"))
        val reloaded = ScanLearning(tmp.root)
        assertEquals(LearnedCard(Language.ENGLISH, "me05", "me05-111"), reloaded.learned(key))
        assertEquals(1, reloaded.learnedReadings)
    }

    @Test
    fun learnedReadingTurnsAnAmbiguousReadIntoASureOne() {
        // "025/165" with no code or name: English 151 and Japanese SV2a both have 165 cards.
        val clues = CardTextParser.parse(listOf("025/165"))
        val key = ScanLearning.readKey(clues, setup)
        val candidates = ScanMatcher.candidateSets(clues)
        val lists = candidates.map { c -> c to listOf(card("${c.set.setId}-025", "025", "Pikachu")) }

        val before = ScanMatcher.match(clues, lists)
        assertFalse(ScanMatcher.confidence(before) == ScanConfidence.HIGH)

        val learning = ScanLearning(tmp.root)
        learning.confirm(key, LearnedCard(Language.ENGLISH, "sv03.5", "sv03.5-025"), corrected = true)
        val after = ScanMatcher.match(clues, ScanMatcher.candidateSets(clues, hints = learning).map { c ->
            c to listOf(card("${c.set.setId}-025", "025", "Pikachu"))
        }, learning, key)
        assertEquals("sv03.5-025", after.first().card.id)
        assertTrue(after.first().learned)
        assertEquals(ScanConfidence.HIGH, ScanMatcher.confidence(after))
        assertEquals(1, learning.corrections)
    }

    @Test
    fun undoneCardIsPushedDownForThatReading() {
        val clues = CardTextParser.parse(listOf("Misty's Vitality", "PBL EN 111/084"))
        val key = ScanLearning.readKey(clues, setup)
        val learning = ScanLearning(tmp.root)
        learning.confirm(key, LearnedCard(Language.ENGLISH, "me05", "me05-111"))
        learning.reject(key, "me05-111")
        assertNull(learning.learned(key))
        assertTrue(learning.isRejected(key, "me05-111"))

        val candidates = ScanMatcher.candidateSets(clues, hints = learning)
        val matches = ScanMatcher.match(clues, candidates.map { it to listOf(card("me05-111", "111", "Misty's Vitality")) }, learning, key)
        assertFalse(ScanMatcher.confidence(matches) == ScanConfidence.HIGH && matches.first().score > 20)
        // Confirming it again (the user re-added it) lifts the rejection.
        learning.confirm(key, LearnedCard(Language.ENGLISH, "me05", "me05-111"))
        assertFalse(learning.isRejected(key, "me05-111"))
    }

    @Test
    fun frequentlyScannedSetsGetASmallBoost() {
        val learning = ScanLearning(tmp.root)
        assertEquals(0, learning.setBoost(Language.ENGLISH, "me05"))
        repeat(7) { i -> learning.confirm("k$i", LearnedCard(Language.ENGLISH, "me05", "me05-00$i")) }
        assertEquals(3, learning.setBoost(Language.ENGLISH, "me05"))
        repeat(50) { i -> learning.confirm("m$i", LearnedCard(Language.ENGLISH, "me05", "me05-$i")) }
        assertEquals(4, learning.setBoost(Language.ENGLISH, "me05")) // capped
    }

    @Test
    fun preferredVersionIsRememberedAcrossSessions() {
        ScanLearning(tmp.root).preferVariant(Language.ENGLISH, "me05-002", "reverse")
        val learning = ScanLearning(tmp.root)
        val v = AutoAddPolicy.variantFor(listOf("normal", "reverse"), ScanFinish.MIXED, learning.preferredVariant(Language.ENGLISH, "me05-002"))
        assertEquals("reverse", v)
    }

    @Test
    fun resetForgetsEverything() {
        val learning = ScanLearning(tmp.root)
        learning.confirm("k", LearnedCard(Language.ENGLISH, "me05", "me05-001"))
        learning.preferVariant(Language.ENGLISH, "me05-001", "reverse")
        learning.reset()
        val reloaded = ScanLearning(tmp.root)
        assertEquals(0, reloaded.learnedReadings)
        assertNull(reloaded.preferredVariant(Language.ENGLISH, "me05-001"))
    }

    @Test
    fun unconfirmRestoresTheStateBeforeAConfirmation() {
        val learning = ScanLearning(tmp.root)
        val card = LearnedCard(Language.ENGLISH, "me05", "me05-002")
        learning.confirm("a", card)
        val boostBefore = learning.setBoost(Language.ENGLISH, "me05")
        learning.confirm("b", card)
        learning.unconfirm("b", card)
        assertNull(learning.learned("b"))
        assertFalse(learning.isRejected("b", "me05-002")) // not treated as a wrong match
        assertEquals(boostBefore, learning.setBoost(Language.ENGLISH, "me05"))
        assertEquals(card, learning.learned("a"))
    }
}
