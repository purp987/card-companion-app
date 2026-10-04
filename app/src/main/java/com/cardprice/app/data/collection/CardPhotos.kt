package com.cardprice.app.data.collection

import android.graphics.Bitmap
import java.io.File

/**
 * Pictures you took for cards that have none from the card databases (e.g. Simplified Chinese cards):
 * saved from a scan, or picked from the gallery. Kept on the phone in files/card-photos, one JPEG per
 * card id, and used wherever the card's picture is shown ([CollectionCard.imageUrl]).
 */
object CardPhotos {
    private const val MAX_HEIGHT = 900
    @Volatile private var dir: File? = null

    fun init(filesDir: File) {
        if (dir == null) dir = File(filesDir, "card-photos").apply { mkdirs() }
    }

    private fun file(cardId: String): File? = dir?.let { File(it, cardId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg") }

    fun has(cardId: String): Boolean = file(cardId)?.exists() == true

    /** A file address for the picture, changing when the picture does (so image caches pick it up). */
    fun uriFor(cardId: String): String? = file(cardId)?.takeIf { it.exists() }?.let { "file://${it.absolutePath}?v=${it.lastModified()}" }

    /** Saves [bitmap] as this card's picture (scaled down), replacing any earlier one. */
    fun save(cardId: String, bitmap: Bitmap): Boolean {
        val target = file(cardId) ?: return false
        val scaled = if (bitmap.height > MAX_HEIGHT) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * MAX_HEIGHT.toFloat() / bitmap.height).toInt().coerceAtLeast(1), MAX_HEIGHT, true)
        } else bitmap
        val tmp = File(target.parentFile, target.name + ".tmp")
        val ok = tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return ok && (tmp.renameTo(target) || run { target.delete(); tmp.renameTo(target) })
    }

    fun delete(cardId: String) {
        file(cardId)?.delete()
        changed()
    }

    /** Goes up whenever a picture is saved or removed, so screens showing cards redraw. */
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun changed() {
        revision.value++
    }
}
