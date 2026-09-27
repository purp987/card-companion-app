package com.cardprice.app.data.scan

/** What the scanner should do with a lookup result. */
sealed interface ScanDecision {
    /** Sure, one variant: add a copy of [variantKey] without asking. */
    data class AutoAdd(val variantKey: String) : ScanDecision
    /** Sure of the card, but it comes in several variants: ask which one before adding anything. */
    data object ChooseVariant : ScanDecision
    /** Looks right but needs one more matching read from the camera before acting. */
    data object HoldSteady : ScanDecision
    /** Not sure enough to act: show the result(s) and let the user decide. */
    data object Review : ScanDecision
    /** This card was just added and is probably still in view; don't add it again. */
    data object AlreadyAdded : ScanDecision
}

/**
 * Decides when scanning may add a card by itself:
 * - only at [ScanConfidence.HIGH];
 * - from the camera, only once the same card has been read twice in a row;
 * - for cards with more than one variant, only when the finish chosen before scanning matches one
 *   of them (otherwise the user picks the version);
 * - never the same card twice in a row (extra copies are added by tapping).
 */
object AutoAddPolicy {
    fun decide(
        cardId: String,
        variantKeys: List<String>,
        confidence: ScanConfidence,
        fromPhoto: Boolean,
        autoAddEnabled: Boolean,
        previousReadCardId: String?,
        lastAutoAddedCardId: String?,
        finish: ScanFinish = ScanFinish.MIXED,
        rememberedVariant: String? = null,
    ): ScanDecision {
        if (confidence != ScanConfidence.HIGH) return ScanDecision.Review
        val variant = variantFor(variantKeys, finish, rememberedVariant) ?: return ScanDecision.ChooseVariant
        if (!autoAddEnabled) return ScanDecision.Review
        if (cardId == lastAutoAddedCardId) return ScanDecision.AlreadyAdded
        if (!fromPhoto && previousReadCardId != cardId) return ScanDecision.HoldSteady
        return ScanDecision.AutoAdd(variant)
    }

    /**
     * The variant to add without asking: the only one there is, the one matching the finish chosen
     * before scanning, or the one the user picked for this card earlier in the session. Null means
     * the user has to pick.
     */
    fun variantFor(variantKeys: List<String>, finish: ScanFinish, rememberedVariant: String? = null): String? = when {
        variantKeys.size == 1 -> variantKeys.single()
        finish.variantKey != null && finish.variantKey in variantKeys -> finish.variantKey
        rememberedVariant != null && rememberedVariant in variantKeys -> rememberedVariant
        else -> null
    }
}
