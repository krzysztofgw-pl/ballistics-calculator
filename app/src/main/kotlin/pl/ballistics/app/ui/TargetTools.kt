package pl.ballistics.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import pl.ballistics.app.CalcResult
import pl.ballistics.app.R
import pl.ballistics.app.TargetAnalysis
import pl.ballistics.app.TargetInputs
import pl.ballistics.app.data.AmmoProfile
import pl.ballistics.app.data.AngularUnit
import pl.ballistics.app.data.ChartKind
import pl.ballistics.app.data.Conditions
import pl.ballistics.app.rangeFromSize
import pl.ballistics.app.reticle.Reticle
import pl.ballistics.app.toNumber
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which [Collapsible] cards are open, by title, so work for a closed card's content can be skipped. */
object OpenCards {
    private val open = mutableStateMapOf<String, Boolean>()
    fun isOpen(title: String): Boolean = open[title] == true
    internal fun set(title: String, isOpen: Boolean) { open[title] = isOpen }
}

/** A card whose content opens and closes with its title; it remembers its state across screens. */
@Composable
fun Collapsible(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    LaunchedEffect(title, open) { OpenCards.set(title, open) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

/** The stretch clockwise from [from] to [to] degrees, split in two where it passes north. */
private fun arc(from: Double, to: Double): List<ClosedFloatingPointRange<Double>> =
    if (from <= to) listOf(from..to) else listOf(from..360.0, 0.0..to)

private fun inArc(deg: Double, from: Double, to: Double): Boolean {
    val d = deg.mod(360.0)
    return if (from <= to) d in from..to else d >= from || d <= to
}

private fun Double.f(decimals: Int) = "%.${decimals}f".format(this)
private fun cm(m: Double) = (m * 100).roundToInt().toString()

/** Wind bracket, range error, temperature, hit probability, ranging with the reticle and BC truing, at the chosen distance. */
@Composable
fun TargetTools(
    result: CalcResult.Ok,
    conditions: Conditions,
    update: ((Conditions) -> Conditions) -> Unit,
    analysis: TargetAnalysis?,
    inputs: TargetInputs,
    reticle: Reticle,
    reticleScale: Double,
    charts: Set<ChartKind>,
    onUseDistance: (Double) -> Unit,
    onSaveAmmo: (AmmoProfile) -> Unit,
    onAddAmmoCopy: (from: AmmoProfile, copy: AmmoProfile) -> Unit,
) {
    val unit = result.angularUnit
    val digits = if (unit == AngularUnit.MOA) 1 else 2
    val right = stringResource(R.string.right_short)
    val left = stringResource(R.string.left_short)
    fun side(rad: Double): String {
        val v = rad / unit.radians
        return when {
            abs(v) < 0.5 * Math.pow(10.0, -digits.toDouble()) -> "0"
            v > 0 -> right + abs(v).f(digits)
            else -> left + abs(v).f(digits)
        }
    }

    Collapsible(stringResource(R.string.section_wind_bracket)) {
        FieldRow { m ->
            NumberField(stringResource(R.string.label_wind_min), conditions.windMinMps, "m/s", m, optional = true) { v ->
                update { it.copy(windMinMps = v) }
            }
            NumberField(stringResource(R.string.label_wind_max), conditions.windMaxMps, "m/s", m, optional = true) { v ->
                update { it.copy(windMaxMps = v) }
            }
        }
        analysis?.bracket?.let { b ->
            val spread = abs(b.maxRad - b.minRad)
            // The reticle shows a hold on the side the bullet drifts to, opposite the correction.
            fun x(rad: Double) = -rad / reticle.unit.radians * reticleScale
            val holds = buildList {
                add(BracketHold(x(b.minRad), side(b.minRad), "${b.minMps.f(1)} m/s", strong = false))
                if (abs(b.maxRad - b.minRad) > 1e-9) add(BracketHold(x(b.maxRad), side(b.maxRad), "${b.maxMps.f(1)} m/s", strong = true))
            }.filter { it.x.isFinite() }
            val spreadText = stringResource(R.string.wind_bracket_spread, (spread / unit.radians).f(digits), unit.label, cm(spread * analysis.point.rangeM))
            if (holds.isNotEmpty()) WindBracketView(reticle, holds, if (holds.size == 2) spreadText else null)
            if (ChartKind.BRACKET_DIRECTION in charts) analysis.bracketSweep?.let { sw ->
                val lo = conditions.windFromMinDeg.toNumber()?.mod(360.0)
                val hi = conditions.windFromMaxDeg.toNumber()?.mod(360.0)
                // The forecast's swing of direction, clockwise from [lo] to [hi]; dropped once the direction is set outside it by hand.
                val bands = if (lo != null && hi != null && inArc(b.fromDeg, lo, hi)) arc(lo, hi) else emptyList()
                LineChart(
                    title = stringResource(R.string.chart_bracket_direction, unit.label, analysis.point.rangeM.roundToInt()),
                    series = listOf(
                        ChartSeries("${b.minMps.f(1)} m/s", BracketLight, sw.light.map { (d, c) -> d to c / unit.radians }),
                        ChartSeries("${b.maxMps.f(1)} m/s", BracketStrong, sw.strong.map { (d, c) -> d to c / unit.radians }),
                    ),
                    xTicks = (0..4).map { it * 90.0 },
                    xLabel = { clockText(it) },
                    yLabel = { v -> side(v * unit.radians) },
                    markerX = b.fromDeg,
                    points = listOf(
                        ChartPoint(b.fromDeg, b.minRad / unit.radians, BracketLight),
                        ChartPoint(
                            b.fromDeg, b.maxRad / unit.radians, BracketStrong,
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = BracketLight)) { append(side(b.minRad)) }
                                append(" – ")
                                withStyle(SpanStyle(color = BracketStrong)) { append(side(b.maxRad)) }
                            },
                            "${clockText(b.fromDeg)} · $spreadText",
                        ),
                    ),
                    fillBetween = BracketSpread,
                    readout = { x ->
                        val l = sw.light.valueAt(x)
                        val st = sw.strong.valueAt(x)
                        if (l == null || st == null) emptyList()
                        else listOf(
                            ChartPoint(x, l / unit.radians, BracketLight),
                            ChartPoint(
                                x, st / unit.radians, BracketStrong,
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = BracketLight)) { append(side(l)) }
                                    append(" – ")
                                    withStyle(SpanStyle(color = BracketStrong)) { append(side(st)) }
                                },
                                "${clockText(x)} · ↔ ${(abs(st - l) / unit.radians).f(digits)} ${unit.label}",
                            ),
                        )
                    },
                    xBands = bands,
                    xBandLabel = if (lo != null && hi != null) stringResource(R.string.chart_forecast_direction, clockText(lo), clockText(hi)) else null,
                )
            }
        }
    }

    Collapsible(stringResource(R.string.section_range_error)) {
        analysis?.tolerance?.let { t ->
            t.misses.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.range_error_row, (if (m.errorM > 0) "+" else "−") + abs(m.errorM).roundToInt()),
                        Modifier.width(96.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (m.missM >= 0) "↑" else "↓",
                        Modifier.width(32.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("${cm(abs(m.missM))} cm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            if (ChartKind.RANGE_ERROR in charts && analysis.rangeCurve.size > 1) {
                val half = inputs.heightM * 50
                LineChart(
                    title = stringResource(R.string.chart_range_error),
                    series = listOf(ChartSeries("", chartColors[1], analysis.rangeCurve.map { it.errorM to it.missM * 100 })),
                    xTicks = listOf(-50.0, -25.0, 0.0, 25.0, 50.0).filter { e -> analysis.rangeCurve.any { abs(it.errorM - e) < 1e-6 } },
                    xLabel = { e -> if (e > 0) "+" + e.roundToInt() else if (e < 0) "−" + abs(e).roundToInt() else "0" },
                    yLabel = { v -> if (abs(v) < 1e-9) "0" else (if (v > 0) "↑" else "↓") + abs(v).roundToInt() },
                    markerX = 0.0,
                    points = listOfNotNull(
                        t.shortM?.let { ChartPoint(it, half, chartColors[1]) },
                        t.longM?.let { ChartPoint(it, -half, chartColors[1]) },
                    ),
                    yBand = -half..half,
                    readout = { x ->
                        val curve = analysis.rangeCurve.map { it.errorM to it.missM * 100 }
                        listOfNotNull(curve.valueAt(x)?.let { v ->
                            val signed = if (x > 0.5) "+" + x.roundToInt() else if (x < -0.5) "−" + abs(x).roundToInt() else "0"
                            ChartPoint(x, v, chartColors[1], AnnotatedString((if (v >= 0) "↑" else "↓") + "${abs(v).roundToInt()} cm"), "$signed m")
                        })
                    },
                )
            }
            Text(
                stringResource(
                    R.string.range_error_window,
                    t.shortM?.let { "−" + abs(it).roundToInt() } ?: "−50+",
                    t.longM?.let { "+" + it.roundToInt() } ?: "+50+",
                    cm(inputs.heightM),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }

    Collapsible(stringResource(R.string.section_temperature)) {
        analysis?.temperatures?.forEach { row ->
            Text(
                stringResource(
                    R.string.temperature_row, row.temperatureC.roundToInt(), row.muzzleVelocityMps.roundToInt(),
                    (-row.point.dropRad / unit.radians).f(digits), unit.label,
                ),
                style = if (row.deltaC == 0.0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Collapsible(stringResource(R.string.section_hit_probability)) {
        FieldRow { m ->
            NumberField(stringResource(R.string.label_target_width), conditions.targetWidthCm, "cm", m) { v ->
                update { it.copy(targetWidthCm = v) }
            }
            NumberField(stringResource(R.string.label_target_height), conditions.targetHeightCm, "cm", m) { v ->
                update { it.copy(targetHeightCm = v) }
            }
        }
        FieldRow { m ->
            NumberField(stringResource(R.string.label_wind_uncertainty), conditions.windUncertaintyMps, "m/s", m) { v ->
                update { it.copy(windUncertaintyMps = v) }
            }
            NumberField(stringResource(R.string.label_range_uncertainty), conditions.rangeUncertaintyM, "m", m) { v ->
                update { it.copy(rangeUncertaintyM = v) }
            }
        }
        val assumed = stringResource(R.string.assumed)
        Text(
            stringResource(
                R.string.hit_inputs,
                inputs.velocitySdMps.f(1) + if (result.ammo.velocitySd.toNumber() == null) " $assumed" else "",
                (inputs.groupRad / AngularUnit.MOA.radians).f(2) + if (result.weapon.groupMoa.toNumber() == null) " $assumed" else "",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        analysis?.hit?.let { h ->
            Text(
                stringResource(R.string.hit_result, (h.probability * 100).roundToInt()),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    R.string.hit_breakdown,
                    cm(h.verticalSdM), cm(h.fromVelocityM), cm(h.fromRangeM), cm(h.fromRifleM),
                    cm(h.horizontalSdM), cm(h.fromWindM), cm(h.fromRifleM),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (ChartKind.HIT_RANGE in charts && analysis.hitByRange.size > 1) {
                val to = analysis.hitByRange.last().first
                LineChart(
                    title = stringResource(R.string.chart_hit_range),
                    series = listOf(ChartSeries("", chartColors[2], listOf(0.0 to 100.0) + analysis.hitByRange.map { (r, p) -> r to p * 100 })),
                    xTicks = niceTicks(0.0, to).filter { it <= to + 1e-6 },
                    xLabel = { it.roundToInt().toString() },
                    yLabel = { "${it.roundToInt()} %" },
                    markerX = analysis.point.rangeM,
                    points = listOf(
                        ChartPoint(
                            analysis.point.rangeM, h.probability * 100, chartColors[2],
                            AnnotatedString("${(h.probability * 100).roundToInt()} %"), "${analysis.point.rangeM.roundToInt()} m",
                        ),
                    ),
                    readout = { x ->
                        val curve = listOf(0.0 to 100.0) + analysis.hitByRange.map { (r, p) -> r to p * 100 }
                        listOfNotNull(curve.valueAt(x)?.let { v ->
                            ChartPoint(x, v, chartColors[2], AnnotatedString("${v.roundToInt()} %"), "${x.roundToInt()} m")
                        })
                    },
                    onLongPress = onUseDistance,
                )
            }
        }
    }

    Collapsible(stringResource(R.string.section_ranging)) {
        var size by remember { mutableStateOf("") }
        var reading by remember { mutableStateOf("") }
        val sizeCm = size.toNumber() ?: (inputs.heightM * 100)
        FieldRow { m ->
            NumberField(stringResource(R.string.label_ranging_size), size, "cm", m, placeholder = cm(inputs.heightM)) { size = it }
            NumberField(stringResource(R.string.label_ranging_reading), reading, reticle.unit.label, m) { reading = it }
        }
        rangeFromSize(sizeCm / 100, (reading.toNumber() ?: 0.0) * reticle.unit.radians)?.let { d ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ranging_result, d.roundToInt()), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = { onUseDistance(d) }) { Text(stringResource(R.string.action_use_distance)) }
            }
        }
    }

    BcTruingCard(result, analysis?.point?.rangeM, onSaveAmmo, onAddAmmoCopy)
}
