package pl.ballistics.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.coroutines.launch
import pl.ballistics.app.R
import pl.ballistics.app.data.BulletSuggestion
import pl.ballistics.app.data.BulletSuggestion.Problem
import pl.ballistics.app.data.BulletSuggestion.SourceType
import pl.ballistics.app.toNumber
import java.util.Locale

private fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.4f", it).trimEnd('0').trimEnd('.') }.orEmpty()

/**
 * Proposes the bullet of a load for the community database. Sending needs a
 * signed-in account; a maintainer reviews every suggestion before it is published.
 */
@Composable
fun SuggestBulletDialog(
    initial: BulletSuggestion,
    signedIn: Boolean,
    onSend: suspend (BulletSuggestion) -> String?,
    onDismiss: () -> Unit,
) {
    if (!signedIn) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.suggest_title)) },
            text = { Text(stringResource(R.string.suggest_sign_in)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        )
        return
    }
    var maker by remember { mutableStateOf(initial.maker) }
    var name by remember { mutableStateOf(initial.name) }
    var diameter by remember { mutableStateOf(fmt(initial.diameterIn)) }
    var weight by remember { mutableStateOf(fmt(initial.weightGr)) }
    var g1 by remember { mutableStateOf(fmt(initial.g1)) }
    var g7 by remember { mutableStateOf(fmt(initial.g7)) }
    var length by remember { mutableStateOf(fmt(initial.lengthIn)) }
    var twist by remember { mutableStateOf(fmt(initial.twistIn)) }
    var sourceType by remember { mutableStateOf(initial.sourceType) }
    var sourceRef by remember { mutableStateOf(initial.sourceRef) }
    var note by remember { mutableStateOf(initial.note) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val suggestion = initial.copy(
        maker = maker,
        name = name,
        diameterIn = diameter.toNumber() ?: 0.0,
        weightGr = weight.toNumber() ?: 0.0,
        g1 = g1.toNumber(),
        g7 = g7.toNumber(),
        lengthIn = length.toNumber(),
        twistIn = twist.toNumber(),
        sourceType = sourceType,
        sourceRef = sourceRef,
        note = note,
    )
    val problems = suggestion.problems()

    if (sent) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.suggest_title)) },
            text = { Text(stringResource(R.string.suggest_sent)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.suggest_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (initial.replaces != null) R.string.suggest_intro_correction else R.string.suggest_intro),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextInput(stringResource(R.string.suggest_maker), maker) { maker = it.take(BulletSuggestion.MAX_MAKER) }
                TextInput(stringResource(R.string.suggest_name), name) { name = it.take(BulletSuggestion.MAX_NAME) }
                FieldRow { m ->
                    NumberField(stringResource(R.string.label_diameter), diameter, "in", m) { diameter = it }
                    NumberField(stringResource(R.string.label_mass), weight, "gr", m) { weight = it }
                }
                FieldRow { m ->
                    NumberField("G1", g1, "", m, optional = true) { g1 = it }
                    NumberField("G7", g7, "", m, optional = true) { g7 = it }
                }
                FieldRow { m ->
                    NumberField(stringResource(R.string.suggest_length), length, "in", m, optional = true) { length = it }
                    NumberField(stringResource(R.string.suggest_twist), twist, "in", m, optional = true) { twist = it }
                }
                Choice(stringResource(R.string.suggest_source), sourceType, SourceType.entries, { sourceLabel(it) }) { sourceType = it }
                OutlinedTextField(
                    value = sourceRef,
                    onValueChange = { sourceRef = it.take(BulletSuggestion.MAX_REF) },
                    label = { Text(stringResource(R.string.suggest_source_ref)) },
                    placeholder = { Text(stringResource(R.string.suggest_source_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(BulletSuggestion.MAX_NOTE) },
                    label = { Text(stringResource(R.string.suggest_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                problems.firstOrNull()?.let {
                    Text(problemText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problems.isEmpty() && !sending,
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        val result = onSend(suggestion)
                        sending = false
                        if (result == null) sent = true else error = result
                    }
                },
            ) { Text(stringResource(if (sending) R.string.suggest_sending else R.string.suggest_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun sourceLabel(type: SourceType) = stringResource(
    when (type) {
        SourceType.WEBSITE -> R.string.suggest_source_website
        SourceType.CATALOGUE -> R.string.suggest_source_catalogue
        SourceType.BOX -> R.string.suggest_source_box
        SourceType.DOPPLER -> R.string.suggest_source_doppler
    },
)

@Composable
private fun problemText(problem: Problem) = stringResource(
    when (problem) {
        Problem.MAKER -> R.string.suggest_problem_maker
        Problem.NAME -> R.string.suggest_problem_name
        Problem.DIAMETER -> R.string.suggest_problem_diameter
        Problem.WEIGHT -> R.string.suggest_problem_weight
        Problem.BC -> R.string.suggest_problem_bc
        Problem.LENGTH -> R.string.suggest_problem_length
        Problem.TWIST -> R.string.suggest_problem_twist
        Problem.SOURCE -> R.string.suggest_problem_source
        Problem.NOTE -> R.string.suggest_problem_note
    },
)
