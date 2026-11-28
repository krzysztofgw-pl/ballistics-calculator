package pl.ballistics.app.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import pl.ballistics.app.data.AltitudeSource
import pl.ballistics.app.data.TerrainHeight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.ballistics.app.R
import pl.ballistics.app.data.Conditions
import pl.ballistics.app.data.ForecastHour
import pl.ballistics.app.data.UiSettings
import pl.ballistics.app.data.WindForecast
import pl.ballistics.app.data.WindHeight
import pl.ballistics.app.data.WindSummary
import pl.ballistics.app.toNumber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private sealed interface ForecastState {
    data object Loading : ForecastState
    data class Failed(val message: String) : ForecastState
    data class Ready(val hours: List<ForecastHour>, val latitude: Double, val elevationM: Double?) : ForecastState
}

private fun one(v: Double) = String.format(Locale.US, "%.1f", v)

/** Hours shown on the chart at once, and how far the arrows move it. */
private const val CHART_HOURS = 12
private const val PAGE_HOURS = 6

/**
 * Fetches the hourly forecast for the phone's location and suggests the mean wind over a window
 * picked on a chart of the coming hours, converted to the line of fire with the current shot azimuth.
 */
@Composable
fun ForecastDialog(
    conditions: Conditions,
    ui: UiSettings,
    onApply: ((Conditions) -> Conditions) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<ForecastState>(ForecastState.Loading) }
    // The window, in hours from the current one: [start, end).
    var start by remember { mutableIntStateOf(0) }
    var end by remember { mutableIntStateOf(2) }
    var page by remember { mutableIntStateOf(0) }
    var height by remember { mutableStateOf(WindHeight.OPEN) }
    var fillAtmosphere by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var unsteadyInfo by remember { mutableStateOf(false) }

    val noLocation = stringResource(R.string.location_unavailable)
    LaunchedEffect(Unit) {
        val location = DeviceSensors.location(context)
        state = if (location == null) {
            ForecastState.Failed(noLocation)
        } else {
            try {
                val terrain = ui.altitudeSource == AltitudeSource.TERRAIN
                // The forecast's own height is a coarse model; the terrain map is finer (1 m in Poland).
                val (forecast, ground) = coroutineScope {
                    val height = async {
                        if (terrain) runCatching { TerrainHeight.lookup(location.latitude, location.longitude).heightM }.getOrNull() else null
                    }
                    WindForecast.fetch(location.latitude, location.longitude) to height.await()
                }
                ForecastState.Ready(forecast.hours, location.latitude, if (terrain) ground ?: forecast.elevationM else null)
            } catch (e: WindForecast.RefusedException) {
                // The phone reached the service, so the connection is fine; say what the service said.
                ForecastState.Failed(
                    if (e.reason != null) context.getString(R.string.forecast_refused_reason, e.code, e.reason)
                    else context.getString(R.string.forecast_refused, e.code),
                )
            } catch (e: java.io.IOException) {
                ForecastState.Failed(context.getString(R.string.forecast_failed, e.message ?: e.javaClass.simpleName))
            } catch (e: Exception) {
                ForecastState.Failed(context.getString(R.string.forecast_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    val azimuth = conditions.azimuthDeg.toNumber() ?: 0.0
    val ready = state as? ForecastState.Ready
    // The coming hours, starting with the one under way now.
    val upcoming = remember(ready) {
        val now = System.currentTimeMillis() / 1000
        ready?.hours?.filter { it.timeSec >= now - now.mod(3600L) }.orEmpty()
    }
    val window = upcoming.drop(start).take(end - start)
    val summary = WindForecast.summarize(window)
    val factor = height.factor
    val maxPage = max(0, upcoming.size - CHART_HOURS)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.title_forecast), Modifier.weight(1f))
                IconButton(onClick = { help = true }) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_help), stringResource(R.string.title_help))
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = state) {
                    ForecastState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.forecast_loading))
                    }
                    is ForecastState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    is ForecastState.Ready -> {
                        if (upcoming.isEmpty()) {
                            Text(stringResource(R.string.forecast_no_hours), color = MaterialTheme.colorScheme.error)
                        } else {
                            WindChart(
                                hours = upcoming,
                                first = page,
                                start = start,
                                end = end,
                                azimuthDeg = azimuth,
                                trueBearing = ui.forecastTrueBearing,
                                onWindow = { a, b -> start = a; end = b },
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(enabled = page > 0, onClick = { page = max(0, page - PAGE_HOURS) }) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.forecast_earlier))
                                }
                                Text(
                                    windowText(upcoming, start, end),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    textAlign = TextAlign.Center,
                                )
                                IconButton(enabled = page < maxPage, onClick = { page = minOf(maxPage, page + PAGE_HOURS) }) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.forecast_later))
                                }
                            }
                        }
                        if (summary != null) {
                            Text(stringResource(R.string.label_wind_height), style = MaterialTheme.typography.labelLarge)
                            HeightButtons(height) { height = it }
                            ResultCard(summary, factor, azimuth, ui.forecastTrueBearing) { unsteadyInfo = true }
                            AtmosphereSwitch(summary, fillAtmosphere) { fillAtmosphere = it }
                        }
                        Text(
                            stringResource(R.string.forecast_source),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = summary != null,
                onClick = {
                    val s = summary ?: return@FilledTonalButton
                    onApply { c ->
                        val az = c.azimuthDeg.toNumber() ?: 0.0
                        var next = c.copy(
                            windSpeed = one(s.speedMps * factor),
                            windMinMps = one(s.minSpeedMps * factor),
                            // Gusts count for the bracket: the strongest wind is the higher of the hourly speeds and gusts.
                            windMaxMps = one(maxOf(s.maxSpeedMps, s.gustMps ?: 0.0) * factor),
                            windFromDeg = (s.fromDeg - az).mod(360.0).roundToInt().mod(360).toString(),
                            windFromMinDeg = (s.fromDeg + s.fromSpreadDeg.start - az).mod(360.0).roundToInt().mod(360).toString(),
                            windFromMaxDeg = (s.fromDeg + s.fromSpreadDeg.endInclusive - az).mod(360.0).roundToInt().mod(360).toString(),
                        )
                        ready?.let { r ->
                            if (ui.locationSetsLatitude) next = next.copy(latitudeDeg = String.format(Locale.US, "%.2f", r.latitude))
                            if (ui.altitudeSource == AltitudeSource.TERRAIN) r.elevationM?.let { next = next.copy(altitudeM = it.roundToInt().toString()) }
                        }
                        if (fillAtmosphere) {
                            s.temperatureC?.let { next = next.copy(temperatureC = one(it)) }
                            s.humidityPct?.let { next = next.copy(humidityPct = it.roundToInt().toString()) }
                            s.pressureMslHpa?.let { next = next.copy(pressureHpa = one(it), pressureIsQnh = true) }
                        }
                        next
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_use)) }
        },
        dismissButton = { FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.title_forecast)) },
            text = { Text(stringResource(R.string.forecast_help), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { FilledTonalButton(onClick = { help = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (unsteadyInfo) {
        AlertDialog(
            onDismissRequest = { unsteadyInfo = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            text = { Text(stringResource(R.string.forecast_unsteady)) },
            confirmButton = { FilledTonalButton(onClick = { unsteadyInfo = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

private val dayHour = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)
private val hourOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

private fun windowText(hours: List<ForecastHour>, start: Int, end: Int): String {
    val zone = ZoneId.systemDefault()
    val from = hours.getOrNull(start) ?: return ""
    val to = Instant.ofEpochSecond((hours.getOrNull(end - 1) ?: from).timeSec + 3600)
    return dayHour.withZone(zone).format(Instant.ofEpochSecond(from.timeSec)) + "–" + hourOnly.withZone(zone).format(to)
}

/**
 * Bars of the mean wind (dark) and gusts (light) for [CHART_HOURS] hours from [first], with an arrow
 * above each showing where the wind blows: relative to the line of fire (12 up), or north up with
 * [trueBearing]. The window [start, end) is shaded; drag its edges, drag it whole, or tap an hour.
 */
@Composable
private fun WindChart(
    hours: List<ForecastHour>,
    first: Int,
    start: Int,
    end: Int,
    azimuthDeg: Double,
    trueBearing: Boolean,
    onWindow: (Int, Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val red = northRed()
    val shown = hours.drop(first).take(CHART_HOURS)
    val top = shown.maxOfOrNull { it.gustMps ?: it.speedMps } ?: 0.0
    val yMax = max(5.0, ceil(top / 5) * 5)
    val hourFormat = DateTimeFormatter.ofPattern("HH", Locale.US).withZone(ZoneId.systemDefault())
    val window by rememberUpdatedState(start to end)
    val set by rememberUpdatedState(onWindow)
    val count = hours.size

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(first) {
                val axis = 26.dp.toPx()
                fun hourAt(x: Float): Int = (first + ((x - axis) / ((size.width - axis) / CHART_HOURS)).toInt()).coerceIn(0, count - 1)
                detectTapGestures { p ->
                    val h = hourAt(p.x)
                    val (a, b) = window
                    val len = b - a
                    val s = h.coerceAtMost(count - len).coerceAtLeast(0)
                    set(s, minOf(count, s + len))
                }
            }
            .pointerInput(first) {
                val axis = 26.dp.toPx()
                val bar = (size.width - axis) / CHART_HOURS
                fun edgeX(h: Int) = axis + (h - first) * bar
                var mode = 0 // 1 = start edge, 2 = end edge, 3 = whole window
                var grab = 0f
                detectHorizontalDragGestures(
                    onDragStart = { p ->
                        val (a, b) = window
                        val near = 20.dp.toPx()
                        mode = when {
                            kotlin.math.abs(p.x - edgeX(a)) < near -> 1
                            kotlin.math.abs(p.x - edgeX(b)) < near -> 2
                            else -> 3
                        }
                        grab = p.x
                        if (mode == 3 && (p.x < edgeX(a) || p.x > edgeX(b))) {
                            // Dragging from outside the window moves it there first.
                            val len = b - a
                            val h = (first + ((p.x - axis) / bar).toInt()).coerceIn(0, count - len)
                            set(h, h + len)
                        }
                    },
                ) { change, _ ->
                    change.consume()
                    val (a, b) = window
                    val x = change.position.x
                    val h = (first + ((x - axis) / bar).roundToInt()).coerceIn(0, count)
                    when (mode) {
                        1 -> if (h < b) set(h, b)
                        2 -> if (h > a) set(a, h)
                        3 -> {
                            val steps = ((x - grab) / bar).toInt()
                            if (steps != 0) {
                                val len = b - a
                                val s = (a + steps).coerceIn(0, count - len)
                                set(s, s + len)
                                grab += steps * bar
                            }
                        }
                    }
                }
            },
    ) {
        val axis = 26.dp.toPx()
        val arrowRow = 22.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val bar = (size.width - axis) / CHART_HOURS
        fun y(v: Double) = (bottom - (bottom - arrowRow - 4.dp.toPx()) * (v / yMax)).toFloat()

        // Grid and speed scale.
        for (v in listOf(0.0, yMax / 2, yMax)) {
            drawLine(colors.outlineVariant, Offset(axis, y(v)), Offset(size.width, y(v)), 1.dp.toPx())
            val t = measurer.measure(one(v).removeSuffix(".0"), axisStyle)
            drawText(t, topLeft = Offset(axis - 6.dp.toPx() - t.size.width, y(v) - t.size.height / 2f))
        }
        // What the arrows are drawn against: north or the line of fire.
        val key = measurer.measure(if (trueBearing) "N↑" else "12↑", axisStyle.copy(color = if (trueBearing) red else colors.onSurfaceVariant, fontWeight = FontWeight.Bold))
        drawText(key, topLeft = Offset(0f, arrowRow / 2 - key.size.height / 2f))

        // The window.
        val a = (start - first).coerceIn(0, CHART_HOURS)
        val b = (end - first).coerceIn(0, CHART_HOURS)
        if (b > a) {
            drawRoundRect(
                colors.primaryContainer, Offset(axis + a * bar, 0f), Size((b - a) * bar, bottom), CornerRadius(6.dp.toPx()),
            )
        }

        shown.forEachIndexed { i, h ->
            val hour = first + i
            val inWindow = hour in start until end
            val x = axis + i * bar + bar * 0.2f
            val w = bar * 0.6f
            h.gustMps?.takeIf { it > h.speedMps }?.let { g ->
                drawRect(
                    (if (inWindow) colors.primary else colors.onSurfaceVariant).copy(alpha = 0.35f),
                    Offset(x, y(g)), Size(w, y(h.speedMps) - y(g)),
                )
            }
            drawRect(if (inWindow) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.7f), Offset(x, y(h.speedMps)), Size(w, bottom - y(h.speedMps)))
            // Arrow from where the wind comes to where it goes.
            val dir = if (trueBearing) h.fromDeg else h.fromDeg - azimuthDeg
            val c = Offset(x + w / 2, arrowRow / 2)
            val u = unit(dir)
            val half = minOf(bar * 0.42f, 9.dp.toPx())
            drawArrowPx(c + u * half, c - u * half, if (inWindow) colors.primary else colors.onSurfaceVariant, 2.dp.toPx(), 6.dp.toPx())
            if (hour % 2 == 0) {
                val t = measurer.measure(hourFormat.format(Instant.ofEpochSecond(h.timeSec)), axisStyle)
                drawText(t, topLeft = Offset(x + w / 2 - t.size.width / 2f, bottom + 3.dp.toPx()))
            }
        }
        // Handles on the window's edges.
        if (b > a) {
            for (e in listOf(a, b)) {
                val x = axis + e * bar
                drawRoundRect(
                    colors.primary, Offset(x - 3.dp.toPx(), bottom / 2 - 16.dp.toPx()), Size(6.dp.toPx(), 32.dp.toPx()),
                    CornerRadius(3.dp.toPx()),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeightButtons(selected: WindHeight, onSelect: (WindHeight) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        WindHeight.entries.forEachIndexed { i, h ->
            SegmentedButton(
                selected = h == selected,
                onClick = { onSelect(h) },
                shape = SegmentedButtonDefaults.itemShape(i, WindHeight.entries.size),
                icon = {},
            ) {
                Text(
                    stringResource(
                        when (h) {
                            WindHeight.FORECAST -> R.string.wind_height_forecast
                            WindHeight.OPEN -> R.string.wind_height_open
                            WindHeight.ROUGH -> R.string.wind_height_rough
                        },
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The suggestion: speed at the shooting height and direction, with a small dial. */
@Composable
private fun ResultCard(s: WindSummary, factor: Double, azimuthDeg: Double, trueBearing: Boolean, onUnsteady: () -> Unit) {
    val relative = (s.fromDeg - azimuthDeg).mod(360.0)
    val clock = clockText(relative, halfHours = true)
    val bearing = "${s.fromDeg.roundToInt().mod(360)}° ${compassPoint(s.fromDeg)}"
    val unsteady = s.steadiness < 0.7
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniWindDial(s.fromDeg, azimuthDeg, trueBearing)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.value_mps, one(s.speedMps * factor)),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.forecast_from, if (trueBearing) "$bearing · $clock" else "$clock · $bearing"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(
                        R.string.forecast_range,
                        one(s.minSpeedMps * factor), one(s.maxSpeedMps * factor),
                        s.gustMps?.let { one(it * factor) } ?: "–",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (unsteady) {
                    Row(Modifier.clickable(onClick = onUnsteady), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Text(
                            " " + stringResource(R.string.forecast_unsteady_short),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A small dial of the suggested wind. As a clock: the line of fire up, a red N where north is.
 * As a true bearing: north up, the line of fire dashed.
 */
@Composable
private fun MiniWindDial(fromDeg: Double, azimuthDeg: Double, trueBearing: Boolean) {
    val colors = MaterialTheme.colorScheme
    val red = northRed()
    val measurer = rememberTextMeasurer()
    val nStyle = MaterialTheme.typography.labelLarge.copy(color = red, fontWeight = FontWeight.ExtraBold)
    Canvas(Modifier.size(84.dp)) {
        val c = center
        val r = size.minDimension / 2 - 1.dp.toPx()
        val fire = if (trueBearing) azimuthDeg else 0.0
        val north = if (trueBearing) 0.0 else -azimuthDeg
        val wind = if (trueBearing) fromDeg else fromDeg - azimuthDeg
        drawCircle(colors.surface, r, c)
        drawCircle(colors.outline, r, c, style = Stroke(1.dp.toPx()))
        for (d in 0 until 360 step 30) {
            val u = unit(d.toDouble())
            drawLine(colors.onSurfaceVariant, c + u * r, c + u * (r - (if (d % 90 == 0) 6.dp else 3.dp).toPx()), 1.2.dp.toPx())
        }
        val f = unit(fire)
        drawLine(
            colors.onSurfaceVariant, c, c + f * (r - 8.dp.toPx()), 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
        // North: a red wedge on the rim and an N inside it.
        val n = unit(north)
        val side = Offset(-n.y, n.x)
        val tip = c + n * (r + 1.dp.toPx())
        val base = c + n * (r - 8.dp.toPx())
        val wedge = androidx.compose.ui.graphics.Path().apply {
            moveTo(tip.x, tip.y)
            (base + side * 5.dp.toPx()).let { lineTo(it.x, it.y) }
            (base - side * 5.dp.toPx()).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(wedge, red)
        label(measurer.measure("N", nStyle), c + n * (r - 18.dp.toPx()))
        val u = unit(wind)
        drawArrowPx(c + u * (r - 3.dp.toPx()), c + u * 5.dp.toPx(), colors.primary, 4.5.dp.toPx(), 12.dp.toPx())
    }
}

@Composable
private fun AtmosphereSwitch(s: WindSummary, checked: Boolean, onChange: (Boolean) -> Unit) {
    val values = listOfNotNull(
        s.temperatureC?.let { "${one(it)} °C" },
        s.humidityPct?.let { "${it.roundToInt()} %" },
        s.pressureMslHpa?.let { "${it.roundToInt()} hPa QNH" },
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.label_forecast_atmosphere))
            if (values.isNotEmpty()) {
                Text(values, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
