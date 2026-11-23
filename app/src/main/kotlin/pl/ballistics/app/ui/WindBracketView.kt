package pl.ballistics.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.ballistics.app.data.AngularUnit
import pl.ballistics.app.reticle.Mark
import pl.ballistics.app.reticle.Reticle
import pl.ballistics.app.reticle.Weight
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val Light = Color(0xFF1E88E5)
private val Strong = Color(0xFFE53935)
private val Spread = Color(0xFFF08A24)
private val Ink = Color(0xFF111111)
private val Lens = Color(0xFFCFD8CC)

/** One wind hold on the strip: its place on the reticle, its label and the wind it is for. */
class BracketHold(val x: Double, val hold: String, val wind: String, val strong: Boolean)

/**
 * The wind bracket as a zoomed horizontal slice of [reticle]: the hold for the lightest and the strongest wind
 * marked on the horizontal stem, with the spread between them shaded and summed up in [spreadText] underneath.
 * Hold positions are in reticle units, on the side the reticle shows them, as in the reticle view above.
 */
@Composable
fun WindBracketView(reticle: Reticle, holds: List<BracketHold>, spreadText: String?) {
    val text = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(164.dp)) {
            val top = 44.dp.toPx()
            val stripH = size.height - top
            val pad = 12.dp.toPx()
            val (a, b) = window(reticle, holds.map { it.x })
            val px = (size.width - 2 * pad) / (b - a)
            fun sx(v: Double) = (pad + (v - a) * px).toFloat()
            val cy = top + stripH * 0.45f
            fun sy(v: Double) = (cy - v * px).toFloat()

            drawRoundRect(Lens, Offset(0f, top), Size(size.width, stripH), CornerRadius(14.dp.toPx()))
            clipRect(0f, top, size.width, size.height) {
                if (holds.size == 2) {
                    val l = sx(min(holds[0].x, holds[1].x))
                    val r = sx(max(holds[0].x, holds[1].x))
                    drawRect(Spread.copy(alpha = 0.3f), Offset(l, cy - 28.dp.toPx()), Size(r - l, 56.dp.toPx()))
                }
                reticle.marks.forEach { m ->
                    when (m) {
                        is Mark.Line -> drawLine(
                            Ink, Offset(sx(m.x1), sy(m.y1)), Offset(sx(m.x2), sy(m.y2)),
                            strokeWidth = when (m.weight) {
                                Weight.FINE -> 1.5.dp.toPx()
                                Weight.MEDIUM -> 2.5.dp.toPx()
                                Weight.HEAVY -> 6.dp.toPx()
                            },
                            cap = StrokeCap.Butt,
                        )
                        is Mark.Dot -> drawCircle(Ink, max((m.r * px).toFloat(), 2.dp.toPx()), Offset(sx(m.x), sy(m.y)))
                        is Mark.Label -> centred(text, m.text, Offset(sx(m.x), sy(m.y)), Ink, 11)
                    }
                }
                // Scale numbers along the bottom of the strip, so the zoom is readable on any reticle.
                val step = listOf(0.1, 0.2, 0.25, 0.5, 1.0, 2.0, 2.5, 5.0, 10.0, 20.0, 50.0).first { (b - a) / it <= 6 }
                var v = kotlin.math.ceil(a / step) * step
                while (v <= b + 1e-9) {
                    if (abs(v) > 1e-9) centred(text, number(abs(v)), Offset(sx(v), size.height - 12.dp.toPx()), labelColor.copy(alpha = 0.8f), 11)
                    v += step
                }
                holds.forEach { h ->
                    val c = if (h.strong) Strong else Light
                    val x = sx(h.x)
                    drawLine(c, Offset(x, cy - 28.dp.toPx()), Offset(x, cy + 28.dp.toPx()), 2.5.dp.toPx())
                    drawCircle(c, 5.dp.toPx(), Offset(x, cy))
                }
            }
            // Hold labels above the strip: the left one ends at its mark, the right one starts at it.
            val sorted = holds.sortedBy { it.x }
            sorted.forEachIndexed { i, h ->
                val layout = text.measure(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp)) { append(h.hold) }
                        append("\n")
                        withStyle(SpanStyle(fontSize = 12.sp)) { append(h.wind) }
                    },
                    TextStyle(color = if (h.strong) Strong else Light, textAlign = if (i == 0 && sorted.size > 1) TextAlign.End else TextAlign.Start),
                )
                val x = sx(h.x)
                val w = layout.size.width.toFloat()
                val left = when {
                    sorted.size == 1 -> x - w / 2
                    i == 0 -> x - w - 2.dp.toPx()
                    else -> x + 2.dp.toPx()
                }.coerceIn(0f, max(0f, size.width - w))
                drawText(layout, topLeft = Offset(left, top - layout.size.height - 4.dp.toPx()))
            }
        }
        if (spreadText != null) {
            Box(Modifier.padding(top = 8.dp)) {
                Surface(shape = RoundedCornerShape(50), color = Spread, contentColor = Color.White) {
                    Text(
                        spreadText,
                        Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * The stretch of the horizontal stem to show, in reticle units: both holds with some margin,
 * and the centre too while the holds are near it, so the zoom stays readable.
 */
private fun window(reticle: Reticle, xs: List<Double>): Pair<Double, Double> {
    val mrad = AngularUnit.MRAD.radians / reticle.unit.radians
    val lo = xs.minOrNull() ?: 0.0
    val hi = xs.maxOrNull() ?: 0.0
    val span = hi - lo
    val nearest = when {
        lo > 0 -> lo
        hi < 0 -> -hi
        else -> 0.0
    }
    val withCentre = nearest <= max(2 * span, 1.5 * mrad)
    var a = if (withCentre) min(lo, 0.0) else lo
    var b = if (withCentre) max(hi, 0.0) else hi
    val margin = max((b - a) * 0.2, 0.3 * mrad)
    a -= margin; b += margin
    val minWidth = 1.5 * mrad
    if (b - a < minWidth) {
        val grow = (minWidth - (b - a)) / 2
        a -= grow; b += grow
    }
    return a to b
}

private fun number(v: Double): String {
    val decimals = (0..2).firstOrNull { d -> val k = Math.pow(10.0, d.toDouble()); abs(v * k - Math.rint(v * k)) < 1e-6 } ?: 2
    return "%.${decimals}f".format(v)
}

private fun DrawScope.centred(measurer: TextMeasurer, s: String, center: Offset, color: Color, sizeSp: Int) {
    val layout = measurer.measure(s, TextStyle(fontSize = sizeSp.sp, color = color))
    drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
}
