package com.cardprice.app.data.scan

import com.cardprice.app.data.Language
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.roundToInt

/** A card the scanner learned for a particular reading. */
data class LearnedCard(val language: Language, val setId: String, val cardId: String)

/** What the matcher asks of the learning store (kept as an interface so matching stays testable). */
interface ScanHints {
    /** Card confirmed for this reading before, unless it was later rejected. */
    fun learned(readKey: String): LearnedCard?
    fun isRejected(readKey: String, cardId: String): Boolean
    /** Small score bonus for sets the user scans often (0–4). */
    fun setBoost(language: Language, setId: String): Int
    /** Version the user picked for this card before. */
    fun preferredVariant(language: Language, cardId: String): String?

    object None : ScanHints {
        override fun learned(readKey: String): LearnedCard? = null
        override fun isRejected(readKey: String, cardId: String) = false
        override fun setBoost(language: Language, setId: String) = 0
        override fun preferredVariant(language: Language, cardId: String): String? = null
    }
}

/**
 * Makes scanning better with use. It learns from what the user does with results:
 * - confirming a match (keeping an auto-add, or tapping a result) ties that reading to the card;
 * - undoing an auto-add or picking a different candidate marks the wrong card for that reading;
 * - sets scanned often get a small preference in close calls;
 * - the version picked for a card is remembered across sessions.
 * Kept on the device in one small JSON file.
 */
class ScanLearning(dir: File) : ScanHints {
    private val file = File(dir.apply { mkdirs() }, "scan-learning.json")
    private val confirmed = mutableMapOf<String, Pair<LearnedCard, Int>>()
    private val rejected = mutableMapOf<String, MutableSet<String>>()
    private val setCounts = mutableMapOf<String, Int>()
    private val variants = mutableMapOf<String, String>()
    var corrections = 0
        private set

    init {
        load()
    }

    val confirmations: Int @Synchronized get() = confirmed.values.sumOf { it.second }
    val learnedReadings: Int @Synchronized get() = confirmed.size

    @Synchronized
    override fun learned(readKey: String): LearnedCard? =
        confirmed[readKey]?.first?.takeIf { !isRejected(readKey, it.cardId) }

    @Synchronized
    override fun isRejected(readKey: String, cardId: String): Boolean = rejected[readKey]?.contains(cardId) == true

    @Synchronized
    override fun setBoost(language: Language, setId: String): Int {
        val n = setCounts["${language.name}|$setId"] ?: return 0
        // 1 scan → 1, 3 → 2, 7 → 3, 15+ → 4: helps in close calls without overriding a clear reading.
        return (ln(n + 1.0) / ln(2.0)).roundToInt().coerceIn(0, 4)
    }

    @Synchronized
    override fun preferredVariant(language: Language, cardId: String): String? = variants["${language.name}|$cardId"]

    /** The user kept or chose [card] for this reading. [corrected] means it wasn't the scanner's first guess. */
    @Synchronized
    fun confirm(readKey: String, card: LearnedCard, corrected: Boolean = false) {
        val previous = confirmed[readKey]
        confirmed[readKey] = card to (if (previous?.first == card) previous.second + 1 else 1)
        rejected[readKey]?.remove(card.cardId)
        setCounts.merge("${card.language.name}|${card.setId}", 1, Int::plus)
        if (corrected) corrections++
        save()
    }

    /**
     * Takes back a confirmation without saying the card was wrong (e.g. a whole binder page undone
     * because it was added by mistake, not because the cards were misread).
     */
    @Synchronized
    fun unconfirm(readKey: String, card: LearnedCard) {
        val entry = confirmed[readKey] ?: return
        if (entry.first != card) return
        if (entry.second <= 1) confirmed.remove(readKey) else confirmed[readKey] = card to entry.second - 1
        val setKey = "${card.language.name}|${card.setId}"
        setCounts[setKey]?.let { if (it <= 1) setCounts.remove(setKey) else setCounts[setKey] = it - 1 }
        save()
    }

    /** The user said [cardId] was wrong for this reading (e.g. undid the auto-add). */
    @Synchronized
    fun reject(readKey: String, cardId: String) {
        rejected.getOrPut(readKey) { mutableSetOf() }.add(cardId)
        if (confirmed[readKey]?.first?.cardId == cardId) confirmed.remove(readKey)
        corrections++
        save()
    }

    @Synchronized
    fun preferVariant(language: Language, cardId: String, variantKey: String) {
        if (variants["${language.name}|$cardId"] == variantKey) return
        variants["${language.name}|$cardId"] = variantKey
        save()
    }

    @Synchronized
    fun reset() {
        confirmed.clear(); rejected.clear(); setCounts.clear(); variants.clear(); corrections = 0
        save()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONObject("confirmed")?.let { c ->
                c.keys().forEach { k ->
                    val e = c.getJSONObject(k)
                    val lang = Language.entries.firstOrNull { it.name == e.getString("language") } ?: return@forEach
                    confirmed[k] = LearnedCard(lang, e.getString("setId"), e.getString("cardId")) to e.getInt("count")
                }
            }
            o.optJSONObject("rejected")?.let { r ->
                r.keys().forEach { k ->
                    val a = r.getJSONArray(k)
                    rejected[k] = (0 until a.length()).map(a::getString).toMutableSet()
                }
            }
            o.optJSONObject("sets")?.let { s -> s.keys().forEach { k -> setCounts[k] = s.getInt(k) } }
            o.optJSONObject("variants")?.let { v -> v.keys().forEach { k -> variants[k] = v.getString(k) } }
            corrections = o.optInt("corrections")
        }
    }

    private fun save() {
        val o = JSONObject()
        o.put("confirmed", JSONObject().apply {
            confirmed.forEach { (k, v) ->
                put(k, JSONObject().put("language", v.first.language.name).put("setId", v.first.setId).put("cardId", v.first.cardId).put("count", v.second))
            }
        })
        o.put("rejected", JSONObject().apply { rejected.forEach { (k, v) -> put(k, JSONArray(v.toList())) } })
        o.put("sets", JSONObject(setCounts as Map<*, *>))
        o.put("variants", JSONObject(variants as Map<*, *>))
        o.put("corrections", corrections)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        @Volatile private var shared: ScanLearning? = null

        /** The app-wide store, so the scanner and Settings never overwrite each other. */
        fun get(filesDir: File): ScanLearning =
            shared ?: synchronized(this) { shared ?: ScanLearning(File(filesDir, "collection")).also { shared = it } }

        /** Identifies a reading: what was printed/read, plus the set chosen up front (if any). */
        fun readKey(clues: ScanClues, setup: ScanSetup): String = "${setup.setId.orEmpty()}|${clues.summary}"
    }
}
