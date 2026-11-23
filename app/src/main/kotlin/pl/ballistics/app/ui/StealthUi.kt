package pl.ballistics.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.ballistics.app.R
import pl.ballistics.app.data.Stealth

/** Which of the phone's radios are on; read again each time the app comes back to the front. */
data class Radios(val airplane: Boolean, val wifi: Boolean, val bluetooth: Boolean, val location: Boolean) {
    /** Radios that still transmit: cellular is covered by airplane mode. GPS only receives, so it is left out. */
    val transmitting: Boolean get() = !airplane || wifi || bluetooth

    companion object {
        /** Global settings, readable without any permission. */
        fun read(context: Context): Radios {
            val cr = context.contentResolver
            fun global(name: String) = runCatching { Settings.Global.getInt(cr, name, 0) != 0 }.getOrDefault(false)
            val lm = context.getSystemService(LocationManager::class.java)
            val location = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
                else lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
            return Radios(
                airplane = global(Settings.Global.AIRPLANE_MODE_ON),
                wifi = global(Settings.Global.WIFI_ON),
                bluetooth = global(Settings.Global.BLUETOOTH_ON),
                location = location,
            )
        }
    }
}

@Composable
fun rememberRadios(): Radios {
    val context = LocalContext.current
    var radios by remember { mutableStateOf(Radios.read(context)) }
    LifecycleResumeEffect(Unit) {
        radios = Radios.read(context)
        onPauseOrDispose { }
    }
    return radios
}

private fun Context.openSettings(action: String) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** The names of the radios still transmitting, for the bar; empty when all are quiet. */
@Composable
private fun loudRadios(r: Radios): String = buildList {
    if (!r.airplane) add(stringResource(R.string.stealth_radio_airplane) + " " + stringResource(R.string.stealth_state_off))
    if (r.wifi) add(stringResource(R.string.stealth_radio_wifi))
    if (r.bluetooth) add(stringResource(R.string.stealth_radio_bluetooth))
}.joinToString(", ")

/** A thin bar over every tab while stealth mode is on, warning when the phone's radios still transmit. */
@Composable
fun StealthBar(onClick: () -> Unit) {
    val radios = rememberRadios()
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (radios.transmitting) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (radios.transmitting) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ImageVector.vectorResource(R.drawable.ic_stealth), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.stealth_short), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (radios.transmitting) {
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.stealth_radios_on, loudRadios(radios)),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Turns stealth mode on or off. It says what the app stops, shows which of the phone's radios
 * still transmit, with a shortcut to each one's settings, since an app cannot switch them off.
 */
@Composable
fun StealthDialog(garminWatch: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current by Stealth.state.collectAsStateWithLifecycle()
    var allowWatch by remember { mutableStateOf(current.allowWatch) }
    val radios = rememberRadios()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(ImageVector.vectorResource(R.drawable.ic_stealth), contentDescription = null) },
        title = {
            Text(
                stringResource(R.string.stealth_title) + " · " +
                    stringResource(if (current.on) R.string.stealth_on else R.string.stealth_off),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.stealth_what) +
                        if (!garminWatch || !allowWatch) " " + stringResource(R.string.stealth_what_watch_off) else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (garminWatch) SwitchRow(stringResource(R.string.stealth_allow_watch), allowWatch) { v ->
                    allowWatch = v
                    if (current.on) Stealth.set(context, current.copy(allowWatch = v))
                }
                Text(stringResource(R.string.stealth_radios), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                // Each row is red while that radio still gives the phone away.
                RadioRow(R.string.stealth_radio_airplane, radios.airplane, loud = !radios.airplane) { context.openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS) }
                RadioRow(R.string.stealth_radio_wifi, radios.wifi, loud = radios.wifi) { context.openSettings(Settings.ACTION_WIFI_SETTINGS) }
                RadioRow(R.string.stealth_radio_bluetooth, radios.bluetooth, loud = radios.bluetooth) { context.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
                RadioRow(R.string.stealth_radio_location, radios.location, loud = false) { context.openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
                Text(stringResource(R.string.stealth_note_gps), style = MaterialTheme.typography.bodySmall)
                if (garminWatch) Text(stringResource(R.string.stealth_note_watch), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Stealth.set(context, Stealth.Settings(on = !current.on, allowWatch = allowWatch))
                onDismiss()
            }) { Text(stringResource(if (current.on) R.string.stealth_turn_off else R.string.stealth_turn_on)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun RadioRow(name: Int, on: Boolean, loud: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(name), Modifier.weight(1f))
        Text(
            stringResource(if (on) R.string.stealth_state_on else R.string.stealth_state_off),
            color = if (loud) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (loud) FontWeight.SemiBold else null,
        )
        TextButton(onClick = onOpen) { Text(stringResource(R.string.stealth_open_settings)) }
    }
}
