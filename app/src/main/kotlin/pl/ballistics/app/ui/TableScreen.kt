package pl.ballistics.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.ballistics.app.CalcResult
import pl.ballistics.app.R
import pl.ballistics.app.asString
import pl.ballistics.app.data.Conditions
import pl.ballistics.core.TrajectoryPoint
import kotlin.math.abs
import kotlin.math.roundToInt

// Widths fit the widest entry plus the cell padding, so both clicks columns fit on a phone without scrolling.
private val columns = listOf(
    R.string.col_range to 42, R.string.col_drop to 66, R.string.col_elevation to 54, R.string.col_clicks to 48,
    R.string.col_windage to 64, R.string.col_wind_correction to 46, R.string.col_clicks to 48,
    R.string.col_velocity to 40, R.string.col_energy to 46, R.string.col_time to 46, R.string.col_mach to 46,
)

@Composable
fun TableScreen(
    result: CalcResult?,
    conditions: Conditions? = null,
    update: ((Conditions) -> Conditions) -> Unit = {},
    modifier: Modifier = Modifier,
    details: Boolean = true,
    onToggleDetails: () -> Unit = {},
) {
    when (result) {
        null -> Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.padding(32.dp))
        }
        is CalcResult.Error -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(result.message.asString(), color = MaterialTheme.colorScheme.error)
            // A bad range or step is fixed here, so the fields stay reachable.
            conditions?.let { TableExtent(it, update) }
        }
        is CalcResult.Ok -> Table(result, conditions, update, modifier, details, onToggleDetails)
    }
}

@Composable
private fun Table(
    result: CalcResult.Ok,
    conditions: Conditions?,
    update: ((Conditions) -> Conditions) -> Unit,
    modifier: Modifier,
    details: Boolean,
    onToggleDetails: () -> Unit,
) {
    val s = result.solution
    val unit = result.angularUnit
    val click = result.clickValue

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The rifle and load stay; the rest folds away with the arrow or a tap on the DOPE tab.
            Row(Modifier.clickable(onClick = onToggleDetails), verticalAlignment = Alignment.CenterVertically) {
                Text("${result.weapon.name} · ${result.ammo.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    if (details) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    stringResource(if (details) R.string.action_collapse else R.string.action_expand),
                )
            }
            if (details) {
            val summary = buildList {
                add(stringResource(R.string.summary_muzzle_velocity, s.muzzleVelocityMps.roundToInt()))
                add(stringResource(R.string.summary_density_altitude, s.densityAltitudeM.roundToInt()))
                s.stabilityFactor?.let { add(stringResource(R.string.summary_stability, it)) }
            }
            Text(summary.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            Text(velocitySource(result.weapon, result.ammo), style = MaterialTheme.typography.bodySmall)
            }
            // A stability warning shows even folded.
            s.stabilityFactor?.let {
                if (it < 1.0) Warning(stringResource(R.string.warning_unstable))
                else if (it < 1.4) Warning(stringResource(R.string.warning_marginal))
            }
            if (details) Text(
                stringResource(R.string.table_legend, unit.label, result.weapon.clickValue),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // The range column stays put while the other columns scroll sideways under one shared scroll state.
        val sideways = rememberScrollState()
        val (rangeColumn, rest) = columns.first() to columns.drop(1)
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            Cell(stringResource(rangeColumn.first), rangeColumn.second, bold = true)
            Row(Modifier.horizontalScroll(sideways)) {
                rest.forEach { (title, w) -> Cell(stringResource(title), w, bold = true, highlight = title == R.string.col_clicks) }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                    s.points.forEach { p -> Cell(p.rangeM.roundToInt().toString(), rangeColumn.second, bold = true) }
                }
                Column(Modifier.horizontalScroll(sideways)) {
                    s.points.forEach { p -> PointRow(p, unit.radians, click) }
                }
            }
            // The table's extent sits under it, where its effect shows.
            conditions?.let { Column(Modifier.padding(12.dp)) { TableExtent(it, update) } }
        }
    }
}

@Composable
private fun TableExtent(c: Conditions, update: ((Conditions) -> Conditions) -> Unit) {
    FieldRow { m ->
        NumberField(stringResource(R.string.label_table_range), c.maxRangeM, "m", m) { v -> update { it.copy(maxRangeM = v) } }
        NumberField(stringResource(R.string.label_table_step), c.stepM, "m", m) { v -> update { it.copy(stepM = v) } }
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PointRow(p: TrajectoryPoint, unitRad: Double, click: Double?) {
    // The cm columns are where the bullet goes: drop below the line of sight (negative only above it,
    // between the near and far zero) and drift, unsigned: the wind tells which way. The angle and clicks columns are the corrections.
    val up = -p.dropRad / unitRad
    val right = -p.windageRad / unitRad
    val dropCm = -p.dropM * 100
    val driftCm = p.windageM * 100
    val cells = listOf(
        (if (dropCm < -0.05) "-" else "") + grouped("%.1f".format(abs(dropCm))),
        if (p.rangeM > 0) vertical(up, 0.005) + "%.2f".format(abs(up)) else "",
        if (p.rangeM > 0 && click != null) vertical(up / click, 0.5) + abs(up / click).roundToInt() else "",
        grouped("%.1f".format(abs(driftCm))),
        if (p.rangeM > 0) side(right, 0.005) + "%.2f".format(abs(right)) else "",
        if (p.rangeM > 0 && click != null) side(right / click, 0.5) + (abs(right) / click).roundToInt() else "",
        p.velocityMps.roundToInt().toString(),
        p.energyJ.roundToInt().toString(),
        "%.3f".format(p.timeS),
        "%.2f".format(p.mach),
    )
    Row {
        cells.forEachIndexed { index, text ->
            val (title, width) = columns[index + 1]
            Cell(text, width, bold = title == R.string.col_clicks, highlight = title == R.string.col_clicks)
        }
    }
}

/** R or L for a correction to the right or left; nothing within [dead] of zero, where the figure rounds to 0. */
private fun side(right: Double, dead: Double) = when {
    right > dead -> "R"
    right < -dead -> "L"
    else -> ""
}

/** A narrow gap between metres and centimetres in figures of 100 cm and over (13 20,8), so the metres read at a glance. */
private fun grouped(figure: String): String {
    val digits = figure.indexOfFirst { !it.isDigit() }.let { if (it < 0) figure.length else it }
    return if (digits > 2) figure.substring(0, digits - 2) + '\u202F' + figure.substring(digits - 2) else figure
}

/** Up is the usual case and goes unmarked; ↓ marks a correction down (between the near and far zero). */
private fun vertical(up: Double, dead: Double) = when {
    up < -dead -> "↓"
    else -> ""
}

private val ROW_HEIGHT = 28.dp

/**
 * [highlight]: the clicks columns, what is actually dialled. A light band in the accent colour,
 * like the selected tab, with bold figures: it stands out without outshouting the range column.
 */
@Composable
private fun Cell(text: String, width: Int, bold: Boolean = false, highlight: Boolean = false) {
    val band = if (highlight) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)) else Modifier
    Text(
        text,
        modifier = Modifier.width(width.dp).height(ROW_HEIGHT).then(band).padding(horizontal = 6.dp).wrapContentHeight(),
        color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else Color.Unspecified,
        maxLines = 1,
        textAlign = TextAlign.End,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        style = MaterialTheme.typography.bodySmall,
    )
}
