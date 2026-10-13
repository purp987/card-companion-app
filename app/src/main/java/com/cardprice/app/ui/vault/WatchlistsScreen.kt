package com.cardprice.app.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardprice.app.data.market.Load

/** Watchlists, split like the web UI: Chase List (cards) and Shrink Wrap Watch (sealed). */
@Composable
fun WatchlistsScreen(onOpenProduct: (Long) -> Unit, onBack: () -> Unit) {
    val vm: WatchlistViewModel = viewModel()
    val items by vm.items.collectAsState()
    var sealed by rememberSaveable { mutableStateOf(false) }
    VaultBackground {
        Column(Modifier.fillMaxSize()) {
            VaultTopBar(title = "Watchlists", onBack = onBack)
            val all = (items as? Load.Ready)?.value.orEmpty()
            val shown = all.filter { it.isSealed == sealed }
            val grid = when (val s = items) {
                Load.Loading -> GridState(loading = true)
                is Load.Failed -> GridState(loading = false, error = s.message)
                is Load.Ready -> GridState(shown, shown.size, loading = false)
            }
            ProductGrid(
                state = grid,
                onOpen = { onOpenProduct(it.id) },
                onLoadMore = {},
                onRetry = vm::reload,
                emptyTitle = if (sealed) "Nothing on your Shrink Wrap Watch" else "Your Chase List is empty",
                emptyBody = "Tap ☆ on any ${if (sealed) "sealed product" else "card"} to watch its price.",
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Segmented(
                        listOf(false, true),
                        sealed,
                        { if (it) "Shrink Wrap Watch (${all.count { p -> p.isSealed }})" else "Chase List (${all.count { p -> !p.isSealed }})" },
                        { sealed = it },
                        Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
