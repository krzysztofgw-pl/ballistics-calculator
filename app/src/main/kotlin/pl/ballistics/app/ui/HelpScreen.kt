package pl.ballistics.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.ballistics.app.R

/** One question and its answer; the answer's paragraphs are separated by blank lines. */
private class Topic(@StringRes val title: Int, @StringRes val body: Int)

private val topics = listOf(
    Topic(R.string.help_start_title, R.string.help_start),
    Topic(R.string.help_zero_title, R.string.help_zero),
    Topic(R.string.help_pressure_title, R.string.help_pressure),
    Topic(R.string.help_velocity_title, R.string.help_velocity),
    Topic(R.string.help_wind_title, R.string.help_wind),
    Topic(R.string.help_angle_title, R.string.help_angle),
    Topic(R.string.help_target_title, R.string.help_target),
    Topic(R.string.help_moving_title, R.string.help_moving),
    Topic(R.string.help_table_title, R.string.help_table),
    Topic(R.string.help_sync_title, R.string.help_sync),
    Topic(R.string.help_update_title, R.string.help_update),
    Topic(R.string.help_accuracy_title, R.string.help_accuracy),
)

/** How to use the app, as questions that open to their answers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_help)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            topics.forEach { t ->
                Collapsible(stringResource(t.title)) {
                    stringResource(t.body).split("\n\n").forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
