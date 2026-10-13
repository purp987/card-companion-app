package com.cardprice.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.cardprice.app.MainActivity
import com.cardprice.app.data.market.userMessage
import com.cardprice.app.data.scan.ScanLog
import com.cardprice.app.data.vault.AppRelease
import com.cardprice.app.data.vault.AppUpdates
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.data.vault.VaultException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where an over-the-air update stands, for the banner and Settings. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    /** Android needs "Install unknown apps" allowed for this app first. */
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val done: Long, val total: Long) : UpdateState {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/**
 * Over-the-air updates from TCG Vault: checks `/api/app/update` (on start and resume, at most every
 * 6 hours, or on demand), downloads the APK with the pairing token, verifies its SHA-256 and installs it
 * with a PackageInstaller session. On Android 12+ later updates can install without a prompt once this
 * app is the installer of record; the first time Android asks.
 */
object AppUpdater {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    /** Release the user closed the banner for this run (they can still update from Settings). */
    var dismissedVersion: Long? = null

    fun versionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Automatic check (app start/resume): skipped when the last one was under 6 hours ago or not paired. */
    fun maybeCheck(context: Context) {
        if (Vault.settings.token.isNullOrBlank()) return
        if (!AppUpdates.shouldCheck(Vault.settings.lastUpdateCheck, System.currentTimeMillis())) return
        check(context, manual = false)
    }

    fun check(context: Context, manual: Boolean = true) {
        val app = context.applicationContext
        val busy = _state.value
        if (busy is UpdateState.Downloading || busy is UpdateState.Installing || busy is UpdateState.Checking) return
        _state.value = UpdateState.Checking
        Vault.scope.launch {
            val current = versionCode(app)
            _state.value = try {
                val result = Vault.api.checkUpdate(app.packageName, current)
                Vault.settings.lastUpdateCheck = System.currentTimeMillis()
                val latest = result.latest
                if (latest != null && AppUpdates.isUpdate(result, current, Build.VERSION.SDK_INT)) UpdateState.Available(latest)
                else UpdateState.UpToDate(versionName(app))
            } catch (e: Exception) {
                // An automatic check that fails (offline, older server) stays quiet.
                if (manual) UpdateState.Failed(e.userMessage()) else UpdateState.Idle
            }
        }
    }

    /** Downloads, verifies and installs [release]. */
    fun update(context: Context, release: AppRelease) {
        val app = context.applicationContext
        if (!canInstall(app)) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        _state.value = UpdateState.Downloading(release, 0, release.size)
        Vault.scope.launch {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // old downloads
            val apk = File(dir, "card-companion-${release.versionCode}.apk")
            try {
                var last = 0L
                Vault.api.downloadApk(release, apk) { done, total ->
                    if (done - last > 256 * 1024 || done == total) {
                        last = done
                        _state.value = UpdateState.Downloading(release, done, total)
                    }
                }
                _state.value = UpdateState.Installing(release)
                withContext(Dispatchers.IO) { install(app, apk) }
            } catch (e: Exception) {
                apk.delete()
                ScanLog.w("update ${release.versionCode} failed", e)
                _state.value = UpdateState.Failed(if (e is VaultException || e is java.io.IOException) e.message ?: e.userMessage() else e.userMessage(), release)
            }
        }
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" page for this app. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12+: no confirmation once this app installed the current version itself.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        ScanLog.d("update session $sessionId committed")
    }

    internal fun onResult(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        ScanLog.d("update status $status ${message.orEmpty()}")
        val release = (_state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Normally this process is already replaced by the new version; restart is handled by [UpdatedReceiver].
                _state.value = UpdateState.UpToDate(versionName(context))
            }
            else -> {
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the update."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "The update conflicts with the installed app (is it signed with a different key?)."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update isn't compatible with this phone or the installed app's signature."
                    PackageInstaller.STATUS_FAILURE_INVALID -> "The update file is invalid."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
                    else -> "The update couldn't be installed."
                } + (message?.let { " ($it)" } ?: "")
                _state.value = UpdateState.Failed(reason, release)
                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            }
        }
    }

    const val ACTION_INSTALL_RESULT = "com.cardprice.app.UPDATE_INSTALL_RESULT"
}

/** Receives the PackageInstaller session result. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppUpdater.ACTION_INSTALL_RESULT) AppUpdater.onResult(context, intent)
    }
}

/** After an update replaced this app, open it again. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
