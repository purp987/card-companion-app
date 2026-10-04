package com.cardprice.app.ui.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.cardprice.app.data.Language
import com.cardprice.app.data.SetNames
import com.cardprice.app.data.collection.CardSeries
import com.cardprice.app.data.collection.CardSet
import com.cardprice.app.data.market.Load
import com.cardprice.app.ui.AppIcons
import com.cardprice.app.ui.LanguageSelector
import com.cardprice.app.ui.collectionSetArt
import com.cardprice.app.ui.display
import kotlin.math.absoluteValue

/**
 * The Collection tab: your totals, a carousel of the sets you're collecting (big artwork cards with
 * progress rings), and every set as a tile grid grouped by series.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("My Collection", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { onRefreshCatalog(language) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh set list")
                    }
                },
                scrollBehavior = scroll,
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
                is Load.Ready -> SetCatalogGrid(
                    language = language,
                    series = catalog.value,
                    collection = collection,
                    query = query,
                    onQuery = { query = it },
                    onSelectLanguage = onSelectLanguage,
                    onOpenSet = onOpenSet,
                )
            }
        }
    }
}

@Composable
private fun SetCatalogGrid(
    language: Language,
    series: List<CardSeries>,
    collection: CollectionState,
    query: String,
    onQuery: (String) -> Unit,
    onSelectLanguage: (Language) -> Unit,
    onOpenSet: (CardSet, List<String>) -> Unit,
) {
    val q = query.trim()
    fun matches(set: CardSet) = q.isEmpty() || set.name.contains(q, true) || set.id.contains(q, true)
    val seriesOf = series.flatMap { s -> s.sets.map { it.id to s } }.toMap()
    val allSets = series.flatMap { it.sets }
    val started = collection.inProgress.filter { it.language == language }
        .mapNotNull { p -> allSets.firstOrNull { it.id == p.setId }?.let { it to p } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
        item(key = "summary", span = full) {
            CollectionSummary(collection.totalValue.display(), collection.inProgress.size, collection.totalCopies)
        }
        item(key = "search", span = full) {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search sets") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                )
                LanguageSelector(language, onSelectLanguage)
            }
        }

        // Sets being collected: a carousel of big cards (or tiles, while searching).
        if (started.isNotEmpty() && q.isEmpty()) {
            item(key = "h_started", span = full) { SectionHeader("Collecting now", "${started.size} ${if (started.size == 1) "set" else "sets"}") }
            item(key = "carousel", span = full) {
                CollectingNowCarousel(language, started, seriesOf, onOpenSet)
            }
        } else if (q.isNotEmpty()) {
            val hits = started.filter { matches(it.first) }
            if (hits.isNotEmpty()) {
                item(key = "h_started", span = full) { SectionHeader("Collecting now", null) }
                itemsIndexed(hits, key = { _, (set, _) -> "started_${set.id}" }) { i, (set, p) ->
                    val art = collectionSetArt(language, set, seriesOf[set.id]?.id)
                    val english = SetNames.english(language, set.id)
                    SetTile(art, set.id, english ?: set.name, cardCount(set), p, i, localName = set.name.takeIf { english != null }) { onOpenSet(set.withEnglishName(language), art) }
                }
            }
        }

        series.forEach { s ->
            val sets = s.sets.filter(::matches)
            if (sets.isEmpty()) return@forEach
            item(key = "h_${s.id}", span = full) { SectionHeader(s.name, "${sets.size} ${if (sets.size == 1) "set" else "sets"}") }
            itemsIndexed(sets, key = { _, set -> "${s.id}_${set.id}" }) { i, set ->
                val art = collectionSetArt(language, set, s.id)
                val english = SetNames.english(language, set.id)
                SetTile(art, set.id, english ?: set.name, cardCount(set), collection.progressFor(language, set.id), i, localName = set.name.takeIf { english != null }) {
                    onOpenSet(set.withEnglishName(language), art)
                }
            }
        }
        if (q.isNotEmpty() && series.none { it.sets.any(::matches) }) {
            item(span = full) { Text("No sets match \"$q\".", modifier = Modifier.padding(32.dp)) }
        }
    }
}

private fun cardCount(set: CardSet) = if (set.totalCount > 0) set.totalCount else set.officialCount

/** Swipeable big cards for the sets being collected; the centered one is full size, its neighbors smaller and dimmer. */
@Composable
private fun CollectingNowCarousel(
    language: Language,
    started: List<Pair<CardSet, com.cardprice.app.data.collection.SetProgress>>,
    seriesOf: Map<String, CardSeries>,
    onOpenSet: (CardSet, List<String>) -> Unit,
) {
    val pager = rememberPagerState { started.size }
    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = 24.dp),
        pageSpacing = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        val (set, progress) = started[page]
        val series = seriesOf[set.id]
        val art = collectionSetArt(language, set, series?.id)
        val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
        val english = SetNames.english(language, set.id)
        SetHeroCard(
            art = art,
            title = english ?: set.name,
            subtitle = listOfNotNull(set.name.takeIf { english != null }, series?.name, language.chipLabel).joinToString(" · "),
            progress = progress,
            modifier = Modifier.graphicsLayer {
                val scale = lerp(1f, 0.92f, offset)
                scaleX = scale
                scaleY = scale
                alpha = lerp(1f, 0.7f, offset)
            },
        ) { onOpenSet(set.withEnglishName(language), art) }
    }
}

/** Japanese and Chinese sets open under their English name, with the original after it. */
private fun CardSet.withEnglishName(language: Language): CardSet =
    SetNames.english(language, id)?.let { copy(name = "$it · $name") } ?: this

@Composable
private fun SectionHeader(title: String, detail: String?) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
