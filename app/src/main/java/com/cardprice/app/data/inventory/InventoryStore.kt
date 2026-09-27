package com.cardprice.app.data.inventory

import com.cardprice.app.data.Language
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the inventory in files/inventory/inventory.json. Every save writes a new file and swaps it
 * in, so a crash mid-save can't leave half a file. Before the file is replaced, the old version is
 * kept in backups/ (at most one an hour, the last [MAX_BACKUPS]). A damaged file is set aside and
 * the newest backup loaded instead.
 */
class InventoryStore(filesDir: File) {
    private val dir = File(filesDir, "inventory").apply { mkdirs() }
    private val file = File(dir, "inventory.json")
    private val backups = File(dir, "backups").apply { mkdirs() }

    @Synchronized
    fun load(): List<InventoryItem> {
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }.getOrElse {
            file.renameTo(File(dir, "inventory.damaged-${System.currentTimeMillis()}.json"))
            newestBackup()?.let { b -> runCatching { parse(b.readText()) }.getOrNull() }.orEmpty()
        }
    }

    @Synchronized
    fun save(items: List<InventoryItem>, now: Long = System.currentTimeMillis()) {
        if (file.exists()) backUp(now)
        val tmp = File(dir, "inventory.json.tmp")
        tmp.writeText(toJson(items).toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun backUp(now: Long) {
        val newest = newestBackup()
        if (newest != null && now - newest.lastModified() < HOUR_MS) return
        file.copyTo(File(backups, "inventory-$now.json"), overwrite = true)
        backups.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(MAX_BACKUPS).forEach { it.delete() }
    }

    private fun newestBackup(): File? = backups.listFiles().orEmpty().maxByOrNull { it.lastModified() }

    companion object {
        const val MAX_BACKUPS = 20
        private const val HOUR_MS = 60L * 60 * 1000

        internal fun toJson(items: List<InventoryItem>): JSONObject {
            val array = JSONArray()
            items.forEach { i ->
                array.put(
                    JSONObject()
                        .put("id", i.id).put("kind", i.kind.name).put("name", i.name).put("setName", i.setName ?: JSONObject.NULL)
                        .put("language", i.language.name).put("number", i.number ?: JSONObject.NULL)
                        .put("productId", i.productId ?: JSONObject.NULL).put("image", i.image ?: JSONObject.NULL)
                        .put("finish", i.finish ?: JSONObject.NULL).put("condition", i.condition?.name ?: JSONObject.NULL)
                        .put("grade", i.grade ?: JSONObject.NULL).put("quantity", i.quantity)
                        .put("costEach", i.costEach ?: JSONObject.NULL).put("marketPrice", i.marketPrice ?: JSONObject.NULL)
                        .put("priceUpdatedAt", i.priceUpdatedAt ?: JSONObject.NULL).put("askingPrice", i.askingPrice ?: JSONObject.NULL)
                        .put("status", i.status.name).put("location", i.location ?: JSONObject.NULL).put("notes", i.notes ?: JSONObject.NULL)
                        .put("addedAt", i.addedAt).put("soldPriceEach", i.soldPriceEach ?: JSONObject.NULL).put("soldAt", i.soldAt ?: JSONObject.NULL)
                        .put("collectionKey", i.collectionKey ?: JSONObject.NULL),
                )
            }
            return JSONObject().put("version", 1).put("items", array)
        }

        internal fun parse(json: String): List<InventoryItem> {
            val items = JSONObject(json).getJSONArray("items")
            return (0 until items.length()).mapNotNull { n ->
                val o = items.getJSONObject(n)
                fun str(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
                fun dbl(k: String) = if (o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() }
                fun lng(k: String) = if (o.isNull(k) || !o.has(k)) null else o.optLong(k)
                runCatching {
                    InventoryItem(
                        id = o.getString("id"),
                        kind = InventoryKind.valueOf(o.getString("kind")),
                        name = o.getString("name"),
                        setName = str("setName"),
                        language = str("language")?.let { runCatching { Language.valueOf(it) }.getOrNull() } ?: Language.ENGLISH,
                        number = str("number"),
                        productId = lng("productId"),
                        image = str("image"),
                        finish = str("finish"),
                        condition = str("condition")?.let { runCatching { CardCondition.valueOf(it) }.getOrNull() },
                        grade = str("grade"),
                        quantity = o.optInt("quantity", 1).coerceAtLeast(1),
                        costEach = dbl("costEach"),
                        marketPrice = dbl("marketPrice"),
                        priceUpdatedAt = lng("priceUpdatedAt"),
                        askingPrice = dbl("askingPrice"),
                        status = str("status")?.let { runCatching { InventoryStatus.valueOf(it) }.getOrNull() } ?: InventoryStatus.IN_STOCK,
                        location = str("location"),
                        notes = str("notes"),
                        addedAt = o.optLong("addedAt"),
                        soldPriceEach = dbl("soldPriceEach"),
                        soldAt = lng("soldAt"),
                        collectionKey = str("collectionKey"),
                    )
                }.getOrNull()
            }
        }
    }
}
