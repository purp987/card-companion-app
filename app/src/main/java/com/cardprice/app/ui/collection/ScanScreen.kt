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
import com.cardprice.app.ui.usd
import com.cardprice.app.ui.theme.Palette
import com.cardprice.app.data.vault.SyncNote
import com.cardprice.app.data.collection.CollectionCard
import com.cardprice.app.data.collection.VariantPrice
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalDensity
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
    /** Copies owned of a version in the TCG Vault portfolio scans go to. */
    counts: (ScanResult, variantKey: String) -> Int,
    /** Net copies and value added since the scanner opened, shown as a running tally. */
    session: SessionStats = SessionStats(),
    /** What happened on the server for the last change (added / saved offline / problem). */
    syncNote: SyncNote? = null,
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
    /** The card inside the on-screen frame, from the latest camera reading (kept for cards with no picture). */
    onCardFrame: (Bitmap) -> Unit = {},
    /** A photo picked for scanning (kept as the picture of a card with none). */
    onPhotoBitmap: (Bitmap) -> Unit = {},
    onAddAnother: (ScanStatus.AutoAdded) -> Unit,
    onOpenHistory: () -> Unit,
    setup: ScanSetup,
    /** Sets offered as quick picks in the toolbar: (language, set id, set name). */
    recentSets: List<Triple<Language, String, String>>,
    /** Apply changed scan settings on the fly (restarts reading with them). */
    onApplySetup: (ScanSetup) -> Unit,
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
            recognizePhoto(context, uri, onPhotoBitmap, onPhotoText, onPhotoError)
        }
    }
    DisposableEffect(Unit) {
        if (!hasCamera && !asked) permission.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    // Full-bleed camera with floating controls over it, like a dedicated scanner: the live view fills the
    // screen, a translucent bar floats on top, and results/setup slide up from the bottom as a sheet.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val sheetMax = maxHeight * 0.78f

        if (hasCamera) {
            Box(
                Modifier.fillMaxSize().clipToBounds()
                    .onGloballyPositioned { if (it.size.height > 0) cameraAspect = it.size.width.toFloat() / it.size.height },
                contentAlignment = Alignment.Center,
            ) {
                CameraPreview(
                    torch = torch,
                    onCaptureReady = { imageCapture = it },
                    onMotion = onMotion,
                    onCameraEvent = onCameraEvent,
                    onCardFrame = onCardFrame,
                    boxAspect = { cameraAspect },
                    // Frames keep being read while a suggestion is up, so reading another card replaces it.
                    // The view model decides what to do with each reading (binder mode and pauses included).
                    onText = onCameraText,
                )
                if (mode == ScanMode.BINDER) PageGuide(layout) else CardGuide()
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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

        // Floating top bar: back, what's being scanned, torch and an overflow menu.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .statusBarsPadding(),
        ) {
            ScannerTopBar(
                summary = setup.summary,
                hasCamera = hasCamera,
                torch = torch,
                mode = mode,
                autoAdd = autoAdd,
                onAutoAddChange = onAutoAddChange,
                onTorch = { torch = !torch },
                onMode = binder.onMode,
                onBack = onBack,
                onPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onHistory = onOpenHistory,
            )
            if (session.cards > 0) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) { SessionSummary(session) }
            }
        }

        ConfirmedSplash(splashFor(status as? ScanStatus.AutoAdded, bulkAdded, manualAdded))

        // Bottom: the scan result sheet (when there's something to show), then the settings toolbar.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val showSheet = mode == ScanMode.BINDER || status !is ScanStatus.Searching
            if (showSheet) {
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = sheetMax),
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                    color = Palette.surfaceSolid,
                    border = BorderStroke(1.dp, Palette.border),
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SheetGrip()
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
                        } else {
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
                                is ScanStatus.AutoAdded -> AddedBanner(status, counts, prices[status.result.card.id], onUndo, onAddAnother)
                                ScanStatus.Searching -> Unit // handled by the slim hint below
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
                                is ScanStatus.Found -> FoundCard(status, counts, prices, onChoose, onNext, onSkip, onSetCount)
                            }
                            if (status is ScanStatus.NotFound || status is ScanStatus.Failed) {
                                TextButton(onClick = onNext) { Text("Scan again") }
                            }
                            syncNote?.let { SyncLine(it) }
                        }
                    }
                }
            } else {
                // Searching: just a slim hint floating above the toolbar, so the camera stays dominant.
                Text(
                    "Point at a card — keep its number sharp and lit",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            ScanToolbar(setup, recentSets, onApplySetup)
        }
    }
}

