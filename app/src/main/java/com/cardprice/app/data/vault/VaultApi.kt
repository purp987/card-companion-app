package com.cardprice.app.data.vault

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A raw HTTP exchange, so tests can swap the network for a fake. */
fun interface VaultTransport {
    /** Returns the status code and body; throws [IOException] when the server can't be reached. */
    fun execute(method: String, url: String, headers: Map<String, String>, body: String?): VaultResponse
}

data class VaultResponse(val code: Int, val body: String)

/** Why a request to TCG Vault failed, in terms the screens can explain. */
sealed class VaultException(message: String) : IOException(message) {
    /** No pairing token, or the server revoked it (401). */
    class Unauthorized(message: String) : VaultException(message)
    /** 404: the item doesn't exist, or (for `/api/app/…`) the server is older than this app. */
    class NotFound(message: String, val path: String) : VaultException(message)
    /** 429: too many pairing attempts. */
    class RateLimited(message: String) : VaultException(message)
    /** Any other non-2xx answer. */
    class Http(val code: Int, message: String) : VaultException(message)
    /** The server couldn't be reached (offline, Tailscale off, wrong address). */
    class Offline(message: String, cause: Throwable? = null) : VaultException(message) {
        init { if (cause != null) initCause(cause) }
    }
    /** No server address set, or it's not a usable http(s) URL. */
    class NotConfigured(message: String) : VaultException(message)
}

/** Plain `HttpURLConnection` transport (no extra dependencies), with brief retries for 502/503/504. */
class UrlConnectionTransport(private val connectTimeoutMs: Int = 10_000, private val readTimeoutMs: Int = 25_000) : VaultTransport {
    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): VaultResponse {
        var attempt = 0
        while (true) {
            val r = once(method, url, headers, body)
            if (r.code !in RETRYABLE || attempt >= RETRY_DELAYS_MS.size) return r
            Thread.sleep(RETRY_DELAYS_MS[attempt++])
        }
    }

    private fun once(method: String, url: String, headers: Map<String, String>, body: String?): VaultResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return VaultResponse(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        val RETRYABLE = setOf(502, 503, 504)
        val RETRY_DELAYS_MS = longArrayOf(600, 1500)
    }
}

/**
 * Client for the TCG Vault server (see `docs/app-api.md` in the server repository). Every request sends
 * `Authorization: Bearer <token>` when the phone is paired; `/api/app/…` routes need it, the rest ignore it.
 * Calls block, so run them off the main thread.
 */
