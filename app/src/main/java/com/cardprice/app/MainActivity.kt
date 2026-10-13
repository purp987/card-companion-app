package com.cardprice.app

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cardprice.app.data.collection.CardPhotos
import com.cardprice.app.data.scan.ScanHistoryEntry
import com.cardprice.app.data.scan.ScanHistoryStore
import com.cardprice.app.data.scan.ScanLog
import com.cardprice.app.data.vault.ResolveRequest
import com.cardprice.app.data.vault.SearchSort
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.data.vault.VariantMap
import com.cardprice.app.ui.AppIcons
import com.cardprice.app.update.AppUpdater
import com.cardprice.app.update.UpdateBanner
import com.cardprice.app.update.UpdateState
import com.cardprice.app.ui.AppViewModel
import com.cardprice.app.ui.CalculatorScreen
import com.cardprice.app.ui.CurrencyState
import com.cardprice.app.ui.MarketViewModel
import com.cardprice.app.ui.SetListScreen
import com.cardprice.app.ui.collection.BinderActions
import com.cardprice.app.ui.collection.ScanHistoryScreen
import com.cardprice.app.ui.collection.ScanHistoryViewModel
import com.cardprice.app.ui.collection.ScanScreen
import com.cardprice.app.ui.collection.ScanViewModel
import com.cardprice.app.ui.theme.CardPricerTheme
import com.cardprice.app.ui.theme.Palette
import com.cardprice.app.ui.vault.ExploreQuery
import com.cardprice.app.ui.vault.ExploreScreen
import com.cardprice.app.ui.vault.MoreScreen
import com.cardprice.app.ui.vault.PortfolioScreen
import com.cardprice.app.ui.vault.ProductScreen
import com.cardprice.app.ui.vault.SetDetailScreen
import com.cardprice.app.ui.vault.SetsScreen
import com.cardprice.app.ui.vault.SettingsScreen
import com.cardprice.app.ui.vault.VaultHomeScreen
import com.cardprice.app.ui.vault.WatchlistsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Bottom bar: Home · Explore · Scan (center) · Portfolio · More. */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    EXPLORE("explore", "Explore", Icons.Filled.Search),
    SCAN("scan", "Scan", AppIcons.Camera),
    PORTFOLIO("portfolio", "Portfolio", Icons.Filled.Star),
    MORE("more", "More", Icons.Filled.Menu),
}

