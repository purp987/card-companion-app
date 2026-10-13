package com.cardprice.app.data.vault

import com.cardprice.app.data.vault.VaultJson.int
import com.cardprice.app.data.vault.VaultJson.lng
import com.cardprice.app.data.vault.VaultJson.str
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A build published on TCG Vault (`scripts/publish-apk.js`). */
data class AppRelease(
    val id: Long,
    val appId: String?,
    val versionCode: Long,
    val versionName: String,
    val size: Long,
    val sha256: String,
    val minSdk: Int?,
    val notes: String?,
    val publishedAt: String?,
    /** Server path of the APK, e.g. "/api/app/update/apk/2". */
    val url: String,
)

/** `GET /api/app/update` reply. */
data class UpdateCheck(val available: Boolean, val current: Long, val latest: AppRelease?)

/** A streamed HTTP response (for the APK download). */
class StreamResponse(val code: Int, val length: Long, val sha256: String?, val body: InputStream, private val onClose: () -> Unit = {}) {
    fun close() {
        runCatching { body.close() }
        onClose()
    }
}

/** Opens a streamed GET; swapped for a fake in tests. Throws [IOException] when unreachable. */
fun interface StreamOpener {
    fun open(url: String, headers: Map<String, String>): StreamResponse
}

class UrlConnectionStreamOpener : StreamOpener {
    override fun open(url: String, headers: Map<String, String>): StreamResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream) ?: java.io.ByteArrayInputStream(ByteArray(0))
        return StreamResponse(code, conn.contentLengthLong, conn.getHeaderField("X-Content-SHA256"), body) { conn.disconnect() }
    }
}

/** The downloaded file didn't match the published SHA-256 (it's deleted). */
class BadDownloadException(message: String) : IOException(message)

/** Pure update logic: when to check, what's newer, parsing and checksum verification. */
object AppUpdates {
    /** Automatic checks run at most this often. */
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    fun shouldCheck(lastCheckMs: Long?, nowMs: Long, intervalMs: Long = CHECK_INTERVAL_MS): Boolean =
        lastCheckMs == null || nowMs - lastCheckMs >= intervalMs || nowMs < lastCheckMs

    /** Only a strictly newer build counts, and only one this phone's Android can run. */
    fun isUpdate(check: UpdateCheck, currentVersionCode: Long, sdkInt: Int): Boolean {
        val r = check.latest ?: return false
        return r.versionCode > currentVersionCode && (r.minSdk == null || r.minSdk <= sdkInt)
    }

    fun parse(json: String): UpdateCheck {
        val o = JSONObject(json)
        val l = o.optJSONObject("latest")
        val latest = l?.let {
            AppRelease(
                id = it.lng("id") ?: return@let null,
                appId = it.str("appId"),
                versionCode = it.lng("versionCode") ?: return@let null,
                versionName = it.str("versionName") ?: "",
                size = it.lng("size") ?: 0,
                sha256 = it.str("sha256") ?: "",
                minSdk = it.int("minSdk"),
                notes = it.str("notes"),
                publishedAt = it.str("publishedAt"),
                url = it.str("url") ?: "/api/app/update/apk/${it.lng("id")}",
            )
        }
        return UpdateCheck(o.optBoolean("available", false), o.lng("current") ?: 0, latest)
    }

    fun sha256Hex(file: File): String = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Checks [file] against [expected] (hex, any case); deletes it and throws on mismatch. */
    fun verify(file: File, expected: String) {
        val actual = sha256Hex(file)
        if (expected.isBlank() || !actual.equals(expected.trim(), ignoreCase = true)) {
            file.delete()
            throw BadDownloadException("The download didn't match the published checksum, so it was discarded. Try again.")
        }
    }
}
