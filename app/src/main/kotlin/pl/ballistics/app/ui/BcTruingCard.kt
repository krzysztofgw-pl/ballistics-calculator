package pl.ballistics.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.ballistics.app.CalcResult
import pl.ballistics.app.R
import pl.ballistics.app.TruedBc
import pl.ballistics.app.bcFromDrop
import pl.ballistics.app.bcFromVelocities
import pl.ballistics.app.data.AmmoProfile
import pl.ballistics.app.data.AngularUnit
import pl.ballistics.app.data.DragType
import pl.ballistics.app.data.newId
import pl.ballistics.app.toNumber
import pl.ballistics.core.DragFunction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private fun bcText(bc: Double) = "%.3f".format(Locale.ROOT, bc)

/**
 * BC of the active load from range data: two measured velocities, or the elevation that
 * centred the group at a known distance. The result can be saved to the load.
 */
@Composable
fun BcTruingCard(
    result: CalcResult.Ok,
    currentRangeM: Double?,
    onSaveAmmo: (AmmoProfile) -> Unit,
    onAddAmmoCopy: (from: AmmoProfile, copy: AmmoProfile) -> Unit,
) {
    Collapsible(stringResource(R.string.section_bc_truing)) {
        val ammo = result.ammo
        if (ammo.dragType == DragType.CUSTOM) {
            Text(stringResource(R.string.bc_needs_g_model), style = MaterialTheme.typography.bodyMedium)
            return@Collapsible
        }
        val function = DragFunction.valueOf(ammo.dragType.name)
        val unit = result.angularUnit
        var byVelocity by rememberSaveable { mutableStateOf(false) }
        var nearRange by rememberSaveable { mutableStateOf("") }
        var nearVelocity by rememberSaveable { mutableStateOf("") }
        var farRange by rememberSaveable { mutableStateOf("") }
        var farVelocity by rememberSaveable { mutableStateOf("") }
        var dropRange by rememberSaveable { mutableStateOf("") }
        var correction by rememberSaveable { mutableStateOf("") }

        TwoOptions(
            stringResource(R.string.label_bc_method),
            stringResource(R.string.bc_method_drop),
            stringResource(R.string.bc_method_velocity),
            !byVelocity,
        ) { byVelocity = !it }

        val muzzle = result.solution.muzzleVelocityMps.roundToInt().toString()
        val placeholderRange = currentRangeM?.roundToInt()?.toString()
        if (byVelocity) {
            FieldRow { m ->
                NumberField(stringResource(R.string.label_near_distance), nearRange, "m", m, placeholder = "0") { nearRange = it }
                NumberField(stringResource(R.string.label_near_velocity), nearVelocity, "m/s", m, placeholder = muzzle) { nearVelocity = it }
            }
            FieldRow { m ->
                NumberField(stringResource(R.string.label_far_distance), farRange, "m", m) { farRange = it }
                NumberField(stringResource(R.string.label_far_velocity), farVelocity, "m/s", m) { farVelocity = it }
            }
        } else {
            FieldRow { m ->
                NumberField(stringResource(R.string.label_bc_distance), dropRange, "m", m, placeholder = placeholderRange) { dropRange = it }
                NumberField(stringResource(R.string.label_elevation_up), correction, unit.label, m) { correction = it }
            }
            Text(stringResource(R.string.bc_drop_hint), style = MaterialTheme.typography.bodySmall)
        }

        val setup = result.setup
        val step = if (unit == AngularUnit.MOA) 0.25 else 0.1
        val velocityError = 3.0
        val inputs = if (byVelocity) {
            listOf(nearRange.toNumber() ?: 0.0, nearVelocity.toNumber() ?: muzzle.toDouble(), farRange.toNumber(), farVelocity.toNumber())
        } else {
            listOf(dropRange.toNumber() ?: currentRangeM, correction.toNumber())
        }
        val trued by produceState<Result<TruedBc?>?>(null, setup, function, byVelocity, inputs) {
            value = null
            if (inputs.any { it == null }) return@produceState
            val v = inputs.map { it!! }
            value = withContext(Dispatchers.Default) {
                runCatching {
                    if (byVelocity) setup.bcFromVelocities(function, v[0], v[1], v[2], v[3], velocityError)
                    else setup.bcFromDrop(function, v[0], v[1] * unit.radians, step * unit.radians)
                }
            }
        }

        val bc = trued?.getOrNull()
        if (trued != null && bc == null) {
            Text(stringResource(R.string.bc_no_fit), style = MaterialTheme.typography.bodyMedium)
        }
        if (bc != null) {
            var asking by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.bc_result, function.name, bcText(bc.bc)),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = { asking = true }) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Text(stringResource(R.string.action_apply_to_load))
                }
            }
            if (asking) {
                SaveBcDialog(ammo, function.name, bc.bc, onSaveAmmo, onAddAmmoCopy) { asking = false }
            }
            val spread = if (byVelocity) "±${velocityError.roundToInt()} m/s" else "±$step ${unit.label}"
            Text(
                stringResource(
                    R.string.bc_result_details,
                    spread,
                    bc.low?.let(::bcText) ?: "?",
                    bc.high?.let(::bcText) ?: "?",
                    ammo.bc,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Asks whether the trued BC replaces the load's BC or goes into a copy of the load. */
@Composable
private fun SaveBcDialog(
    ammo: AmmoProfile,
    model: String,
    bc: Double,
    onSaveAmmo: (AmmoProfile) -> Unit,
    onAddAmmoCopy: (from: AmmoProfile, copy: AmmoProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val newBc = bcText(bc)
    val old = ammo.bc.toNumber()
    val diff = old?.let { bcText(bc).toDouble() - it }
    val copyName = stringResource(R.string.bc_copy_name, ammo.name, newBc)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bc_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ammo.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildAnnotatedString {
                        append("$model  ${ammo.bc.ifBlank { "—" }} → $newBc")
                        if (diff != null) {
                            val color = when {
                                diff > 0 -> GAIN
                                diff < 0 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                                append("  " + (if (diff >= 0) "+" else "−") + bcText(abs(diff)))
                            }
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSaveAmmo(ammo.copy(bc = newBc))
                Toast.makeText(context, context.getString(R.string.toast_bc_saved, newBc, ammo.name), Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text(stringResource(R.string.action_replace_bc)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = {
                    onAddAmmoCopy(ammo, ammo.copy(id = newId(), name = copyName, bc = newBc))
                    Toast.makeText(context, context.getString(R.string.toast_bc_copy_saved, copyName), Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) { Text(stringResource(R.string.action_new_load_copy)) }
            }
        },
    )
}

/** A higher BC than before; red (the theme's error colour) marks a lower one. */
private val GAIN = Color(0xFF2E9D4A)
