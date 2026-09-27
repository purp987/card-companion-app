package com.cardprice.app.data.collection

import android.content.Context
import com.cardprice.app.data.Language
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device storage for the collection (owned counts and per-set progress) plus caches of
 * TCGdex set catalogs and card lists, so sets open instantly and work offline after the first load.
 *
 * The collection is also snapshotted into rolling backups. If the main file is ever missing or
 * unreadable, [load] falls back to the newest good backup instead of starting empty.
 */
class CollectionStore(filesDir: File, cacheRoot: File) {
    constructor(context: Context) : this(context.filesDir, context.cacheDir)

    private val dir = File(filesDir, "collection").apply { mkdirs() }
    private val collectionFile = File(dir, "collection.json")
    private val backupDir = File(dir, "backups").apply { mkdirs() }
    /** Named restore points: kept until deleted by the user, never rotated out like backups. */
    private val restorePointDir = File(dir, "restore-points").apply { mkdirs() }
    private val cacheDir = File(cacheRoot, "tcgdex").apply { mkdirs() }

    /** [restoredFrom] is set when the main file was missing or damaged and a backup was used instead. */
    data class Saved(val owned: Map<String, Int>, val progress: List<SetProgress>, val restoredFrom: Backup? = null)

    /**
     * A snapshot of the collection. [cards] counts distinct card variants, [copies] every copy.
     * [name] is set for restore points the user saved on purpose (these are never rotated out).
     */
    data class Backup(val file: File, val takenAt: Long, val cards: Int, val copies: Int, val name: String? = null) {
        val pinned: Boolean get() = name != null
    }

    fun load(): Saved {
        if (collectionFile.exists()) {
            parse(collectionFile)?.let { return it }
            // Unreadable: keep it aside rather than let the next save overwrite it.
            collectionFile.renameTo(File(dir, "collection.damaged-${System.currentTimeMillis()}.json"))
        }
        // Main file missing or corrupt: use the newest backup that still reads correctly.
        for (backup in backups()) {
            val saved = parse(backup.file) ?: continue
            if (saved.owned.isEmpty()) continue
            write(collectionFile, backup.file.readText())
            return saved.copy(restoredFrom = backup)
        }
        return Saved(emptyMap(), emptyList())
    }

    /**
     * Saves the collection. Up to once an hour, the version being replaced is kept as a backup first,
     * so a backup always holds the collection as it was before a round of changes.
     */
    fun save(owned: Map<String, Int>, progress: List<SetProgress>, now: Long = System.currentTimeMillis()) {
        val newest = backupTimes().maxOrNull()
        if (newest == null || now - newest >= SNAPSHOT_INTERVAL_MS) {
            val previous = collectionFile.takeIf { it.exists() }?.let { f -> parse(f)?.let { f.readText() } }
            if (previous != null) snapshot(previous, now)
        }
        write(collectionFile, toJson(owned, progress))
    }

    /** Saves the current collection as a named restore point. Returns null if there's nothing saved yet. */
    fun saveRestorePoint(name: String, now: Long = System.currentTimeMillis()): Backup? {
        val current = collectionFile.takeIf { it.exists() }?.readText() ?: return null
        val root = runCatching { JSONObject(current) }.getOrNull() ?: return null
        val file = File(restorePointDir, "restore-$now.json")
        write(file, root.put("name", name.trim().ifBlank { "Restore point" }).toString())
        return restorePoints().firstOrNull { it.file == file }
    }

