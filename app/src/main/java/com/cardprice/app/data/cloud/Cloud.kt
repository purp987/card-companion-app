package com.cardprice.app.data.cloud

import android.content.Context
import com.cardprice.app.data.SecretStore
import com.cardprice.app.data.market.Http
import com.cardprice.app.data.market.HttpException
import com.cardprice.app.data.market.userMessage
import org.json.JSONObject
import java.net.URI

/** A backup stored on the server, as listed (without its data). */
data class ServerBackup(
    val id: String,
    val createdAt: Long,
    val appVersion: String?,
    val device: String?,
    /** Numbers the app sent with it, e.g. "cards" and "sets". */
    val summary: Map<String, Int>,
    val size: Long,
)

/** A failed server request, with a message that's safe to show. [signedOut] means sign in again. */
class CloudException(message: String, val signedOut: Boolean = false) : Exception(message)

/**
 * The backup server this phone uses and who's signed in. The address and email are plain settings;
 * the sign-in token is encrypted ([SecretStore]) and never leaves the phone except to that server.
 */
class CloudAccount(context: Context) {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(value) = prefs.edit().putString("server_url", value).apply()

    var email: String?
        get() = prefs.getString("email", null)
        set(value) = prefs.edit().putString("email", value).apply()

    var token: String?
        get() = runCatching { secrets.get(TOKEN) }.getOrNull()
        set(value) = secrets.put(TOKEN, value)

    val signedIn: Boolean get() = serverUrl != null && token != null

    private companion object {
        const val TOKEN = "cloud_token"
    }
}

object CloudUrls {
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    /**
     * The server address as typed, cleaned up ("cards.example.com" → "https://cards.example.com"), or
     * null if it isn't usable. Only HTTPS is accepted, except that beta builds ([allowLocalHttp]) may
     * use plain HTTP to this phone or a test computer (localhost via adb reverse, or the emulator host).
     */
    fun normalize(input: String, allowLocalHttp: Boolean): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return when (uri.scheme?.lowercase()) {
            "https" -> withScheme
            "http" -> withScheme.takeIf { allowLocalHttp && host in LOCAL_HOSTS }
            else -> null
        }
    }
}

/** The backup server's API (see the card-companion-server project). Calls block; run them off the main thread. */
class CloudApi(private val baseUrl: String, private val token: String? = null) {
    /** Creates an account; returns the sign-in token. */
    fun register(email: String, password: String, signupCode: String, device: String): String =
        call("POST", "/v1/auth/register", JSONObject().put("email", email).put("password", password).put("signupCode", signupCode).put("device", device))
            .getString("token")

    /** Signs in; returns the sign-in token. */
    fun login(email: String, password: String, device: String): String =
        call("POST", "/v1/auth/login", JSONObject().put("email", email).put("password", password).put("device", device))
            .getString("token")

    fun logout() {
        // An empty JSON body: the server refuses POSTs without a JSON content type.
        call("POST", "/v1/auth/logout", JSONObject())
    }

    fun uploadBackup(payload: JSONObject, summary: Map<String, Int>, appVersion: String, device: String): ServerBackup {
        val body = JSONObject()
            .put("payload", payload)
            .put("summary", JSONObject(summary as Map<*, *>))
            .put("appVersion", appVersion.take(100))
            .put("device", device.take(100))
        return parseBackup(call("POST", "/v1/backups", body))
    }

    fun listBackups(): List<ServerBackup> {
        val list = call("GET", "/v1/backups", null).getJSONArray("backups")
        return (0 until list.length()).map { parseBackup(list.getJSONObject(it)) }
    }

    fun downloadBackup(id: String): JSONObject = call("GET", "/v1/backups/${enc(id)}", null).getJSONObject("payload")

    fun deleteBackup(id: String) {
        call("DELETE", "/v1/backups/${enc(id)}", null)
    }

    private fun call(method: String, path: String, body: JSONObject?): JSONObject {
        val headers = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
        val text = try {
            Http.send(method, baseUrl + path, headers, body?.toString())
        } catch (e: HttpException) {
            // The server's own message is written to be shown; never the raw response.
            val message = runCatching { JSONObject(e.body).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() && it.length < 200 }
            throw CloudException(message ?: e.userMessage(), signedOut = e.code == 401 && token != null)
        } catch (e: java.io.IOException) {
            throw CloudException(e.userMessage())
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun parseBackup(o: JSONObject): ServerBackup {
        val summaryJson = o.optJSONObject("summary")
        val summary = summaryJson?.keys()?.asSequence()?.associateWith { summaryJson.optInt(it) }.orEmpty()
        return ServerBackup(
            id = o.getString("id"),
            createdAt = o.getLong("createdAt"),
            appVersion = o.optString("appVersion").takeIf { it.isNotEmpty() && !o.isNull("appVersion") },
            device = o.optString("device").takeIf { it.isNotEmpty() && !o.isNull("device") },
            summary = summary,
            size = o.optLong("size"),
        )
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
