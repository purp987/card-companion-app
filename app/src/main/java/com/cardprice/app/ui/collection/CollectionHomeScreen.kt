package com.cardprice.app.ui.collection

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.Language
import com.cardprice.app.data.collection.CardSeries
import com.cardprice.app.data.collection.CardSet
import com.cardprice.app.data.collection.SetProgress
import com.cardprice.app.data.market.Load
import com.cardprice.app.ui.AppIcons
import com.cardprice.app.ui.LanguageSelector
import com.cardprice.app.ui.SetArt
import com.cardprice.app.ui.collectionSetArt
import com.cardprice.app.ui.display

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionHomeScreen(
    language: Language,
    catalog: Load<List<CardSeries>>?,
    collection: CollectionState,
    onSelectLanguage: (Language) -> Unit,
    onLoadCatalog: (Language) -> Unit,
    onRefreshCatalog: (Language) -> Unit,
    onOpenSet: (CardSet, List<String>) -> Unit,
    onScan: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(language) { onLoadCatalog(language) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Collection", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { onRefreshCatalog(language) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh set list")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(AppIcons.Camera, contentDescription = null) },
                text = { Text("Scan cards") },
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
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
            )
            LanguageSelector(language, onSelectLanguage)

            when (catalog) {
                null, Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is Load.Failed -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Couldn't load the set list.", fontWeight = FontWeight.Bold)
                    Text(catalog.message)
                    TextButton(onClick = { onRefreshCatalog(language) }) { Text("Try again") }
                }
                is Load.Ready -> SetCatalogList(language, catalog.value, collection, query.trim(), onOpenSet)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetCatalogList(
    language: Language,
    series: List<CardSeries>,
    collection: CollectionState,
    query: String,
    onOpenSet: (CardSet, List<String>) -> Unit,
) {
    fun matches(set: CardSet) = query.isEmpty() || set.name.contains(query, true) || set.id.contains(query, true)
    val allSets = series.flatMap { it.sets }
    val seriesOf = series.flatMap { s -> s.sets.map { it.id to s.id } }.toMap()
    val started = collection.inProgress.filter { it.language == language }
        .mapNotNull { p -> allSets.firstOrNull { it.id == p.setId } }
        .filter(::matches)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (started.isNotEmpty()) {
            stickyHeader(key = "h_started") { Header("Collecting now") }
            items(started, key = { "started_${it.id}" }) { set ->
                SetRow(language, seriesOf[set.id], set, collection.progressFor(language, set.id), onOpenSet)
            }
        }
        series.forEach { s ->
            val sets = s.sets.filter(::matches)
            if (sets.isEmpty()) return@forEach
            stickyHeader(key = "h_${s.id}") { Header(s.name) }
            items(sets, key = { "${s.id}_${it.id}" }) { set ->
                SetRow(language, s.id, set, collection.progressFor(language, set.id), onOpenSet)
            }
        }
        if (query.isNotEmpty() && started.isEmpty() && series.none { it.sets.any(::matches) }) {
            item { Text("No sets match \"$query\".", modifier = Modifier.padding(32.dp)) }
        }
    }
}

@Composable
private fun Header(title: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SetRow(language: Language, seriesId: String?, set: CardSet, progress: SetProgress?, onOpenSet: (CardSet, List<String>) -> Unit) {
    val art = collectionSetArt(language, set, seriesId)
    Row(
        Modifier.fillMaxWidth().clickable { onOpenSet(set, art) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SetArt(
            urls = art,
            label = set.id,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(64.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(set.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val total = if (set.totalCount > 0) set.totalCount else set.officialCount
            val detail = if (progress != null && progress.ownedCards > 0) {
                listOfNotNull("${progress.ownedCards} of ${progress.totalCards} cards", progress.value.display()).joinToString(" · ")
            } else if (total > 0) "$total cards" else "Card list from TCGplayer"
            Text(
                "${set.id} · $detail",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null && progress.ownedCards > 0) {
                LinearProgressIndicator(
                    progress = { progress.cardFraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        if (progress != null && progress.ownedCards > 0) {
            Text("${(progress.cardFraction * 100).toInt()}%", fontWeight = FontWeight.Bold)
        }
    }
    HorizontalDivider(Modifier.padding(start = 96.dp))
}
