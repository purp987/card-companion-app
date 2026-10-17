package com.cardprice.app.data.vault

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.provider.Settings
import com.cardprice.app.data.SecretStore
import com.cardprice.app.data.scan.ScanLogUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate

/** How often the beta build flushes the scanner log to the server. */
private const val SCAN_LOG_FLUSH_MS = 20_000L

/** Where the app stands with its server, for the status dot and Settings. */
sealed interface Connection {
    data object Unknown : Connection
    data object Checking : Connection
    /** Reachable and paired. */
    data class Connected(val me: Me) : Connection
    /** Reachable (catalog works) but the phone isn't paired, or its token was revoked. */
    data class Unpaired(val message: String) : Connection
    /** Reachable, but `/api/app/…` is missing: the server needs updating and a restart. */
    data class OldServer(val message: String) : Connection
    data class Offline(val message: String) : Connection
}

/** App-wide settings for the server connection. The token lives in [SecretStore] (Android Keystore). */
class VaultSettings(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    /** Beta builds may use plain http://localhost through `adb reverse` (res/values/flags.xml). */
    val allowsHttp: Boolean = context.resources.getBoolean(com.cardprice.app.R.bool.allow_local_http)
    private val defaultDeviceName: String = runCatching {
        Settings.Global.getString(context.contentResolver, "device_name")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    var baseUrl: String
        get() = prefs.getString(KEY_URL, null) ?: VaultApi.DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var token: String?
        get() = runCatching { secrets.get(KEY_TOKEN) }.getOrNull()
        set(value) = secrets.put(KEY_TOKEN, value)

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE, null) ?: defaultDeviceName
        set(value) = prefs.edit().putString(KEY_DEVICE, value).apply()

    var currency: String
        get() = prefs.getString(KEY_CURRENCY, null) ?: "USD"
        set(value) = prefs.edit().putString(KEY_CURRENCY, value).apply()

    /** Portfolio the scanner adds to; null = the server's first portfolio. */
    var scanPortfolioId: Long?
        get() = prefs.getLong(KEY_SCAN_PID, -1).takeIf { it > 0 }
        set(value) = prefs.edit().putLong(KEY_SCAN_PID, value ?: -1).apply()

    /** Portfolio picked on the Portfolio screen; null = all. */
    var portfolioId: Long?
        get() = prefs.getLong(KEY_PID, -1).takeIf { it > 0 }
        set(value) = prefs.edit().putLong(KEY_PID, value ?: -1).apply()

    var importedAt: Long?
        get() = prefs.getLong(KEY_IMPORTED, -1).takeIf { it > 0 }
        set(value) = prefs.edit().putLong(KEY_IMPORTED, value ?: -1).apply()

    /** When the app last asked TCG Vault for an update (automatic checks run every 6 hours at most). */
    var lastUpdateCheck: Long?
        get() = prefs.getLong(KEY_UPDATE_CHECK, -1).takeIf { it > 0 }
        set(value) = prefs.edit().putLong(KEY_UPDATE_CHECK, value ?: -1).apply()

    /** Checks an address before it's saved: an http(s) URL, and https unless this is a beta build. */
    fun validate(raw: String): Result<String> {
        val url = VaultApi.normalizeBaseUrl(raw) ?: return Result.failure(IllegalArgumentException("That isn't a web address."))
        if (url.startsWith("http://", ignoreCase = true) && !allowsHttp) {
            return Result.failure(IllegalArgumentException("Use the https:// address (Tailscale Serve)."))
        }
        return Result.success(url)
    }

    private companion object {
        const val KEY_URL = "base_url"
        const val KEY_TOKEN = "vault_token"
        const val KEY_DEVICE = "device_name"
        const val KEY_CURRENCY = "currency"
        const val KEY_SCAN_PID = "scan_portfolio"
        const val KEY_PID = "portfolio"
        const val KEY_IMPORTED = "imported_at"
        const val KEY_UPDATE_CHECK = "update_checked_at"
    }
}

/**
 * The app's single connection to TCG Vault: settings, API client, currency rates, tracked games and
 * the offline add queue (sent whenever the server is reachable again).
 */
object Vault {
    lateinit var settings: VaultSettings
        private set
    lateinit var api: VaultApi
        private set
    lateinit var pending: PendingAdds
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow<Connection>(Connection.Unknown)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _rates = MutableStateFlow(mapOf("USD" to 1.0))
    val rates: StateFlow<Map<String, Double>> = _rates.asStateFlow()

    private val _currency = MutableStateFlow("USD")
    val currency: StateFlow<String> = _currency.asStateFlow()

    private val _tracked = MutableStateFlow<List<TrackedCategory>>(emptyList())
    val tracked: StateFlow<List<TrackedCategory>> = _tracked.asStateFlow()