/** A short handle at the top of the bottom sheet, so it reads as a sheet. */
@Composable
private fun SheetGrip() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.borderStrong))
    }
}

/** Floating controls over the camera: back, the current setup, torch, and an overflow menu. */
@Composable
private fun ScannerTopBar(
    summary: String,
    hasCamera: Boolean,
    torch: Boolean,
    mode: ScanMode,
    autoAdd: Boolean,
    onAutoAddChange: (Boolean) -> Unit,
    onTorch: () -> Unit,
    onMode: (ScanMode) -> Unit,
    onBack: () -> Unit,
    onPhoto: () -> Unit,
    onHistory: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
        Text(
            summary,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        if (hasCamera) TorchPill(torch, onTorch)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Menu, contentDescription = "More", tint = Color.White) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Pick a photo") }, onClick = { menu = false; onPhoto() })
                DropdownMenuItem(text = { Text("Scan history") }, onClick = { menu = false; onHistory() })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Auto-add sure matches") },
                    onClick = { onAutoAddChange(!autoAdd) },
                    trailingIcon = { if (autoAdd) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.accent) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Single card") },
                    onClick = { menu = false; onMode(ScanMode.SINGLE) },
                    trailingIcon = { if (mode == ScanMode.SINGLE) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.accent) },
                )
                DropdownMenuItem(
                    text = { Text("Binder page") },
                    onClick = { menu = false; onMode(ScanMode.BINDER) },
                    trailingIcon = { if (mode == ScanMode.BINDER) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.accent) },
                )
            }
        }
    }
}

/** A small pill toggle for the camera light. */
@Composable
private fun TorchPill(on: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(999.dp),
        color = if (on) Palette.accent else Color.Black.copy(alpha = 0.35f),
        contentColor = if (on) Palette.onAccent else Color.White,
        border = BorderStroke(1.dp, if (on) Palette.accent else Color.White.copy(alpha = 0.4f)),
    ) {
        Text(
            if (on) "Light on" else "Light",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}


@Composable
private fun PriceLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
}

/** The card-shaped aiming frame over the camera: a faint full outline with accent corner brackets. */
@Composable
private fun CardGuide() {
    Box(
        Modifier.fillMaxHeight(0.58f).aspectRatio(63f / 88f).offset(y = (-48).dp).drawBehind {
            val r = 16.dp.toPx()
            val arm = size.minDimension * 0.16f
            val w = 3.dp.toPx()
            // Faint full outline so the whole card target reads at a glance.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.35f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
            )
            // Accent L-brackets at each corner.
            val a = Palette.accent
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(a, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x + dx, y), w, StrokeCap.Round)
                drawLine(a, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x, y + dy), w, StrokeCap.Round)
            }
            corner(r * 0.4f, r * 0.4f, arm, arm)
            corner(size.width - r * 0.4f, r * 0.4f, -arm, arm)
            corner(r * 0.4f, size.height - r * 0.4f, arm, -arm)
            corner(size.width - r * 0.4f, size.height - r * 0.4f, -arm, -arm)
        },
    )
}