class VaultApi(
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val transport: VaultTransport = UrlConnectionTransport(),
    private val opener: StreamOpener = UrlConnectionStreamOpener(),
) {
    /** Absolute URL for a server path ("/img/p/123"), or null when no server is set. */
    fun absolute(path: String): String? = baseUrl()?.let { normalizeBaseUrl(it) }?.let { it + path }

    // ---- app endpoints ----
    fun pair(code: String, deviceName: String): PairResult =
        VaultJson.pair(call("POST", "/api/app/pair", VaultJson.pairBody(code, deviceName), auth = false))

    fun me(): Me = VaultJson.me(call("GET", "/api/app/me", requireToken = true))

    fun resolve(request: ResolveRequest): List<ResolveMatch> =
        VaultJson.resolve(call("POST", "/api/app/resolve", VaultJson.resolveBody(request), requireToken = true))

    fun import(portfolio: String, items: List<ImportItem>, dryRun: Boolean): ImportResult =
        VaultJson.importResult(call("POST", "/api/app/import", VaultJson.importBody(portfolio, items, dryRun), requireToken = true))

    /** Uploads a batch of scanner-log events (beta only; see server/scanLog.js). Body built by ScanLogUploader. */
    fun scanLog(body: String) {
        call("POST", "/api/app/scan-log", body, requireToken = true)
    }

    /** Change counters for holdings, portfolios and watchlist plus the price day (`GET /api/app/sync`, see [SyncState]). */
    fun sync(): SyncState = VaultJson.sync(call("GET", "/api/app/sync", requireToken = true))

    /** Is there a newer build of this app on the server? (`GET /api/app/update`) */
    fun checkUpdate(appId: String, versionCode: Long): UpdateCheck =
        AppUpdates.parse(call("GET", "/api/app/update" + query("appId" to appId, "versionCode" to versionCode), requireToken = true))

    /**
     * Downloads a release's APK to [dest] with the pairing token, reporting (bytes, total), then checks
     * its SHA-256 against the published one (and the server's X-Content-SHA256). A mismatch deletes the file.
     */
    fun downloadApk(release: AppRelease, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val base = baseUrl()?.let(::normalizeBaseUrl) ?: throw VaultException.NotConfigured("Set your TCG Vault server address in Settings.")
        val tok = token()?.takeIf { it.isNotBlank() } ?: throw VaultException.Unauthorized("Pair this phone with TCG Vault first.")
        val url = if (release.url.startsWith("http")) release.url else base + release.url
        val response = try {
            opener.open(url, mapOf("Authorization" to "Bearer $tok"))
        } catch (e: IOException) {
            throw VaultException.Offline("Couldn't reach TCG Vault to download the update.", e)
        }
        try {
            if (response.code !in 200..299) {
                val msg = VaultJson.errorMessage(response.body.bufferedReader().readText())
                throw when (response.code) {
                    401 -> VaultException.Unauthorized(msg ?: "TCG Vault didn't accept this phone. Pair it again.")
                    404 -> VaultException.NotFound(msg ?: "That update is no longer on the server.", release.url)
                    else -> VaultException.Http(response.code, msg ?: "Download failed (${response.code}).")
                }
            }
            val total = response.length.takeIf { it > 0 } ?: release.size
            dest.parentFile?.mkdirs()
            try {
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = response.body.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            } catch (e: IOException) {
                dest.delete()
                throw VaultException.Offline("The download was interrupted. Try again.", e)
            }
            response.sha256?.takeIf { it.isNotBlank() && !it.equals(release.sha256, ignoreCase = true) }?.let {
                dest.delete()
                throw BadDownloadException("The server's checksum doesn't match the release. Try again.")
            }
            AppUpdates.verify(dest, release.sha256)
        } finally {
            response.close()
        }
    }

    // ---- catalog ----
    fun home(): HomeFeed = VaultJson.home(call("GET", "/api/home"))

    fun status(): ServerStatus = VaultJson.status(call("GET", "/api/status"))

    fun rates(): Map<String, Double> = VaultJson.rates(call("GET", "/api/rates"))

    fun search(
        q: String? = null,
        cat: Long? = null,
        set: Long? = null,
        type: String? = null,
        sort: SearchSort = SearchSort.BEST,
        minPrice: Double? = null,
        limit: Int = 48,
        offset: Int = 0,
    ): SearchPage = VaultJson.search(
        call(
            "GET",
            "/api/search" + query(
                "q" to q?.trim()?.ifEmpty { null },
                "cat" to cat,
                "set" to set,
                "type" to type,
                "sort" to sort.key,
                "minPrice" to minPrice?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() },
                "limit" to limit,
                "offset" to offset.takeIf { it > 0 },
            ),
        ),
    )

    fun sets(cat: Long?): List<VaultSet> = VaultJson.sets(call("GET", "/api/sets" + query("cat" to cat)))

    fun set(id: Long): SetDetail = VaultJson.setDetail(call("GET", "/api/sets/$id"))

    fun product(id: Long): ProductDetail = VaultJson.productDetail(call("GET", "/api/products/$id"))

    fun history(id: Long, range: PriceRange, variantId: Long? = null): PriceHistory =
        VaultJson.history(call("GET", "/api/products/$id/history" + query("range" to range.key, "variant" to variantId)))

    /** Spends server API credits; the server limits it to once a day per card. */
    fun loadGraded(id: Long) {
        call("POST", "/api/products/$id/graded", "{}")
    }

    // ---- portfolio ----
    fun portfolios(): List<Portfolio> = VaultJson.portfolios(call("GET", "/api/portfolios"))

    fun createPortfolio(name: String): Long? = VaultJson.createdId(call("POST", "/api/portfolios", VaultJson.nameBody(name)))

    fun renamePortfolio(id: Long, name: String) {
        call("PUT", "/api/portfolios/$id", VaultJson.nameBody(name))
    }

    fun deletePortfolio(id: Long) {
        call("DELETE", "/api/portfolios/$id")
    }

    fun portfolio(pid: Long?): PortfolioView = VaultJson.portfolio(call("GET", "/api/portfolio" + query("pid" to pid)))

    fun portfolioHistory(pid: Long?, range: PriceRange): List<PricePoint> =
        VaultJson.portfolioHistory(call("GET", "/api/portfolio/history" + query("pid" to pid, "range" to range.key)))

    /** Movers, set completion and milestones for a fun portfolio view (GET /api/portfolio/fun). */
    fun portfolioFun(pid: Long?): PortfolioFun =
        VaultJson.portfolioFun(call("GET", "/api/portfolio/fun" + query("pid" to pid)))

    fun addHolding(h: HoldingInput): Long? = VaultJson.createdId(call("POST", "/api/holdings", VaultJson.holdingBody(h)))

    fun updateHolding(id: Long, h: HoldingInput) {
        call("PUT", "/api/holdings/$id", VaultJson.holdingBody(h))
    }

    fun deleteHolding(id: Long) {
        call("DELETE", "/api/holdings/$id")
    }

    // ---- watchlists ----
    fun watchlist(): List<ProductItem> = VaultJson.watchlist(call("GET", "/api/watchlist"))

    fun setWatched(productId: Long, watched: Boolean) {
        call(if (watched) "PUT" else "DELETE", "/api/watchlist/$productId")
    }

    // ---- plumbing ----
    private fun call(method: String, path: String, body: String? = null, auth: Boolean = true, requireToken: Boolean = false): String {
        val base = baseUrl()?.let(::normalizeBaseUrl) ?: throw VaultException.NotConfigured("Set your TCG Vault server address in Settings.")
        val tok = if (auth) token()?.takeIf { it.isNotBlank() } else null
        if (requireToken && tok == null) throw VaultException.Unauthorized("Pair this phone with TCG Vault first (Settings → Pair).")
        val headers = buildMap {
            if (tok != null) put("Authorization", "Bearer $tok")
        }
        val response = try {
            transport.execute(method, base + path, headers, body)
        } catch (e: VaultException) {
            throw e
        } catch (e: IOException) {
            throw VaultException.Offline("Couldn't reach TCG Vault. Check that Tailscale is on and the server is running.", e)
        }
        if (response.code in 200..299) return response.body
        val message = VaultJson.errorMessage(response.body)
        throw when (response.code) {
            401 -> VaultException.Unauthorized(message ?: "TCG Vault didn't accept this phone. Pair it again in Settings.")
            404 -> VaultException.NotFound(
                message ?: if (path.startsWith("/api/app/")) "This TCG Vault server doesn't support the app yet. Update and restart it." else "Not found.",
                path,
            )
            429 -> VaultException.RateLimited(message ?: "Too many attempts. Wait a few minutes and try again.")
            else -> VaultException.Http(response.code, message ?: "TCG Vault returned an error (${response.code}).")
        }
    }

    companion object {
        /** From `tcgVault.serverUrl` in local.properties at build time (not in the repository); "" if unset. */
        val DEFAULT_BASE_URL: String get() = com.cardprice.app.BuildConfig.DEFAULT_SERVER_URL

        /** "host:8443/" → "https://host:8443"; null when it isn't an http(s) address. */
        fun normalizeBaseUrl(raw: String): String? {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "https://$s"
            val url = runCatching { URL(s) }.getOrNull() ?: return null
            if (url.host.isNullOrBlank()) return null
            return s.removeSuffix("/api")
        }

        internal fun query(vararg params: Pair<String, Any?>): String {
            val parts = params.filter { it.second != null }.map { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), "UTF-8") }
            return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        }
    }
}