    private val _queue = MutableStateFlow<List<PendingAdd>>(emptyList())
    val queue: StateFlow<List<PendingAdd>> = _queue.asStateFlow()

    /** Bumped whenever holdings (or their values) change on the server, from any source, so portfolio screens reload. */
    private val _holdingsVersion = MutableStateFlow(0)
    val holdingsVersion: StateFlow<Int> = _holdingsVersion.asStateFlow()

    /** Bumped when portfolios are created, renamed or deleted on the server. */
    private val _portfoliosVersion = MutableStateFlow(0)
    val portfoliosVersion: StateFlow<Int> = _portfoliosVersion.asStateFlow()

    /** Bumped when the Chase List / Shrink Wrap Watch changes on the server. */
    private val _watchlistVersion = MutableStateFlow(0)
    val watchlistVersion: StateFlow<Int> = _watchlistVersion.asStateFlow()

    /** Bumped when a new daily price set lands (home rows, values and history change). */
    private val _pricesVersion = MutableStateFlow(0)
    val pricesVersion: StateFlow<Int> = _pricesVersion.asStateFlow()

    /** When the app last heard from the server (ms), and whether the last try failed (showing older data). */
    private val _syncedAt = MutableStateFlow<Long?>(null)
    val syncedAt: StateFlow<Long?> = _syncedAt.asStateFlow()
    private val _syncOffline = MutableStateFlow(false)
    val syncOffline: StateFlow<Boolean> = _syncOffline.asStateFlow()

    /** One-off messages for the user (e.g. the scanner's portfolio was deleted elsewhere). */
    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: kotlinx.coroutines.flow.SharedFlow<String> = _notices

    /** Turns `/api/app/sync` answers into change signals (see [SyncMonitor]). */
    private lateinit var monitor: SyncMonitor
    private var syncJob: kotlinx.coroutines.Job? = null
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val pollLock = Mutex()

