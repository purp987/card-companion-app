package com.cardprice.app.ui.collection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import androidx.camera.core.ImageCapture
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch
import com.cardprice.app.data.scan.MotionDetector
import androidx.compose.foundation.layout.PaddingValues
import com.cardprice.app.data.collection.CardPrices
import com.cardprice.app.ui.formatCurrency
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.cardprice.app.data.scan.CardTextParser
import com.cardprice.app.data.Language
import com.cardprice.app.data.scan.ScanConfidence
import com.cardprice.app.data.scan.ScanFinish
import com.cardprice.app.data.scan.ScanSetup
import com.cardprice.app.data.scan.ScanResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text as MlText
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(
    status: ScanStatus,
    collection: CollectionState,
    onCameraText: (List<String>) -> Unit,
    onPhotoText: (List<String>) -> Unit,
    onPhotoError: (String) -> Unit,
    onChoose: (Int) -> Unit,
    onNext: () -> Unit,
    onSetCount: (ScanResult, variantKey: String, count: Int) -> Unit,
    autoAdd: Boolean,
    onAutoAddChange: (Boolean) -> Unit,
    onUndo: (ScanStatus.AutoAdded) -> Unit,
    /** The card in view was shaken or moved a lot: start the read over. */
    onMotion: () -> Unit,
    /** "Not this card" on a suggestion. */
    onSkip: () -> Unit,
    /** Camera problems (stalls, restarts) for the scan log. */
    onCameraEvent: (String) -> Unit,
    onAddAnother: (ScanStatus.AutoAdded) -> Unit,
    onOpenHistory: () -> Unit,
    setup: ScanSetup,
    choosing: Boolean,
    /** Sets offered as quick picks in the setup panel: (language, set id, set name). */
    recentSets: List<Triple<Language, String, String>>,
    onStart: (ScanSetup) -> Unit,
    onChangeSetup: () -> Unit,
    mode: ScanMode,
    layout: BinderLayout,
    bulk: BulkState?,
    bulkAdded: BulkAdded?,
    manualAdded: ManualAdded?,
    /** Market prices of the cards in results, by card id. */
    prices: Map<String, CardPrices>,
    binder: BinderActions,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraAspect by remember { mutableFloatStateOf(1f) }
    var capturing by remember { mutableStateOf(false) }
    val numberOnly = setup.setId != null
    fun readBinderPage(page: Bitmap) {
        scope.launch {
            binder.onReading(0, layout.rows * layout.cols)
            val pockets = readPage(page, layout, numberOnly) { done, total -> binder.onReading(done, total) }
            binder.onPage(pockets)
        }
    }
    val context = LocalContext.current
    val view = LocalView.current
    // Keep the screen on while the scanner is open; normal timeout resumes when leaving it.
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var hasCamera by remember { mutableStateOf(hasCameraPermission(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var torch by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        asked = true
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (mode == ScanMode.BINDER) {
            val page = runCatching { loadBitmap(context, uri) }.getOrNull()
            if (page == null) onPhotoError("Couldn't open that photo.") else readBinderPage(page)
        } else {
            recognizePhoto(context, uri, onPhotoText, onPhotoError)
        }
    }
    DisposableEffect(Unit) {
        if (!hasCamera && !asked) permission.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan cards", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    // Kept in the top bar so they never move when a result appears below.
                    TextButton(onClick = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Photo") }
                    if (hasCamera) {
                        TextButton(onClick = { torch = !torch }) { Text(if (torch) "Light ✓" else "Light") }
                    }
                    TextButton(onClick = onOpenHistory) { Text("History") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Camera area: live preview with a card-shaped guide.
            Box(
                // While choosing what to scan, the camera shrinks so the whole setup fits without scrolling.
                Modifier.fillMaxWidth()
                    // Reviewing a binder page needs the room; the camera isn't used then.
                    .weight(
                        when {
                            choosing -> 0.3f
                            mode == ScanMode.BINDER && (bulk != null || bulkAdded != null) -> 0.2f
                            mode == ScanMode.BINDER -> 0.45f
                            else -> 1f
                        },
                    )
                    .background(Color.Black)
                    .onGloballyPositioned { if (it.size.height > 0) cameraAspect = it.size.width.toFloat() / it.size.height },
                contentAlignment = Alignment.Center,
            ) {
                if (hasCamera) {
                    CameraPreview(
                        torch = torch,
                        onCaptureReady = { imageCapture = it },
                        onMotion = { if (!choosing) onMotion() },
                        onCameraEvent = onCameraEvent,
                        // Frames keep being read while a suggestion is up, so reading another card replaces it.
                        // The view model decides what to do with each reading (including while a lookup runs).
                        onText = { if (!choosing) onCameraText(it) },
                    )
                    if (mode == ScanMode.BINDER && !choosing) {
                        PageGuide(layout)
                    } else {
                        Box(
                            Modifier.fillMaxHeight(0.86f).aspectRatio(63f / 88f)
                                .border(BorderStroke(3.dp, Color.White.copy(alpha = 0.85f)), RoundedCornerShape(14.dp)),
                        )
                    }
                    ConfirmedSplash(splashFor(status as? ScanStatus.AutoAdded, bulkAdded, manualAdded))
                } else {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Camera access is needed to scan cards. You can still pick a photo of a card.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        OutlinedButton(
                            onClick = {
                                if (asked) {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                    )
                                } else permission.launch(Manifest.permission.CAMERA)
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text(if (asked) "Open app settings" else "Allow camera", color = Color.White) }
                    }
                }
            }

            // Result / status panel.
            Column(
                (
                    when {
                        choosing -> Modifier.fillMaxWidth().weight(0.7f)
                        mode == ScanMode.BINDER && (bulk != null || bulkAdded != null) -> Modifier.fillMaxWidth().weight(0.8f)
                        mode == ScanMode.BINDER -> Modifier.fillMaxWidth().weight(0.55f)
                        else -> Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    }
                )
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (choosing) {
                    SetupPanel(setup, recentSets, onStart)
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Scanning", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(setup.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = onChangeSetup) { Text("Change") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == ScanMode.SINGLE, onClick = { binder.onMode(ScanMode.SINGLE) }, label = { Text("Single card") })
                    FilterChip(selected = mode == ScanMode.BINDER, onClick = { binder.onMode(ScanMode.BINDER) }, label = { Text("Binder page") })
                }
                if (mode == ScanMode.BINDER) {
                    BinderPanel(layout, bulk, bulkAdded, capturing, binder) {
                        val capture = imageCapture ?: return@BinderPanel
                        capturing = true
                        scope.launch {
                            val picture = runCatching { capture.captureBitmap(context) }.getOrNull()
                            capturing = false
                            if (picture == null) onPhotoError("Couldn't take a picture. Try again.")
                            else readBinderPage(cropToGuide(picture, cameraAspect, layout))
                        }
                    }
                    return@Column
                }
                FilterChip(
                    selected = autoAdd,
                    onClick = { onAutoAddChange(!autoAdd) },
                    label = { Text(if (autoAdd) "Auto-add sure matches: on" else "Auto-add sure matches: off") },
                )
                when (status) {
                    is ScanStatus.HoldSteady -> Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = status.result.card.imageUrl("low"),
                            contentDescription = null,
                            modifier = Modifier.width(44.dp).aspectRatio(0.716f),
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Hold steady…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Checking ${status.result.card.name} (${status.result.setName} #${status.result.card.number}) once more before adding it.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            rowPriceText(status.result.card, prices[status.result.card.id])?.let { PriceLine(it) }
                            TextButton(onClick = onSkip, contentPadding = PaddingValues(0.dp)) { Text("Not this card") }
                        }
                    }
                    is ScanStatus.AutoAdded -> AddedBanner(status, collection, prices[status.result.card.id], onUndo, onAddAnother)
                    ScanStatus.Searching -> StatusText(
                        "Hold a card inside the frame",
                        "Make sure the number at the bottom (like \"PBL EN 111/084\") is sharp and well lit. " +
                            "Or tap Photo to scan a picture.",
                    )
                    is ScanStatus.LookingUp -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                        Text("Read ${status.summary}, looking it up…", modifier = Modifier.padding(start = 12.dp))
                    }
                    is ScanStatus.NotFound -> StatusText(
                        if (status.fromPhoto && status.summary == "no card number") "Couldn't read a card number in that photo"
                        else "No card found for ${status.summary}",
                        if (status.fromPhoto) "Try a sharper photo that shows the bottom of the card."
                        else "Still scanning. Try more light, or hold the card flatter.",
                    )
                    is ScanStatus.Failed -> StatusText("Something went wrong", status.message)
                    is ScanStatus.Found -> FoundCard(status, collection, prices, onChoose, onNext, onSkip, onSetCount)
                }
                if (status is ScanStatus.NotFound || status is ScanStatus.Failed) {
                    TextButton(onClick = onNext) { Text("Scan again") }
                }
            }
        }
    }
}

