package com.cardprice.app.ui.cloud

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardprice.app.data.cloud.BackupBundle
import com.cardprice.app.data.cloud.CloudAccount
import com.cardprice.app.data.cloud.CloudApi
import com.cardprice.app.data.cloud.CloudException
import com.cardprice.app.data.cloud.CloudUrls
import com.cardprice.app.data.cloud.ServerBackup
import com.cardprice.app.data.inventory.InventoryItem
import com.cardprice.app.data.inventory.InventoryStore
import com.cardprice.app.data.scan.ScanLog
import com.cardprice.app.ui.appVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CloudState(
    val serverUrl: String? = null,
    val email: String? = null,
    val signedIn: Boolean = false,
    /** What's running right now ("Backing up…"), or null. */
    val busy: String? = null,
    /** The last result or error, to show once. */
    val message: String? = null,
    val backups: List<ServerBackup>? = null,
    val lastBackupAt: Long? = null,
)

/** A downloaded backup ready to apply: the collection as a file (for the usual restore) and the inventory. */
class RestoreData(val collectionFile: File, val inventory: List<InventoryItem>?)

/** Cloud backup: signing in to the backup server, uploading backups, listing and restoring them. */
class CloudViewModel(application: Application) : AndroidViewModel(application) {
    private val account = CloudAccount(application)
    private val filesDir = application.filesDir
    private val _state = MutableStateFlow(stateFromAccount())
    val state: StateFlow<CloudState> = _state.asStateFlow()

    /** Beta (debug) builds may use a test server on this computer over plain HTTP; release builds never. */
    private val allowLocalHttp = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun stateFromAccount() = CloudState(
        serverUrl = account.serverUrl,
        email = account.email,
        signedIn = account.signedIn,
        lastBackupAt = getApplication<Application>().getSharedPreferences("cloud", Context.MODE_PRIVATE)
            .getLong("last_backup_at", 0L).takeIf { it > 0 },
    )

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun signIn(server: String, email: String, password: String) = authenticate(server, email) { api, device ->
        api.login(email.trim(), password, device)
    }

    fun register(server: String, email: String, password: String, signupCode: String) = authenticate(server, email) { api, device ->
        api.register(email.trim(), password, signupCode.trim(), device)
    }

    private fun authenticate(server: String, email: String, action: (CloudApi, String) -> String) {
        val url = CloudUrls.normalize(server, allowLocalHttp)
        if (url == null) {
            _state.update { it.copy(message = "Enter the server's address, e.g. cards.example.com. It must use HTTPS.") }
            return
        }
        run("Signing in…") {
            val token = action(CloudApi(url), deviceName())
            account.serverUrl = url
            account.email = email.trim()
            account.token = token
            ScanLog.d("cloud: signed in to ${hostOf(url)}")
            _state.value = stateFromAccount().copy(message = "Signed in as ${email.trim()}.")
            loadBackupsNow()
        }
    }

    fun signOut() {
        val url = account.serverUrl
        val token = account.token
        account.token = null
        _state.value = stateFromAccount().copy(message = "Signed out. Backups stay on the server.")
        // Also end the sign-in on the server; if that fails the token still expires there by itself.
        if (url != null && token != null) viewModelScope.launch(Dispatchers.IO) { runCatching { CloudApi(url, token).logout() } }
    }

    fun backUpNow() = run("Backing up…") {
        val api = api() ?: return@run
        val app = getApplication<Application>()
        val version = appVersion(app)
        val prefs = listOf("card_pricer", "scan_setup").associateWith { name ->
            app.getSharedPreferences(name, Context.MODE_PRIVATE).all
        }
        val bundle = BackupBundle.build(filesDir, prefs, version, System.currentTimeMillis())
        val summary = BackupBundle.summary(bundle)
        val saved = api.uploadBackup(bundle, summary, version, deviceName())
        val now = System.currentTimeMillis()
        app.getSharedPreferences("cloud", Context.MODE_PRIVATE).edit().putLong("last_backup_at", now).apply()
        ScanLog.d("cloud: backup uploaded (${saved.size} bytes)")
        _state.update { it.copy(message = "Backed up ${summary["cards"]} cards.", lastBackupAt = now) }
        loadBackupsNow()
    }

    fun loadBackups() = run("Loading backups…") { loadBackupsNow() }

    private fun loadBackupsNow() {
        val api = api() ?: return
        val list = api.listBackups()
        _state.update { it.copy(backups = list) }
    }

    fun delete(backup: ServerBackup) = run("Deleting…") {
        api()?.deleteBackup(backup.id)
        loadBackupsNow()
    }

    /** Downloads [backup] and hands it to [apply] (on the main thread) to replace the collection and inventory. */
    fun restore(backup: ServerBackup, apply: (RestoreData) -> Unit) = run("Restoring…") {
        val api = api() ?: return@run
        val bundle = api.downloadBackup(backup.id)
        BackupBundle.validate(bundle)?.let { throw CloudException(it) }
        val collection = File(getApplication<Application>().cacheDir, "cloud-restore.json")
        collection.writeText(BackupBundle.collectionJson(bundle)!!)
        val inventory = BackupBundle.inventoryJson(bundle)?.let { runCatching { InventoryStore.parse(it) }.getOrNull() }
        withContext(Dispatchers.Main) { apply(RestoreData(collection, inventory)) }
        ScanLog.d("cloud: restored backup from ${backup.createdAt}")
        _state.update { it.copy(message = "Restored. Your previous collection was kept as a local backup.") }
    }

    private fun api(): CloudApi? {
        val url = account.serverUrl
        val token = account.token
        if (url == null || token == null) {
            _state.value = stateFromAccount().copy(message = "Sign in first.")
            return null
        }
        return CloudApi(url, token)
    }

    /** Runs [block] off the main thread, showing [label] meanwhile and any error afterwards. */
    private fun run(label: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = label, message = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudException) {
                ScanLog.w("cloud: $label failed: ${e.message}")
                if (e.signedOut) {
                    account.token = null
                    _state.value = stateFromAccount()
                }
                _state.update { it.copy(message = e.message) }
            } catch (e: Exception) {
                ScanLog.w("cloud: $label failed", e)
                _state.update { it.copy(message = "Something went wrong. Try again.") }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}".take(100)

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: "server"
}
