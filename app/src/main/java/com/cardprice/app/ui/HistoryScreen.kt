package com.cardprice.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.market.HistoryRange
import com.cardprice.app.data.market.HistoryStats
import com.cardprice.app.data.market.Load
import com.cardprice.app.data.market.PricePoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val detailDate = DateTimeFormatter.ofPattern("EEE, MMM d")
private val shortDate = DateTimeFormatter.ofPattern("MMM d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    productId: Long,
    productName: String,
    /** Cards in one unit of this product, or 0 when unknown. */
    cardsPerProduct: Int,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(productName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        Text("TCGplayer sales history", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryRange.entries.forEach { range ->
                        FilterChip(
                            selected = state.range == range,
                            onClick = { viewModel.select(range) },
                            label = { Text(range.label) },
                        )
                    }
                }
            }

            when (val points = state.points) {
                Load.Loading -> item {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is Load.Failed -> item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Couldn't load price history.", fontWeight = FontWeight.Bold)
                        Text(points.message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::retry) { Text("Try again") }
                    }
                }
                is Load.Ready -> if (points.value.isEmpty()) {
                    item { Text("TCGplayer has no sales recorded for this product in this period.") }
                } else {
                    item(key = state.range) {
                        HistoryContent(points.value, cardsPerProduct)
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://www.tcgplayer.com/product/$productId") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("View on TCGplayer") }
            }
        }
    }
}

@Composable
private fun HistoryContent(points: List<PricePoint>, cardsPerProduct: Int) {
    val stats = HistoryStats.of(points)
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(points) { selected = points.lastIndex }
    val bucketLabel = when (stats.bucketDays) {
        1 -> "day"
        7 -> "week"
        else -> "${stats.bucketDays} days"
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Market price",
                value = stats.latestMarket?.money() ?: "—",
                detail = if (cardsPerProduct > 0 && stats.latestMarket != null) {
                    "${(stats.latestMarket / cardsPerProduct).moneyPerCard()} per card"
                } else null,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Avg sale price",
                value = stats.avgSalePrice?.money() ?: "—",
                detail = if (stats.low != null && stats.high != null) {
                    "${stats.low.moneyShort()} – ${stats.high.moneyShort()}"
                } else null,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Units sold", "${stats.totalSold}", "in this period", Modifier.weight(1f))
            StatTile("Sold per day", "%.1f".format(stats.avgSold), "on average", Modifier.weight(1f))
        }

        SelectionDetail(points, selected, stats.bucketDays)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Price", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendItem(MaterialTheme.colorScheme.primary, "Avg sale price")
                    LegendItem(MaterialTheme.colorScheme.secondary, "Market price (dashed)")
                }
                PriceChart(points, selected, onSelect = { selected = it })
                DateAxis(points)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Units sold per $bucketLabel",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                SoldChart(points, selected, onSelect = { selected = it })
                DateAxis(points)
            }
        }

        if (stats.bucketDays > 1) {
            Text(
                "For ranges longer than a month, TCGplayer groups sales into $bucketLabel periods. Use 1M for daily figures.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectionDetail(points: List<PricePoint>, selected: Int?, bucketDays: Int) {
    val point = selected?.let(points::getOrNull)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (point == null) {
            Text("Touch a chart to see a single day.", modifier = Modifier.padding(12.dp))
            return@Surface
        }
        Column(Modifier.padding(12.dp)) {
            val date = if (bucketDays == 1) point.date.format(detailDate)
            else {
                // The newest bucket is still in progress, so don't show dates in the future.
                val end = minOf(point.date.plusDays(bucketDays - 1L), LocalDate.now())
                if (end == point.date) point.date.format(detailDate)
                else "${point.date.format(shortDate)} – ${end.format(shortDate)}"
            }
            Text(date, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Avg sale ${point.avgSalePrice?.money() ?: "—"}")
                Text("Market ${point.marketPrice?.money() ?: "—"}")
                Text("Sold ${point.quantitySold}")
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, detail: String?, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Box(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
