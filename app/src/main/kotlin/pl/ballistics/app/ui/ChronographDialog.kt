package pl.ballistics.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.LaunchedEffect
import pl.ballistics.core.Shot
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import pl.ballistics.app.ChronographImportState
import pl.ballistics.app.R
import pl.ballistics.app.data.AppData
import pl.ballistics.app.data.STANDARD_POWDER_SENSITIVITY
import pl.ballistics.app.toNumber
import pl.ballistics.core.Chronograph
import pl.ballistics.core.ChronographFormatException
import pl.ballistics.core.ShotSeries
import pl.ballistics.core.SpeedUnit
import pl.ballistics.core.VelocityStats
import java.util.Locale
import kotlin.math.abs

private fun f1(v: Double) = "%.1f".format(Locale.ROOT, v)

private fun deviceName(d: Chronograph): String? = when (d) {
    Chronograph.LABRADAR -> "Labradar"
    Chronograph.GARMIN_XERO -> "Garmin Xero"
    Chronograph.ATHLON_RANGECRAFT -> "Athlon Rangecraft"
    Chronograph.MAGNETOSPEED -> "MagnetoSpeed"
    Chronograph.OTHER -> null
}

/** Shows a chronograph file being read, why it could not be read, or its series to save to a load. */
@Composable
fun ChronographDialog(
    state: ChronographImportState,
    data: AppData,
    onApply: (weaponId: String?, ammoId: String, velocity: String, sd: String, forRifle: Boolean, copyName: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        ChronographImportState.Reading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.title_chronograph)) },
            text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } },
            confirmButton = { FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
        is ChronographImportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.title_chronograph)) },
            text = {
                Text(
                    stringResource(
                        when (state.reason) {
                            ChronographFormatException.Reason.NO_SHOTS -> R.string.chrono_no_shots
                            ChronographFormatException.Reason.LABRADAR_TRACK -> R.string.chrono_labradar_track
                            ChronographFormatException.Reason.NOT_RECOGNISED -> R.string.chrono_not_recognised
                        },
                    ),
                )
            },
            confirmButton = { FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        )
        is ChronographImportState.Loaded -> SeriesDialog(state.series, data, onApply, onDismiss)
    }
}

