package com.cardprice.app.ui.showcase

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/**
 * One card as the showcase draws it: in a gallery grid, and full screen in [ShowcaseViewer].
 * [owned] cards are in full colour (missing ones are dimmed and grey); [shiny] ones get a holo sheen.
 */
data class ShowcaseCard(
    val key: String,
    val name: String,
    /** Under the name, e.g. "Pitch Black · #111". */
    val caption: String,
    val image: String?,
    /** A sharper picture for full screen; falls back to [image]. */
    val imageLarge: String? = null,
    val owned: Boolean = true,
    val shiny: Boolean = false,
    /** Small label on the tile, e.g. "×3" or "Listed". */
    val badge: String? = null,
    /** e.g. "$12.40". */
    val price: String? = null,
)

/** Card proportions (63 × 88 mm). */
const val CARD_ASPECT = 0.716f

/**
 * A row of gallery tiles, for use inside a LazyColumn (so a screen can keep its header, filters and
 * search above the gallery). Pass [columns] cards; a short last row is padded with empty space.
 */
@Composable
fun ShowcaseRow(cards: List<ShowcaseCard>, columns: Int, onOpen: (ShowcaseCard) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.forEach { card -> ShowcaseTile(card, Modifier.weight(1f)) { onOpen(card) } }
        repeat(columns - cards.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** A gallery tile: the card with a soft shadow, a sheen if it's shiny, and its badge and price. */
@Composable
fun ShowcaseTile(card: ShowcaseCard, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT)
                .shadow(if (card.owned) 8.dp else 0.dp, shape, clip = false)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            CardPicture(card.image, card.owned, Modifier.fillMaxSize())
            if (card.owned && card.shiny) Sheen(Modifier.fillMaxSize())
            card.badge?.let {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(topStart = 8.dp),
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    Text(
                        it,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            card.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (card.owned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        card.price?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

/** The card picture; missing cards are shown in grey and faded, like an empty binder pocket. */
@Composable
private fun CardPicture(url: String?, owned: Boolean, modifier: Modifier) {
    if (url == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        colorFilter = if (owned) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
        alpha = if (owned) 1f else 0.4f,
        modifier = modifier,
    )
}

/** A soft band of light sweeping across the card every few seconds. */
@Composable
private fun Sheen(modifier: Modifier) {
    val sweep by rememberInfiniteTransition(label = "sheen").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    Box(
        modifier.drawWithContent {
            val w = size.width
            val x = sweep * w
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent,
                    0.45f to Color.White.copy(alpha = 0.0f),
                    0.5f to Color.White.copy(alpha = 0.35f),
                    0.55f to Color.White.copy(alpha = 0.0f),
                    1f to Color.Transparent,
                    start = Offset(x - w, 0f),
                    end = Offset(x, size.height),
                ),
            )
        },
    )
}

/**
 * Full screen: swipe between [cards], starting at [startIndex]. The card leans with the phone (tilt
 * it and the card turns in 3D, with a glare and a rainbow holo shimmer that follow the light).
 * [details] adds content under the card, e.g. version buttons or item details.
 */
@Composable
fun ShowcaseViewer(
    cards: List<ShowcaseCard>,
    startIndex: Int,
    onDismiss: () -> Unit,
    details: @Composable (ShowcaseCard) -> Unit = {},
) {
    if (cards.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, cards.lastIndex)) { cards.size }
    val tilt = rememberDeviceTilt()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val current = cards[pager.currentPage.coerceIn(0, cards.lastIndex)]
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B12))) {
            // The card itself, huge and blurred, as a coloured backdrop (blur needs Android 12+).
            if (Build.VERSION.SDK_INT >= 31) {
                AsyncImage(
                    model = current.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.45f,
                    modifier = Modifier.fillMaxSize().blur(60.dp),
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.85f)))))

            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Text("${pager.currentPage + 1} / ${cards.size}", color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(end = 16.dp))
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
                    val card = cards[page]
                    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        // As large as fits, keeping the card's shape.
                        val width = minOf(maxWidth, maxHeight * CARD_ASPECT)
                        TiltingCard(card, tilt.x, tilt.y, active = page == pager.currentPage, modifier = Modifier.width(width))
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(current.name, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(current.caption, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    val extras = listOfNotNull(current.price, current.badge, if (!current.owned) "Not in your collection" else null)
                    if (extras.isNotEmpty()) {
                        Text(extras.joinToString("  ·  "), color = Color(0xFFFFD27A), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    details(current)
                }
            }
        }
    }
}

