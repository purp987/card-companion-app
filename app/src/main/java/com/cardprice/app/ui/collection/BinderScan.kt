package com.cardprice.app.ui.collection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.cardprice.app.data.scan.CardTextParser
import com.cardprice.app.data.scan.ScanConfidence
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------- Reading text from pictures

/** Callbacks for the binder panel, grouped to keep the scanner screen's parameter list readable. */
class BinderActions(
    val onMode: (ScanMode) -> Unit,
    val onLayout: (BinderLayout) -> Unit,
    val onPage: (List<List<String>>) -> Unit,
    val onReading: (Int, Int) -> Unit,
    val onToggle: (Int) -> Unit,
    val onVariant: (Int, String) -> Unit,
    val onChoose: (Int, Int) -> Unit,
    val onAdd: () -> Unit,
    val onUndo: () -> Unit,
    val onNextPage: () -> Unit,
)

/**
 * Reads one card's text: the whole picture first, then (if the number wasn't found) the bottom strip
 * and the bottom-left corner enlarged, where the small print is.
 */
internal suspend fun readCardText(bitmap: Bitmap, recognizer: TextRecognizer, enough: (List<String>) -> Boolean): List<String> {
    var lines = recognizer.read(bitmap)
    if (enough(lines)) return lines
    lines = lines + recognizer.read(crop(bitmap, 0f, 0.84f, 1f))
    if (enough(lines)) return lines
    return lines + recognizer.read(crop(bitmap, 0f, 0.9f, 0.45f))
}

private suspend fun TextRecognizer.read(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
    process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { cont.resume(it.linesTopToBottom()) }
        .addOnFailureListener { cont.resume(emptyList()) }
}

/** Splits a binder page into pockets (left to right, top to bottom) and reads each one. */
internal suspend fun readPage(
    page: Bitmap,
    layout: BinderLayout,
    numberOnly: Boolean,
    onProgress: (done: Int, total: Int) -> Unit,
): List<List<String>> = withContext(Dispatchers.Default) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val cellW = page.width / layout.cols
        val cellH = page.height / layout.rows
        val total = layout.rows * layout.cols
        (0 until total).map { i ->
            val row = i / layout.cols
            val col = i % layout.cols
            // Trim a little off each side so neighbouring pockets' edges don't bleed in.
            val insetX = (cellW * 0.03f).toInt()
            val insetY = (cellH * 0.03f).toInt()
            val pocket = Bitmap.createBitmap(page, col * cellW + insetX, row * cellH + insetY, cellW - 2 * insetX, cellH - 2 * insetY)
            readCardText(pocket, recognizer) { lines ->
                val clues = CardTextParser.parse(lines)
                if (numberOnly) clues.number != null else clues.usable
            }.also { withContext(Dispatchers.Main) { onProgress(i + 1, total) } }
        }
    } finally {
        recognizer.close()
    }
}

/** Takes a full-resolution picture and returns it upright. */
internal suspend fun ImageCapture.captureBitmap(context: Context): Bitmap = suspendCancellableCoroutine { cont ->
    takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            image.close()
            cont.resume(
                if (rotation == 0) bitmap
                else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true),
            )
        }

        override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
    })
}

/** Aspect ratio (width / height) of a binder page with this layout. */
internal fun BinderLayout.pageAspect(): Float = (cols * 63f) / (rows * 88f)

/**
 * Cuts the part of a captured picture that sits inside the on-screen page guide. The preview shows
 * the centre of the picture cropped to the camera area ([viewAspect]); the guide is centred in it at
 * 86% of its height.
 */
internal fun cropToGuide(picture: Bitmap, viewAspect: Float, layout: BinderLayout): Bitmap {
    val pictureAspect = picture.width.toFloat() / picture.height
    val (visibleW, visibleH) = if (pictureAspect > viewAspect) {
        picture.height * viewAspect to picture.height.toFloat()
    } else {
        picture.width.toFloat() to picture.width / viewAspect
    }
    var guideH = visibleH * 0.86f
    var guideW = guideH * layout.pageAspect()
    if (guideW > visibleW) {
        guideW = visibleW
        guideH = guideW / layout.pageAspect()
    }
    val left = ((picture.width - guideW) / 2).toInt().coerceAtLeast(0)
    val top = ((picture.height - guideH) / 2).toInt().coerceAtLeast(0)
    return Bitmap.createBitmap(picture, left, top, guideW.toInt().coerceAtMost(picture.width - left), guideH.toInt().coerceAtMost(picture.height - top))
}

// ---------------------------------------------------------------- UI

