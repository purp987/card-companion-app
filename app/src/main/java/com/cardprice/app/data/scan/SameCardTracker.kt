package com.cardprice.app.data.scan

/**
 * Makes scanning several copies of one card quick without double-counting:
 * - a card that was just added isn't added again while it stays in view;
 * - once no card has been readable for [clearGapMs] (the card was taken away), the same card
 *   counts as a new copy when it comes back;
 * - the version picked for a card with several finishes is remembered for the rest of the session.
 */
class SameCardTracker(private val clearGapMs: Long = CLEAR_GAP_MS) {
    /** Card added most recently, blocked from being added again until it leaves view. */
    var justAdded: String? = null
        private set
    private var noCardSince: Long? = null
    private val chosenVariants = mutableMapOf<String, String>()

    /** Call for every camera frame: [readable] is whether a card number could be read. */
    fun onFrame(readable: Boolean, now: Long) {
        if (readable) {
            noCardSince = null
            return
        }
        val since = noCardSince ?: now.also { noCardSince = it }
        if (now - since >= clearGapMs) justAdded = null
    }

    fun onAdded(cardId: String) {
        justAdded = cardId
    }

    /** A different card was read, so the previous one is no longer "just added". */
    fun onRead(cardId: String) {
        if (cardId != justAdded) justAdded = null
    }

    fun rememberChoice(cardId: String, variantKey: String) {
        chosenVariants[cardId] = variantKey
    }

    fun rememberedChoice(cardId: String): String? = chosenVariants[cardId]

    companion object {
        /** Long enough to ignore a wobble or glare, short enough to swap cards quickly. */
        const val CLEAR_GAP_MS = 1500L
    }
}
