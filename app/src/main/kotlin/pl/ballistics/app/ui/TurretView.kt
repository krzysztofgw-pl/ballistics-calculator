package pl.ballistics.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A scope turret seen from the side, as a drum turning under a fixed red pointer.
 *
 * [vertical] draws the windage turret on the right of the scope (R up, L down); otherwise the
 * elevation turret on top (U left, D right). [value] is the dialed figure, positive up or right,
 * and [step] one click; every fifth and tenth click is a longer mark.
 */
@androidx.compose.runtime.Composable
fun TurretView(
    vertical: Boolean,
    value: Double,
    step: Double,
    figure: String,
    positiveLetter: String,
    negativeLetter: String,
    description: String,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = rememberTextMeasurer()
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val dragging = Modifier.pointerInput(vertical, step) {
        // The drum follows the finger, in whole clicks: toward U (left) or R (up) turns it that way.
        val spacing = SPACING.toPx()
        var start = 0.0
        var moved = 0f
        val onStart = { _: Offset -> start = current; moved = 0f }
        val onDrag = { d: Float ->
            moved += d
            change(start + (-moved / spacing).roundToInt() * step)
        }
        if (vertical) detectVerticalDragGestures(onDragStart = onStart) { c, d -> c.consume(); onDrag(d) }
        else detectHorizontalDragGestures(onDragStart = onStart) { c, d -> c.consume(); onDrag(d) }
    }
    Canvas(modifier.then(dragging).semantics { contentDescription = description }) {
        val along = if (vertical) size.height else size.width
        val across = if (vertical) size.width else size.height
        val body = Brush.linearGradient(
            listOf(Color(0xFF1E1E1E), Color(0xFF5A5A5A), Color(0xFF1E1E1E)),
            start = Offset.Zero,
            end = if (vertical) Offset(0f, size.height) else Offset(size.width, 0f),
        )
        drawRoundRect(body, cornerRadius = CornerRadius(8.dp.toPx()))
        drawRoundRect(Color.Black, cornerRadius = CornerRadius(8.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))

        // The marks sit on the edge nearest the lens: bottom of the top turret, left of the side one.
        val spacing = SPACING.toPx()
        val mid = along / 2
        val inset = 6.dp.toPx()
        clipRect(
            if (vertical) 0f else inset, if (vertical) inset else 0f,
            if (vertical) size.width else size.width - inset, if (vertical) size.height - inset else size.height,
        ) {
            val clicks = value / step
            val first = floor(clicks - mid / spacing).toInt()
            val last = ceil(clicks + mid / spacing).toInt()
            for (k in first..last) {
                // Turning toward U (left) or R (up) moves the marks that way.
                val pos = mid + ((k - clicks) * spacing).toFloat()
                val major = k % 10 == 0
                val half = k % 5 == 0
                // Short marks on the side turret, so the figure beside them keeps its room.
                val len = (when { major -> 14.dp; half -> 10.dp; else -> 6.dp } * (if (vertical) 0.6f else 1f)).toPx()
                val w = (if (major) 2.dp else 1.2.dp).toPx()
                if (vertical) {
                    drawLine(Color(0xFFDDDDDD), Offset(0f, pos), Offset(len, pos), w)
                } else {
                    drawLine(Color(0xFFDDDDDD), Offset(pos, across), Offset(pos, across - len), w)
                }
            }
        }

        // The dialed figure, large, and which way each end turns.
        val big = text.measure(figure, TextStyle(fontSize = if (vertical) 14.sp else 16.sp, fontWeight = FontWeight.Bold, color = Color.White))
        val small = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEEEEEE))
        val pos = text.measure(positiveLetter, small)
        val neg = text.measure(negativeLetter, small)
        if (vertical) {
            val x = across - 4.dp.toPx()
            drawText(big, topLeft = Offset(x - big.size.width, mid - big.size.height / 2f))
            drawText(pos, topLeft = Offset(x - pos.size.width, 6.dp.toPx()))
            drawText(neg, topLeft = Offset(x - neg.size.width, along - 6.dp.toPx() - neg.size.height))
        } else {
            val y = 2.dp.toPx()
            drawText(big, topLeft = Offset(mid - big.size.width / 2f, y))
            drawText(pos, topLeft = Offset(8.dp.toPx(), y + 2.dp.toPx()))
            drawText(neg, topLeft = Offset(along - 8.dp.toPx() - neg.size.width, y + 2.dp.toPx()))
        }
        pointer(vertical, mid)
    }
}

private val SPACING = 7.dp

/** The fixed red index on the lens side of the drum. */
private fun DrawScope.pointer(vertical: Boolean, mid: Float) {
    val s = 7.dp.toPx()
    val red = Color(0xFFE53935)
    val path = Path().apply {
        if (vertical) {
            moveTo(0f, mid - s); lineTo(s * 1.4f, mid); lineTo(0f, mid + s)
        } else {
            moveTo(mid - s, size.height); lineTo(mid, size.height - s * 1.4f); lineTo(mid + s, size.height)
        }
        close()
    }
    drawPath(path, red)
}
