package com.cardprice.app.data.scan

import com.cardprice.app.data.Language
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How a scan-history entry came about. */
enum class ScanAction(val label: String) {
    AUTO_ADDED("Auto-added"),
    ADDED("Added"),
    REMOVED("Removed"),
    UNDONE("Undone"),
}

/** One change made from the scanner. [delta] is the change in copies (+1 added, -1 removed…). */
data class ScanHistoryEntry(
    val at: Long,
    val action: ScanAction,
    val language: Language,
    val setId: String,
    val setName: String,
    val cardId: String,
    val number: String,
    val cardName: String,
    val image: String?,
    val variantKey: String,
    val variantLabel: String,
    val delta: Int,
)

/** Newest-first log of what the scanner added or removed, kept on the device (last [MAX] entries). */
class ScanHistoryStore(dir: File) {
    private val file = File(dir.apply { mkdirs() }, "scan-history.json")

    fun load(): List<ScanHistoryEntry> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { i -> fromJson(a.getJSONObject(i)) }
    }.getOrDefault(emptyList())

    /** Synchronized: a binder page logs many entries at once, and each append rewrites the file. */
    @Synchronized
    fun append(entry: ScanHistoryEntry): List<ScanHistoryEntry> {
        val all = (listOf(entry) + load()).take(MAX)
        save(all)
        return all
    }

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(entries: List<ScanHistoryEntry>) {
        val a = JSONArray()
        entries.forEach { a.put(toJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun toJson(e: ScanHistoryEntry) = JSONObject()
        .put("at", e.at).put("action", e.action.name).put("language", e.language.name)
        .put("setId", e.setId).put("setName", e.setName).put("cardId", e.cardId).put("number", e.number)
        .put("cardName", e.cardName).put("image", e.image ?: JSONObject.NULL)
        .put("variantKey", e.variantKey).put("variantLabel", e.variantLabel).put("delta", e.delta)

    private fun fromJson(o: JSONObject): ScanHistoryEntry? = runCatching {
        ScanHistoryEntry(
            at = o.getLong("at"),
            action = ScanAction.valueOf(o.getString("action")),
            language = Language.valueOf(o.getString("language")),
            setId = o.getString("setId"),
            setName = o.getString("setName"),
            cardId = o.getString("cardId"),
            number = o.getString("number"),
            cardName = o.getString("cardName"),
            image = if (o.isNull("image")) null else o.optString("image"),
            variantKey = o.getString("variantKey"),
            variantLabel = o.getString("variantLabel"),
            delta = o.getInt("delta"),
        )
    }.getOrNull()

    companion object {
        const val MAX = 500
    }
}