/** A running tally of what this scanning session has added, so bulk scanning feels like progress. */
@Composable
private fun SessionSummary(session: SessionStats) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Palette.accentSoft,
        border = BorderStroke(1.dp, Palette.accent.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                Text(
                    "  ${session.cards} ${if (session.cards == 1) "card" else "cards"} this session",
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.text,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (session.value > 0) Text(session.value.usd(), style = MaterialTheme.typography.labelLarge, color = Palette.accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusText(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.text2)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundCard(
    status: ScanStatus.Found,
    counts: (ScanResult, String) -> Int,
    prices: Map<String, CardPrices>,
    onChoose: (Int) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onSetCount: (ScanResult, String, Int) -> Unit,
) {
    val result = status.results[status.chosen]
    val card = result.card
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    fun count(key: String) = counts(result, key)
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
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.text2)
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
                    color = Palette.text2,
                )
                Text(
                    "Tap to add a copy · hold to remove",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.text2,
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
                        Text("${r.setName} #${r.card.number}" + (price?.let { " · ${it.display()}" } ?: ""))
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
            color = Palette.text2,
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

/**
 * The settings toolbar pinned to the bottom of the scanner: Finish, Set and Language, so what's being
 * scanned can be changed on the fly without a separate setup step. The whole bar is one wide hump that
 * reaches both edges, filled solid with the teal selector colour; the three buttons sit flat on it,
 * divided by hairlines, with the centre (Set) emphasised. Any change applies immediately via [onApply].
 */
@Composable
private fun ScanToolbar(
    setup: ScanSetup,
    recentSets: List<Triple<Language, String, String>>,
    onApply: (ScanSetup) -> Unit,
) {
    val humpDp = 18.dp
    val humpPx = with(LocalDensity.current) { humpDp.toPx() }
    val shape = remember(humpPx) { humpShape(humpPx) }

    val finishOptions = ScanFinish.entries.map { it to it.label }
    val languages = listOf<Language?>(null, Language.ENGLISH, Language.JAPANESE, Language.CHINESE_SIMPLIFIED)
    val languageOptions = languages.map { it to (it?.chipLabel ?: "Any") }
    val set = setup.setId?.let { id -> setup.setName?.let { id to it } }
    val setOptions: List<Pair<Pair<String, String>?, String>> =
        listOf<Pair<Pair<String, String>?, String>>(null to "Any set") +
            recentSets.filter { setup.language == null || it.first == setup.language }.take(12).map { (_, id, name) -> (id to name) to name }

    // The hump is filled completely with the selector teal (accent over the solid surface), edge to edge.
    Box(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Palette.surfaceSolid)
            .background(Palette.accentGlow)
            .border(1.dp, Palette.accent.copy(alpha = 0.6f), shape),
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 6.dp, end = 6.dp, top = humpDp + 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            ToolbarButton("Finish", setup.finish.label, finishOptions, setup.finish, Modifier.weight(1f)) {
                onApply(setup.copy(finish = it))
            }
            ToolbarDivider()
            ToolbarButton("Set", set?.second ?: "Any set", setOptions, set, Modifier.weight(1.25f).offset(y = -8.dp), center = true) { picked ->
                val lang = if (picked != null) recentSets.firstOrNull { it.second == picked.first }?.first ?: setup.language else setup.language
                onApply(setup.copy(setId = picked?.first, setName = picked?.second, language = lang))
            }
            ToolbarDivider()
            ToolbarButton("Language", setup.language?.chipLabel ?: "Any", languageOptions, setup.language, Modifier.weight(1f)) { l ->
                val keepSet = set != null && (l == null || recentSets.any { it.first == l && it.second == set.first })
                onApply(setup.copy(language = l, setId = if (keepSet) setup.setId else null, setName = if (keepSet) setup.setName else null))
            }
        }
    }
}

/** The whole top edge is one wide hump: it reaches both edges and peaks in the centre. */
private fun humpShape(humpPx: Float): androidx.compose.ui.graphics.Shape =
    androidx.compose.foundation.shape.GenericShape { size, _ ->
        val w = size.width
        val h = size.height
        moveTo(0f, humpPx)
        cubicTo(w * 0.25f, 0f, w * 0.75f, 0f, w, humpPx)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }

/** A hairline divider between toolbar buttons (the Row aligns it to the bottom). */
@Composable
private fun ToolbarDivider() {
    Box(Modifier.width(1.dp).height(30.dp).background(Color.White.copy(alpha = 0.18f)))
}

/** A flat tap-to-open setting on the toolbar. [center] (Set) is emphasised. */
@Composable
private fun <T> ToolbarButton(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    center: Boolean = false,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (center) Palette.accent else Palette.text2,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    color = Palette.text,
                    style = if (center) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (center) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = if (center) Palette.accent else Palette.text2, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, t) ->
                DropdownMenuItem(
                    text = { Text(t) },
                    onClick = { open = false; onSelect(v) },
                    trailingIcon = { if (v == selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.accent) },
                )
            }
        }
    }
}

