package com.cardprice.app.ui.collection

import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.cardprice.app.data.collection.SetProgress
import com.cardprice.app.ui.SetArt
import com.cardprice.app.ui.display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Colors picked from set artwork, by the art's first URL (kept for the app's lifetime). */
private val artColors = ConcurrentHashMap<String, Int>()

/**
 * The most vivid color in a set's artwork (its logo, usually), for tinting its card: picked once with
 * AndroidX Palette from a small copy of the image, then remembered. Null until it's ready, or if no
 * picture loads.
 */
@Composable
fun rememberArtColor(urls: List<String>): Color? {
    val context = LocalContext.current
    val key = urls.firstOrNull() ?: return null
    var color by remember(key) { mutableStateOf(artColors[key]?.let(::Color)) }
    LaunchedEffect(key) {
        if (color != null) return@LaunchedEffect
        for (url in urls.take(3)) {
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(160).build()
            val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: continue
            val rgb = withContext(Dispatchers.Default) {
                val p = Palette.from(bitmap).generate()
                p.vibrantSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: p.darkVibrantSwatch?.rgb ?: p.dominantSwatch?.rgb
            } ?: continue
            artColors[key] = rgb
            color = Color(rgb)
            break
        }
    }
    return color
}

/** Animates from 0 to [target] the first time it's shown, [delayMs] after appearing (for a staggered fill). */
@Composable
private fun animatedFill(target: Float, delayMs: Long = 0): Float {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        shown = true
    }
    val value by animateFloatAsState(if (shown) target else 0f, tween(900, easing = FastOutSlowInEasing), label = "fill")
    return value
}

/** A ring that fills to [fraction] (animated), with [content] in the middle. */
@Composable
fun ProgressRing(fraction: Float, color: Color, size: Dp, stroke: Dp, track: Color, delayMs: Long = 0, content: @Composable BoxScope.() -> Unit) {
    val shown = animatedFill(fraction.coerceIn(0f, 1f), delayMs)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
            val inset = stroke.toPx() / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = s)
            drawArc(color, -90f, 360f * shown, false, topLeft = Offset(inset, inset), size = arcSize, style = s)
        }
        content()
    }
}

/** Shrinks a little while pressed, springing back: makes tiles feel like things you can pick up. */
@Composable
private fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A gold band of light sweeping across, for completed sets. */
@Composable
private fun Modifier.shine(): Modifier {
    val sweep by rememberInfiniteTransition(label = "shine").animateFloat(
        -1f, 2f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    return drawWithContent {
        drawContent()
        val x = sweep * size.width
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent, 0.45f to Color.Transparent, 0.5f to Color(0x66FFE8A0), 0.55f to Color.Transparent, 1f to Color.Transparent,
                start = Offset(x - size.width, 0f), end = Offset(x, size.height),
            ),
        )
    }
}

private val Gold = Color(0xFFFFC94A)

/**
 * The big card for a set you're collecting: its artwork blurred into a colored backdrop, the logo on
 * top, and your progress (ring, counts) and value over a dark scrim.
 */
