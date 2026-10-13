package com.cardprice.app.ui.vault

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardprice.app.data.vault.ProductItem
import com.cardprice.app.data.vault.SearchSort
import com.cardprice.app.data.vault.TrackedCategory
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.ui.theme.Palette

/** Games to offer as tabs/filters. Simplified Chinese is the app's Chinese catalog, so Traditional is hidden when both are tracked. */
fun visibleGames(tracked: List<TrackedCategory>): List<TrackedCategory> {
    val list = if (tracked.any { it.id == 900002L }) tracked.filterNot { it.id == 900001L } else tracked
    return list.sortedWith(compareBy({ it.lang != null }, { it.id }))
}

/** Tab label for a game: "English", "Japanese", "Chinese". */
fun gameLabel(c: TrackedCategory): String = when (c.id) {
    3L -> "English"
    85L -> "Japanese"
    900002L, 900001L -> "Chinese"
    else -> c.lang ?: c.name
}

@Composable
fun vaultFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Palette.input,
    unfocusedContainerColor = Palette.input,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.borderStrong,
)

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Palette.text3) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.text3) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onValueChange(""); onSearch() }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = vaultFieldColors(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Sort picker (web "Sort by:" pill). */
@Composable
fun SortMenu(selected: SearchSort, options: List<SearchSort>, onSelect: (SearchSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("Sort: ${selected.label}", active = false, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { s ->
                DropdownMenuItem(text = { Text(s.label, color = if (s == selected) Palette.accent else Palette.text) }, onClick = { open = false; onSelect(s) })
            }
        }
    }
}

/** Grid of product tiles with paging; [header] items span the full width. */
@Composable
fun ProductGrid(
    state: GridState,
    onOpen: (ProductItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    emptyTitle: String = "No products found",
    emptyBody: String? = "Try a different search.",
    header: LazyGridScope.() -> Unit = {},
) {
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) onLoadMore() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = grid,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        header()
        items(state.items, key = { it.id }, contentType = { "product" }) { p -> ProductTile(p, { onOpen(p) }) }
        when {
            state.error != null -> item(span = { GridItemSpan(maxLineSpan) }) { MessageBox("Couldn't load", state.error, "Try again", onRetry) }
            state.loading -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox() }
            state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { MessageBox(emptyTitle, emptyBody) }
        }
    }
}

@Composable
fun ExploreScreen(initial: ExploreQuery?, onOpenProduct: (Long) -> Unit, onBack: (() -> Unit)?) {
    val vm: ExploreViewModel = viewModel()
    LaunchedEffect(initial) { if (initial != null) vm.set(initial) }
    val query by vm.query.collectAsState()
    val grid by vm.grid.collectAsState()
    val tracked by Vault.tracked.collectAsState()
    var text by rememberSaveable { mutableStateOf(initial?.q.orEmpty()) }
    var showFilters by rememberSaveable { mutableStateOf(false) }

    VaultBackground {
        Column(Modifier.fillMaxSize()) {
            VaultTopBar(title = "Explore", onBack = onBack)
            ProductGrid(
                state = grid,
                onOpen = { onOpenProduct(it.id) },
                onLoadMore = vm::loadMore,
                onRetry = vm::reload,
                emptyBody = if (query.sort == SearchSort.GAIN || query.sort == SearchSort.LOSS) "Movers need at least two days of tracked prices." else "Try a different search.",
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SearchField(text, { text = it }, "Search any sealed or unsealed product…", onSearch = { vm.set(query.copy(q = text.trim())) })
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SortMenu(query.sort, listOf(SearchSort.BEST, SearchSort.PRICE_DESC, SearchSort.PRICE_ASC, SearchSort.GAIN, SearchSort.LOSS, SearchSort.NEWEST, SearchSort.NAME)) {
                                vm.set(query.copy(sort = it))
                            }
                            Pill("Cards", query.type == "cards", { vm.set(query.copy(type = if (query.type == "cards") null else "cards")) })
                            Pill("Sealed", query.type == "sealed", { vm.set(query.copy(type = if (query.type == "sealed") null else "sealed")) })
                            Pill(if (showFilters) "Filters ▴" else "Filters ▾", showFilters || query.cat != null || query.minPrice != null, { showFilters = !showFilters })
                        }
                        if (showFilters) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("All games", query.cat == null, { vm.set(query.copy(cat = null)) })
                                visibleGames(tracked).forEach { c -> Pill(gameLabel(c), query.cat == c.id, { vm.set(query.copy(cat = c.id)) }) }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(null, 5.0, 25.0, 100.0, 500.0).forEach { min ->
                                    Pill(if (min == null) "Any price" else "\$${min.toInt()}+", query.minPrice == min, { vm.set(query.copy(minPrice = min)) })
                                }
                            }
                        }
                        if (!grid.loading || grid.items.isNotEmpty()) {
                            Text(
                                "${"%,d".format(grid.total)} result${if (grid.total == 1) "" else "s"}${grid.window?.let { " · $it change" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.text3,
                            )
                        }
                    }
                }
            }
        }
    }
}
