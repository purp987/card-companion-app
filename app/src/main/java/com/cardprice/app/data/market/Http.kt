package com.cardprice.app.data.market

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object Http {
    // TCGplayer's endpoints reject requests that don't look like they come from a browser.
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

    fun get(url: String, headers: Map<String, String> = emptyMap()): String = request("GET", url, headers, null)

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        request("POST", url, headers + ("Content-Type" to "application/json"), body)

    /** Retries brief server hiccups (502/503/504) a couple of times before giving up. */
    private fun request(method: String, url: String, headers: Map<String, String>, body: String?): String {
        var attempt = 0
        while (true) {
            try {
                return requestOnce(method, url, headers, body)
            } catch (e: HttpException) {
                if (e.code !in RETRYABLE || attempt >= RETRY_DELAYS_MS.size) throw e
                Thread.sleep(RETRY_DELAYS_MS[attempt++])
            }
        }
    }

    private val RETRYABLE = setOf(502, 503, 504)
    private val RETRY_DELAYS_MS = longArrayOf(700, 1500)

    private fun requestOnce(method: String, url: String, headers: Map<String, String>, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpException(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }
}

class HttpException(val code: Int, val body: String) : IOException("HTTP $code")
