package com.cardprice.app.ui.vault

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.vault.SearchSort
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.ui.dayLabel
import com.cardprice.app.ui.theme.Palette
import com.cardprice.app.ui.theme.PriceStyle
import com.cardprice.app.ui.usd

@Composable
fun SetsScreen(onOpenSet: (Long) -> Unit, onBack: () -> Unit) {
    val vm: SetsViewModel = viewModel()
    val tracked by Vault.tracked.collectAsState()
    val games = visibleGames(tracked)
    val cat by vm.cat.collectAsState()
    val sets by vm.sets.collectAsState()
    var filter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(games.firstOrNull()?.id) { if (cat == null) vm.select(games.firstOrNull()?.id ?: 3L) }

    VaultBackground {
        Column(Modifier.fillMaxSize()) {
            VaultTopBar(title = "Sets", onBack = onBack)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (games.size > 1) {
                            Segmented(games, games.firstOrNull { it.id == cat } ?: games.first(), ::gameLabel, { vm.select(it.id) })
                        }
                        SearchField(filter, { filter = it }, "Search by set name or code…", onSearch = {})
                    }
                }
                when (val s = sets) {
                    Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox() }
                    is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { MessageBox("Couldn't load sets", s.message, "Try again", { vm.select(cat) }) }
                    is Load.Ready -> {
                        val q = filter.trim().lowercase()
                        val list = if (q.isEmpty()) s.value else s.value.filter { q.isEmpty() || q in it.name.lowercase() || it.abbreviation?.lowercase()?.startsWith(q) == true }
                        if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { MessageBox("No sets match") }
                        items(list, key = { it.id }, contentType = { "set" }) { set -> SetTile(set, { onOpenSet(set.id) }) }
                    }
                }
            }
        }
    }
}

@Composable
fun SetDetailScreen(setId: Long, onOpenProduct: (Long) -> Unit, onBack: () -> Unit) {
    val vm: SetDetailViewModel = viewModel(key = "set-$setId", factory = viewModelFactory { initializer { SetDetailViewModel(setId) } })
    val detail by vm.detail.collectAsState()
    val type by vm.type.collectAsState()
    val sort by vm.sort.collectAsState()
    val grid by vm.grid.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }

    VaultBackground {
        Column(Modifier.fillMaxSize()) {
            VaultTopBar(title = (detail as? Load.Ready)?.value?.set?.name ?: "Set", onBack = onBack)
            ProductGrid(grid, { onOpenProduct(it.id) }, vm::loadMore, { vm.setType(type) }, emptyTitle = "Nothing here", emptyBody = null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                        when (val d = detail) {
                            Load.Loading -> LoadingBox()
                            is Load.Failed -> MessageBox("Couldn't load this set", d.message)
                            is Load.Ready -> {
                                val set = d.value.set
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SetArtBox(set, Modifier.width(140.dp).aspectRatio(16f / 10f))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(listOfNotNull(d.value.categoryName, set.abbreviation).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.text3)
                                        Text(set.name, style = MaterialTheme.typography.headlineSmall, color = Palette.text)
                                        set.publishedOn?.let { Text("Released ${it.dayLabel()}", style = MaterialTheme.typography.bodySmall, color = Palette.text2) }
                                    }
                                }
                                val stats = d.value.stats
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Stat("Set value (cards)", stats.cardsValue.usd(), change = stats.cardsValue?.let { v -> stats.cardsValue7d?.takeIf { it > 0 }?.let { (v - it) / it } })
                                    Stat("Cards", set.cardCount.toString())
                                    Stat("Sealed", set.sealedCount.toString())
                                    Stat("Top card", stats.topCard.usd())
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Segmented(listOf("cards", "sealed"), type, { if (it == "cards") "Cards" else "Sealed" }, vm::setType)
                            SortMenu(sort, listOf(SearchSort.PRICE_DESC, SearchSort.PRICE_ASC, SearchSort.NUMBER, SearchSort.NAME, SearchSort.GAIN, SearchSort.LOSS), vm::setSort)
                        }
                        SearchField(text, { text = it }, "Search this set…", onSearch = { vm.setQuery(text.trim()) })
                    }
                }
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String, change: Double? = null) {
    GlassCard(padding = 12.dp) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.text3)
        Text(value, style = PriceStyle, fontSize = 18.sp, color = Palette.text)
        ChangeBadge(change, "7D")
    }
}
