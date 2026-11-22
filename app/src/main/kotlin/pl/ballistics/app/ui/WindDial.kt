package pl.ballistics.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.roundToInt

/** The red of north on dials: a little lighter on a dark theme. */
@Composable
fun northRed(): Color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFEF5350) else Color(0xFFC62828)

/**
 * The wind and line-of-fire dial: a wind clock inside a compass ring.
 *
 * Shooter view: the line of fire points up, the clock is fixed and the ring turns with the azimuth.
 * North up: the ring is fixed with north up and the clock turns so 12 points along the line of fire.
 * Once unlocked by a tap, a touch inside the clock sets the wind; on the ring it sets the azimuth
 * (shooter view: dragging turns the ring; north up: the touched bearing becomes the azimuth).
 * The round button in the corner switches the view; its needle points north.
 *
 * @param relativeDeg where the wind blows from, degrees clockwise from the line of fire; null if not set.
 */
@Composable
fun WindDial(
    relativeDeg: Double?,
    azimuthDeg: Double,
    northUp: Boolean,
    onWind: (Double) -> Unit,
    onAzimuth: (Double) -> Unit,
    onNorthUp: (Boolean) -> Unit,
    centerText: String,
    centerSubText: String,
    northButtonDescription: String,
    modifier: Modifier = Modifier,
    maxSize: Dp = 360.dp,
) {
    val wind by rememberUpdatedState(onWind)
    val turn by rememberUpdatedState(onAzimuth)
    val azimuth by rememberUpdatedState(azimuthDeg)
    val isNorthUp by rememberUpdatedState(northUp)
    // As on CompassDial: touches count only after a tap unlocks the dial, so a swipe over it scrolls the screen.
    val key = remember { Any() }
    val active = DialFocus.active === key
    var bounds by remember { mutableStateOf(Rect.Zero) }
    DisposableEffect(key) { onDispose { if (DialFocus.active === key) DialFocus.active = null } }

    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val red = northRed()
    val cardinalStyle = type.titleMedium.copy(color = colors.onSurface, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
    val ringStyle = type.labelSmall.copy(color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    val hourStyle = type.titleMedium.copy(color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    val quarterStyle = type.headlineSmall.copy(color = colors.onSurface, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    val centerStyle = type.headlineSmall.copy(color = colors.primary, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
    val subStyle = type.labelMedium.copy(color = colors.onSurfaceVariant, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)

    /** Screen angle of [p], degrees clockwise from up. */
    fun angleAt(p: Offset, size: IntSize): Double =
        Math.toDegrees(atan2((p.x - size.width / 2f).toDouble(), (size.height / 2f - p.y).toDouble())).mod(360.0)

    fun onRing(p: Offset, size: IntSize, ringPx: Float): Boolean =
        (p - Offset(size.width / 2f, size.height / 2f)).getDistance() > minOf(size.width, size.height) / 2f - ringPx

    fun setWind(screen: Double) {
        val rel = if (isNorthUp) screen - azimuth else screen
        wind(((rel / 5).roundToInt() * 5.0).mod(360.0))
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = maxSize).fillMaxWidth().aspectRatio(1f)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { bounds = it.boundsInRoot(); if (active) DialFocus.bounds = bounds }
                    .pointerInput(active) {
                        val ringPx = RING.toPx()
                        detectTapGestures {
                            if (!onFace(it, size)) return@detectTapGestures
                            if (!active) {
                                DialFocus.active = key; DialFocus.bounds = bounds
                            } else if (!onRing(it, size, ringPx)) {
                                setWind(angleAt(it, size))
                            } else if (isNorthUp) {
                                turn(angleAt(it, size).roundToInt().mod(360).toDouble())
                            }
                        }
                    }
                    .then(
                        if (!active) Modifier else Modifier.pointerInput(Unit) {
                            val ringPx = RING.toPx()
                            var onDial = false
                            var ring = false
                            var last = 0.0
                            var az = 0.0
                            detectDragGestures(onDragStart = {
                                onDial = onFace(it, size)
                                ring = onRing(it, size, ringPx)
                                last = angleAt(it, size)
                                az = azimuth
                                if (onDial && !ring) setWind(last)
                                if (onDial && ring && isNorthUp) turn(last.roundToInt().mod(360).toDouble())
                            }) { change, _ ->
                                if (!onDial) return@detectDragGestures
                                change.consume()
                                val now = angleAt(change.position, size)
                                when {
                                    !ring -> setWind(now)
                                    isNorthUp -> turn(now.roundToInt().mod(360).toDouble())
                                    else -> {
                                        // The ring turns with the finger; the azimuth is read at the top, so it moves the other way.
                                        az -= ((now - last + 540.0).mod(360.0) - 180.0)
                                        last = now
                                        turn(az.roundToInt().mod(360).toDouble())
                                    }
                                }
                            }
                        },
                    ),
            ) {
                val c = center
                val r = size.minDimension / 2 - 3.dp.toPx()
                val rb = r - RING.toPx()
                val ringRot = if (northUp) 0.0 else -azimuthDeg
                val faceRot = if (northUp) azimuthDeg else 0.0
                val line = 1.5.dp.toPx()

                // Compass ring, true bearings.
                drawCircle(colors.surfaceVariant, r, c)
                drawCircle(if (active) colors.primary else colors.outline, r, c, style = Stroke(if (active) 3.dp.toPx() else line))
                for (t in 0 until 360 step 10) {
                    val dir = unit(t + ringRot)
                    val major = t % 30 == 0
                    drawLine(colors.onSurfaceVariant, c + dir * r, c + dir * (r - (if (major) 5.dp else 3.dp).toPx()), if (major) line else 1.dp.toPx())
                }
                for (t in 0 until 360 step 30) {
                    val at = c + unit(t + ringRot) * (r - 16.dp.toPx())
                    when (t) {
                        0 -> label(measurer.measure("N", cardinalStyle.copy(color = red)), at)
                        90 -> label(measurer.measure("E", cardinalStyle), at)
                        180 -> label(measurer.measure("S", cardinalStyle), at)
                        270 -> label(measurer.measure("W", cardinalStyle), at)
                        else -> label(measurer.measure(t.toString(), ringStyle), at)
                    }
                }

                // Wind clock, relative to the line of fire.
                drawCircle(colors.surfaceContainerLow, rb, c)
                drawCircle(colors.outline, rb, c, style = Stroke(line))
                for (d in 0 until 360 step 15) {
                    val dir = unit(d + faceRot)
                    val major = d % 30 == 0
                    drawLine(colors.onSurfaceVariant, c + dir * rb, c + dir * (rb - (if (major) 11.dp else 5.dp).toPx()), if (major) 2.dp.toPx() else 1.dp.toPx())
                }
                for (h in 1..12) {
                    val text = measurer.measure(h.toString(), if (h % 3 == 0) quarterStyle else hourStyle)
                    label(text, c + unit(h * 30 + faceRot) * (rb - 27.dp.toPx()))
                }

                // Line of fire: dashed, from the centre towards 12.
                val fire = unit(faceRot)
                val hub = 44.dp.toPx()
                val tip = c + fire * (rb - 40.dp.toPx())
                drawLine(
                    colors.onSurfaceVariant, c + fire * hub, tip - fire * 12.dp.toPx(), 3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())),
                )
                triangle(tip, fire, 14.dp.toPx(), 7.dp.toPx(), colors.onSurfaceVariant)

                relativeDeg?.let { rel ->
                    val dir = unit(rel + faceRot)
                    drawArrowPx(c + dir * (rb - 6.dp.toPx()), c + dir * (hub + 4.dp.toPx()), colors.primary, 9.dp.toPx(), 24.dp.toPx())
                }

                drawCircle(colors.surface, hub, c)
                drawCircle(colors.outline, hub, c, style = Stroke(1.dp.toPx()))
                label(measurer.measure(centerText, centerStyle), c - Offset(0f, 9.dp.toPx()))
                label(measurer.measure(centerSubText, subStyle), c + Offset(0f, 18.dp.toPx()))
            }
            NorthButton(
                rotationDeg = if (northUp) 0.0 else -azimuthDeg,
                northUp = northUp,
                description = northButtonDescription,
                onClick = { onNorthUp(!northUp) },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

private val RING = 30.dp

/** A filled triangle with its point at [tip], pointing along [dir]. */
private fun DrawScope.triangle(tip: Offset, dir: Offset, length: Float, halfWidth: Float, color: Color) {
    val base = tip - dir * length
    val normal = Offset(-dir.y, dir.x)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        (base + normal * halfWidth).let { lineTo(it.x, it.y) }
        (base - normal * halfWidth).let { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, color)
}

/** A round button with a compass needle that points north, as on a map; tinted while north is up. */
@Composable
private fun NorthButton(rotationDeg: Double, northUp: Boolean, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val red = northRed()
    Surface(
        onClick = onClick,
        modifier = modifier.size(44.dp).semantics { contentDescription = description },
        shape = CircleShape,
        color = if (northUp) colors.primaryContainer else colors.surface,
        shadowElevation = 3.dp,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val k = size.minDimension / 44.dp.toPx()
            rotate(rotationDeg.toFloat(), c) {
                val up = Offset(0f, -1f)
                triangle(c + up * 15.dp.toPx() * k, up, 15.dp.toPx() * k, 5.dp.toPx() * k, red)
                triangle(c - up * 15.dp.toPx() * k, -up, 15.dp.toPx() * k, 5.dp.toPx() * k, colors.outline)
                drawCircle(colors.surface, 2.dp.toPx() * k, c)
            }
        }
    }
}
