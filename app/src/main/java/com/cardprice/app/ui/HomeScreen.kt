package com.cardprice.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.Purchase
import com.cardprice.app.data.collection.SetProgress
import com.cardprice.app.data.collection.CollectionStore
import com.cardprice.app.data.scan.ScanLearning
import com.cardprice.app.ui.collection.SetHeroCard
import com.cardprice.app.ui.collection.CollectionState
import com.cardprice.app.ui.theme.seriesColor
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: AppState,
    collection: CollectionState,
    onOpenCalculator: () -> Unit,
    onOpenCollection: () -> Unit,
    onScan: () -> Unit,
    onOpenInventory: () -> Unit,
    onSearch: () -> Unit,
    onOpenCollectionSet: (SetProgress) -> Unit,
    onOpenPurchaseSet: (String) -> Unit,
    onSaveToken: (String?) -> Unit,
    onListBackups: suspend () -> List<CollectionStore.Backup>,
    onRestoreBackup: (CollectionStore.Backup) -> Unit,
    onSaveRestorePoint: suspend (String) -> CollectionStore.Backup?,
    onDismissRestoreNotice: () -> Unit,
    onOpenCloud: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val learning = ScanLearning.get(LocalContext.current.filesDir)
    var showBackups by rememberSaveable { mutableStateOf(false) }
    var showSavePoint by rememberSaveable { mutableStateOf(false) }
    val purchases = app.purchases.sortedByDescending { it.timestamp }
    val spent = purchases.sumOf { it.totalPrice }
    val cardsOpened = purchases.sumOf { it.totalCards }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Card Companion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                expandedHeight = 48.dp,
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            collection.restoredFrom?.let { takenAt ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Collection restored from backup", fontWeight = FontWeight.Bold)
                            Text(
                                "Your collection file was missing or damaged, so the backup from ${formatDateTime(takenAt)} was loaded. " +
                                    "Changes after that time may need re-adding.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onDismissRestoreNotice) { Text("OK") }
                        }
                    }
                }
            }
            item {
                Column {
                    Text(greeting(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Check what packs really cost and track your collection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                // Looks like a search field; opens the full search screen (most popular cards first).
                Surface(
                    onClick = onSearch,
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text(
                            "Search cards or sets, e.g. Charizard or Pitch Black",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Cards collected", "${collection.totalCopies}", Modifier.weight(1f))
                    StatTile("Collection value", collection.totalValue.display() ?: "—", Modifier.weight(1f))
                    StatTile(
                        "Avg per card",
                        if (cardsOpened > 0) (spent / cardsOpened).moneyPerCard() else "—",
                        Modifier.weight(1f),
                    )
                }
            }

            item {
                SectionTile(
                    icon = Icons.Filled.ShoppingCart,
                    color = Color(0xFFD84315),
                    title = "Pack Calculator",
                    body = "Price per card for any pack, bundle, ETB or box, checked against live market prices.",
                    onClick = onOpenCalculator,
                )
            }
            item {
                SectionTile(
                    icon = Icons.Filled.Star,
                    color = Color(0xFF3B5BA9),
                    title = "My Collection",
                    body = "Tick off cards and every variant (reverse holos, Poké Ball and Master Ball patterns, stamps) set by set.",
                    onClick = onOpenCollection,
                )
            }

            item {
                SectionTile(
                    icon = AppIcons.Camera,
                    color = Color(0xFF00897B),
                    title = "Scan cards",
                    body = "Point your camera at a card to find it and add it to your collection.",
                    onClick = onScan,
                )
            }
            item {
                SectionTile(
                    icon = AppIcons.Inventory,
                    color = Color(0xFF8E5A2B),
                    title = "Inventory",
                    body = "Cards and sealed product you hold to sell or trade: cost, market value, profit.",
                    onClick = onOpenInventory,
                )
            }
            item {
                SectionTitle(
                    if (collection.inProgress.isEmpty()) "Collecting now" else "Collecting now · ${collection.inProgress.size} " + if (collection.inProgress.size == 1) "set" else "sets",
                    if (collection.inProgress.isNotEmpty()) "See all" else null,
                    onOpenCollection,
                )
            }
            if (collection.inProgress.isEmpty()) {
                item { EmptyHint("Open My Collection, pick a set and tap the cards you own. Your sets will show up here.") }
            } else {
                items(collection.inProgress.take(3), key = { "p_${it.language}_${it.setId}" }) { p ->
                    ProgressCard(p) { onOpenCollectionSet(p) }
                }
            }

            item { SectionTitle("Recent purchases", if (purchases.isNotEmpty()) "Calculator" else null, onOpenCalculator) }
            if (purchases.isEmpty()) {
                item { EmptyHint("Save a purchase in the Pack Calculator to see your spending and price per card here.") }
            } else {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Total spent ${spent.money()}", fontWeight = FontWeight.Bold)
                                Text("$cardsOpened cards")
                            }
                            purchases.take(3).forEach { p -> PurchaseLine(app, p) { onOpenPurchaseSet(p.setId) } }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            currentToken = app.priceChartingToken,
            onDismiss = { showSettings = false },
            onSave = {
                onSaveToken(it)
                showSettings = false
            },
            onOpenBackups = {
                showSettings = false
                showBackups = true
            },
            onOpenCloud = {
                showSettings = false
                onOpenCloud()
            },
            onSaveRestorePoint = {
                showSettings = false
                showSavePoint = true
            },
            learningSummary = learning.let {
                if (it.learnedReadings == 0) "Nothing learned yet."
                else "Learned ${it.learnedReadings} readings from ${it.confirmations} confirmations (${it.corrections} corrections)."
            },
            onResetLearning = { learning.reset() },
        )
    }

    if (showSavePoint) {
        SaveRestorePointDialog(onSave = onSaveRestorePoint, onDone = { showSavePoint = false })
    }

    if (showBackups) {
        BackupsDialog(onListBackups, onRestore = { onRestoreBackup(it); showBackups = false }, onDismiss = { showBackups = false })
    }
}

@Composable
private fun BackupsDialog(
    onListBackups: suspend () -> List<CollectionStore.Backup>,
    onRestore: (CollectionStore.Backup) -> Unit,
    onDismiss: () -> Unit,
) {
    val backups by produceState<List<CollectionStore.Backup>?>(null) { value = onListBackups() }
    var confirm by remember { mutableStateOf<CollectionStore.Backup?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore a backup") },
        text = {
            when (val list = backups) {
                null -> CircularProgressIndicator()
                emptyList<CollectionStore.Backup>() -> Text("No backups yet. One is made the first time you add a card.")
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.takenAt }) { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { confirm = b }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                if (b.name != null) Text("📌 ${b.name}", fontWeight = FontWeight.Bold)
                                Text(formatDateTime(b.takenAt), fontWeight = if (b.name == null) FontWeight.Medium else FontWeight.Normal)
                                Text("${b.copies} cards (${b.cards} different)", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Restore this backup")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Your collection will be replaced with ${b.name?.let { "\"$it\" " } ?: "the backup "}from ${formatDateTime(b.takenAt)} " +
                        "(${b.copies} cards). Your current collection is saved as a backup first, so you can switch back.",
                )
            },
            confirmButton = { TextButton(onClick = { onRestore(b); confirm = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SaveRestorePointDialog(onSave: suspend (String) -> CollectionStore.Backup?, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("Manual entries") }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Save a restore point") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (result == null) {
                    Text("Keeps a copy of your collection as it is now. Restore points are never replaced by automatic backups.")
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                } else {
                    Text(result!!)
                }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch {
                        val saved = onSave(name)
                        result = if (saved != null) "Saved \"${saved.name}\" with ${saved.copies} cards." else "There's no collection to save yet."
                    }
                }) { Text("Save") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

private fun formatDateTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(millis))

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTile(icon: ImageVector, color: Color, title: String, body: String, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).background(color, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onAction).padding(8.dp),
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ProgressCard(p: SetProgress, onClick: () -> Unit) {
    // The same card as the Collection tab's carousel, a little shorter.
    SetHeroCard(
        art = p.art,
        title = p.setName,
        subtitle = p.language.label,
        progress = p,
        height = 150.dp,
        onClick = onClick,
    )
}

@Composable
private fun PurchaseLine(app: AppState, p: Purchase, onClick: () -> Unit) {
    val set = app.setById(p.setId)
    val setName = set?.name ?: "Deleted set"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (set != null) {
            SetArt(calculatorSetArt(set), set.code ?: set.series.shortTitle, seriesColor(set.series), Modifier.size(44.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(setName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val qty = if (p.quantity > 1) " ×${p.quantity}" else ""
            Text("${p.product}$qty · ${p.timestamp.shortDate()}", style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${p.pricePerCard.moneyPerCard()}/card", fontWeight = FontWeight.Bold)
            Text(p.totalPrice.money(), style = MaterialTheme.typography.bodySmall)
        }
    }
}