@Composable
fun SetHeroCard(
    art: List<String>,
    title: String,
    subtitle: String,
    progress: SetProgress,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    onClick: () -> Unit,
) {
    val tint = rememberArtColor(art) ?: MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(24.dp)
    val complete = progress.totalCards > 0 && progress.ownedCards >= progress.totalCards
    Box(
        modifier.fillMaxWidth().height(height)
            .pressScale(interaction)
            .shadow(12.dp, shape, spotColor = tint)
            .clip(shape)
            .background(Brush.linearGradient(listOf(tint, lerp(tint, Color.Black, 0.6f))))
            .clickable(interaction, indication = null, onClick = onClick)
            .then(if (complete) Modifier.shine() else Modifier),
    ) {
        // The artwork itself, huge and blurred, as a glow of the set's colors (blur needs Android 12+).
        if (Build.VERSION.SDK_INT >= 31) {
            AsyncImage(
                model = art.firstOrNull(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.55f,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }.blur(24.dp),
            )
        }
        SetArt(
            urls = art,
            label = title,
            color = Color.Transparent,
            modifier = Modifier.padding(20.dp).height(if (height >= 180.dp) 72.dp else 52.dp).width(if (height >= 180.dp) 150.dp else 110.dp).align(Alignment.TopStart),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.85f)),
            ),
        )
        val ringSize = if (height >= 180.dp) 64.dp else 48.dp
        ProgressRing(
            fraction = progress.cardFraction,
            color = if (complete) Gold else Color.White,
            size = ringSize,
            stroke = if (height >= 180.dp) 6.dp else 5.dp,
            track = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        ) {
            Text(
                if (complete) "✓" else "${(progress.cardFraction * 100).toInt()}%",
                color = Color.White,
                style = if (height >= 180.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "${progress.ownedCards}/${progress.totalCards} cards · ${progress.ownedVariants}/${progress.totalVariants} variants",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                progress.value.display()?.let {
                    Surface(color = Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
                        Text(it, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressRing(
    fraction: Float, color: Color, size: Dp, stroke: Dp, track: Color, modifier: Modifier, content: @Composable BoxScope.() -> Unit,
) = Box(modifier) { ProgressRing(fraction, color, size, stroke, track, content = content) }

/**
 * A set in the catalog grid: its logo on a soft glow of its own color, name and size, and for sets
 * you've started, a progress bar (filling in on first sight) with your percentage and value.
 * Unstarted sets are slightly faded so the ones you collect stand out.
 */
@Composable
fun SetTile(
    art: List<String>,
    setId: String,
    name: String,
    cardCount: Int,
    progress: SetProgress?,
    index: Int,
    /** The set's own name when [name] is an English translation (Japanese and Chinese sets). */
    localName: String? = null,
    onClick: () -> Unit,
) {
    val tint = rememberArtColor(art) ?: MaterialTheme.colorScheme.primary
    val started = progress != null && progress.ownedCards > 0
    val complete = started && progress!!.totalCards > 0 && progress.ownedCards >= progress.totalCards
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth()
            .pressScale(interaction)
            .graphicsLayer { alpha = if (started) 1f else 0.85f }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(interaction, indication = null, onClick = onClick)
            .then(if (complete) Modifier.shine() else Modifier),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f)
                .background(Brush.radialGradient(listOf(tint.copy(alpha = if (started) 0.45f else 0.22f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            SetArt(urls = art, label = setId, color = tint, modifier = Modifier.fillMaxSize().padding(14.dp))
            if (complete) {
                Surface(color = Gold, shape = RoundedCornerShape(bottomStart = 12.dp), modifier = Modifier.align(Alignment.TopEnd)) {
                    Text("Complete", color = Color(0xFF3A2A00), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(setId, localName ?: cardCount.takeIf { it > 0 }?.let { "$it cards" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (started) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("${(progress!!.cardFraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (complete) Gold else tint)
                    Spacer(Modifier.weight(1f))
                    progress.value.display()?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                }
                val fill = animatedFill(progress!!.cardFraction, delayMs = 40L * index.coerceAtMost(12))
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(tint.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(fill).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (complete) Gold else tint))
                }
            }
        }
    }
}

/** Totals over every set being collected, at the top of the Collection tab. */
@Composable
fun CollectionSummary(value: String?, sets: Int, cards: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text("Collection value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "$sets ${if (sets == 1) "set" else "sets"} · $cards ${if (cards == 1) "card" else "cards"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A light shimmer while artwork loads (also usable as a placeholder block). */
@Composable
fun Modifier.loadingShimmer(): Modifier {
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "x",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    return background(
        Brush.linearGradient(
            listOf(base, lerp(base, Color.White, 0.08f), base),
            start = Offset(x * 1000f - 500f, 0f),
            end = Offset(x * 1000f, 0f),
        ),
    )
}