    /** Named restore points, newest first. */
    fun restorePoints(): List<Backup> =
        (restorePointDir.listFiles { f -> f.name.startsWith("restore-") && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f ->
                val takenAt = f.name.removePrefix("restore-").removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
                val saved = parse(f) ?: return@mapNotNull null
                val name = runCatching { JSONObject(f.readText()).optString("name") }.getOrNull().orEmpty().ifBlank { "Restore point" }
                Backup(f, takenAt, saved.owned.size, saved.owned.values.sum(), name)
            }
            .sortedByDescending { it.takenAt }

    /** Automatic backups, newest first. */
    fun backups(): List<Backup> =
        (backupDir.listFiles { f -> f.name.startsWith("collection-") && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f ->
                val takenAt = f.name.removePrefix("collection-").removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
                val saved = parse(f) ?: return@mapNotNull null
                Backup(f, takenAt, saved.owned.size, saved.owned.values.sum())
            }
            .sortedByDescending { it.takenAt }

    /** Replaces the collection with [backup], saving the current collection as a backup first. */
    fun restore(backup: Backup, now: Long = System.currentTimeMillis()): Saved? {
        val restored = parse(backup.file) ?: return null
        if (collectionFile.exists()) snapshot(collectionFile.readText(), now)
        write(collectionFile, backup.file.readText())
        return restored
    }

    private fun snapshot(json: String, now: Long) {
        write(File(backupDir, "collection-$now.json"), json)
        backupTimes().sortedDescending().drop(MAX_BACKUPS).forEach { File(backupDir, "collection-$it.json").delete() }
    }

    private fun backupTimes(): List<Long> =
        (backupDir.list() ?: emptyArray()).mapNotNull { it.removePrefix("collection-").removeSuffix(".json").toLongOrNull() }

    private fun parse(file: File): Saved? = runCatching {
        val root = JSONObject(file.readText())
        val ownedJson = root.optJSONObject("owned") ?: JSONObject()
        val owned = ownedJson.keys().asSequence().associateWith { ownedJson.getInt(it) }
        val progressJson = root.optJSONArray("progress") ?: JSONArray()
        val progress = (0 until progressJson.length()).mapNotNull { i ->
            val p = progressJson.getJSONObject(i)
            val language = Language.entries.firstOrNull { it.name == p.optString("language") } ?: return@mapNotNull null
            SetProgress(
                language = language,
                setId = p.getString("setId"),
                setName = p.getString("setName"),
                totalCards = p.getInt("totalCards"),
                totalVariants = p.getInt("totalVariants"),
                ownedCards = p.getInt("ownedCards"),
                ownedVariants = p.getInt("ownedVariants"),
                updatedAt = p.getLong("updatedAt"),
                value = p.optJSONObject("value")?.let { v ->
                    SetValue(v.optDouble("usd", 0.0), v.optDouble("eur", 0.0), v.optInt("priced"), v.optInt("unpriced"))
                } ?: SetValue.NONE,
                art = p.optJSONArray("art")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
            )
        }
        Saved(owned, progress)
    }.getOrNull()

    private fun toJson(owned: Map<String, Int>, progress: List<SetProgress>): String {
        val ownedJson = JSONObject()
        owned.forEach { (k, v) -> if (v > 0) ownedJson.put(k, v) }
        val progressJson = JSONArray()
        progress.forEach { p ->
            progressJson.put(
                JSONObject()
                    .put("language", p.language.name)
                    .put("setId", p.setId)
                    .put("setName", p.setName)
                    .put("totalCards", p.totalCards)
                    .put("totalVariants", p.totalVariants)
                    .put("ownedCards", p.ownedCards)
                    .put("ownedVariants", p.ownedVariants)
                    .put("updatedAt", p.updatedAt)
                    .put(
                        "value",
                        JSONObject().put("usd", p.value.usd).put("eur", p.value.eur)
                            .put("priced", p.value.pricedCopies).put("unpriced", p.value.unpricedCopies),
                    )
                    .put("art", JSONArray(p.art))
            )
        }
        return JSONObject().put("owned", ownedJson).put("progress", progressJson).toString()
    }

    /** Writes via a temp file so a crash mid-write can't leave a half-written file behind. */
    private fun write(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    // ---- Caches ----

    fun cachedSeries(language: Language, maxAgeMs: Long): List<CardSeries>? {
        val file = File(cacheDir, "series_v3_${language.tcgdexCode}.json")
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > maxAgeMs) return null
        return runCatching { seriesFromJson(JSONArray(file.readText())) }.getOrNull()
    }

    fun cacheSeries(language: Language, series: List<CardSeries>) {
        File(cacheDir, "series_v3_${language.tcgdexCode}.json").writeText(seriesToJson(series).toString())
    }

    fun cachedCards(language: Language, setId: String): List<CollectionCard>? {
        val file = cardsFile(language, setId)
        if (!file.exists()) return null
        return runCatching { cardsFromJson(JSONArray(file.readText())) }.getOrNull()
    }

    fun cacheCards(language: Language, setId: String, cards: List<CollectionCard>) {
        cardsFile(language, setId).writeText(cardsToJson(cards).toString())
    }

    /** Card prices younger than [maxAgeMs], keyed by card id. */
    fun cachedPrices(language: Language, maxAgeMs: Long): Map<String, CardPrices> {
        val file = pricesFile(language)
        if (!file.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText())
            val now = System.currentTimeMillis()
            root.keys().asSequence().mapNotNull { cardId ->
                val entry = root.getJSONObject(cardId)
                if (now - entry.getLong("at") > maxAgeMs) return@mapNotNull null
                val p = entry.getJSONObject("p")
                cardId to CardPrices(p.keys().asSequence().associateWith { key ->
                    val v = p.getJSONArray(key)
                    VariantPrice(v.getDouble(0), v.getString(1), v.getString(2))
                })
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun cachePrices(language: Language, prices: Map<String, CardPrices>) {
        val file = pricesFile(language)
        val root = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        prices.forEach { (cardId, cardPrices) ->
            val p = JSONObject()
            cardPrices.byVariant.forEach { (key, v) -> p.put(key, JSONArray().put(v.amount).put(v.currency).put(v.source)) }
            root.put(cardId, JSONObject().put("at", now).put("p", p))
        }
        file.writeText(root.toString())
    }

    private fun pricesFile(language: Language) = File(cacheDir, "prices_${language.tcgdexCode}.json")

    private fun cardsFile(language: Language, setId: String) =
        File(cacheDir, "cards_v2_${language.tcgdexCode}_${setId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    companion object {
        /** At most one backup an hour; with 20 kept, that covers the last 20 sessions of changes. */
        const val SNAPSHOT_INTERVAL_MS = 60L * 60 * 1000
        const val MAX_BACKUPS = 20

        internal fun seriesToJson(series: List<CardSeries>) = JSONArray().apply {
            series.forEach { s ->
                val sets = JSONArray()
                s.sets.forEach { set ->
                    sets.put(
                        JSONObject().put("id", set.id).put("name", set.name)
                            .put("official", set.officialCount).put("total", set.totalCount)
                            .put("logo", set.logo ?: JSONObject.NULL).put("symbol", set.symbol ?: JSONObject.NULL)
                    )
                }
                put(JSONObject().put("id", s.id).put("name", s.name).put("sets", sets))
            }
        }

        internal fun seriesFromJson(a: JSONArray) = (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            val sets = s.getJSONArray("sets")
            CardSeries(
                id = s.getString("id"),
                name = s.getString("name"),
                sets = (0 until sets.length()).map { j ->
                    val o = sets.getJSONObject(j)
                    CardSet(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        officialCount = o.getInt("official"),
                        totalCount = o.getInt("total"),
                        logo = o.optString("logo").takeIf { !o.isNull("logo") && it.isNotBlank() },
                        symbol = o.optString("symbol").takeIf { !o.isNull("symbol") && it.isNotBlank() },
                    )
                },
            )
        }

        internal fun cardsToJson(cards: List<CollectionCard>) = JSONArray().apply {
            cards.forEach { c ->
                val variants = JSONArray()
                c.variants.forEach { variants.put(JSONObject().put("key", it.key).put("label", it.label)) }
                put(
                    JSONObject().put("id", c.id).put("number", c.number).put("name", c.name)
                        .put("rarity", c.rarity ?: JSONObject.NULL).put("image", c.image ?: JSONObject.NULL)
                        .put("variants", variants)
                        .put("marketPrice", c.marketPrice ?: JSONObject.NULL)
                )
            }
        }

        internal fun cardsFromJson(a: JSONArray) = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val v = o.getJSONArray("variants")
            CollectionCard(
                id = o.getString("id"),
                number = o.getString("number"),
                name = o.getString("name"),
                rarity = o.optString("rarity").takeIf { !o.isNull("rarity") && it.isNotBlank() && it != "None" },
                image = o.optString("image").takeIf { !o.isNull("image") && it.isNotBlank() },
                variants = (0 until v.length()).map { j ->
                    CardVariant(v.getJSONObject(j).getString("key"), v.getJSONObject(j).getString("label"))
                },
                marketPrice = if (o.isNull("marketPrice")) null else o.optDouble("marketPrice").takeIf { !it.isNaN() },
            )
        }
    }
}

/** Storage key for one owned variant. */
fun ownedKey(language: Language, setId: String, cardId: String, variantKey: String) =
    "${language.name}|$setId|$cardId|$variantKey"
