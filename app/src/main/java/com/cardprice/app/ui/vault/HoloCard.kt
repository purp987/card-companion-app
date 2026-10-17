package com.cardprice.app.ui.vault

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cardprice.app.ui.theme.Palette
import kotlin.math.abs
import kotlin.random.Random

/** Rarer cards get the rainbow foil; commons just get a soft glare. */
fun isFoil(rarity: String?): Boolean =
    rarity != null && Regex("holo|rare|illustration|ultra|secret|hyper|special|rainbow|gold|shiny|amazing|radiant|crown|gallery", RegexOption.IGNORE_CASE).containsMatchIn(rarity)

private val RAINBOW = listOf(
    Color(0xFFFF2BAE), Color(0xFF00E6FF), Color(0xFFB478FF), Color(0xFFFFD23F), Color(0xFF35D49B),
)

/**
 * A card tile that tilts toward the finger with a moving sheen (rarity-gated rainbow foil), and flips to
 * [back] on tap when a back is given. Drag on the card to tilt it; let go and it springs flat.
 */
@Composable
fun HoloCard(
    productId: Long,
    imageUrl: String?,
    isSealed: Boolean,
    rarity: String?,
    modifier: Modifier = Modifier,
    back: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current.density
    var flipped by remember { mutableStateOf(false) }
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    var mx by remember { mutableFloatStateOf(0.5f) }
    var my by remember { mutableFloatStateOf(0.35f) }
    var lit by remember { mutableStateOf(false) }
    val rx by animateFloatAsState(tiltX, label = "rx")
    val ry by animateFloatAsState(tiltY, label = "ry")
    val flip by animateFloatAsState(if (flipped) 180f else 0f, tween(520), label = "flip")

    Box(
        modifier
            .aspectRatio(63f / 88f)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { lit = true },
                    onDragEnd = { tiltX = 0f; tiltY = 0f; lit = false },
                    onDragCancel = { tiltX = 0f; tiltY = 0f; lit = false },
                ) { change, _ ->
                    val px = (change.position.x / size.width).coerceIn(0f, 1f)
                    val py = (change.position.y / size.height).coerceIn(0f, 1f)
                    tiltY = (px - 0.5f) * 2f * 12f
                    tiltX = (0.5f - py) * 2f * 12f
                    mx = px; my = py
                    change.consume()
                }
            }
            .pointerInput(back != null) {
                if (back != null) detectTapGestures(onTap = { flipped = !flipped })
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationX = rx
                    rotationY = ry + flip
                    cameraDistance = 16f * density
                },
        ) {
            if (flip <= 90f) {
                HoloFront(productId, imageUrl, isSealed, rarity, mx, my, lit)
            } else if (back != null) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }
                        .clip(RoundedCornerShape(9.dp)).background(Palette.surfaceSolid).padding(10.dp),
                ) { back() }
            }
        }
    }
}

@Composable
private fun HoloFront(productId: Long, imageUrl: String?, isSealed: Boolean, rarity: String?, mx: Float, my: Float, lit: Boolean) {
    val foil = isFoil(rarity)
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp))
            .background(if (isSealed) Palette.showcase else Palette.cardImageBg),
    ) {
        AsyncImage(
            VaultImages.product(productId, imageUrl, isSealed),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (lit) Canvas(Modifier.fillMaxSize()) {
            val c = Offset(mx * size.width, my * size.height)
            drawRect(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), center = c, radius = size.minDimension * 0.9f),
                blendMode = BlendMode.Screen,
            )
            if (foil) drawRect(
                Brush.linearGradient(RAINBOW.map { it.copy(alpha = 0.33f) }, start = Offset(0f, 0f), end = Offset(size.width, size.height)),
                blendMode = BlendMode.Overlay,
            )
        }
    }
}

/** A short celebratory confetti burst drawn over the whole screen; re-fires whenever [fire] changes (>0). */
@Composable
fun ConfettiOverlay(fire: Int) {
    if (fire <= 0) return
    val colors = listOf(Color(0xFF2FD4B5), Color(0xFF8B80F9), Color(0xFFF2B55E), Color(0xFFFF6B81), Color(0xFF35D49B), Color.White)
    val parts = remember(fire) {
        List(150) {
            Confetto(
                x0 = 0.5f + (Random.nextFloat() - 0.5f) * 0.3f, y0 = 0.3f,
                vx = (Random.nextFloat() - 0.5f) * 0.9f, vy = -Random.nextFloat() * 0.9f - 0.35f,
                size = 5f + Random.nextFloat() * 6f, color = colors[it % colors.size],
                rot = Random.nextFloat() * 6.28f, vr = (Random.nextFloat() - 0.5f) * 0.5f,
            )
        }
    }
    val t = remember(fire) { Animatable(0f) }
    LaunchedEffect(fire) { t.snapTo(0f); t.animateTo(1f, tween(1900, easing = LinearEasing)) }
    if (t.value < 1f) Canvas(Modifier.fillMaxSize()) {
        val k = t.value * 42f // integrate over ~42 frames
        val alpha = (1f - t.value).coerceIn(0f, 1f)
        for (p in parts) {
            val x = (p.x0 + p.vx * k * 0.03f) * size.width
            val y = (p.y0 * size.height) + (p.vy * k * 0.03f) * size.height + 0.5f * 0.0016f * k * k * size.height
            if (y > size.height) continue
            rotate(Math.toDegrees((p.rot + p.vr * k).toDouble()).toFloat(), pivot = Offset(x, y)) {
                drawRect(p.color.copy(alpha = alpha), topLeft = Offset(x - p.size / 2, y - p.size / 2), size = androidx.compose.ui.geometry.Size(p.size, p.size * 0.6f))
            }
        }
    }
}

private data class Confetto(val x0: Float, val y0: Float, val vx: Float, val vy: Float, val size: Float, val color: Color, val rot: Float, val vr: Float)

/** True when |target| differs from what's displayed — used to avoid re-counting on every recomposition. */
internal fun sameMoney(a: Double, b: Double) = abs(a - b) < 0.005
