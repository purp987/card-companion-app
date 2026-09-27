package com.cardprice.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cardprice.app.data.market.PricePoint
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val AXIS_GUTTER = 52.dp
private val dayFormat = DateTimeFormatter.ofPattern("MMM d")

/** Both charts give each bucket an equal-width slot and plot it at the slot's centre, so they line up. */
private fun slotWidth(width: Float, gutter: Float, count: Int) = (width - gutter) / count.coerceAtLeast(1)

private fun indexAt(x: Float, width: Float, gutter: Float, count: Int): Int =
    ((x - gutter) / slotWidth(width, gutter, count)).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))

private fun xAt(i: Int, width: Float, gutter: Float, count: Int): Float =
    gutter + (i + 0.5f) * slotWidth(width, gutter, count)

private fun Modifier.selectable(count: Int, gutter: Dp, onSelect: (Int) -> Unit) =
    pointerInput(count) {
        detectTapGestures { onSelect(indexAt(it.x, size.width.toFloat(), gutter.toPx(), count)) }
    }.pointerInput(count) {
        detectHorizontalDragGestures { change, _ ->
            onSelect(indexAt(change.position.x, size.width.toFloat(), gutter.toPx(), count))
        }
    }

/** Line chart of average sale price (solid) and TCGplayer market price (dashed). */
@Composable
fun PriceChart(
    points: List<PricePoint>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val saleColor = MaterialTheme.colorScheme.primary
    val marketColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()

    val values = points.flatMap { listOfNotNull(it.avgSalePrice, it.marketPrice) }
    val rawMin = values.minOrNull() ?: 0.0
    val rawMax = values.maxOrNull() ?: 1.0
    val pad = ((rawMax - rawMin) * 0.1).coerceAtLeast(rawMax * 0.02).coerceAtLeast(0.01)
    val min = (rawMin - pad).coerceAtLeast(0.0)
    val max = rawMax + pad

    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .selectable(points.size, AXIS_GUTTER, onSelect)
    ) {
        val gutter = AXIS_GUTTER.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        fun y(v: Double) = (bottom - (v - min) / (max - min) * (bottom - top)).toFloat()

        drawGrid(3, top, bottom, gutter, gridColor, measurer, labelStyle) { f -> (min + (max - min) * f).moneyShort() }

        fun line(select: (PricePoint) -> Double?, color: Color, dashed: Boolean) {
            val path = Path()
            var started = false
            points.forEachIndexed { i, p ->
                val v = select(p) ?: return@forEachIndexed
                val o = Offset(xAt(i, size.width, gutter, points.size), y(v))
                if (started) path.lineTo(o.x, o.y) else path.moveTo(o.x, o.y).also { started = true }
            }
            drawPath(
                path, color,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null,
                ),
            )
        }
        line({ it.marketPrice }, marketColor, dashed = true)
        line({ it.avgSalePrice }, saleColor, dashed = false)

        // Dots mark the buckets that actually had sales.
        points.forEachIndexed { i, p ->
            p.avgSalePrice?.let { drawCircle(saleColor, 3.dp.toPx(), Offset(xAt(i, size.width, gutter, points.size), y(it))) }
        }

        selected?.takeIf { it in points.indices }?.let { i ->
            val x = xAt(i, size.width, gutter, points.size)
            drawLine(gridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1.5.dp.toPx())
            points[i].marketPrice?.let { drawCircle(marketColor, 5.dp.toPx(), Offset(x, y(it))) }
            points[i].avgSalePrice?.let { drawCircle(saleColor, 6.dp.toPx(), Offset(x, y(it))) }
        }
    }
}

/** Bar chart of units sold per bucket. */
@Composable
fun SoldChart(
    points: List<PricePoint>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.tertiary
    val dimBarColor = barColor.copy(alpha = 0.45f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val maxQty = (points.maxOfOrNull { it.quantitySold } ?: 0).coerceAtLeast(1)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(140.dp)
            .selectable(points.size, AXIS_GUTTER, onSelect)
    ) {
        val gutter = AXIS_GUTTER.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 4.dp.toPx()
        drawGrid(2, top, bottom, gutter, gridColor, measurer, labelStyle) { f -> "${(maxQty * f).roundToInt()}" }

        val barWidth = (slotWidth(size.width, gutter, points.size) * 0.7f).coerceAtLeast(2f)
        points.forEachIndexed { i, p ->
            if (p.quantitySold == 0) return@forEachIndexed
            val h = p.quantitySold.toFloat() / maxQty * (bottom - top)
            val cx = xAt(i, size.width, gutter, points.size)
            drawRoundRect(
                color = if (selected == null || selected == i) barColor else dimBarColor,
                topLeft = Offset(cx - barWidth / 2, bottom - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/** First, middle and last dates, aligned under the chart area. */
@Composable
fun DateAxis(points: List<PricePoint>) {
    if (points.isEmpty()) return
    val style = MaterialTheme.typography.labelSmall
    Row(Modifier.fillMaxWidth().padding(start = AXIS_GUTTER)) {
        Text(points.first().date.format(dayFormat), style = style, modifier = Modifier.weight(1f))
        if (points.size > 2) {
            Text(points[points.size / 2].date.format(dayFormat), style = style, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
        Text(points.last().date.format(dayFormat), style = style, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** Horizontal gridlines with value labels in the left gutter. [label] receives 0..1 from bottom to top. */
private fun DrawScope.drawGrid(
    lines: Int,
    top: Float,
    bottom: Float,
    gutter: Float,
    color: Color,
    measurer: TextMeasurer,
    style: TextStyle,
    label: (Float) -> String,
) {
    for (i in 0..lines) {
        val f = i.toFloat() / lines
        val y = bottom - f * (bottom - top)
        drawLine(color, Offset(gutter, y), Offset(size.width, y), strokeWidth = 1f)
        val text = measurer.measure(label(f), style)
        drawText(text, topLeft = Offset(gutter - text.size.width - 6.dp.toPx(), y - text.size.height / 2))
    }
}
