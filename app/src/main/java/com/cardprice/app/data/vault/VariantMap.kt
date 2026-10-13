package com.cardprice.app.data.vault

import com.cardprice.app.data.Language

/**
 * Maps the scanner's TCGdex-style variant keys ("normal", "holo", "reverse:foil=pokeball",
 * "holo:stamp=1st-edition") to TCGplayer printings ("Normal", "Holofoil", "Reverse Holofoil",
 * "1st Edition Holofoil"), which is how TCG Vault names a product's variants.
 */
object VariantMap {
    /** The TCGplayer printing for a TCGdex variant key; null when the key is empty. */
    fun subTypeFor(variantKey: String?): String? {
        if (variantKey.isNullOrBlank()) return null
        val parts = variantKey.split(':')
        val base = when (parts.first()) {
            "holo" -> "Holofoil"
            "reverse" -> "Reverse Holofoil"
            else -> "Normal"
        }
        val firstEdition = parts.any { it == "stamp=1st-edition" }
        return if (firstEdition) "1st Edition $base" else base
    }

    /** Free-text versions from the old inventory ("Reverse Holo", "Holo", "1st Edition") as a printing. */
    fun subTypeForLabel(label: String?): String? {
        val l = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val first = "1st" in l
        val base = when {
            "reverse" in l -> "Reverse Holofoil"
            "holo" in l || "foil" in l -> "Holofoil"
            "normal" in l || "non" in l -> "Normal"
            first -> "Normal"
            else -> return label.trim()
        }
        return if (first) "1st Edition $base" else base
    }

    /**
     * The server variant that best fits [variantKey]: exact printing, then the same printing ignoring
     * edition words ("Unlimited Holofoil" for "Holofoil"), then the product's only variant, then null
     * (the server falls back to the primary printing).
     */
    fun pick(variants: List<Variant>, variantKey: String?): Variant? {
        if (variants.isEmpty()) return null
        val wanted = subTypeFor(variantKey) ?: return variants.singleOrNull()
        variants.firstOrNull { it.subType.equals(wanted, ignoreCase = true) }?.let { return it }
        val plain = wanted.removePrefix("1st Edition ")
        variants.firstOrNull { it.subType.endsWith(plain, ignoreCase = true) && !(plain == "Holofoil" && it.subType.contains("Reverse", true)) }
            ?.let { return it }
        return variants.singleOrNull()
    }

    /** Language code the server's `/api/app/resolve` and `/api/app/import` expect. */
    fun langCode(language: Language): String = when (language) {
        Language.ENGLISH -> "en"
        Language.JAPANESE -> "ja"
        Language.CHINESE_SIMPLIFIED -> "zh-cn"
    }
}
