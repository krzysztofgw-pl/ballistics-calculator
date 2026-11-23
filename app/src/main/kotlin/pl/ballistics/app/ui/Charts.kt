package pl.ballistics.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A marked point on a chart. The first point with a [title] gets a callout: the bold [title] (it may carry its own
 * colours) over a smaller [subtitle].
 */
data class ChartPoint(val x: Double, val y: Double, val color: Color, val title: AnnotatedString? = null, val subtitle: String? = null)

data class ChartSeries(val label: String, val color: Color, val points: List<Pair<Double, Double>>)

/** Distinct line colours that read on both light and dark backgrounds. */
val chartColors = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00897B),
)

/**
 * A plain line chart with a zero line, an optional vertical marker and a legend.
 *
 * @param fillBetween shades the area between the first two series, which share their x values.
 * @param xBands shaded vertical stretches, named [xBandLabel] in the legend.
 * @param yBand a shaded horizontal stretch, always in view.
 * @param points marked points; see [ChartPoint].
 * @param readout the points to show for the x a finger reads (dragged to, or tapped); by default each series'
 *   value there, the first with a callout of [yLabel] over [xLabel]. The marked [points] stay, faint. The reading
 *   stays after the finger lifts; a tap clears it.
 * @param markAllSeries marks every series at [markerX] and while reading, each with a callout of its value.
 * @param onLongPress called with the x being read when a finger then rests still on its callout or line; null when
 *   holding does nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineChart(
    title: String,
    series: List<ChartSeries>,
    xTicks: List<Double>,
    xLabel: (Double) -> String,
    yLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    markerX: Double? = null,
    points: List<ChartPoint> = emptyList(),
    fillBetween: Color? = null,
    xBands: List<ClosedFloatingPointRange<Double>> = emptyList(),
    xBandLabel: String? = null,
    yBand: ClosedFloatingPointRange<Double>? = null,
    readout: ((Double) -> List<ChartPoint>)? = null,
    onLongPress: ((Double) -> Unit)? = null,
    markAllSeries: Boolean = false,
) {
    val text = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val pick by rememberUpdatedState(onLongPress)
    // The x being read, set by a finger and kept after it lifts until a tap clears it or the chart's marker moves;
    // the plot's left edge and width from the last draw turn a finger position into x.
    var reading by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(markerX) { reading = null }
    val plot = remember { FloatArray(2) }
    // The reading's callout (left, top, right, bottom) and line x in pixels from the last draw: holding there accepts it.
    val target = remember { FloatArray(5) { Float.NaN } }
    val ink = MaterialTheme.colorScheme.onSurface
    val plate = MaterialTheme.colorScheme.surface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val shade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val all = series.flatMap { it.points }
    if (all.isEmpty()) return
    val xMin = minOf(all.minOf { it.first }, xTicks.minOrNull() ?: Double.MAX_VALUE)
    val xMax = maxOf(all.maxOf { it.first }, xTicks.maxOrNull() ?: -Double.MAX_VALUE).let { if (it > xMin) it else xMin + 1 }
    val yTicks = niceTicks(
        minOf(0.0, all.minOf { it.second }, yBand?.start ?: 0.0),
        maxOf(0.0, all.maxOf { it.second }, yBand?.endInclusive ?: 0.0),
    )
    val yMin = yTicks.first()
    val yMax = yTicks.last()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        fun xAt(px: Float): Double = xMin + ((px - plot[0]) / plot[1]).coerceIn(0f, 1f) * (xMax - xMin)
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1.6f).pointerInput(xMin, xMax) {
                val slop = viewConfiguration.touchSlop
                val hold = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val shown = reading
                    var moved = false
                    var held = false
                    while (true) {
                        val event = if (!moved && !held) withTimeoutOrNull(hold) { awaitPointerEvent() } else awaitPointerEvent()
                        if (event == null) {
                            // A finger resting on the reading's callout or line accepts it; elsewhere it moves the reading there.
                            held = true
                            val p = pick
                            val grip = 24.dp.toPx()
                            val at = down.position
                            val onIt = (at.x >= target[0] - grip / 2 && at.x <= target[2] + grip / 2 && at.y >= target[1] - grip / 2 && at.y <= target[3] + grip / 2) ||
                                abs(at.x - target[4]) <= grip
                            if (shown != null && p != null && onIt) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                reading = null
                                p(shown)
                            } else {
                                reading = xAt(down.position.x)
                            }
                            continue
                        }
                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) {
                            // A tap clears the reading, or reads where it landed when nothing was read.
                            if (!moved && !held) reading = if (shown != null) null else xAt(down.position.x)
                            break
                        }
                        val d = c.position - down.position
                        if (!moved && (abs(d.x) > slop || abs(d.y) > slop)) {
                            // A mostly vertical move scrolls the screen instead.
                            if (abs(d.y) > abs(d.x)) break
                            moved = true
                        }
                        if (moved) {
                            c.consume()
                            reading = xAt(c.position.x)
                        }
                    }
                }
            },
        ) {
            val left = yTicks.maxOf { text.measure(yLabel(it), labelStyle).size.width } + 6.dp.toPx()
            val bottom = text.measure("0", labelStyle).size.height + 4.dp.toPx()
            val top = 6.dp.toPx()
            val right = 12.dp.toPx()
            val w = size.width - left - right
            val h = size.height - top - bottom
            fun x(v: Double) = left + ((v - xMin) / (xMax - xMin)).toFloat() * w
            fun y(v: Double) = top + ((yMax - v) / (yMax - yMin)).toFloat() * h
            plot[0] = left
            plot[1] = w
            val fingerX = reading

            xBands.forEach { b ->
                val l = x(b.start.coerceIn(xMin, xMax))
                val r = x(b.endInclusive.coerceIn(xMin, xMax))
                if (r > l) drawRect(shade, Offset(l, top), Size(r - l, h))
            }
            yBand?.let { b -> drawRect(shade, Offset(left, y(b.endInclusive)), Size(w, y(b.start) - y(b.endInclusive))) }
            yTicks.forEach { t ->
                drawLine(if (t == 0.0) ink else grid, Offset(left, y(t)), Offset(left + w, y(t)), strokeWidth = if (t == 0.0) 1.5f else 1f)
                val l = text.measure(yLabel(t), labelStyle)
                drawText(l, topLeft = Offset(left - l.size.width - 4.dp.toPx(), y(t) - l.size.height / 2f))
            }
            xTicks.forEach { t ->
                drawLine(grid, Offset(x(t), top), Offset(x(t), top + h), strokeWidth = 1f)
                val l = text.measure(xLabel(t), labelStyle)
                drawText(l, topLeft = Offset(x(t) - l.size.width / 2f, top + h + 2.dp.toPx()))
            }
            if (fillBetween != null && series.size >= 2) {
                val upper = series[1].points
                val lower = series[0].points
                if (upper.isNotEmpty() && lower.isNotEmpty()) {
                    val area = Path()
                    upper.forEachIndexed { i, (px, py) -> if (i == 0) area.moveTo(x(px), y(py)) else area.lineTo(x(px), y(py)) }
                    lower.asReversed().forEach { (px, py) -> area.lineTo(x(px), y(py)) }
                    area.close()
                    drawPath(area, fillBetween.copy(alpha = 0.3f))
                }
            }
            listOfNotNull(markerX, fingerX).filter { it in xMin..xMax }.forEach { m ->
                drawLine(
                    ink.copy(alpha = if (fingerX != null && m != fingerX) 0.35f else 1f), Offset(x(m), top), Offset(x(m), top + h),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
            }
            series.forEach { s ->
                val path = Path()
                s.points.forEachIndexed { i, (px, py) ->
                    if (i == 0) path.moveTo(x(px), y(py)) else path.lineTo(x(px), y(py))
                }
                drawPath(path, s.color, style = Stroke(2.dp.toPx()))
            }
            val read = fingerX?.let { fx ->
                readout?.invoke(fx) ?: series.mapIndexedNotNull { i, s ->
                    s.points.valueAt(fx)?.let { v ->
                        ChartPoint(
                            fx, v, s.color,
                            if (i == 0 || markAllSeries) AnnotatedString(yLabel(v)) else null,
                            if (i == 0 && !markAllSeries) xLabel(fx) else null,
                        )
                    }
                }
            }
            if (read == null) target.fill(Float.NaN) else {
                target.fill(Float.NaN)
                target[4] = x(fingerX!!)
            }
            val marked = if (markAllSeries && markerX != null) series.mapNotNull { s ->
                s.points.valueAt(markerX)?.let { v -> ChartPoint(markerX, v, s.color, AnnotatedString(yLabel(v))) }
            } else emptyList()
            val fixed = (points + marked).filter { it.x in xMin..xMax && it.y.isFinite() }
            val shown = read ?: fixed
            if (read != null) fixed.forEach { p ->
                val c = Offset(x(p.x), y(p.y.coerceIn(yMin, yMax)))
                drawCircle(p.color.copy(alpha = 0.35f), 5.dp.toPx(), c)
            }
            shown.forEach { p ->
                val c = Offset(x(p.x), y(p.y.coerceIn(yMin, yMax)))
                drawCircle(plate, 7.dp.toPx(), c)
                drawCircle(p.color, 5.dp.toPx(), c)
            }
            val titled = shown.filter { it.title != null }
            if (titled.isNotEmpty()) {
                val pad = 6.dp.toPx()
                val gap = 12.dp.toPx()
                val space = 3.dp.toPx()
                class Box(val p: ChartPoint, val t: TextLayoutResult, val st: TextLayoutResult?, val c: Offset) {
                    val w = maxOf(t.size.width, st?.size?.width ?: 0) + 2 * pad
                    val h = t.size.height + (st?.size?.height ?: 0) + pad
                    var top = 0f
                }
                val all = titled.map { p ->
                    Box(
                        p,
                        text.measure(p.title!!, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.color)),
                        p.subtitle?.let { text.measure(it, TextStyle(fontSize = 11.sp, color = ink)) },
                        Offset(x(p.x), y(p.y.coerceIn(yMin, yMax))),
                    )
                }
                // When not all fit beside the line, the biggest values (whichever way) keep their callouts.
                val fit = maxOf(1, ((h + space) / (all.maxOf { it.h } + space)).toInt())
                val boxes = all.sortedByDescending { abs(it.p.y) }.take(fit).sortedBy { it.c.y }
                val bw = boxes.maxOf { it.w }
                val cx = boxes.first().c.x
                // Callouts sit beside the points, toward the roomier side; one sits above its point, several stack by height.
                val bx = (if (cx + gap + bw <= left + w) cx + gap else cx - gap - bw).coerceIn(left, maxOf(left, left + w - bw))
                if (boxes.size == 1) {
                    val b = boxes.first()
                    b.top = (if (b.c.y - gap - b.h >= top) b.c.y - gap - b.h else b.c.y + gap).coerceIn(top, maxOf(top, top + h - b.h))
                } else {
                    var next = top
                    boxes.forEach { b -> b.top = maxOf(b.c.y - b.h / 2, next); next = b.top + b.h + space }
                    val over = next - space - (top + h)
                    if (over > 0) boxes.forEach { it.top = maxOf(top, it.top - over) }
                }
                if (read != null) {
                    target[0] = bx; target[1] = boxes.minOf { it.top }; target[2] = bx + bw; target[3] = boxes.maxOf { it.top + it.h }
                }
                val corner = CornerRadius(7.dp.toPx())
                boxes.forEach { b ->
                    val w0 = if (boxes.size == 1) b.w else bw
                    drawRoundRect(plate, Offset(bx, b.top), Size(w0, b.h), corner)
                    drawRoundRect(b.p.color, Offset(bx, b.top), Size(w0, b.h), corner, style = Stroke(1.dp.toPx()))
                    drawText(b.t, topLeft = Offset(bx + (w0 - b.t.size.width) / 2, b.top + pad / 2))
                    b.st?.let { drawText(it, topLeft = Offset(bx + (w0 - it.size.width) / 2, b.top + pad / 2 + b.t.size.height)) }
                }
            }
        }
        val named = series.filter { it.label.isNotEmpty() }
        if (named.isNotEmpty() || (xBandLabel != null && xBands.isNotEmpty())) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                named.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(10.dp)) { drawCircle(s.color) }
                        Text(s.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (xBandLabel != null && xBands.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(10.dp)) { drawRect(shade.copy(alpha = 0.35f)) }
                        Text(xBandLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** The y value at [x], interpolated between points sorted by x; null outside them. */
fun List<Pair<Double, Double>>.valueAt(x: Double): Double? {
    if (isEmpty() || x < first().first - 1e-9 || x > last().first + 1e-9) return null
    val i = indexOfFirst { it.first >= x }.takeIf { it >= 0 } ?: return last().second
    if (i == 0) return first().second
    val (x0, y0) = this[i - 1]
    val (x1, y1) = this[i]
    return if (x1 == x0) y1 else y0 + (x - x0) / (x1 - x0) * (y1 - y0)
}

/** Round tick values covering [min]..[max], about five of them. */
internal fun niceTicks(min: Double, max: Double): List<Double> {
    val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
    val raw = span / 5
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= raw } * magnitude
    val start = floor(min / step) * step
    val end = maxOf(ceil(max / step) * step, start + step)
    val count = ((end - start) / step).roundToInt()
    return (0..count).map { start + it * step }.map { if (abs(it) < step * 1e-6) 0.0 else it }
}