    private val flushLock = Mutex()
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        settings = VaultSettings(app)
        api = VaultApi(baseUrl = { settings.baseUrl }, token = { settings.token })
        pending = PendingAdds(FilePendingStore(File(File(app.filesDir, "vault"), "pending-adds.json")))
        pending.observe { _queue.value = it }
        monitor = SyncMonitor { api.sync() }
        _currency.value = settings.currency
        // Send queued scans as soon as the phone is back online.
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { flush() }
                    if (com.cardprice.app.BuildConfig.SCAN_LOG_UPLOAD) scope.launch { ScanLogUploader.flush() }
                }
            })
        }
        startScanLogUpload(app)
        refresh()
    }

    /**
     * Beta only: forward the scanner's log to the server so detection problems can be looked into. Flushes
     * on a timer; the scanner also flushes when it closes. Release builds never do this (gated at build time).
     */
    private fun startScanLogUpload(app: Context) {
        if (!com.cardprice.app.BuildConfig.SCAN_LOG_UPLOAD) return
        val info = ScanLogUploader.deviceInfo(
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            android = Build.VERSION.RELEASE ?: "",
            appVersion = runCatching {
                val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                pi.versionName ?: ""
            }.getOrDefault(""),
            appVersionCode = runCatching {
                val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            }.getOrDefault(0L),
        )
        ScanLogUploader.start(app.filesDir, info) { session, device, events ->
            // Blocks on the uploader's own thread; throwing on failure keeps the batch queued for retry.
            api.scanLog(ScanLogUploader.body(session, device, events).toString())
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(SCAN_LOG_FLUSH_MS)
                if (!settings.token.isNullOrBlank()) runCatching { ScanLogUploader.flush() }
            }
        }
    }

    /**
     * This app changed holdings on the server. With live sync, the next `/api/app/sync` answer carries the new
     * rev and triggers one quiet reload (so the change isn't loaded twice); without it (older server, or no
     * baseline yet) screens reload right away.
     */
    fun holdingsChanged() {
        if (syncJob?.isActive == true && !monitor.unsupported && monitor.last != null) syncNow()
        else {
            _holdingsVersion.update { it + 1 }
            // Adding a holding takes the product off a watchlist on the server, so refresh that too.
            _watchlistVersion.update { it + 1 }
        }
    }

    /** Any other change made here (watchlist star, portfolio rename): just ask the server sooner. */
    fun changedOnServer() = syncNow()

    // ---- live sync -------------------------------------------------------------------------------
    // While the app is in the foreground it polls GET /api/app/sync every 20 s (backing off when the
    // server can't be reached), plus right away on start/resume and after the offline queue is sent.

    /** Start polling (Activity onStart). */
    fun startSync() {
        if (syncJob?.isActive == true) {
            syncNow()
            return
        }
        syncJob = scope.launch {
            while (true) {
                pollOnce()
                kotlinx.coroutines.withTimeoutOrNull(monitor.nextDelayMs()) { wake.receive() }
            }
        }
    }

    /** Stop polling (Activity onStop): nothing runs in the background. */
    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /** Poll now instead of waiting for the next tick. */
    fun syncNow() {
        if (syncJob?.isActive == true) wake.trySend(Unit) else scope.launch { pollOnce() }
    }

    /** Re-reads everything (pull to refresh). */
    fun reloadAll() {
        _holdingsVersion.update { it + 1 }
        _portfoliosVersion.update { it + 1 }
        _watchlistVersion.update { it + 1 }
        syncNow()
    }

    private suspend fun pollOnce() = pollLock.withLock {
        if (settings.token.isNullOrBlank()) return@withLock
        when (val r = monitor.poll()) {
            PollResult.Baseline, PollResult.Unchanged -> markSynced()
            is PollResult.Changed -> {
                markSynced()
                val s = r.signals
                if (s.portfolios) {
                    _portfoliosVersion.update { it + 1 }
                    checkScanPortfolio()
                }
                if (s.holdings || s.prices) _holdingsVersion.update { it + 1 }
                if (s.watchlist) _watchlistVersion.update { it + 1 }
                if (s.prices) _pricesVersion.update { it + 1 }
            }
            is PollResult.Offline -> _syncOffline.value = true
            PollResult.Unauthorized -> _connection.value = Connection.Unpaired("TCG Vault didn't accept this phone. Pair it again in Settings.")
            PollResult.Unsupported -> Unit // older server: screens reload after this app's own changes only
        }
    }

    private fun markSynced() {
        _syncedAt.value = monitor.lastOkAt
        _syncOffline.value = false
        if (_connection.value is Connection.Offline) refresh()
    }

    /** The portfolio scans go to was deleted elsewhere: fall back to the first one and say so. */
    private fun checkScanPortfolio() {
        val chosen = settings.scanPortfolioId ?: return
        val list = runCatching { api.portfolios() }.getOrNull() ?: return
        if (list.none { it.id == chosen }) {
            settings.scanPortfolioId = null
            _notices.tryEmit(
                "The portfolio your scans went to was deleted. Scans now go to " +
                    (list.firstOrNull()?.name?.let { "\"$it\"" } ?: "your first portfolio") + ".",
            )
        }
    }

    fun setCurrency(code: String) {
        settings.currency = code
        _currency.value = code
    }

    /** Re-checks the connection, reloads rates and tracked games, then sends queued adds. */
    fun refresh() {
        scope.launch {
            _connection.value = Connection.Checking
            val status = runCatching { api.status() }
            status.onSuccess { s -> if (s.tracked.isNotEmpty()) _tracked.value = s.tracked }
            runCatching { api.rates() }.onSuccess { r -> if (r.isNotEmpty()) _rates.value = r + ("USD" to 1.0) }
            _connection.value = when {
                status.exceptionOrNull() is VaultException.Offline || status.exceptionOrNull() is VaultException.NotConfigured ->
                    Connection.Offline(status.exceptionOrNull()?.message ?: "Offline")
                settings.token.isNullOrBlank() -> Connection.Unpaired("Not paired yet. Pair this phone to scan and import.")
                else -> try {
                    val me = api.me()
                    if (me.tracked.isNotEmpty()) _tracked.value = me.tracked
                    Connection.Connected(me)
                } catch (e: VaultException.Unauthorized) {
                    Connection.Unpaired(e.message ?: "This phone isn't paired.")
                } catch (e: VaultException.NotFound) {
                    Connection.OldServer(e.message ?: "Server needs an update.")
                } catch (e: VaultException) {
                    Connection.Offline(e.message ?: "Offline")
                }
            }
            flush()
        }
    }

    /** Pairs with a 6-digit code from the web UI (Settings → Phone app → Pair a phone). */
    suspend fun pair(code: String): PairResult {
        val result = kotlinx.coroutines.withContext(Dispatchers.IO) { api.pair(code.trim(), settings.deviceName) }
        settings.token = result.token
        monitor.reset()
        refresh()
        syncNow()
        return result
    }

    fun forgetPairing() {
        settings.token = null
        _connection.value = Connection.Unpaired("This phone was unpaired.")
    }

    /** The portfolio new scans go to: the chosen one, else the server's first. */
    fun scanPortfolio(): Long? = settings.scanPortfolioId ?: runCatching { api.portfolios().firstOrNull()?.id }.getOrNull()

    /** Sends the offline queue (one flush at a time). */
    suspend fun flush(): FlushResult? {
        if (pending.size == 0) return null
        return flushLock.withLock {
            val r = pending.flush(api, ::scanPortfolio) { LocalDate.now().toString() }
            if (r.sent > 0) holdingsChanged()
            syncNow()
            r
        }
    }

    fun today(): String = LocalDate.now().toString()
}