/** The big card: turns with [tiltX]/[tiltY] (−1…1), with a moving glare and, if shiny, a holo shimmer. */
@Composable
private fun TiltingCard(card: ShowcaseCard, tiltX: Float, tiltY: Float, active: Boolean, modifier: Modifier) {
    val springy = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    val rx by animateFloatAsState(if (active) -tiltY * MAX_TILT_DEGREES else 0f, springy, label = "rx")
    val ry by animateFloatAsState(if (active) tiltX * MAX_TILT_DEGREES else 0f, springy, label = "ry")
    val scale by animateFloatAsState(if (active) 1f else 0.9f, label = "scale")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.aspectRatio(CARD_ASPECT)
            .graphicsLayer {
                rotationX = rx
                rotationY = ry
                scaleX = scale
                scaleY = scale
                cameraDistance = 14f * density
            }
            .shadow(24.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color(0xFF1C1C24))
            // Offscreen so the holo layer can blend with the picture under it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        CardPicture(card.imageLarge ?: card.image, card.owned, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val w = size.width
                val h = size.height
                // Where the light hits: moves opposite to the tilt, like a real reflection.
                val light = Offset(w * (0.5f - ry / MAX_TILT_DEGREES * 0.6f), h * (0.5f + rx / MAX_TILT_DEGREES * 0.6f))
                if (card.owned && card.shiny) {
                    val shift = (ry - rx) / MAX_TILT_DEGREES
                    drawRect(
                        Brush.linearGradient(
                            HOLO_COLORS,
                            start = Offset(w * (-0.5f + shift), 0f),
                            end = Offset(w * (1.5f + shift), h),
                        ),
                        blendMode = BlendMode.Overlay,
                        alpha = 0.55f,
                    )
                }
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = if (card.owned) 0.38f else 0.12f), Color.Transparent),
                        center = light,
                        radius = w * 0.75f,
                    ),
                    blendMode = BlendMode.Screen,
                )
            },
        )
    }
}

private const val MAX_TILT_DEGREES = 14f

private val HOLO_COLORS = listOf(
    Color(0x00FFFFFF),
    Color(0xFFFF6EC7),
    Color(0xFFFFE66E),
    Color(0xFF6EFFB0),
    Color(0xFF6EC8FF),
    Color(0xFFB26EFF),
    Color(0x00FFFFFF),
)

/** Phone tilt, each axis −1…1 (0 = held level in front of you). Stays 0 without a gravity sensor. */
class DeviceTilt {
    var x by mutableStateOf(0f)
    var y by mutableStateOf(0f)
}

@Composable
fun rememberDeviceTilt(): DeviceTilt {
    val context = LocalContext.current
    val tilt = remember { DeviceTilt() }
    DisposableEffect(context) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // The resting angle people hold a phone at becomes "level", so the card starts straight.
        var restY: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0] / SensorManager.GRAVITY_EARTH
                val gy = event.values[1] / SensorManager.GRAVITY_EARTH
                val baseY = restY ?: gy.also { restY = it }
                // Smooth out hand tremor.
                tilt.x = tilt.x * 0.8f + (-gx * 2f).coerceIn(-1f, 1f) * 0.2f
                tilt.y = tilt.y * 0.8f + ((gy - baseY) * 2f).coerceIn(-1f, 1f) * 0.2f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sensors?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors?.unregisterListener(listener) }
    }
    return tilt
}
