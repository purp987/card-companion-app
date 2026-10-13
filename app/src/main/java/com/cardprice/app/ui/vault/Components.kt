package com.cardprice.app.ui.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cardprice.app.data.vault.PricePoint
import com.cardprice.app.data.vault.PriceRange
import com.cardprice.app.data.vault.ProductItem
import com.cardprice.app.data.vault.Vault
import com.cardprice.app.data.vault.VaultSet
import com.cardprice.app.ui.dayLabel
import com.cardprice.app.ui.percent
import com.cardprice.app.ui.theme.Palette
import com.cardprice.app.ui.theme.PriceStyle
import com.cardprice.app.ui.theme.SpaceGrotesk
import com.cardprice.app.ui.usd
import com.cardprice.app.ui.usdShort

// ---------------------------------------------------------------- images

/** Picture URLs as the web UI uses them: sealed products from the server's cut-out cache, cards resized. */
object VaultImages {
    // Compiled once: these run for every tile on screen.
    private val tcgdexSize = Regex("/(low|high)\\.webp$")
    private val tcgplayerSize = Regex("_\\d+w\\.jpg$")
    private val productId = Regex("/product/(\\d+)_")

    fun product(id: Long, url: String?, sealed: Boolean, size: Int = 200): String? {
        if (sealed) Vault.api.absolute("/img/p/$id")?.let { return it }
        return resize(url, size)
    }

    fun resize(url: String?, size: Int): String? = when {
        url == null -> null
        // Server-relative pictures, e.g. Simplified Chinese cards at /img/cn/:id.
        url.startsWith("/") -> Vault.api.absolute(url)
        "assets.tcgdex.net" in url -> url.replace(tcgdexSize, if (size >= 400) "/high.webp" else "/low.webp")
        else -> url.replace(tcgplayerSize, "_${size}w.jpg")
    }

    /** Sealed set art: the set's product photo, cut out. */
    fun setArt(set: VaultSet): String? {
        val id = set.imageUrl?.let { productId.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        return id?.let { Vault.api.absolute("/img/p/$it") } ?: set.imageUrl
    }
}

/** Language badge for a product's category: JP for Japanese, CN for Chinese; nothing for English. */
fun langBadge(categoryId: Long): String? = when (categoryId) {
    85L -> "JP"
    900002L -> "CN"
    else -> Vault.tracked.value.firstOrNull { it.id == categoryId }?.code
}

// ---------------------------------------------------------------- surfaces

/** The aurora backdrop behind every screen. */
@Composable
fun VaultBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            // Brushes are built once per size, not on every frame while lists scroll.
            .drawWithCache {
                val teal = Brush.radialGradient(listOf(Color(0x332FD4B5), Color.Transparent), center = Offset(120f, 160f), radius = 900f)
                val violet = Brush.radialGradient(listOf(Color(0x2B8B80F9), Color.Transparent), center = Offset(size.width * 0.95f, 80f), radius = 850f)
                onDrawBehind {
                    drawRect(Palette.bg)
                    drawRect(teal)
                    drawRect(violet)
                }
            },
        content = content,
    )
}

/** A glassy card (web `.card`). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(Palette.surface)
            .border(1.dp, Palette.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Card heading with an accent icon-less dot, like the web's `.card h3`. */
@Composable
fun CardTitle(text: String, badge: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.text)
        if (badge != null) Badge(badge, Modifier.padding(start = 8.dp))
    }
}

