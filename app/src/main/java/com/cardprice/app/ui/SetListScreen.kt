package com.cardprice.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.Language
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.cardsPerPackFor
import com.cardprice.app.data.presetsFor
import com.cardprice.app.data.Series
import com.cardprice.app.ui.theme.FavoriteGold
import com.cardprice.app.ui.theme.seriesColor
import kotlinx.coroutines.launch

private const val FILTER_ALL = "ALL"
private const val FILTER_FAVORITES = "FAV"

private data class SetGroup(val key: String, val title: String, val color: Color, val sets: List<PokemonSet>)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SetListScreen(
    state: AppState,
    onOpenSet: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddCustomSet: (String, Int) -> Unit,
    onDeleteCustomSet: (String) -> Unit,
    onSelectLanguage: (Language) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val groups = buildGroups(state, query, filter)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pack Calculator", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add set") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search sets") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            LanguageSelector(
                selected = state.language,
                onSelect = {
                    onSelectLanguage(it)
                    filter = FILTER_ALL
                    scope.launch { listState.scrollToItem(0) }
                },
            )

            val languageSets = state.allSets.filter { it.language == state.language }
            val filters = buildList {
                add(FILTER_ALL to "All")
                add(FILTER_FAVORITES to "★ Favorites")
                Series.entries
                    .filter { series -> languageSets.any { it.series == series } }
                    .forEach { add(it.name to it.title) }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filters, key = { it.first }) { (key, label) ->
                    FilterChip(
                        selected = filter == key,
                        onClick = { filter = key },
                        label = { Text(label) },
                    )
                }
            }

            if (groups.isEmpty()) {
                EmptyMessage(
                    if (filter == FILTER_FAVORITES && query.isBlank()) "Tap the ☆ on any set to pin it here."
                    else "No sets match \"$query\"."
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    groups.forEach { group ->
                        // While searching, every match is shown regardless of collapse state.
                        val isCollapsed = group.key in collapsed && query.isBlank()
                        stickyHeader(key = "header_${group.key}") {
                            GroupHeader(group, isCollapsed) {
                                collapsed = if (group.key in collapsed) collapsed - group.key else collapsed + group.key
                            }
                        }
                        if (!isCollapsed) {
                            items(group.sets, key = { "${group.key}_${it.id}" }) { set ->
                                SetRow(
                                    set = set,
                                    isFavorite = set.id in state.favorites,
                                    avgPerCard = averagePerCard(state, set.id),
                                    onClick = { onOpenSet(set.id) },
                                    onToggleFavorite = { onToggleFavorite(set.id) },
                                    onDelete = if (set.series == Series.CUSTOM) {
                                        { pendingDelete = set.id }
                                    } else null,
                                )
                                HorizontalDivider(Modifier.padding(start = 88.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSetDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, cards ->
                onAddCustomSet(name, cards)
                showAddDialog = false
                // Show the new set, which lands in "My Sets" at the top.
                query = ""
                if (filter != FILTER_ALL && filter != Series.CUSTOM.name) filter = FILTER_ALL
                collapsed = collapsed - Series.CUSTOM.name
                scope.launch { listState.animateScrollToItem(0) }
            },
        )
    }

    pendingDelete?.let { id ->
        val name = state.setById(id)?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"$name\"?") },
            text = { Text("This also removes any purchases saved for this set.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteCustomSet(id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun buildGroups(state: AppState, query: String, filter: String): List<SetGroup> {
    val q = query.trim()
    val matches = state.allSets
        .filter { it.language == state.language }
        .filter { q.isEmpty() || it.matchesQuery(q) }
    val favorites = matches.filter { it.id in state.favorites }

    if (filter == FILTER_FAVORITES) {
        return if (favorites.isEmpty()) emptyList()
        else listOf(SetGroup("fav", "Favorites", FavoriteGold, favorites))
    }

    val bySeries = Series.entries
        .filter { filter == FILTER_ALL || it.name == filter }
        .mapNotNull { series ->
            val sets = matches.filter { it.series == series }
            if (sets.isEmpty()) null else SetGroup(series.name, series.title, seriesColor(series), sets)
        }

    // On the unfiltered view, pin favorites to the top for one-tap access.
    return if (filter == FILTER_ALL && q.isEmpty() && favorites.isNotEmpty()) {
        listOf(SetGroup("fav", "Favorites", FavoriteGold, favorites)) + bySeries
    } else bySeries
}

/** Search by English name, set code (e.g. "SV2a") or the local-language name. */
private fun PokemonSet.matchesQuery(q: String) =
    name.contains(q, ignoreCase = true) ||
        code?.contains(q, ignoreCase = true) == true ||
        localName?.contains(q, ignoreCase = true) == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelector(selected: Language, onSelect: (Language) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
        Language.entries.forEachIndexed { index, language ->
            SegmentedButton(
                selected = language == selected,
                onClick = { onSelect(language) },
                shape = SegmentedButtonDefaults.itemShape(index, Language.entries.size),
                icon = {},
            ) {
                Text(language.nativeName, maxLines = 1, modifier = Modifier.semantics { contentDescription = language.label })
            }
        }
    }
}

private fun averagePerCard(state: AppState, setId: String): Double? {
    val purchases = state.purchases.filter { it.setId == setId }
    val cards = purchases.sumOf { it.totalCards }
    return if (cards > 0) purchases.sumOf { it.totalPrice } / cards else null
}

@Composable
private fun GroupHeader(group: SetGroup, collapsed: Boolean, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(group.color, CircleShape))
            Text(
                group.title,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${group.sets.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun SetRow(
    set: PokemonSet,
    isFavorite: Boolean,
    avgPerCard: Double?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            SetArt(
                urls = calculatorSetArt(set),
                label = set.code ?: set.series.shortTitle,
                color = seriesColor(set.series),
            )
        },
        headlineContent = { Text(set.name, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column {
                if (set.localName != null) Text(set.localName, fontWeight = FontWeight.Medium)
                val details = buildString {
                    append(set.year)
                    if (set.code != null) append(" · ${set.code}")
                    append(" · ${cardsSummary(set)} cards/pack")
                    if (avgPerCard != null) append(" · avg ${avgPerCard.moneyPerCard()}/card")
                }
                Text(details)
            }
        },
        trailingContent = {
            Row {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete ${set.name}")
                    }
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (isFavorite) FavoriteGold else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        },
    )
}

/** "5" normally, or "5 or 20" when a set sells packs of different sizes. */
private fun cardsSummary(set: PokemonSet): String =
    presetsFor(set).dropLast(1).map { cardsPerPackFor(set, it) }.ifEmpty { listOf(set.cardsPerPack) }
        .distinct().sorted().joinToString(" or ")

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddSetDialog(onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var cards by rememberSaveable { mutableStateOf("10") }
    val cardsValue = cards.toIntOrNull()
    val valid = name.isNotBlank() && cardsValue != null && cardsValue > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a set") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "For new releases or promos that aren't listed. It's added to the language you're viewing.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Set name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = cards,
                    onValueChange = { cards = it.filter(Char::isDigit).take(3) },
                    label = { Text("Cards per pack") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(name, cardsValue!!) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsDialog(
    currentToken: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
    onOpenBackups: (() -> Unit)? = null,
    onSaveRestorePoint: (() -> Unit)? = null,
    /** e.g. "Learned 12 readings from 15 confirmations (3 corrections)"; null hides the section. */
    learningSummary: String? = null,
    onResetLearning: (() -> Unit)? = null,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var token by rememberSaveable { mutableStateOf(currentToken.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            // Scrolls: with backups, scanner learning and About it's taller than most screens.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "TCGplayer market prices and sales history work without any setup.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "To also compare PriceCharting prices, paste the API token from your PriceCharting " +
                        "subscription (pricecharting.com → Subscription → API).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("PriceCharting API token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (onOpenBackups != null) {
                    HorizontalDivider()
                    Text("Collection backups", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Your collection is backed up automatically (up to once an hour, last 20 kept).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onOpenBackups) { Text("Restore a backup…") }
                    if (onSaveRestorePoint != null) {
                        OutlinedButton(onClick = onSaveRestorePoint) { Text("Save a restore point…") }
                    }
                }
                if (learningSummary != null) {
                    HorizontalDivider()
                    Text("Scanner learning", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "The scanner learns from cards you keep, pick or undo, so repeat readings get faster and surer. $learningSummary",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (onResetLearning != null) {
                        TextButton(onClick = { confirmReset = true }) { Text("Reset what it learned") }
                    }
                }
                HorizontalDivider()
                val context = LocalContext.current
                var noLog by remember { mutableStateOf(false) }
                Text("About", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Card Companion ${appVersion(context)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Something not working? Send the debug log (what the scanner read and did, plus any crashes) " +
                        "to whoever shared the app with you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { noLog = !shareDebugLog(context) }) { Text("Send debug log…") }
                if (noLog) {
                    Text("Nothing logged yet. Use the scanner first.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(token.ifBlank { null }) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (currentToken != null) {
                    TextButton(onClick = { onSave(null) }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (confirmReset && onResetLearning != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset scanner learning?") },
            text = { Text("The scanner forgets confirmed readings, corrections and preferred versions. Your collection isn't affected.") },
            confirmButton = { TextButton(onClick = { onResetLearning(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
