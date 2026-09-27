package com.cardprice.app.ui.collection

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.cardprice.app.data.scan.ScanAction
import com.cardprice.app.data.scan.ScanHistoryEntry
import com.cardprice.app.data.scan.ScanHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class ScanHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ScanHistoryStore(File(application.filesDir, "collection"))
    private val _entries = MutableStateFlow<List<ScanHistoryEntry>?>(null)
    val entries: StateFlow<List<ScanHistoryEntry>?> = _entries.asStateFlow()

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) { _entries.value = store.load() }
    }

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) {
            store.clear()
            _entries.value = emptyList()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanHistoryScreen(
    entries: List<ScanHistoryEntry>?,
    onOpen: (ScanHistoryEntry) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan history", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!entries.isNullOrEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                entries == null -> Unit
                entries.isEmpty() -> Text(
                    "Nothing scanned yet. Cards you add with the scanner show up here.",
                    modifier = Modifier.padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    val byDay = entries.groupBy { dayOf(it.at) }
                    byDay.forEach { (day, dayEntries) ->
                        item(key = "day_$day") {
                            val added = dayEntries.sumOf { it.delta }
                            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Text(dayLabel(dayEntries.first().at), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(
                                        "${if (added >= 0) "+" else ""}$added ${if (added == 1 || added == -1) "card" else "cards"}",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                        items(dayEntries, key = { "${it.at}_${it.cardId}_${it.variantKey}_${it.action}" }) { e ->
                            HistoryRow(e, onOpen)
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear scan history?") },
            text = { Text("This only clears the list. Cards stay in your collection.") },
            confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryRow(e: ScanHistoryEntry, onOpen: (ScanHistoryEntry) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(model = e.image, contentDescription = null, modifier = Modifier.width(40.dp).aspectRatio(0.716f))
        Column(Modifier.weight(1f)) {
            Text("${e.cardName} · ${e.variantLabel}", fontWeight = FontWeight.Medium)
            Text(
                "${e.setName} #${e.number} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.at))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ActionTag(e)
    }
    HorizontalDivider(Modifier.padding(start = 68.dp))
}

@Composable
private fun ActionTag(e: ScanHistoryEntry) {
    val (bg, fg) = when (e.action) {
        ScanAction.AUTO_ADDED, ScanAction.ADDED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ScanAction.REMOVED, ScanAction.UNDONE -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val amount = if (e.delta > 0) "+${e.delta}" else "${e.delta}"
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(8.dp)) {
        Text("${e.action.label} $amount", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private fun dayOf(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(millis: Long): String {
    val today = dayOf(System.currentTimeMillis())
    return when (dayOf(millis)) {
        today -> "Today"
        today - 24L * 60 * 60 * 1000 -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}