/** Section title with the teal→violet accent bar (web `.home-head`). */
@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(4.dp)).background(Palette.bar))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.text3,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                )
            }
        }
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.accent,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(CircleShape)
                    .background(Palette.accentSoft)
                    .border(1.dp, Color(0x382FD4B5), CircleShape)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = Palette.text2, background: Color = Palette.surface2) {
    Text(
        text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .border(1.dp, Palette.border, CircleShape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** JP / CN badge in violet, as on the web tiles. */
@Composable
fun LangBadge(categoryId: Long, modifier: Modifier = Modifier) {
    val code = langBadge(categoryId) ?: return
    Badge(code, modifier, color = Palette.accent2, background = Color(0x228B80F9))
}

/** "▲ 4.2% 7D" in green/red; nothing for flat. */
@Composable
fun ChangeBadge(pct: Double?, window: String? = null, modifier: Modifier = Modifier, size: Int = 12) {
    if (pct == null || kotlin.math.abs(pct) < 0.0005) return
    val up = pct > 0
    Text(
        buildAnnotatedString {
            append(if (up) "▲ " else "▼ ")
            append(pct.percent())
            if (window != null) withStyle(SpanStyle(fontSize = (size - 2).sp, color = (if (up) Palette.up else Palette.down).copy(alpha = 0.7f))) { append(" $window") }
        },
        color = if (up) Palette.up else Palette.down,
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier,
    )
}

/** The "TCG VAULT" wordmark with the brand gradient. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, small: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "TCG VAULT",
            style = MaterialTheme.typography.headlineSmall.copy(brush = Brush.linearGradient(listOf(Color.White, Color(0xFFD9FBF3), Palette.accent, Palette.accent2))),
            fontSize = if (small) 16.sp else 21.sp,
            letterSpacing = 5.sp,
        )
        if (!small) Text("COLLECT · TRACK · PROFIT", fontSize = 7.5.sp, letterSpacing = 2.5.sp, color = Palette.text2)
    }
}

/** A screen's top bar: translucent, with an optional back arrow and actions. */
@Composable
fun VaultTopBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    center: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(Color(0x9E070A0F)).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp, end = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.text) }
                } else Spacer(Modifier.width(12.dp))
                if (title != null && center == null) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (center != null) Box(Modifier.align(Alignment.Center)) { center() }
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x8C2FD4B5), Color(0x8C8B80F9), Color.Transparent))),
        )
    }
}

/** Accent action (web `.btn.accent`): teal gradient pill. */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.onAccent),
        modifier = modifier,
    ) { Text(text, fontWeight = FontWeight.Black) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.text) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Palette.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        modifier = modifier,
    ) { Text(text, fontWeight = FontWeight.Bold) }
}

// ---------------------------------------------------------------- tiles

/** Product tile (web `.p-card`): picture, name, set with language badge, details, price and change. */
@Composable
fun ProductTile(item: ProductItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(Palette.surface)
            .border(1.dp, Palette.border, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(5f / 7f).clip(RoundedCornerShape(9.dp))
                .background(if (item.isSealed) Palette.showcase else Palette.cardImageBg),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = VaultImages.product(item.id, item.imageUrl, item.isSealed),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(if (item.isSealed) 8.dp else 0.dp),
            )
        }
        Text(
            item.name,
            style = MaterialTheme.typography.labelLarge,
            color = Palette.text,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            LangBadge(item.categoryId, Modifier.padding(end = 4.dp))
            Text(item.setName, style = MaterialTheme.typography.bodySmall, color = Palette.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val meta = if (item.isSealed) "Sealed Product" else listOfNotNull(item.rarity, item.number).joinToString(" • ")
        Text(meta, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = Palette.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.price.usd(), style = PriceStyle, fontSize = 16.sp, color = Palette.text, modifier = Modifier.weight(1f), maxLines = 1)
            item.shownChange?.let { (pct, win) -> ChangeBadge(pct, win, size = 11) }
        }
    }
}

