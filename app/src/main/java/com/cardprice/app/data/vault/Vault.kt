package com.cardprice.app.data.vault

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.provider.Settings
import com.cardprice.app.data.SecretStore
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

    /** Bumped whenever holdings change on the server from this app, so portfolio screens reload. */
    private val _holdingsVersion = MutableStateFlow(0)
    val holdingsVersion: StateFlow<Int> = _holdingsVersion.asStateFlow()

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
        _currency.value = settings.currency
        // Send queued scans as soon as the phone is back online.
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { flush() }
                }
            })
        }
        refresh()
    }

    fun holdingsChanged() = _holdingsVersion.update { it + 1 }

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
        refresh()
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
            r
        }
    }

    fun today(): String = LocalDate.now().toString()
}
