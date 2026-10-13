package com.cardprice.app.ui.vault

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.userMessage
import com.cardprice.app.data.collection.CollectionStore
import com.cardprice.app.data.inventory.InventoryStore
import com.cardprice.app.data.scan.ScanLearning
import com.cardprice.app.data.vault.Connection
import com.cardprice.app.data.vault.ImportItem
import com.cardprice.app.data.vault.ImportMapper
import com.cardprice.app.data.vault.ImportResult
import com.cardprice.app.data.vault.Portfolio
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.ui.appVersion
import com.cardprice.app.ui.shareDebugLog
import com.cardprice.app.ui.shortDate
import com.cardprice.app.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-time move of the old local collection and inventory into TCG Vault. */
sealed interface MigrationState {
    data object Idle : MigrationState
    data object Working : MigrationState
    /** Dry run done: what would be imported. */
    data class Preview(val collection: ImportResult?, val inventory: ImportResult?, val collectionItems: Int, val inventoryItems: Int) : MigrationState
    data class Done(val collection: ImportResult?, val inventory: ImportResult?) : MigrationState
    data class Failed(val message: String) : MigrationState
}

data class LocalData(val collection: List<ImportItem>, val inventory: List<ImportItem>) {
    val collectionCopies: Int get() = collection.sumOf { it.qty }
    val inventoryUnits: Int get() = inventory.sumOf { it.qty }
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val _portfolios = MutableStateFlow<List<Portfolio>>(emptyList())
    val portfolios: StateFlow<List<Portfolio>> = _portfolios.asStateFlow()
    private val _local = MutableStateFlow<LocalData?>(null)
    val local: StateFlow<LocalData?> = _local.asStateFlow()
    private val _migration = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migration: StateFlow<MigrationState> = _migration.asStateFlow()
    private val _pairing = MutableStateFlow<String?>(null)
    val pairing: StateFlow<String?> = _pairing.asStateFlow()

    init {
        loadPortfolios()
        viewModelScope.launch { _local.value = withContext(Dispatchers.IO) { readLocal() } }
    }

    fun loadPortfolios() {
        viewModelScope.launch { (load { Vault.api.portfolios() } as? Load.Ready)?.value?.let { _portfolios.value = it } }
    }

    /** Reads (never changes) the old collection and inventory files. */
    private fun readLocal(): LocalData {
        val app = getApplication<Application>()
        val store = CollectionStore(app)
        val saved = store.load()
        val collection = ImportMapper.collection(saved.owned, saved.progress) { lang, set -> store.cachedCards(lang, set) }
        val inventory = ImportMapper.inventory(InventoryStore(app.filesDir).load())
        return LocalData(collection, inventory)
    }

    fun pair(code: String) {
        viewModelScope.launch {
            _pairing.value = "Pairing…"
            _pairing.value = try {
                val r = Vault.pair(code)
                loadPortfolios()
                "Paired with ${r.serverName ?: "TCG Vault"}."
            } catch (e: Exception) {
                e.userMessage()
            }
        }
    }

    fun preview() = run(dryRun = true)
    fun import() = run(dryRun = false)

    private fun run(dryRun: Boolean) {
        val data = _local.value ?: return
        _migration.value = MigrationState.Working
        viewModelScope.launch {
            try {
                val (c, i) = withContext(Dispatchers.IO) {
                    val c = data.collection.takeIf { it.isNotEmpty() }?.let { Vault.api.import(ImportMapper.COLLECTION, it, dryRun) }
                    val i = data.inventory.takeIf { it.isNotEmpty() }?.let { Vault.api.import(ImportMapper.INVENTORY, it, dryRun) }
                    c to i
                }
                _migration.value = if (dryRun) {
                    MigrationState.Preview(c, i, data.collection.size, data.inventory.size)
                } else {
                    Vault.settings.importedAt = System.currentTimeMillis()
                    Vault.holdingsChanged()
                    loadPortfolios()
                    MigrationState.Done(c, i)
                }
            } catch (e: Exception) {
                _migration.value = MigrationState.Failed(e.userMessage())
            }
        }
    }