@Composable
private fun PriceLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
}

@Composable
private fun StatusText(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundCard(
    status: ScanStatus.Found,
    collection: CollectionState,
    prices: Map<String, CardPrices>,
    onChoose: (Int) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onSetCount: (ScanResult, String, Int) -> Unit,
) {
    val result = status.results[status.chosen]
    val card = result.card
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    fun count(key: String) = collection.count(result.set.language, result.set.setId, card.id, key)
    // A result can pop up under a finger that was already tapping; ignore taps for a moment so that
    // tap can't add a card by accident.
    val shownAt = remember(result) { System.currentTimeMillis() }
    fun settled() = System.currentTimeMillis() - shownAt > TAP_GUARD_MS

    val (heading, detail) = when {
        status.askVariant -> "Which version do you have?" to
            "This card comes in ${card.variants.size} versions, so it isn't added until you tap the one you have."
        status.confidence == ScanConfidence.MEDIUM -> "Likely match: check it's right" to
            "Tap the version you have to add it."
        else -> "Not sure this is the right card" to "Check it, or pick another match below."
    }
    Column {
        Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = card.imageUrl("low"),
                contentDescription = null,
                modifier = Modifier.width(84.dp).aspectRatio(0.716f),
            )
            Column(Modifier.weight(1f)) {
                Text(card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${result.setName} · #${card.number}", style = MaterialTheme.typography.bodyMedium)
                rowPriceText(card, prices[card.id])?.let { PriceLine(it) }
                Text(
                    listOfNotNull(result.set.language.label, card.rarity).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Tap to add a copy · hold to remove",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    card.variants.forEach { v ->
                        VariantButton(
                            label = v.label,
                            count = count(v.key),
                            onTap = { if (settled()) onSetCount(result, v.key, count(v.key) + 1) },
                            onLongPress = { if (settled() && count(v.key) > 0) pendingRemoval = v.key },
                        )
                    }
                }
            }
        }
    }
    if (status.results.size > 1) {
        Text("Other possible matches", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            status.results.forEachIndexed { i, r ->
                val price = prices[r.card.id]?.byVariant?.values?.minByOrNull { it.amount }
                FilterChip(
                    selected = i == status.chosen,
                    onClick = { onChoose(i) },
                    label = {
                        Text("${r.setName} #${r.card.number}" + (price?.let { " · ${formatCurrency(it.amount, it.currency)}" } ?: ""))
                    },
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { if (settled()) onSkip() }, modifier = Modifier.weight(1f)) {
            Text(if (status.results.size > 1) "Not this one" else "Not this card")
        }
        Button(onClick = { if (settled()) onNext() }, modifier = Modifier.weight(1f)) { Text("Scan next card") }
    }
    if (!status.fromPhoto) {
        Text(
            "Wrong card? Tap \"Not this card\", or give the card a shake to read it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    pendingRemoval?.let { key ->
        val variant = card.variants.firstOrNull { it.key == key }
        val n = count(key)
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove from collection?") },
            text = { Text("Remove ${if (n == 1) "your copy" else "all $n copies"} of #${card.number} ${card.name} (${variant?.label})?") },
            confirmButton = { TextButton(onClick = { onSetCount(result, key, 0); pendingRemoval = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Keep") } },
        )
    }
}

private const val TAP_GUARD_MS = 600L
private const val SPLASH_MS = 1100L
/** How often the camera is checked, and how long without a frame counts as stuck. */
private const val CAMERA_CHECK_MS = 2_000L
private const val CAMERA_STALL_MS = 6_000L

/** What the "Confirmed" splash shows. */
private data class SplashInfo(val event: Long, val image: String?, val title: String, val subtitle: String)

/** The most recent of an automatic add, a binder page or a copy added by hand. */
private fun splashFor(added: ScanStatus.AutoAdded?, page: BulkAdded?, manual: ManualAdded?): SplashInfo? {
    val newest = listOfNotNull(added?.event, page?.event, manual?.event).maxOrNull() ?: return null
    return when (newest) {
        manual?.event -> manual.let { m ->
            SplashInfo(
                m.event,
                m.result.card.imageUrl("low"),
                "Added",
                "${m.result.card.name} · ${m.result.card.variants.firstOrNull { it.key == m.variantKey }?.label.orEmpty()}",
            )
        }
        page?.event -> splashFor(null, page)
        else -> splashFor(added, null)
    }
}

private fun splashFor(added: ScanStatus.AutoAdded?, page: BulkAdded?): SplashInfo? = when {
    page != null -> SplashInfo(
        page.event,
        page.added.firstOrNull()?.result?.card?.imageUrl("low"),
        "Confirmed ${page.added.size} ${if (page.added.size == 1) "card" else "cards"}",
        "from this binder page",
    )
    added != null -> SplashInfo(
        added.event,
        added.result.card.imageUrl("low"),
        if (added.copies > 1) "Confirmed ×${added.copies}" else "Confirmed",
        "${added.result.card.name} · ${added.result.card.variants.firstOrNull { it.key == added.variantKey }?.label.orEmpty()}",
    )
    else -> null
}

/**
 * A big green check over the camera when a card is verified and added, with a short vibration, so
 * it's clear without looking down at the result panel. Shows again for each +1.
 */
@Composable
private fun ConfirmedSplash(info: SplashInfo?) {
    val haptics = LocalHapticFeedback.current
    var shown by remember { mutableStateOf<SplashInfo?>(null) }
    // When the newest confirmation goes away (an added card's banner clears), an older one becomes the
    // "latest" again; remembering what was already shown keeps it from playing twice.
    var lastShownEvent by remember { mutableStateOf(info?.event ?: Long.MIN_VALUE) }
    LaunchedEffect(info?.event) {
        if (info == null || info.event <= lastShownEvent) return@LaunchedEffect
        lastShownEvent = info.event
        shown = info
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(SPLASH_MS)
        shown = null
    }
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn(tween(120)) + scaleIn(tween(180), initialScale = 0.85f),
        exit = fadeOut(tween(250)),
        modifier = Modifier.fillMaxSize(),
    ) {
        val a = shown ?: return@AnimatedVisibility
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            // Small camera area (binder review): a one-line version that fits.
            if (maxHeight < 320.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(52.dp).background(Color(0xFF2E7D32), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Column {
                        Text(a.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(a.subtitle, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                return@BoxWithConstraints
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(96.dp).background(Color(0xFF2E7D32), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
                }
                AsyncImage(
                    model = a.image,
                    contentDescription = null,
                    modifier = Modifier.height(140.dp).aspectRatio(0.716f),
                )
                Text(a.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(a.subtitle, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** "What are you scanning?": finish, language and set, chosen before the camera starts reading. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupPanel(
    initial: ScanSetup,
    recentSets: List<Triple<Language, String, String>>,
    onStart: (ScanSetup) -> Unit,
) {
    var finish by remember { mutableStateOf(initial.finish) }
    var language by remember { mutableStateOf(initial.language) }
    var set by remember { mutableStateOf(initial.setId?.let { id -> initial.setName?.let { id to it } }) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("What are you scanning?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Text("Finish", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanFinish.entries.forEach { f ->
                FilterChip(selected = finish == f, onClick = { finish = f }, label = { Text(f.label) })
            }
        }
        Text(
            if (finish == ScanFinish.MIXED) "Cards that come in several finishes will ask which one you have."
            else "Cards that come in several finishes are added as ${finish.label} without asking.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Language", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = language == null, onClick = { language = null; set = null }, label = { Text("Any") })
            listOf(Language.ENGLISH, Language.JAPANESE, Language.CHINESE_SIMPLIFIED).forEach { l ->
                FilterChip(
                    selected = language == l,
                    onClick = {
                        language = l
                        if (set != null && recentSets.none { it.first == l && it.second == set!!.first }) set = null
                    },
                    label = { Text(l.chipLabel) },
                )
            }
        }

        Text("Set", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = set == null, onClick = { set = null }, label = { Text("Any set") })
            recentSets.filter { language == null || it.first == language }.take(8).forEach { (l, id, name) ->
                FilterChip(
                    selected = set?.first == id,
                    onClick = {
                        set = id to name
                        language = l
                    },
                    label = { Text(name) },
                )
            }
        }
        Text(
            if (set == null) "Picking the set you're sorting makes scanning faster: only the card number has to be read."
            else "Only ${set!!.second} will be matched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { onStart(ScanSetup(finish, language, set?.first, set?.second)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start scanning") }
    }
}

/** Shown after a card was added automatically; scanning continues underneath. */
@Composable
private fun AddedBanner(
    added: ScanStatus.AutoAdded,
    collection: CollectionState,
    prices: CardPrices?,
    onUndo: (ScanStatus.AutoAdded) -> Unit,
    onAddAnother: (ScanStatus.AutoAdded) -> Unit,
) {
    val r = added.result
    val variant = r.card.variants.firstOrNull { it.key == added.variantKey }?.label.orEmpty()
    val owned = collection.count(r.set.language, r.set.setId, r.card.id, added.variantKey)
    Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = r.card.imageUrl("low"),
                contentDescription = null,
                modifier = Modifier.width(44.dp).aspectRatio(0.716f),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    "Added ✓ ${r.card.name}${if (added.copies > 1) " ×${added.copies}" else ""}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text("${r.setName} #${r.card.number} · $variant · you have $owned", style = MaterialTheme.typography.bodyMedium)
                prices?.byVariant?.get(added.variantKey)?.let { PriceLine("${formatCurrency(it.amount, it.currency)} market price") }
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = { onAddAnother(added) }) { Text("+1") }
                TextButton(onClick = { onUndo(added) }) { Text("Undo") }
            }
        }
    }
    Text(
        "Next card is picked up automatically. Same card again? Move it out of view for a second and " +
            "bring it back, or tap +1.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Live camera preview that runs text recognition on frames (at most ~3 a second). */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(
    torch: Boolean,
    onCaptureReady: (ImageCapture) -> Unit,
    onMotion: () -> Unit,
    onText: (List<String>) -> Unit,
    onCameraEvent: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val latestOnText by androidx.compose.runtime.rememberUpdatedState(onText)
    val latestOnMotion by androidx.compose.runtime.rememberUpdatedState(onMotion)
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    val latestOnCameraEvent by androidx.compose.runtime.rememberUpdatedState(onCameraEvent)
    // Set when the camera stops delivering frames; changing it rebuilds the camera.
    var restarts by remember { mutableIntStateOf(0) }
    val lastFrameAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val resumed = lifecycleOwner.lifecycle.currentStateFlow.collectAsState().value
        .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(resumed, restarts) {
        if (!resumed) return@LaunchedEffect
        lastFrameAt.set(System.currentTimeMillis())
        while (true) {
            delay(CAMERA_CHECK_MS)
            val silent = System.currentTimeMillis() - lastFrameAt.get()
            if (silent > CAMERA_STALL_MS) {
                latestOnCameraEvent("camera stalled: no frame for $silent ms; restarting it (restart ${restarts + 1})")
                restarts++
                return@LaunchedEffect
            }
        }
    }

    DisposableEffect(lifecycleOwner, restarts) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var lastRun = 0L
        val motion = MotionDetector()
        val main = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                // Card numbers are small; ask for ~1080p frames so they're legible.
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                val now = System.currentTimeMillis()
                lastFrameAt.set(now)
                // Every frame is checked for a big move (card shaken, moved or swapped).
                if (media != null) {
                    val luma = media.planes[0]
                    val moved = runCatching {
                        motion.onFrame(MotionDetector.grid(luma.buffer, media.width, media.height, luma.rowStride, luma.pixelStride), now)
                    }.getOrDefault(false)
                    if (moved) main.execute { latestOnMotion() }
                }
                if (media == null || now - lastRun < 350) {
                    proxy.close()
                    return@setAnalyzer
                }
                lastRun = now
                val task = runCatching { recognizer.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)) }
                    .onFailure { e -> main.execute { latestOnCameraEvent("text recognition couldn't start: $e") } }
                    .getOrNull()
                if (task == null) {
                    proxy.close()
                    return@setAnalyzer
                }
                task.addOnSuccessListener { latestOnText(it.linesTopToBottom()) }
                    .addOnFailureListener { e -> latestOnCameraEvent("text recognition failed: $e") }
                    .addOnCompleteListener { proxy.close() }
            }
            // Full-resolution stills for binder pages (live frames are too small to read nine cards).
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            provider.unbindAll()
            try {
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
                onCaptureReady(capture)
                latestOnCameraEvent("camera started (restart $restarts)")
            } catch (e: Exception) {
                latestOnCameraEvent("camera couldn't start: $e")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            latestOnCameraEvent("camera stopped")
            runCatching { providerFuture.get().unbindAll() }
            recognizer.close()
            executor.shutdown()
        }
    }
    DisposableEffect(camera, torch) {
        camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(torch) }
        onDispose { }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun recognizePhoto(context: Context, uri: Uri, onLines: (List<String>) -> Unit, onError: (String) -> Unit) {
    val bitmap = runCatching { loadBitmap(context, uri) }.getOrNull() ?: run {
        onError("Couldn't open that photo.")
        return
    }
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    recognizer.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { full ->
            val lines = full.linesTopToBottom()
            if (CardTextParser.parse(lines).usable) {
                onLines(lines)
                recognizer.close()
                return@addOnSuccessListener
            }
            // The set code and number are tiny; read the bottom of the card again, enlarged, then just
            // the bottom-left corner (where modern cards print them) enlarged further.
            recognizer.process(InputImage.fromBitmap(crop(bitmap, 0f, 0.84f, 1f), 0))
                .addOnSuccessListener { strip ->
                    val withStrip = lines + strip.linesTopToBottom()
                    if (CardTextParser.parse(withStrip).usable) {
                        onLines(withStrip)
                        recognizer.close()
                        return@addOnSuccessListener
                    }
                    recognizer.process(InputImage.fromBitmap(crop(bitmap, 0f, 0.9f, 0.45f), 0))
                        .addOnSuccessListener { corner -> onLines(withStrip + corner.linesTopToBottom()) }
                        .addOnFailureListener { onLines(withStrip) }
                        .addOnCompleteListener { recognizer.close() }
                }
                .addOnFailureListener {
                    onLines(lines)
                    recognizer.close()
                }
        }
        .addOnFailureListener {
            recognizer.close()
            onError("Couldn't read text from that photo.")
        }
}

/** Loads a photo, scaled so its longer side is at most 3000px (enough for OCR, easy on memory). */
internal fun loadBitmap(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 3000) sample *= 2
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    // Respect the camera's orientation tag so text isn't sideways.
    val rotation = context.contentResolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    if (rotation == 0f) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
}

/** The part of the image from [left] to [right] (fractions of width) below [top], scaled up so small print is readable. */
internal fun crop(bitmap: Bitmap, left: Float, top: Float, right: Float): Bitmap {
    val x = (bitmap.width * left).toInt()
    val y = (bitmap.height * top).toInt()
    val w = (bitmap.width * right).toInt() - x
    val part = Bitmap.createBitmap(bitmap, x, y, w, bitmap.height - y)
    val scale = (2000f / part.width).coerceIn(1f, 6f)
    return Bitmap.createScaledBitmap(part, (part.width * scale).toInt(), (part.height * scale).toInt(), true)
}

/** Every recognized line, top of the card first. */
internal fun MlText.linesTopToBottom(): List<String> =
    textBlocks.flatMap { it.lines }.sortedBy { it.boundingBox?.top ?: 0 }.map { it.text }

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
