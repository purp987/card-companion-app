package com.cardprice.app.data.scan

import android.content.Context
import com.cardprice.app.data.Language

/** The finish being scanned. [variantKey] is the collection's plain variant for it; null means "ask each time". */
enum class ScanFinish(val label: String, val variantKey: String?) {
    NORMAL("Normal", "normal"),
    REVERSE("Reverse Holo", "reverse"),
    HOLO("Holo", "holo"),
    MIXED("Mixed (ask me)", null),
}

/**
 * What the user said they're scanning, chosen before the camera starts. Narrowing the language or set
 * makes lookups faster; the finish lets cards with several versions be added without a prompt.
 */
data class ScanSetup(
    val finish: ScanFinish = ScanFinish.MIXED,
    val language: Language? = null,
    val setId: String? = null,
    val setName: String? = null,
) {
    val summary: String
        get() = listOf(finish.label, language?.nativeName ?: "Any language", setName ?: "Any set").joinToString(" · ")
}

/** Remembers the last setup so starting the next session is one tap. */
class ScanSetupStore(context: Context) {
    private val prefs = context.getSharedPreferences("scan_setup", Context.MODE_PRIVATE)

    fun load(): ScanSetup? {
        if (!prefs.contains("finish")) return null
        return ScanSetup(
            finish = ScanFinish.entries.firstOrNull { it.name == prefs.getString("finish", null) } ?: ScanFinish.MIXED,
            language = Language.entries.firstOrNull { it.name == prefs.getString("language", null) },
            setId = prefs.getString("setId", null),
            setName = prefs.getString("setName", null),
        )
    }

    fun save(setup: ScanSetup) {
        prefs.edit()
            .putString("finish", setup.finish.name)
            .putString("language", setup.language?.name)
            .putString("setId", setup.setId)
            .putString("setName", setup.setName)
            .apply()
    }
}