@Composable
private fun SeriesDialog(
    all: List<ShotSeries>,
    data: AppData,
    onApply: (weaponId: String?, ammoId: String, velocity: String, sd: String, forRifle: Boolean, copyName: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // A file with several series (MagnetoSpeed, an Athlon workbook) opens on its last one: the latest shot.
    var index by remember { mutableStateOf(all.lastIndex) }
    var unitOverride by remember(index) { mutableStateOf<SpeedUnit?>(null) }
    val series = all[index].let { s -> unitOverride?.let { s.inUnit(it) } ?: s }
    val velocities = series.shots.map { it.velocityMps }
    // Misreads are left out to start with; every shot can be ticked back in.
    val outliers = remember(index) { VelocityStats.outliers(all[index].shots.map { it.velocityMps }) }
    var excluded by remember(index) { mutableStateOf(outliers) }
    val included = velocities.filterIndexed { i, _ -> i !in excluded }
    val stats = VelocityStats.of(included)

    var weaponId by remember { mutableStateOf(data.activeWeapon?.id) }
    val weapon = data.weapons.find { it.id == weaponId }
    var ammoId by remember { mutableStateOf(data.activeWeapon?.let { data.ammoFor(it) }?.id ?: data.ammo.firstOrNull()?.id) }
    val ammo = data.ammo.find { it.id == ammoId }
    var forRifle by remember { mutableStateOf(weapon != null) }
    var temperature by remember { mutableStateOf(data.conditions.temperatureC) }

    // The velocity is kept at the load's reference powder temperature, so a series shot
    // in the cold is moved to that temperature with the load's sensitivity.
    val sensitivity = ammo?.powderSensitivity?.toNumber() ?: STANDARD_POWDER_SENSITIVITY
    val reference = ammo?.powderReferenceTemp?.toNumber() ?: 21.0
    val measuredAt = temperature.toNumber()
    val newVelocity = stats?.let { s -> measuredAt?.let { s.mean + sensitivity * (reference - it) } }
    val oldVelocity = if (ammo == null) null else if (forRifle && weapon != null) weapon.measuredVelocity(ammo) else ammo.muzzleVelocity
    val canSave = ammo != null && newVelocity != null && stats != null && (!forRifle || weapon != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(series.title ?: stringResource(R.string.title_chronograph)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(deviceName(series.device), series.date).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (all.size > 1) {
                    Choice(stringResource(R.string.label_series), index, all.indices.toList(), { i ->
                        all[i].title ?: all[i].date ?: stringResource(R.string.chrono_series_number, i + 1)
                    }) { index = it }
                }
                if (series.unitGuessed) {
                    TwoOptions(stringResource(R.string.label_file_unit), "m/s", "fps", series.unit == SpeedUnit.MPS) {
                        unitOverride = if (it) SpeedUnit.MPS else SpeedUnit.FPS
                    }
                }
                if (stats != null) {
                    Text(
                        stringResource(R.string.chrono_average, f1(stats.mean)),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.chrono_stats, f1(stats.sd), f1(stats.es), stats.count, velocities.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
                series.shots.forEachIndexed { i, shot ->
                    val on = i !in excluded
                    Row(
                        Modifier.fillMaxWidth().clickable { excluded = if (on) excluded + i else excluded - i },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { excluded = if (it) excluded - i else excluded + i })
                        Text("${shot.number}", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall)
                        Text(
                            buildString {
                                append(f1(shot.velocityMps)).append(" m/s")
                                if (series.unit == SpeedUnit.FPS) append("  (").append(f1(shot.velocityMps / SpeedUnit.FPS.mps)).append(" fps)")
                            },
                            Modifier.weight(1f),
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (i in outliers) {
                            Text(
                                stringResource(R.string.chrono_outlier),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (stats != null) {
                            val d = shot.velocityMps - stats.mean
                            Text(
                                (if (d >= 0) "+" else "−") + f1(abs(d)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                if (data.ammo.isEmpty()) {
                    Text(stringResource(R.string.chrono_no_loads), style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (data.weapons.isNotEmpty()) {
                        Choice(stringResource(R.string.label_rifle), weaponId, data.weapons.map { it.id }, { id ->
                            data.weapons.find { it.id == id }?.name.orEmpty()
                        }) { id ->
                            weaponId = id
                            data.weapons.find { it.id == id }?.let { w -> data.ammoFor(w)?.let { ammoId = it.id } }
                        }
                    }
                    Choice(stringResource(R.string.label_load), ammoId, data.ammo.map { it.id }, { id ->
                        data.ammo.find { it.id == id }?.name.orEmpty()
                    }) { ammoId = it }
                    if (weapon != null) {
                        TwoOptions(
                            stringResource(R.string.label_velocity_for),
                            stringResource(R.string.option_this_rifle),
                            stringResource(R.string.option_all_rifles),
                            forRifle,
                        ) { forRifle = it }
                    }
                    NumberField(stringResource(R.string.label_powder_temperature), temperature, "°C") { temperature = it }
                    if (newVelocity != null && stats != null && ammo != null) {
                        ChangeLine(stringResource(R.string.label_muzzle_velocity), oldVelocity, f1(newVelocity), "m/s")
                        ChangeLine(stringResource(R.string.label_velocity_sd), ammo.velocitySd.ifBlank { null }, f1(stats.sd), "m/s")
                        if (measuredAt != null && abs(measuredAt - reference) >= 0.5) {
                            Text(
                                stringResource(R.string.chrono_temperature_note, f1(stats.mean), f1(reference).removeSuffix(".0")),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!forRifle && weapon?.measuredVelocity(ammo) != null) {
                            Text(stringResource(R.string.chrono_rifle_overrides, weapon.name), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(enabled = canSave, onClick = {
                val a = ammo ?: return@FilledTonalButton
                onApply(weaponId, a.id, f1(newVelocity!!), f1(stats!!.sd), forRifle && weapon != null, null)
                Toast.makeText(context, context.getString(R.string.toast_chrono_saved, f1(newVelocity), a.name), Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                val copyName = ammo?.let { stringResource(R.string.chrono_copy_name, it.name, f1(newVelocity ?: 0.0)) }
                FilledTonalButton(enabled = canSave, onClick = {
                    onApply(weaponId, ammo!!.id, f1(newVelocity!!), f1(stats!!.sd), forRifle && weapon != null, copyName)
                    Toast.makeText(context, context.getString(R.string.toast_bc_copy_saved, copyName), Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) { Text(stringResource(R.string.action_new_load_copy)) }
            }
        },
    )
}

/** "Muzzle velocity  790 → 805.3 m/s  +15.3", the difference green when higher, red when lower. */
@Composable
private fun ChangeLine(label: String, old: String?, new: String, unit: String) {
    val diff = old?.toNumber()?.let { new.toDouble() - it }
    Column(Modifier.padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            buildAnnotatedString {
                append("${old ?: "—"} → $new $unit")
                if (diff != null && abs(diff) >= 0.05) {
                    withStyle(SpanStyle(color = if (diff > 0) RAISED else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)) {
                        append("  " + (if (diff > 0) "+" else "−") + f1(abs(diff)))
                    }
                }
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private val RAISED = Color(0xFF2E9D4A)

/**
 * Velocities typed one after another, as read off a chronograph's screen: each Enter adds
 * the shot and keeps the keyboard open; several numbers pasted at once are all added.
 * "Next" goes on to the same review as an imported file.
 */
@Composable
fun ManualVelocityDialog(onDone: (ShotSeries) -> Unit, onDismiss: () -> Unit) {
    var fps by remember { mutableStateOf(false) }
    var values by remember { mutableStateOf(listOf<Double>()) }
    var input by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val unit = if (fps) SpeedUnit.FPS else SpeedUnit.MPS

    /** Every number in [text]; a decimal comma counts as a point, a space or new line separates. */
    fun numbers(text: String) = text.split(Regex("[\\s;]+")).mapNotNull { it.toNumber()?.takeIf { v -> v > 0 } }
    fun add() {
        values = values + numbers(input)
        input = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_manual_velocities)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TwoOptions(stringResource(R.string.label_unit), "m/s", "fps", !fps) { fps = !it }
                OutlinedTextField(
                    value = input,
                    onValueChange = { t ->
                        // A pasted list, or a space typed after a number, adds what is complete.
                        if (t.any { it == '\n' || it == ' ' || it == ';' }) {
                            val parts = t.split(Regex("[\\s;]+"))
                            values = values + parts.dropLast(1).mapNotNull { it.toNumber()?.takeIf { v -> v > 0 } }
                            input = parts.last()
                        } else {
                            input = t
                        }
                    },
                    label = { Text(stringResource(R.string.label_shot_n, values.size + 1)) },
                    suffix = { Text(unit.label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { add() }, onDone = { add() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                VelocityStats.of(values)?.let { s ->
                    Text(
                        stringResource(R.string.chrono_manual_stats, f1(s.mean), f1(s.sd), f1(s.es), unit.label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                values.withIndex().reversed().forEach { (i, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall)
                        Text("${f1(v)} ${unit.label}", Modifier.weight(1f))
                        IconButton(onClick = { values = values.filterIndexed { j, _ -> j != i } }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.action_remove_shot))
                        }
                    }
                }
            }
        },
        confirmButton = {
            val all = values + numbers(input)
            FilledTonalButton(enabled = all.isNotEmpty(), onClick = {
                onDone(ShotSeries(Chronograph.OTHER, null, unit, all.mapIndexed { i, v -> Shot(i + 1, v * unit.mps) }))
            }) { Text(stringResource(R.string.action_next)) }
        },
        dismissButton = { FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