/** Shown after a card was added automatically; scanning continues underneath. */
@Composable
private fun AddedBanner(
    added: ScanStatus.AutoAdded,
    counts: (ScanResult, String) -> Int,
    prices: CardPrices?,
    onUndo: (ScanStatus.AutoAdded) -> Unit,
    onAddAnother: (ScanStatus.AutoAdded) -> Unit,
) {
    val r = added.result
    val variant = r.card.variants.firstOrNull { it.key == added.variantKey }?.label.orEmpty()
    val owned = counts(r, added.variantKey)
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
                prices?.byVariant?.get(added.variantKey)?.let { PriceLine("${it.display()} market price") }
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
        color = Palette.text2,
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
    onCardFrame: (Bitmap) -> Unit = {},
    boxAspect: () -> Float = { 1f },
) {
    val latestOnCardFrame by androidx.compose.runtime.rememberUpdatedState(onCardFrame)
    val latestBoxAspect by androidx.compose.runtime.rememberUpdatedState(boxAspect)
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
                // The card inside the frame, for saving as the picture of a card that has none.
                runCatching { cardInFrame(proxy.toBitmap(), proxy.imageInfo.rotationDegrees, latestBoxAspect()) }
                    .getOrNull()?.let { latestOnCardFrame(it) }
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

private fun recognizePhoto(context: Context, uri: Uri, onBitmap: (Bitmap) -> Unit, onLines: (List<String>) -> Unit, onError: (String) -> Unit) {
    val bitmap = runCatching { loadBitmap(context, uri) }.getOrNull() ?: run {
        onError("Couldn't open that photo.")
        return
    }
    onBitmap(bitmap)
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

/**
 * The part of a camera frame inside the on-screen card frame: the frame upright, cut to what the
 * preview shows (it fills a box of [boxAspect] width/height), then the card guide (86% of that height,
 * card-shaped) from its centre, scaled down to keep memory small.
 */
private fun cardInFrame(frame: Bitmap, rotationDegrees: Int, boxAspect: Float): Bitmap {
    val upright = if (rotationDegrees == 0) frame else {
        val m = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
    }
    val w = upright.width.toFloat()
    val h = upright.height.toFloat()
    val visibleH = if (boxAspect > 0f && w / h > boxAspect) h else w / boxAspect.coerceAtLeast(0.01f)
    val guideH = (visibleH * 0.86f).coerceAtMost(h)
    val guideW = (guideH * 0.716f).coerceAtMost(w)
    val left = ((w - guideW) / 2).toInt().coerceAtLeast(0)
    val top = ((h - guideH) / 2).toInt().coerceAtLeast(0)
    val crop = Bitmap.createBitmap(upright, left, top, guideW.toInt().coerceAtMost(upright.width - left), guideH.toInt().coerceAtMost(upright.height - top))
    val targetH = 720
    return if (crop.height > targetH) Bitmap.createScaledBitmap(crop, (crop.width * targetH.toFloat() / crop.height).toInt(), targetH, true) else crop
}


/** A price in its own currency; TCG Vault's USD prices follow the currency chosen in Settings. */
private fun VariantPrice.display(): String = if (currency == "USD") amount.usd() else formatCurrency(amount, currency)

/**
 * Market price per version for a result: "$1.20" for a single version, "Normal $0.10 · Reverse Holo $0.35"
 * for several. Null while loading or for numbered placeholders (no price data).
 */
internal fun rowPriceText(card: CollectionCard, prices: CardPrices?): String? {
    if (card.placeholder || prices == null) return null
    val priced = card.variants.mapNotNull { v -> prices.byVariant[v.key]?.let { v to it } }
    return when {
        priced.isEmpty() -> "No recent price"
        card.variants.size == 1 -> priced.single().second.display()
        else -> priced.joinToString(" · ") { (v, p) -> "${v.label} ${p.display()}" }
    }
}

/** The server outcome of the last change, under the scan result. */
@Composable
private fun SyncLine(note: SyncNote) {
    val color = when (note.kind) {
        SyncNote.Kind.SAVED -> Palette.up
        SyncNote.Kind.QUEUED -> Palette.accent3
        SyncNote.Kind.PROBLEM -> Palette.down
    }
    Text(note.text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Tap adds a copy; press-and-hold asks to remove every copy of this variant. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VariantButton(label: String, count: Int, onTap: () -> Unit, onLongPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val owned = count > 0
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = if (owned) Palette.accentSoft else Color.Transparent,
        contentColor = if (owned) Palette.accent else Palette.text2,
        border = BorderStroke(1.dp, if (owned) Palette.accent.copy(alpha = 0.5f) else Palette.borderStrong),
        modifier = Modifier
            .clip(shape)
            .combinedClickable(
                onClickLabel = "Add a copy",
                onLongClickLabel = "Remove all copies",
                onLongClick = {
                    if (owned) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                },
                onClick = onTap,
            )
            .semantics { contentDescription = if (owned) "$label, $count owned" else "$label, not owned" },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (owned) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (count > 1) Text("×$count", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