/** Page-shaped guide with the pocket grid, so the page can be lined up. */
@Composable
internal fun PageGuide(layout: BinderLayout) {
    Box(
        Modifier.fillMaxHeight(0.86f).aspectRatio(layout.pageAspect())
            .border(BorderStroke(3.dp, Color.White.copy(alpha = 0.85f)), RoundedCornerShape(10.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val line = Color.White.copy(alpha = 0.5f)
            for (c in 1 until layout.cols) {
                val x = size.width * c / layout.cols
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
            }
            for (r in 1 until layout.rows) {
                val y = size.height * r / layout.rows
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BinderPanel(
    layout: BinderLayout,
    bulk: BulkState?,
    bulkAdded: BulkAdded?,
    capturing: Boolean,
    actions: BinderActions,
    onCapture: () -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    when {
        bulkAdded != null -> {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Added ✓ ${bulkAdded.added.size} ${if (bulkAdded.added.size == 1) "card" else "cards"} from this page",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        bulkAdded.added.take(9).forEach { s ->
                            AsyncImage(model = s.result?.card?.imageUrl("low"), contentDescription = null, modifier = Modifier.size(width = 30.dp, height = 42.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.onNextPage) { Text("Next page") }
                        TextButton(onClick = actions.onUndo) { Text("Undo page") }
                    }
                }
            }
        }
        bulk?.reading != null -> Column {
            val (done, total) = bulk.reading
            Text("Reading pocket $done of $total…", fontWeight = FontWeight.Medium)
            LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        bulk != null && bulk.slots.isNotEmpty() -> {
            val ready = bulk.slots.count { it.selected }
            val needLook = bulk.slots.count { it.state == SlotState.FOUND && !it.selected } +
                bulk.slots.count { it.state == SlotState.UNREADABLE || it.state == SlotState.NOT_FOUND }
            val empty = bulk.slots.count { it.state == SlotState.EMPTY }
            Text(
                listOfNotNull(
                    "$ready ready to add",
                    needLook.takeIf { it > 0 }?.let { "$it to check" },
                    empty.takeIf { it > 0 }?.let { "$it empty" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            // Buttons first, so they're reachable without scrolling past the grid.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onAdd, enabled = ready > 0) { Text("Add $ready ${if (ready == 1) "card" else "cards"}") }
                OutlinedButton(onClick = actions.onNextPage) { Text("Rescan page") }
            }
            Text(
                "Tap a pocket to change the card or version, or to include/skip it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            bulk.slots.chunked(layout.cols).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    row.forEach { slot ->
                        // Compact fixed size, like looking at the binder page, whatever the layout.
                        SlotTile(slot, Modifier.width(96.dp)) { if (slot.state == SlotState.FOUND) editing = slot.index }
                    }
                }
            }
        }
        else -> {
            Text("Binder page", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BinderLayout.entries.forEach { l ->
                    FilterChip(selected = layout == l, onClick = { actions.onLayout(l) }, label = { Text(l.label) })
                }
            }
            Text(
                "Line the page up with the grid, straight on and without glare, then capture it. Or tap Photo to use a picture of the page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCapture, enabled = !capturing, modifier = Modifier.fillMaxWidth()) {
                Text(if (capturing) "Capturing…" else "Capture page")
            }
        }
    }

    val slot = editing?.let { i -> bulk?.slots?.firstOrNull { it.index == i } }
    if (slot != null) SlotEditor(slot, actions) { editing = null }
}

@Composable
private fun SlotTile(slot: BulkSlot, modifier: Modifier, onClick: () -> Unit) {
    val r = slot.result
    val border = when {
        slot.selected -> Color(0xFF2E7D32)
        slot.state == SlotState.FOUND -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(2.dp, border),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box {
            Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (r != null) {
                    AsyncImage(model = r.card.imageUrl("low"), contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(0.716f))
                    Text(r.card.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "#${r.card.number} · " + (r.card.variants.firstOrNull { it.key == slot.variantKey }?.label ?: "pick version"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.716f), contentAlignment = Alignment.Center) {
                        Text(
                            when (slot.state) {
                                SlotState.EMPTY -> "Empty"
                                SlotState.UNREADABLE -> "Couldn't read"
                                else -> "Not found ${slot.summary}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (slot.selected) {
                Surface(shape = CircleShape, color = Color(0xFF2E7D32), modifier = Modifier.padding(4.dp).size(20.dp).align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Check, contentDescription = "Will be added", tint = Color.White, modifier = Modifier.padding(2.dp))
                }
            }
        }
    }
}

/** Change a pocket: pick the right card (if unsure), the version, and whether to add it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotEditor(slot: BulkSlot, actions: BinderActions, onDismiss: () -> Unit) {
    val r = slot.result ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${r.card.name} · #${r.card.number}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(model = r.card.imageUrl("low"), contentDescription = null, modifier = Modifier.size(width = 72.dp, height = 100.dp))
                    Column {
                        Text(r.setName, fontWeight = FontWeight.Medium)
                        Text(
                            when (slot.confidence) {
                                ScanConfidence.HIGH -> "Sure match"
                                ScanConfidence.MEDIUM -> "Likely match: check it"
                                ScanConfidence.LOW -> "Not sure"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (slot.results.size > 1) {
                    Text("Which card is it?", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        slot.results.forEachIndexed { i, c ->
                            FilterChip(
                                selected = i == slot.chosen,
                                onClick = { actions.onChoose(slot.index, i) },
                                label = { Text("${c.setName} #${c.card.number}") },
                            )
                        }
                    }
                }
                Text("Version", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.card.variants.forEach { v ->
                        FilterChip(
                            selected = v.key == slot.variantKey,
                            onClick = { actions.onVariant(slot.index, v.key) },
                            label = { Text(v.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = slot.variantKey != null) { actions.onToggle(slot.index) }) {
                    Checkbox(checked = slot.selected, onCheckedChange = { actions.onToggle(slot.index) }, enabled = slot.variantKey != null)
                    Text(if (slot.variantKey == null) "Pick a version to add it" else "Add this card")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
