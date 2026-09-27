package com.cardprice.app.data.cloud

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * One backup of the app's data, as uploaded to the server: the collection, inventory, scan learning
 * and history, and settings such as saved purchases, favorites and custom sets. API tokens aren't in
 * it (they're kept encrypted, separately).
 */
object BackupBundle {
    const val FORMAT = "card-companion-backup"
    const val VERSION = 1

    /** Files under the app's files folder that go into a backup, by the name used in it. */
    private val FILES = mapOf(
        "collection" to "collection/collection.json",
        "scanLearning" to "collection/scan-learning.json",
        "scanHistory" to "collection/scan-history.json",
        "inventory" to "inventory/inventory.json",
    )

    /** [prefs] are settings files by name (e.g. "card_pricer") with their values. */
    fun build(filesDir: File, prefs: Map<String, Map<String, *>>, appVersion: String, now: Long): JSONObject {
        val bundle = JSONObject().put("format", FORMAT).put("version", VERSION).put("createdAt", now).put("appVersion", appVersion)
        FILES.forEach { (name, path) ->
            val file = File(filesDir, path)
            // Files hold a JSON object or (scan history) a list.
            val json = file.takeIf { it.exists() }?.let { runCatching { JSONTokener(it.readText()).nextValue() }.getOrNull() }
            if (json is JSONObject || json is JSONArray) bundle.put(name, json)
        }
        val settings = JSONObject()
        prefs.forEach { (file, values) ->
            val o = JSONObject()
            values.forEach { (k, v) ->
                when (v) {
                    is Set<*> -> o.put(k, JSONArray(v.map { it.toString() }))
                    is String, is Boolean, is Int, is Long, is Float -> o.put(k, v)
                }
            }
            settings.put(file, o)
        }
        bundle.put("settings", settings)
        return bundle
    }

    /** Numbers shown in the server's backup list without downloading the backup. */
    fun summary(bundle: JSONObject): Map<String, Int> {
        val collection = bundle.optJSONObject("collection")
        val owned = collection?.optJSONObject("owned")
        val copies = owned?.keys()?.asSequence()?.sumOf { owned.optInt(it) } ?: 0
        val sets = collection?.optJSONArray("progress")?.length() ?: 0
        val inventory = bundle.optJSONObject("inventory")?.optJSONArray("items")?.length() ?: 0
        return mapOf("cards" to copies, "sets" to sets, "inventory" to inventory)
    }

    /** Checks a downloaded backup is one of ours and not from a newer app version. */
    fun validate(bundle: JSONObject): String? = when {
        bundle.optString("format") != FORMAT -> "That isn't a Card Companion backup."
        bundle.optInt("version") > VERSION -> "That backup was made by a newer version of the app. Update the app first."
        bundle.optJSONObject("collection") == null -> "That backup has no collection in it."
        else -> null
    }

    fun collectionJson(bundle: JSONObject): String? = bundle.optJSONObject("collection")?.toString()

    fun inventoryJson(bundle: JSONObject): String? = bundle.optJSONObject("inventory")?.toString()
}
