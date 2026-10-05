package com.cardprice.app.data.cloud

import android.content.Context
import android.graphics.BitmapFactory
import com.cardprice.app.data.collection.CardPhotos
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.scan.ScanLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Beta only, for the owner's personal use: names and pictures for Simplified Chinese cards that TCGdex
 * has none for, from the owner's own server (which gets them from the PTCG-CHS-Datasets project, whose
 * licence doesn't allow passing them on, so release builds never ask for them). Only works while this
 * phone is paired with the server; pictures are saved like your own photos ([CardPhotos]).
 */
object ChinesePictures {
    private const val NAMES_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val PREFS = "cn_pictures"

    private fun api(context: Context): CloudApi? {
        if (!LiveStream.isBetaBuild(context)) return null
        val account = CloudAccount(context.applicationContext)
        val url = account.serverUrl ?: return null
        val device = account.deviceId ?: return null
        return CloudApi(url, deviceId = device)
    }

    fun available(context: Context): Boolean = api(context) != null

    /** Gives numbered placeholders ("Card 012") their real names, when the server knows the set. */
    suspend fun withNames(context: Context, setId: String, cards: List<CollectionCard>): List<CollectionCard> {
        if (cards.none { it.placeholder }) return cards
        val names = names(context, setId) ?: return cards
        return cards.map { c ->
            val name = if (c.placeholder) c.number.toIntOrNull()?.let { names[it] } else null
            if (name != null) c.copy(name = name) else c
        }
    }

    private suspend fun names(context: Context, setId: String): Map<Int, String>? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "cn-names/${setId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < NAMES_MAX_AGE_MS
        val text = if (fresh) file.readText() else {
            val api = api(context)
            val downloaded = api?.let { runCatching { it.chineseSet(setId).toString() }.getOrNull() }
            if (downloaded != null) {
                file.parentFile?.mkdirs()
                file.writeText(downloaded)
                downloaded
            } else if (file.exists()) file.readText() else null
        } ?: return@withContext null
        runCatching {
            val list = JSONObject(text).getJSONArray("cards")
            (0 until list.length()).map { list.getJSONObject(it) }
                .mapNotNull { o -> o.optString("number").toIntOrNull()?.let { it to o.optString("name") } }
                .filter { it.second.isNotBlank() }
                .toMap()
        }.getOrNull()
    }

    /**
     * Downloads pictures for this set's cards that have none (one at a time, in card order), saving each as
     * the card's picture. Cards you removed a picture from aren't fetched again.
     */
    suspend fun download(context: Context, setId: String, cards: List<CollectionCard>) = withContext(Dispatchers.IO) {
        val api = api(context) ?: return@withContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tried = prefs.getStringSet(setId, emptySet()).orEmpty().toMutableSet()
        val wanted = cards.filter { it.image == null && it.id !in tried && !CardPhotos.has(it.id) && it.number.toIntOrNull() != null }
        if (wanted.isEmpty()) return@withContext
        var saved = 0
        var failures = 0
        for (card in wanted) {
            ensureActive()
            val bytes = try {
                api.chineseImage(setId, card.number.toInt())
            } catch (e: CloudException) {
                // The server is unreachable or doesn't have the set: stop rather than retrying every card.
                if (++failures >= 3) break else continue
            }
            if (bytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null && CardPhotos.save(card.id, bitmap)) {
                    if (++saved % 8 == 0) CardPhotos.changed()
                }
            }
            tried += card.id
            prefs.edit().putStringSet(setId, HashSet(tried)).apply()
        }
        if (saved > 0) {
            CardPhotos.changed()
            ScanLog.d("cloud: saved $saved Chinese card pictures for $setId")
        }
    }
}
