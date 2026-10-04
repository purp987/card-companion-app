package com.cardprice.app

import android.app.Application
import android.net.Uri
import android.os.Bundle
import com.cardprice.app.data.collection.CardPhotos
import com.cardprice.app.ui.money
import com.cardprice.app.ui.display
import com.cardprice.app.ui.appVersion
import com.cardprice.app.data.inventory.InventoryStatus
import com.cardprice.app.data.cloud.LiveStream
import com.cardprice.app.data.inventory.CollectionLink
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.cardprice.app.data.collection.CollectionStore
import com.cardprice.app.ui.cloud.CloudViewModel
import com.cardprice.app.ui.cloud.CloudBackupDialog
import com.cardprice.app.ui.AppIcons
import com.cardprice.app.ui.inventory.InventoryViewModel
import com.cardprice.app.ui.inventory.InventoryScreen
import com.cardprice.app.data.scan.ScanLog
import com.cardprice.app.data.market.Load
import com.cardprice.app.ui.collectionSetArt
import com.cardprice.app.data.collection.CardSeries
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.cardprice.app.data.Language
import com.cardprice.app.ui.AppViewModel
import com.cardprice.app.ui.CalculatorScreen
import com.cardprice.app.ui.HistoryScreen
import com.cardprice.app.ui.HistoryViewModel
import com.cardprice.app.ui.HomeScreen
import com.cardprice.app.ui.SearchScreen
import com.cardprice.app.ui.SearchViewModel
import com.cardprice.app.ui.MarketViewModel
import com.cardprice.app.ui.SetListScreen
import com.cardprice.app.ui.collection.CatalogViewModel
import com.cardprice.app.ui.collection.CollectionHomeScreen
import com.cardprice.app.ui.collection.CollectionSetScreen
import com.cardprice.app.ui.collection.CollectionViewModel
import com.cardprice.app.ui.collection.ScanHistoryScreen
import com.cardprice.app.ui.collection.ScanHistoryViewModel
import com.cardprice.app.ui.collection.BinderActions
import com.cardprice.app.ui.collection.ScanScreen
import com.cardprice.app.ui.collection.ScanViewModel
import com.cardprice.app.ui.collection.SetCardsViewModel
import com.cardprice.app.ui.theme.CardPricerTheme