private const val EXPLORE_ROUTE = "explore?q={q}&sort={sort}&type={type}&minPrice={minPrice}&cat={cat}"

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScanLog.init(filesDir)
        ScanLog.installCrashHandler()
        CardPhotos.init(filesDir)
        Vault.init(this)
        enableEdgeToEdge()
        setContent {
            CardPricerTheme {
                val state by viewModel.state.collectAsState()
                val currency by Vault.currency.collectAsState()
                val rates by Vault.rates.collectAsState()
                LaunchedEffect(currency, rates) { CurrencyState.set(currency, rates[currency]) }
                LaunchedEffect(Unit) { Vault.notices.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() } }

                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val currentRoute = backStack?.destination?.route
                val scope = rememberCoroutineScope()
                val tabRoute = if (currentRoute == EXPLORE_ROUTE) "explore" else currentRoute
                val openProduct: (Long) -> Unit = { id -> nav.navigate("product/$id") }
                val openSet: (Long) -> Unit = { id -> nav.navigate("set/$id") }
                val navigate: (String) -> Unit = { route ->
                    if (route == "portfolio" || route == "more" || route == "home") nav.switchTab(route) else nav.navigate(route)
                }

                Scaffold(
                    containerColor = Palette.bg,
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        Column {
                            // Non-blocking "update available" banner (not over the scanner).
                            if (currentRoute != "scan" && currentRoute != "settings") UpdateBanner()
                            if (tabRoute in Tab.entries.map { it.route } && tabRoute != Tab.SCAN.route) {
                                VaultBottomBar(tabRoute) { tab ->
                                    if (tab == Tab.SCAN) nav.navigate("scan") else nav.switchTab(tab.route)
                                }
                            }
                        }
                    },
                ) { outer ->
                    val bottom = PaddingValues(bottom = outer.calculateBottomPadding())
                    NavHost(
                        navController = nav,
                        startDestination = Tab.HOME.route,
                        modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
                    ) {
                        composable(Tab.HOME.route) { VaultHomeScreen(navigate, openProduct, openSet) }
                        composable(
                            EXPLORE_ROUTE,
                            arguments = listOf("q", "sort", "type", "minPrice", "cat").map { k ->
                                navArgument(k) { type = NavType.StringType; nullable = true; defaultValue = null }
                            },
                        ) { entry ->
                            val a = entry.arguments
                            val initial = remember(a) {
                                val q = a?.getString("q")
                                val sort = a?.getString("sort")
                                val type = a?.getString("type")
                                val min = a?.getString("minPrice")?.toDoubleOrNull()
                                val cat = a?.getString("cat")?.toLongOrNull()
                                if (q == null && sort == null && type == null && min == null && cat == null) null
                                else ExploreQuery(q.orEmpty(), SearchSort.of(sort), type, cat, min)
                            }
                            // Opened from a "See all" link: show a back arrow.
                            val back: (() -> Unit)? = if (initial != null) ({ nav.popBackStack() }) else null
                            ExploreScreen(initial, openProduct, back)
                        }
                        composable(Tab.PORTFOLIO.route) { PortfolioScreen(openProduct) }
                        composable(Tab.MORE.route) { MoreScreen(navigate) }
                        composable("product/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            ProductScreen(entry.arguments!!.getLong("id"), openProduct, openSet) { nav.popBackStack() }
                        }
                        composable("set/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            SetDetailScreen(entry.arguments!!.getLong("id"), openProduct) { nav.popBackStack() }
                        }
                        composable("sets") { SetsScreen(openSet) { nav.popBackStack() } }
                        composable("watchlists") { WatchlistsScreen(openProduct) { nav.popBackStack() } }
                        composable("settings") {
                            SettingsScreen(state.priceChartingToken, viewModel::setPriceChartingToken) { nav.popBackStack() }
                        }
                        composable("calc") {
                            SetListScreen(
                                state = state,
                                onOpenSet = { id -> nav.navigate("calc/${Uri.encode(id)}") },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onAddCustomSet = viewModel::addCustomSet,
                                onDeleteCustomSet = viewModel::deleteCustomSet,
                                onSelectLanguage = viewModel::selectLanguage,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("calc/{setId}") { entry ->
                            val set = entry.arguments?.getString("setId")?.let(state::setById)
                            if (set == null) {
                                // The set was deleted; nothing to show here.
                                LaunchedEffect(Unit) { nav.popBackStack() }
                            } else {
                                val app = application
                                val marketVm: MarketViewModel = viewModel(
                                    key = "market-${set.id}",
                                    factory = viewModelFactory { initializer { MarketViewModel(app, set) } },
                                )
                                LaunchedEffect(state.priceChartingToken) { marketVm.setToken(state.priceChartingToken) }
                                val market by marketVm.state.collectAsState()
                                CalculatorScreen(
                                    set = set,
                                    purchases = state.purchasesFor(set.id),
                                    onBack = { nav.popBackStack() },
                                    onSavePurchase = viewModel::addPurchase,
                                    onDeletePurchase = viewModel::deletePurchase,
                                    market = market,
                                    onRefreshMarket = marketVm::refresh,
                                    onOpenProduct = { p -> openProduct(p.id) },
                                    onChooseServerSet = marketVm::choose,
                                )
                            }
                        }
                        composable("scan") {
                            val scanVm: ScanViewModel = viewModel()
                            val status by scanVm.status.collectAsState()
                            val autoAdd by scanVm.autoAdd.collectAsState()
                            val scanSetup by scanVm.setup.collectAsState()
                            val choosing by scanVm.choosing.collectAsState()
                            val scanMode by scanVm.mode.collectAsState()
                            val binderLayout by scanVm.layout.collectAsState()
                            val bulk by scanVm.bulk.collectAsState()
                            val bulkAdded by scanVm.bulkAdded.collectAsState()
                            val manualAdded by scanVm.manualAdded.collectAsState()
                            val scanPrices by scanVm.prices.collectAsState()
                            val counts by scanVm.counts.collectAsState()
                            val syncNote by scanVm.syncNote.collectAsState()
                            // Quick picks: sets scanned recently (from the scan history), newest first.
                            val recentSets = remember {
                                ScanHistoryStore(File(filesDir, "collection")).load()
                                    .map { Triple(it.language, it.setId, it.setName) }.distinct().take(8)
                            }
                            ScanScreen(
                                status = status,
                                counts = { r, key -> counts["${r.set.language.name}|${r.set.setId}|${r.card.id}|$key"] ?: 0 },
                                syncNote = syncNote,
                                onCameraText = scanVm::onCameraText,
                                onPhotoText = scanVm::onPhotoText,
                                onPhotoError = scanVm::onPhotoError,
                                onCardFrame = scanVm::onCardFrame,
                                onPhotoBitmap = scanVm::onPhotoBitmap,
                                onChoose = scanVm::choose,
                                onNext = scanVm::next,
                                onSetCount = scanVm::setCount,
                                autoAdd = autoAdd,
                                onAutoAddChange = scanVm::setAutoAdd,
                                onUndo = scanVm::undo,
                                onMotion = scanVm::onMotion,
                                onSkip = scanVm::skip,
                                onCameraEvent = scanVm::onCameraEvent,
                                onAddAnother = scanVm::addAnother,
                                onOpenHistory = { nav.navigate("scan-history") },
                                setup = scanSetup,
                                choosing = choosing,
                                recentSets = recentSets,
                                onStart = scanVm::startScanning,
                                onChangeSetup = scanVm::changeSetup,
                                mode = scanMode,
                                layout = binderLayout,
                                bulk = bulk,
                                bulkAdded = bulkAdded,
                                manualAdded = manualAdded,
                                prices = scanPrices,
                                binder = remember(scanVm) {
                                    BinderActions(
                                        onMode = scanVm::setMode,
                                        onLayout = scanVm::setLayout,
                                        onPage = scanVm::onBinderPage,
                                        onReading = scanVm::onPageReading,
                                        onToggle = scanVm::toggleSlot,
                                        onVariant = scanVm::setSlotVariant,
                                        onChoose = scanVm::chooseSlotResult,
                                        onAdd = scanVm::addBulk,
                                        onUndo = scanVm::undoBulk,
                                        onNextPage = scanVm::nextPage,
                                    )
                                },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("scan-history") {
                            val historyVm: ScanHistoryViewModel = viewModel()
                            val entries by historyVm.entries.collectAsState()
                            LaunchedEffect(Unit) { historyVm.reload() }
                            ScanHistoryScreen(
                                entries = entries,
                                onOpen = { e -> scope.launch { openScanned(e)?.let(openProduct) } },
                                onClear = historyVm::clear,
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Keep screens in step with the server while the app is visible (polls /api/app/sync every 20 s).
        Vault.startSync()
    }

    override fun onStop() {
        Vault.stopSync()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Vault.syncNow()
        // Back from "Install unknown apps" with the permission on: carry on with the update.
        (AppUpdater.state.value as? UpdateState.NeedsPermission)?.let { if (AppUpdater.canInstall(this)) AppUpdater.update(this, it.release) }
        AppUpdater.maybeCheck(this)
    }

    /** A scan-history entry's TCG Vault product, looked up like the scanner does. */
    private suspend fun openScanned(e: ScanHistoryEntry): Long? {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                Vault.api.resolve(ResolveRequest(VariantMap.langCode(e.language), e.setId, e.setName, e.number, e.cardName, limit = 1))
            }.firstOrNull()
        }
        result.exceptionOrNull()?.let { Toast.makeText(this, it.message ?: "Couldn't reach TCG Vault", Toast.LENGTH_SHORT).show() }
        val id = result.getOrNull()?.product?.id
        if (result.isSuccess && id == null) Toast.makeText(this, "TCG Vault has no product for ${e.cardName}", Toast.LENGTH_SHORT).show()
        return id
    }
}

/** TCG Vault-style bottom bar with a raised teal Scan button in the middle. */
@Composable
private fun VaultBottomBar(current: String?, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xF20A0F16))) {
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x662FD4B5), Color(0x668B80F9), Color.Transparent))),
        )
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { tab ->
                val selected = current == tab.route
                Box(Modifier.weight(1f).clickable { onSelect(tab) }, contentAlignment = Alignment.Center) {
                    if (tab == Tab.SCAN) {
                        Box(
                            Modifier.offset(y = (-6).dp).size(56.dp).clip(CircleShape).background(Palette.brand)
                                .border(2.dp, Color(0x332FD4B5), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(tab.icon, contentDescription = tab.label, tint = Palette.onAccent, modifier = Modifier.size(26.dp)) }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(tab.icon, contentDescription = null, tint = if (selected) Palette.accent else Palette.text3)
                            Text(tab.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (selected) Palette.accent else Palette.text3)
                        }
                    }
                }
            }
        }
    }
}

/** Standard bottom-bar behavior: one copy of each tab, and each tab keeps its own scroll/state. */
private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
