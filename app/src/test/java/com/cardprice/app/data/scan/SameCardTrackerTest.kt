package com.cardprice.app.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SameCardTrackerTest {
    @Test
    fun sameCardStaysBlockedWhileInView() {
        val t = SameCardTracker(clearGapMs = 1500)
        t.onAdded("me05-002")
        // The card is still readable in later frames.
        t.onFrame(readable = true, now = 100)
        t.onRead("me05-002")
        t.onFrame(readable = true, now = 400)
        assertEquals("me05-002", t.justAdded)
    }

    @Test
    fun takingTheCardAwayLetsTheSameCardCountAgain() {
        val t = SameCardTracker(clearGapMs = 1500)
        t.onAdded("me05-002")
        t.onFrame(readable = false, now = 1000)
        t.onFrame(readable = false, now = 2000)
        assertEquals("me05-002", t.justAdded) // only 1 s without a card
        t.onFrame(readable = false, now = 2600)
        assertNull(t.justAdded) // 1.6 s: the card was taken away
    }

    @Test
    fun briefWobbleDoesNotCount() {
        val t = SameCardTracker(clearGapMs = 1500)
        t.onAdded("me05-002")
        t.onFrame(readable = false, now = 1000)
        t.onFrame(readable = true, now = 1400) // readable again: the gap resets
        t.onFrame(readable = false, now = 1800)
        t.onFrame(readable = false, now = 3000)
        assertEquals("me05-002", t.justAdded)
    }

    @Test
    fun aDifferentCardClearsTheBlock() {
        val t = SameCardTracker()
        t.onAdded("me05-002")
        t.onRead("me05-111")
        assertNull(t.justAdded)
    }

    @Test
    fun rememberedVersionIsUsedForLaterCopies() {
        val t = SameCardTracker()
        t.rememberChoice("me05-002", "reverse")
        val variant = AutoAddPolicy.variantFor(listOf("normal", "reverse"), ScanFinish.MIXED, t.rememberedChoice("me05-002"))
        assertEquals("reverse", variant)
        // The finish chosen up front still wins when it fits.
        assertEquals("normal", AutoAddPolicy.variantFor(listOf("normal", "reverse"), ScanFinish.NORMAL, "reverse"))
        // Nothing remembered for other cards.
        assertNull(AutoAddPolicy.variantFor(listOf("normal", "reverse"), ScanFinish.MIXED, t.rememberedChoice("me05-003")))
    }
}