/** The three sections reachable from the bottom bar. */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    CALCULATOR("sets", "Calculator", Icons.Filled.ShoppingCart),
    COLLECTION("collection", "Collection", Icons.Filled.Star),
    INVENTORY("inventory", "Inventory", AppIcons.Inventory),
}

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val collectionVm: CollectionViewModel by viewModels()
    private val catalogVm: CatalogViewModel by viewModels()
    private val inventoryVm: InventoryViewModel by viewModels()
    private val cloudVm: CloudViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScanLog.init(filesDir)
        ScanLog.installCrashHandler()
        CardPhotos.init(filesDir)
        // Beta: resume streaming to the live viewer if it was on.
        LiveStream.refresh(this)
        enableEdgeToEdge()
        setContent {
            CardPricerTheme {
                val state by viewModel.state.collectAsState()
                val collection by collectionVm.state.collectAsState()
                val catalogs by catalogVm.state.collectAsState()
                // A set's picture, wherever it's shown: what was saved with it, or else worked out from the catalog.
                fun setArt(language: Language, setId: String): List<String> =
                    collection.progressFor(language, setId)?.art?.takeIf { it.isNotEmpty() }
                        ?: catalogArt(catalogs[language], language, setId)
                // Sets added from the scanner, search or history were saved without a picture; fill those in.
                val languagesInCollection = collection.progress.map { it.language }.toSet()
                LaunchedEffect(languagesInCollection) { languagesInCollection.forEach(catalogVm::ensureLoaded) }
                LaunchedEffect(collection.progress, catalogs) {
                    collection.progress.filter { it.art.isEmpty() }.forEach { p ->
                        val art = catalogArt(catalogs[p.language], p.language, p.setId)
                        if (art.isNotEmpty()) collectionVm.updateSetDetails(p.language, p.setId, value = null, art = art)
                    }
                }
                val nav = rememberNavController()
                var showCloud by rememberSaveable { mutableStateOf(false) }
                if (showCloud) {
                    val cloud by cloudVm.state.collectAsState()
                    CloudBackupDialog(
                        state = cloud,
                        vm = cloudVm,
                        onRestore = { data ->
                            collectionVm.restore(CollectionStore.Backup(data.collectionFile, System.currentTimeMillis(), 0, 0))
                            data.inventory?.let(inventoryVm::replaceAll)
                        },
                        onDismiss = { showCloud = false; cloudVm.clearMessage() },
                    )
                }
                val backStack by nav.currentBackStackEntryAsState()
                val currentRoute = backStack?.destination?.route

                // Beta live viewer: screens, collection changes and totals (does nothing unless streaming is on).
                val inventoryItems by inventoryVm.items.collectAsState()
                // Keep inventory's copy of the collection current from the start (not only once Inventory is opened).
                LaunchedEffect(collection.owned, collection.progress) { inventoryVm.syncCollection(collection.owned, collection.progress) }
                val screen = describeScreen(currentRoute, backStack?.arguments)
                LaunchedEffect(screen) { if (screen != null) LiveStream.event("screen", screen) }
                var lastCopies by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(collection.totalCopies) {
                    val before = lastCopies
                    if (before != null && before != collection.totalCopies) {
                        LiveStream.event("action", "Collection ${if (collection.totalCopies > before) "+" else ""}${collection.totalCopies - before} → ${collection.totalCopies} cards")
                    }
                    lastCopies = collection.totalCopies
                }
                LaunchedEffect(screen, collection, inventoryItems) {
                    val held = inventoryItems.filter { it.status != InventoryStatus.SOLD }
                    LiveStream.snapshot(
                        mapOf(
                            "screen" to screen,
                            "cards" to collection.totalCopies,
                            "sets" to collection.inProgress.size,
                            "value" to collection.totalValue.display(),
                            "inventoryUnits" to held.sumOf { it.quantity },
                            "inventoryValue" to held.sumOf { it.totalMarket ?: 0.0 }.money(),
                            "scanner" to if (currentRoute == "scan") "Open" else "Closed",
                            "appVersion" to appVersion(this@MainActivity),
                        ),
                    )
                }

                Scaffold(
                    // Each screen draws its own top bar; this scaffold only adds the bottom bar.
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        if (Tab.entries.any { it.route == currentRoute }) {
                            NavigationBar {
                                Tab.entries.forEach { tab ->
                                    NavigationBarItem(
                                        selected = currentRoute == tab.route,
                                        onClick = { nav.switchTab(tab.route) },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(tab.label) },
                                    )
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
                        composable(Tab.HOME.route) {
                            HomeScreen(
                                app = state,
                                collection = collection,
                                onOpenCalculator = { nav.switchTab(Tab.CALCULATOR.route) },
                                onOpenCollection = { nav.switchTab(Tab.COLLECTION.route) },
                                onScan = { nav.navigate("scan") },
                                onOpenInventory = { nav.switchTab(Tab.INVENTORY.route) },
                                onSearch = { nav.navigate("search") },
                                onOpenCollectionSet = { p ->
                                    nav.navigate(collectionSetRoute(p.language, p.setId, p.setName, p.art))
                                },
                                onOpenPurchaseSet = { id -> nav.navigate("calc/${Uri.encode(id)}") },
                                onSaveToken = viewModel::setPriceChartingToken,
                                onListBackups = collectionVm::backups,
                                onRestoreBackup = collectionVm::restore,
                                onSaveRestorePoint = collectionVm::saveRestorePoint,
                                onDismissRestoreNotice = collectionVm::dismissRestoreNotice,
                                onOpenCloud = { showCloud = true },
                            )
                        }
                        composable(Tab.CALCULATOR.route) {
                            SetListScreen(
                                state = state,
                                onOpenSet = { id -> nav.navigate("calc/${Uri.encode(id)}") },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onAddCustomSet = viewModel::addCustomSet,
                                onDeleteCustomSet = viewModel::deleteCustomSet,
                                onSelectLanguage = viewModel::selectLanguage,
                            )
                        }
                        composable(Tab.INVENTORY.route) {
                            InventoryScreen(
                                vm = inventoryVm,
                                allSets = state.allSets,
                                collection = collection,
                                onSoldFromCollection = { item, count ->
                                    item.collectionKey?.let(CollectionLink::parseKey)?.let { k ->
                                        collectionVm.removeCopies(k.language, k.setId, k.cardId, k.variantKey, count)
                                    }
                                },
                            )
                        }
                        composable(Tab.COLLECTION.route) {
                            CollectionHomeScreen(
                                language = state.language,
                                catalog = catalogs[state.language],
                                collection = collection,
                                onSelectLanguage = viewModel::selectLanguage,
                                onLoadCatalog = catalogVm::ensureLoaded,
                                onRefreshCatalog = catalogVm::refresh,
                                onOpenSet = { set, art -> nav.navigate(collectionSetRoute(state.language, set.id, set.name, art)) },
                                onScan = { nav.navigate("scan") },
                            )
                        }
                        composable(
                            "collection/{lang}/{setId}?name={name}&art={art}&focus={focus}&focusName={focusName}",
                            arguments = listOf(
                                navArgument("lang") { type = NavType.StringType },
                                navArgument("setId") { type = NavType.StringType },
                                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                                navArgument("art") { type = NavType.StringType; defaultValue = "" },
                                navArgument("focus") { type = NavType.StringType; defaultValue = "" },
                                navArgument("focusName") { type = NavType.StringType; defaultValue = "" },
                            ),
                        ) { entry ->
                            val args = entry.arguments!!
                            val language = Language.valueOf(args.getString("lang")!!)
                            val setId = args.getString("setId")!!
                            val setName = args.getString("name").orEmpty().ifBlank { setId }
                            // Art comes from the picker; when opened from Home it's whatever was saved with the set.
                            val art = args.getString("art").orEmpty().split(ART_SEPARATOR).filter { it.isNotBlank() }
                                .ifEmpty { setArt(language, setId) }
                            val app = LocalContext.current.applicationContext as Application
                            val setVm: SetCardsViewModel = viewModel(factory = viewModelFactory {
                                initializer { SetCardsViewModel(app, language, setId) }
                            })
                            val cardsState by setVm.state.collectAsState()
                            CollectionSetScreen(
                                language = language,
                                setId = setId,
                                setName = setName,
                                art = art,
                                cardsState = cardsState,
                                collection = collection,
                                onRefresh = setVm::refresh,
                                onBack = { nav.popBackStack() },
                                onSetCount = { cards, cardId, variantKey, count ->
                                    collectionVm.setCount(language, setId, setName, cards, cardId, variantKey, count)
                                },
                                onCardsLoaded = { cards ->
                                    collectionVm.onSetLoaded(language, setId, setName, cards)
                                    collectionVm.updateSetDetails(language, setId, value = null, art = art)
                                },
                                onEnsurePrices = setVm::ensurePrices,
                                onRowPrice = setVm::requestPrice,
                                onValueChanged = { value -> collectionVm.updateSetDetails(language, setId, value, art) },
                                focusNumber = args.getString("focus").orEmpty().ifBlank { null },
                                focusName = args.getString("focusName").orEmpty().ifBlank { null },
                            )
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
                            scanVm.addCopies = { r, key, delta ->
                                collectionVm.addCopies(r.set.language, r.set.setId, r.setName, r.setCards, r.card.id, key, delta)
                            }
                            ScanScreen(
                                status = status,
                                collection = collection,
                                onCameraText = scanVm::onCameraText,
                                onPhotoText = scanVm::onPhotoText,
                                onPhotoError = scanVm::onPhotoError,
                                onCardFrame = scanVm::onCardFrame,
                                onPhotoBitmap = scanVm::onPhotoBitmap,
                                onChoose = scanVm::choose,
                                onNext = scanVm::next,
                                onSetCount = { r, key, count ->
                                    val before = collection.count(r.set.language, r.set.setId, r.card.id, key)
                                    collectionVm.setCount(r.set.language, r.set.setId, r.setName, r.setCards, r.card.id, key, count)
                                    scanVm.recordManual(r, key, count - before)
                                },
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
                                // Sets being collected, most recent first, as quick picks.
                                recentSets = collection.inProgress.map { Triple(it.language, it.setId, it.setName) },
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
                                onOpen = { e ->
                                    val art = setArt(e.language, e.setId)
                                    nav.navigate(collectionSetRoute(e.language, e.setId, e.setName, art, e.number, e.cardName))
                                },
                                onClear = historyVm::clear,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("search") {
                            val searchVm: SearchViewModel = viewModel()
                            val search by searchVm.state.collectAsState()
                            SearchScreen(
                                state = search,
                                collection = collection,
                                onQuery = searchVm::setQuery,
                                onLanguage = searchVm::setLanguage,
                                onSort = searchVm::setSort,
                                onOpenInCollection = { hit ->
                                    val setId = hit.tcgdexSetId!!
                                    val art = setArt(hit.language, setId)
                                    // TCGplayer set names carry a code prefix ("ME05: Pitch Black").
                                    val setName = hit.setName.substringAfter(": ")
                                    nav.navigate(collectionSetRoute(hit.language, setId, setName, art, hit.number, hit.name))
                                },
                                sets = state.allSets,
                                onOpenSetPrices = { set -> nav.navigate("calc/${Uri.encode(set.id)}?prices=true") },
                                onOpenSetCards = { set, collectionId ->
                                    nav.navigate(collectionSetRoute(set.language, collectionId, set.name, setArt(set.language, collectionId)))
                                },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(
                            "calc/{setId}?prices={prices}",
                            arguments = listOf(navArgument("prices") { type = NavType.BoolType; defaultValue = false }),
                        ) { entry ->
                            val set = entry.arguments?.getString("setId")?.let(state::setById)
                            if (set == null) {
                                // The set was deleted; nothing to show here.
                                LaunchedEffect(Unit) { nav.popBackStack() }
                            } else {
                                val marketVm: MarketViewModel = viewModel(factory = viewModelFactory {
                                    initializer { MarketViewModel(set) }
                                })
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
                                    onOpenHistory = { product, cards ->
                                        val name = Uri.encode(product.name)
                                        nav.navigate("history/${product.id}?name=$name&cards=$cards")
                                    },
                                    pricesFirst = entry.arguments?.getBoolean("prices") == true,
                                )
                            }
                        }
                        composable(
                            "history/{productId}?name={name}&cards={cards}",
                            arguments = listOf(
                                navArgument("productId") { type = NavType.LongType },
                                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                                navArgument("cards") { type = NavType.IntType; defaultValue = 0 },
                            ),
                        ) { entry ->
                            val args = entry.arguments!!
                            val productId = args.getLong("productId")
                            val historyVm: HistoryViewModel = viewModel(factory = viewModelFactory {
                                initializer { HistoryViewModel(productId) }
                            })
                            HistoryScreen(
                                viewModel = historyVm,
                                productId = productId,
                                productName = args.getString("name").orEmpty(),
                                cardsPerProduct = args.getInt("cards"),
                                onBack = { nav.popBackStack() },
                            )
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

private const val ART_SEPARATOR = "\n"

/** A set's pictures from a loaded catalog, the same ones the collection's set list shows. */
private fun catalogArt(catalog: Load<List<CardSeries>>?, language: Language, setId: String): List<String> {
    val series = (catalog as? Load.Ready)?.value ?: return emptyList()
    for (s in series) {
        val set = s.sets.firstOrNull { it.id == setId } ?: continue
        return collectionSetArt(language, set, s.id)
    }
    return emptyList()
}

private fun collectionSetRoute(
    language: Language,
    setId: String,
    name: String,
    art: List<String>,
    focusNumber: String? = null,
    focusName: String? = null,
): String =
    "collection/${language.name}/${Uri.encode(setId)}?name=${Uri.encode(name)}&art=${Uri.encode(art.joinToString(ART_SEPARATOR))}" +
        "&focus=${Uri.encode(focusNumber.orEmpty())}&focusName=${Uri.encode(focusName.orEmpty())}"

/** A readable name for the screen on show, for the live viewer ("Collection · Pitch Black"). */
private fun describeScreen(route: String?, args: Bundle?): String? {
    route ?: return null
    return when {
        route == "home" -> "Home"
        route == "sets" -> "Pack Calculator"
        route == "collection" -> "Collection"
        route == "inventory" -> "Inventory"
        route == "scan" -> "Scanner"
        route == "scan-history" -> "Scan history"
        route == "search" -> "Search"
        route.startsWith("collection/") -> "Collection · " + (args?.getString("name")?.ifBlank { null } ?: args?.getString("setId") ?: "set")
        route.startsWith("calc/") -> "Calculator · " + (args?.getString("setId") ?: "set")
        route.startsWith("history/") -> "Price history · " + (args?.getString("name") ?: "")
        else -> route.substringBefore('/')
    }
}