    fun resetMigration() { _migration.value = MigrationState.Idle }
}

private val POPULAR = listOf("USD", "EUR", "GBP", "CAD", "AUD", "JPY", "NZD", "CHF", "SEK", "NOK", "DKK", "SGD", "HKD", "MXN", "BRL", "KRW", "PLN", "CNY", "INR", "PHP", "THB", "ZAR")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    priceChartingToken: String?,
    onSavePriceChartingToken: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel()
    val connection by Vault.connection.collectAsState()
    val rates by Vault.rates.collectAsState()
    val currency by Vault.currency.collectAsState()
    val queue by Vault.queue.collectAsState()
    val portfolios by vm.portfolios.collectAsState()
    val local by vm.local.collectAsState()
    val migration by vm.migration.collectAsState()
    val pairing by vm.pairing.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf(Vault.settings.baseUrl) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var code by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(Vault.settings.deviceName) }
    var token by rememberSaveable { mutableStateOf(priceChartingToken.orEmpty()) }
    var scanPid by remember { mutableStateOf(Vault.settings.scanPortfolioId) }
    var confirmImport by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val learning = remember { ScanLearning.get(context.filesDir) }

    VaultBackground {
        Column(Modifier.fillMaxSize()) {
            VaultTopBar(title = "Settings", onBack = onBack)
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // ---- connection
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("TCG Vault server")
                    Row { ConnectionDot(connection); Text(connectionText(connection), style = MaterialTheme.typography.bodyMedium, color = Palette.text2) }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; urlError = null },
                        label = { Text("Server address") },
                        singleLine = true,
                        isError = urlError != null,
                        supportingText = { Text(urlError ?: "Tailscale Serve address. The phone must be on your tailnet (Tailscale app on).") },
                        colors = vaultFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentButton("Save & test", {
                            Vault.settings.validate(url).onSuccess { u ->
                                url = u
                                Vault.settings.baseUrl = u
                                Vault.refresh()
                                vm.loadPortfolios()
                            }.onFailure { urlError = it.message }
                        })
                        GhostButton("Default", { url = com.cardprice.app.data.vault.VaultApi.DEFAULT_BASE_URL })
                        if (Vault.settings.allowsHttp) GhostButton("Local", { url = "http://localhost:3000" })
                    }
                }

                // ---- pairing
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("Pair this phone")
                    Text(
                        "On your computer, open TCG Vault → Settings → Phone app → Pair a phone. Enter the 6-digit code shown there (valid 10 minutes).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.text2,
                    )
                    OutlinedTextField(deviceName, { deviceName = it; Vault.settings.deviceName = it }, label = { Text("This phone's name") }, singleLine = true, colors = vaultFieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(
                        code,
                        { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("Pairing code") },
                        singleLine = true,
                        colors = vaultFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        AccentButton("Pair", { vm.pair(code); code = "" }, enabled = code.length == 6)
                        if (!Vault.settings.token.isNullOrBlank()) GhostButton("Forget pairing", { Vault.forgetPairing() }, color = Palette.down)
                    }
                    pairing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.accent, modifier = Modifier.padding(top = 6.dp)) }
                }

                // ---- currency
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("Currency")
                    Text("Prices are tracked in US dollars and converted with TCG Vault's daily rates.", style = MaterialTheme.typography.bodySmall, color = Palette.text2)
                    val options = POPULAR.filter { it in rates }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        options.forEach { c -> Pill(c, c == currency, { Vault.setCurrency(c) }) }
                    }
                }

                // ---- scanner
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("Scanner")
                    if (portfolios.isNotEmpty()) {
                        val names = listOf("First portfolio (default)") + portfolios.map { it.name }
                        Choice("Scanned cards go to", portfolios.firstOrNull { it.id == scanPid }?.name ?: names[0], names) { i ->
                            scanPid = if (i == 0) null else portfolios[i - 1].id
                            Vault.settings.scanPortfolioId = scanPid
                        }
                    } else {
                        Text("Connect to TCG Vault to choose the portfolio scans go to.", style = MaterialTheme.typography.bodySmall, color = Palette.text2)
                    }
                    HorizontalDivider(color = Palette.border, modifier = Modifier.padding(vertical = 10.dp))
                    val failed = queue.count { it.error != null }
                    Text(
                        if (queue.isEmpty()) "No scans waiting to be sent." else "${queue.size} scan${if (queue.size == 1) "" else "s"} saved on the phone, waiting for TCG Vault${if (failed > 0) " ($failed couldn't be matched)" else ""}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.text2,
                    )
                    queue.filter { it.error != null }.take(5).forEach { Text("• ${it.label}: ${it.error}", style = MaterialTheme.typography.bodySmall, color = Palette.down) }
                    if (queue.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            GhostButton("Send now", { scope.launch { Vault.flush() } })
                            if (failed > 0) GhostButton("Discard unmatched", { Vault.pending.clearFailed() }, color = Palette.down)
                        }
                    }
                    HorizontalDivider(color = Palette.border, modifier = Modifier.padding(vertical = 10.dp))
                    Text(
                        "The scanner learns from cards you keep, pick or undo. " +
                            if (learning.learnedReadings == 0) "Nothing learned yet." else "Learned ${learning.learnedReadings} readings from ${learning.confirmations} confirmations (${learning.corrections} corrections).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.text2,
                    )
                    TextButton(onClick = { confirmReset = true }) { Text("Reset what it learned", color = Palette.accent) }
                }

                // ---- migration
                MigrationCard(local, migration, onPreview = vm::preview, onImport = { confirmImport = true }, onReset = vm::resetMigration, connected = connection is Connection.Connected)

                // ---- PriceCharting
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("PriceCharting")
                    Text(
                        "Optional: paste the API token from your PriceCharting subscription to show PriceCharting prices next to TCG Vault's on product and calculator screens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.text2,
                    )
                    OutlinedTextField(
                        token,
                        { token = it.trim() },
                        label = { Text("PriceCharting API token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        colors = vaultFieldColors(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Save token", { onSavePriceChartingToken(token.ifBlank { null }) })
                        if (priceChartingToken != null) GhostButton("Remove", { token = ""; onSavePriceChartingToken(null) }, color = Palette.down)
                    }
                }

                // ---- updates
                com.cardprice.app.update.UpdateSettingsCard()

                // ---- about
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("About")
                    Text("Card Companion ${appVersion(context)}", style = MaterialTheme.typography.bodyMedium, color = Palette.text2)
                    Text("Fonts: Space Grotesk and Lato (SIL Open Font License).", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
                    var noLog by remember { mutableStateOf(false) }
                    GhostButton("Send debug log…", { noLog = !shareDebugLog(context) }, Modifier.padding(top = 8.dp))
                    if (noLog) Text("Nothing logged yet. Use the scanner first.", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
                }
            }
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            containerColor = Palette.surfaceSolid,
            title = { Text("Move your collection to TCG Vault?") },
            text = {
                Text(
                    "Matched cards are added to the portfolios \"Collection\" and \"Inventory\" on TCG Vault. Running it again updates them instead of adding duplicates. Nothing is deleted from this phone.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.import(); confirmImport = false }) { Text("Import", color = Palette.accent) } },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Palette.surfaceSolid,
            title = { Text("Reset scanner learning?") },
            text = { Text("The scanner forgets confirmed readings, corrections and preferred versions. Your portfolio isn't affected.") },
            confirmButton = { TextButton(onClick = { learning.reset(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

private fun connectionText(c: Connection): String = when (c) {
    Connection.Unknown, Connection.Checking -> "Checking…"
    is Connection.Connected -> "Connected to ${c.me.serverName ?: "TCG Vault"}${c.me.serverVersion?.let { " $it" } ?: ""} as \"${c.me.deviceName ?: "this phone"}\"${c.me.lastDay?.let { " · prices as of $it" } ?: ""}"
    is Connection.Unpaired -> "Server reachable. ${c.message}"
    is Connection.OldServer -> c.message
    is Connection.Offline -> c.message
}

@Composable
private fun MigrationCard(
    local: LocalData?,
    state: MigrationState,
    onPreview: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit,
    connected: Boolean,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        CardTitle("Move my collection to TCG Vault")
        if (local == null) {
            Text("Reading this phone's collection…", style = MaterialTheme.typography.bodySmall, color = Palette.text2)
            return@GlassCard
        }
        Text(
            "On this phone: ${local.collection.size} card versions (${local.collectionCopies} copies) in the collection and " +
                "${local.inventory.size} inventory items (${local.inventoryUnits} units). They're sent once to TCG Vault; the files on this phone stay as they are.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.text2,
        )
        Vault.settings.importedAt?.let { Text("Last imported ${it.shortDate()}.", style = MaterialTheme.typography.bodySmall, color = Palette.accent) }
        if (!connected) {
            Text("Pair this phone first.", style = MaterialTheme.typography.bodySmall, color = Palette.accent3, modifier = Modifier.padding(top = 6.dp))
        }
        when (state) {
            MigrationState.Idle -> GhostButton("Check matches (nothing is saved)", onPreview, Modifier.padding(top = 8.dp), enabled = connected && (local.collection.isNotEmpty() || local.inventory.isNotEmpty()))
            MigrationState.Working -> LoadingBox(Modifier.fillMaxWidth().padding(12.dp))
            is MigrationState.Failed -> {
                Text(state.message, color = Palette.down, style = MaterialTheme.typography.bodySmall)
                GhostButton("Try again", onReset, Modifier.padding(top = 6.dp))
            }
            is MigrationState.Preview -> {
                ResultLines("Collection", state.collection, state.collectionItems)
                ResultLines("Inventory", state.inventory, state.inventoryItems)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    AccentButton("Import matched items", onImport)
                    GhostButton("Cancel", onReset)
                }
            }
            is MigrationState.Done -> {
                Text("Done.", style = MaterialTheme.typography.titleSmall, color = Palette.up)
                ResultLines("Collection", state.collection, null)
                ResultLines("Inventory", state.inventory, null)
                GhostButton("OK", onReset, Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ResultLines(label: String, r: ImportResult?, total: Int?) {
    if (r == null) return
    val matched = if (r.dryRun) r.matched.size.takeIf { it > 0 } ?: (total?.minus(r.unmatched.size) ?: r.imported) else r.imported
    Text(
        "$label: $matched ${if (r.dryRun) "would be imported" else "imported"}${if (r.updated > 0) " (${r.updated} updated)" else ""}, ${r.unmatched.size} not matched",
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.text,
        modifier = Modifier.padding(top = 6.dp),
    )
    r.unmatched.take(5).forEach { u -> Text("• ${refLabel(u.ref)}: ${u.reason}", style = MaterialTheme.typography.bodySmall, color = Palette.text3) }
    if (r.unmatched.size > 5) Text("…and ${r.unmatched.size - 5} more", style = MaterialTheme.typography.bodySmall, color = Palette.text3)
}

/** "ENGLISH|sv03.5|sv03.5-199|holo" → "sv03.5-199 (holo)"; inventory refs as they are. */
private fun refLabel(ref: String): String {
    val parts = ref.split('|')
    return if (parts.size >= 4) "${parts[2]} (${parts.drop(3).joinToString("|")})" else ref.removePrefix("inventory:")
}
