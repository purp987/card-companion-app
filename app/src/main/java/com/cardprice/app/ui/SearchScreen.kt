package com.cardprice.app.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.cardprice.app.data.PokemonSet
import com.cardprice.app.data.SetSearch
import com.cardprice.app.ui.theme.seriesColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.cardprice.app.data.Language
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.userMessage
import com.cardprice.app.data.scan.ScanMatcher
import com.cardprice.app.data.search.CardHit
import com.cardprice.app.data.search.CardSearch
import com.cardprice.app.data.search.SearchSort
import com.cardprice.app.ui.collection.CollectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val language: Language = Language.ENGLISH,
    val sort: SearchSort = SearchSort.POPULAR,
    val results: Load<List<CardHit>> = Load.Loading,
)

@OptIn(FlowPreview::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val query = MutableStateFlow("")
    private val language = MutableStateFlow(Language.ENGLISH)
    private val sort = MutableStateFlow(SearchSort.POPULAR)
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Wait for a pause in typing, then search; a newer search cancels the one in flight.
            combine(query.debounce(350), language, sort) { q, l, s -> Triple(q.trim(), l, s) }
                .distinctUntilChanged()
                .collectLatest { (q, l, s) ->
                    _state.value = _state.value.copy(results = Load.Loading)
                    val result = try {
                        Load.Ready(CardSearch.search(q, l, s))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Load.Failed(e.userMessage())
                    }
                    _state.value = _state.value.copy(results = result)
                }
        }
    }

    fun setQuery(q: String) {
        query.value = q
        _state.value = _state.value.copy(query = q)
    }

    fun setLanguage(l: Language) {
        language.value = l
        _state.value = _state.value.copy(language = l)
    }

    fun setSort(s: SearchSort) {
        sort.value = s
        _state.value = _state.value.copy(sort = s)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchState,
    collection: CollectionState,
    onQuery: (String) -> Unit,
    onLanguage: (Language) -> Unit,
    onSort: (SearchSort) -> Unit,
    onOpenInCollection: (CardHit) -> Unit,
    /** Every set the calculator knows (all languages), searched by name or code as you type. */
    sets: List<PokemonSet>,
    /** A set's sealed-product market prices and sales graphs. */
    onOpenSetPrices: (PokemonSet) -> Unit,
    /** A set's card list with each card's price (collection id from [SetSearch.collectionSetId]). */
    onOpenSetCards: (PokemonSet, String) -> Unit,
    onBack: () -> Unit,
) {
    val matchingSets = remember(state.query, sets) { SetSearch.find(sets, state.query) }
    var showAllSets by remember(state.query) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
                placeholder = { Text("Card or set, e.g. Charizard, 199/165 or Pitch Black") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listOf(Language.ENGLISH, Language.JAPANESE)) { l ->
                    FilterChip(selected = state.language == l, onClick = { onLanguage(l) }, label = { Text(l.chipLabel) })
                }
                items(SearchSort.entries) { s ->
                    FilterChip(selected = state.sort == s, onClick = { onSort(s) }, label = { Text(s.label) })
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                // Sets first: typing a set's name is usually about its prices.
                if (matchingSets.isNotEmpty()) {
                    item(key = "sets-title") { SectionTitle("Sets") }
                    val shown = if (showAllSets) matchingSets else matchingSets.take(SETS_SHOWN)
                    items(shown, key = { "set-${it.id}" }) { set ->
                        SetHitRow(
                            set = set,
                            onPrices = { onOpenSetPrices(set) },
                            onCards = SetSearch.collectionSetId(set)?.let { id -> { onOpenSetCards(set, id) } },
                        )
                    }
                    if (matchingSets.size > SETS_SHOWN && !showAllSets) {
                        item(key = "sets-more") {
                            TextButton(onClick = { showAllSets = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("Show all ${matchingSets.size} sets")
                            }
                        }
                    }
                }
                item(key = "cards-title") {
                    SectionTitle(
                        when {
                            state.query.isBlank() && state.sort == SearchSort.POPULAR -> "Most popular cards right now"
                            state.query.isBlank() -> "Most valuable cards"
                            else -> "Cards matching \"${state.query.trim()}\", ${state.sort.label.lowercase()} first"
                        },
                    )
                }
                when (val r = state.results) {
                    Load.Loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    is Load.Failed -> item(key = "failed") { Text("Couldn't search right now. ${r.message}", modifier = Modifier.padding(16.dp)) }
                    is Load.Ready -> if (r.value.isEmpty()) {
                        item(key = "empty") { Text("No cards found. Try fewer words or just the number.", modifier = Modifier.padding(16.dp)) }
                    } else {
                        items(r.value, key = { it.productId }) { hit ->
                            HitRow(
                                hit = hit,
                                owned = ownedCopies(collection, hit),
                                onClick = {
                                    if (hit.tcgdexSetId != null) onOpenInCollection(hit) else uriHandler.openUri(hit.tcgplayerUrl)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** A matching set: tap for sealed prices and sales graphs; "Card prices" opens its card list. */
@Composable
private fun SetHitRow(set: PokemonSet, onPrices: () -> Unit, onCards: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPrices).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SetArt(calculatorSetArt(set), set.code ?: set.series.shortTitle, seriesColor(set.series), Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(set.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(set.language.chipLabel, set.code, set.localName, set.year.toString()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("Sealed prices & sales", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (onCards != null) OutlinedButton(onClick = onCards) { Text("Card prices") }
    }
    HorizontalDivider(Modifier.padding(start = 76.dp))
}

@Composable
private fun HitRow(hit: CardHit, owned: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(model = hit.imageUrl, contentDescription = null, modifier = Modifier.width(48.dp).aspectRatio(0.716f))
        Column(Modifier.weight(1f)) {
            Text(hit.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(hit.setName, hit.number?.let { "#$it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(hit.rarity, if (hit.tcgdexSetId == null) "opens on TCGplayer" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(hit.marketPrice?.money() ?: "—", fontWeight = FontWeight.Bold)
            if (owned > 0) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                    Text("You have $owned", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 76.dp))
}

/** Copies of this card in the collection (any variant), matched by set and printed number. */
private fun ownedCopies(collection: CollectionState, hit: CardHit): Int {
    val setId = hit.tcgdexSetId ?: return 0
    val number = hit.number ?: return 0
    val prefix = "${hit.language.name}|$setId|"
    return collection.owned.entries.sumOf { (key, count) ->
        if (!key.startsWith(prefix)) return@sumOf 0
        val cardId = key.removePrefix(prefix).substringBefore('|')
        if (ScanMatcher.sameNumber(cardId.removePrefix("$setId-"), number)) count else 0
    }
}

/** Sets listed before "Show all". */
private const val SETS_SHOWN = 4