/** Set tile (web `.set-tile`): logo on the dark showcase backdrop, release date, counts. */
@Composable
fun SetTile(set: VaultSet, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick)) {
        SetArtBox(set, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
        Text(set.name, style = MaterialTheme.typography.labelLarge, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val info = listOfNotNull(
            if (set.cardCount > 0) "${set.cardCount} cards" else "${set.sealedCount} products",
            set.topCard?.let { "top ${it.usd()}" },
        ).joinToString(" · ")
        Text(info, style = MaterialTheme.typography.bodySmall, color = Palette.text3, maxLines = 1)
    }
}

@Composable
fun SetArtBox(set: VaultSet, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val urls = remember(set.id) { listOfNotNull(set.logoUrl, VaultImages.setArt(set)) }
    var index by remember(set.id) { mutableIntStateOf(0) }
    Box(modifier.clip(shape).background(Palette.showcase).border(1.dp, Color(0x2996ACCC), shape), contentAlignment = Alignment.Center) {
        val url = urls.getOrNull(index)
        if (url != null) {
            val isLogo = url == set.logoUrl
            AsyncImage(
                model = url,
                contentDescription = set.name,
                contentScale = ContentScale.Fit,
                onError = { index++ },
                modifier = Modifier.fillMaxSize().padding(horizontal = if (isLogo) 18.dp else 8.dp, vertical = if (isLogo) 14.dp else 8.dp),
            )
        } else {
            Text(set.abbreviation ?: set.name.take(4), color = Palette.text2, fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        set.publishedOn?.let {
            Text(
                it.dayLabel(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.text2,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(Color(0x99070A0F)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- states

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxWidth().padding(40.dp)) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent, strokeWidth = 3.dp) }
}

@Composable
fun MessageBox(title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth().padding(16.dp), padding = 24.dp) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (action != null && onAction != null) {
            Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { GhostButton(action, onAction) }
        }
    }
}

// ---------------------------------------------------------------- controls

/** Segmented control (web `.seg`). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surface2.copy(alpha = 0.6f)).border(1.dp, Palette.border, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState()).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val active = o == selected
            Text(
                label(o),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) Palette.text else Palette.text2,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (active) Palette.surface3 else Color.Transparent)
                    .clickable { onSelect(o) }.padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun RangeSelector(selected: PriceRange, onSelect: (PriceRange) -> Unit, modifier: Modifier = Modifier) =
    Segmented(PriceRange.entries, selected, { it.label }, onSelect, modifier)

/** Pill chip (web `.pill-btn`). */
@Composable
fun Pill(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = if (active) Palette.accent else Palette.text2,
        modifier = modifier.clip(CircleShape)
            .background(if (active) Palette.accentSoft else Palette.surface2.copy(alpha = 0.7f))
            .border(1.dp, if (active) Color(0x552FD4B5) else Palette.border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------- charts

/**
 * Line chart with a soft fill, optional dashed comparison line (e.g. Cardmarket), and drag/tap to read a
 * day's value. Values are USD and shown in the chosen currency.
 */
@Composable
fun LineChart(
    series: List<PricePoint>,
    modifier: Modifier = Modifier,
    compare: List<PricePoint>? = null,
    color: Color = Palette.accent,
    compareColor: Color = Palette.accent2,
    height: Dp = 200.dp,
) {
    if (series.size < 2) return
    var selected by remember(series) { mutableStateOf<Int?>(null) }
    val min = (series.map { it.value } + compare.orEmpty().map { it.value }).min()
    val max = (series.map { it.value } + compare.orEmpty().map { it.value }).max()
    val span = (max - min).takeIf { it > 0 } ?: (max * 0.1).coerceAtLeast(1.0)
    val lo = min - span * 0.08
    val hi = max + span * 0.08
    Column(modifier) {
        val shown = selected?.let { series.getOrNull(it) } ?: series.last()
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shown.day.dayLabel(), style = MaterialTheme.typography.bodySmall, color = Palette.text3, modifier = Modifier.weight(1f))
            Text(shown.value.usd(), style = PriceStyle, fontSize = 14.sp, color = Palette.text)
            val cmp = compare?.firstOrNull { it.day == shown.day }
            if (cmp != null) Text("  ${cmp.value.usd()}", style = PriceStyle, fontSize = 14.sp, color = compareColor)
        }
        Row {
            Spacer(
                Modifier.weight(1f).height(height)
                    .pointerInput(series) {
                        detectTapGestures { o -> selected = ((o.x / size.width) * (series.size - 1)).toInt().coerceIn(0, series.lastIndex) }
                    }
                    .pointerInput(series) {
                        detectDragGestures(onDragEnd = { selected = null }) { change, _ ->
                            selected = ((change.position.x / size.width) * (series.size - 1)).toInt().coerceIn(0, series.lastIndex)
                        }
                    }
                    // Paths are built once per size and data; only the selection marker is redrawn on touch.
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        fun x(i: Int) = if (series.size <= 1) 0f else w * i / (series.size - 1)
                        fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
                        val line = Path()
                        series.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(i), y(p.value)) else line.lineTo(x(i), y(p.value)) }
                        val fill = Path().apply {
                            addPath(line)
                            lineTo(w, h)
                            lineTo(0f, h)
                            close()
                        }
                        val fillBrush = Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)))
                        val cmp = if (!compare.isNullOrEmpty() && compare.size > 1) {
                            // Align the comparison by day with the main series.
                            val index = series.withIndex().associate { it.value.day to it.index }
                            var started = false
                            Path().apply {
                                compare.forEach { p ->
                                    val i = index[p.day] ?: return@forEach
                                    if (!started) { moveTo(x(i), y(p.value)); started = true } else lineTo(x(i), y(p.value))
                                }
                            }
                        } else null
                        val lineStroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val dashStroke = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                        onDrawBehind {
                            for (g in 0..3) {
                                val gy = h * g / 3f
                                drawLine(Palette.chartGrid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                            }
                            drawPath(fill, fillBrush)
                            if (cmp != null) drawPath(cmp, compareColor, style = dashStroke)
                            drawPath(line, color, style = lineStroke)
                            selected?.let { i ->
                                val px = x(i)
                                drawLine(Palette.text3, Offset(px, 0f), Offset(px, h), strokeWidth = 1f)
                                drawCircle(color, 5.dp.toPx(), Offset(px, y(series[i].value)))
                                drawCircle(Palette.bg, 2.5.dp.toPx(), Offset(px, y(series[i].value)))
                            }
                        }
                    },
            )
            Column(Modifier.height(height).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(hi.usdShort(), fontSize = 10.sp, color = Palette.text3)
                Text(lo.usdShort(), fontSize = 10.sp, color = Palette.text3)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(series.first().day.dayLabel(false), fontSize = 10.sp, color = Palette.text3, modifier = Modifier.weight(1f))
            Text(series.last().day.dayLabel(false), fontSize = 10.sp, color = Palette.text3)
        }
    }
}

/** Donut for "What's in the Vault". */
@Composable
fun Donut(values: List<Pair<Double, Color>>, modifier: Modifier = Modifier.size(150.dp), center: @Composable () -> Unit = {}) {
    val total = values.sumOf { it.first }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.13f
            val inset = stroke / 2
            if (total <= 0) {
                drawCircle(Palette.surface3, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
                return@Canvas
            }
            var start = -90f
            values.forEach { (v, c) ->
                val sweep = (v / total * 360).toFloat()
                if (sweep > 0) {
                    drawArc(
                        c,
                        start + 1f,
                        (sweep - 2f).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Butt),
                    )
                }
                start += sweep
            }
        }
        center()
    }
}

/** Big price line used on product and portfolio heroes. */
@Composable
fun BigPrice(usdValue: Double?, modifier: Modifier = Modifier, up: Boolean? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (up != null) {
            Text(if (up) "▲" else "▼", color = if (up) Palette.up else Palette.down, fontSize = 15.sp, modifier = Modifier.padding(end = 6.dp))
        }
        Text(usdValue.usd(), style = PriceStyle, fontSize = 32.sp, color = Palette.text, fontFamily = SpaceGrotesk)
    }
}

val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)
