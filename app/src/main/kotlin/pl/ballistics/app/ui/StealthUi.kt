package pl.ballistics.app.ui

import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.graphics.drawable.Icon as GraphicsIcon
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.core.content.ContextCompat
import pl.ballistics.app.tile.StealthTileService
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
data class Radios(
    val airplane: Boolean,
    val wifi: Boolean,
    val bluetooth: Boolean,
    val location: Boolean,
    /**
     * "Wi-Fi scanning" / "Bluetooth scanning" in the location settings: the phone keeps scanning with
     * Wi-Fi or Bluetooth switched off, and a Wi-Fi scan sends probe requests. Airplane mode does not stop it.
     */
    val scanning: Boolean,
) {
    /**
     * Whether the phone still transmits. Cellular is covered by airplane mode; Wi-Fi and Bluetooth are
     * checked on their own, since airplane mode does not always turn them off (Android remembers if they
     * were turned back on in airplane mode). GPS only receives, so it is left out.
     */
    val transmitting: Boolean get() = !airplane || wifi || bluetooth || (scanning && location)

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
                scanning = global("wifi_scan_always_enabled") || global("ble_scan_always_enabled"),
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
    if (r.scanning && r.location) add(stringResource(R.string.stealth_radio_scanning))
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
    val radios = rememberRadios()
    var notice by remember { mutableStateOf(false) }
    if (notice) {
        StealthNoticeDialog(onDismiss)
        return
    }

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
                Text(stealthSummary(current, garminWatch), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.stealth_radios), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                // Each row is red while that radio still gives the phone away.
                RadioRow(R.string.stealth_radio_airplane, radios.airplane, loud = !radios.airplane) { context.openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS) }
                RadioRow(R.string.stealth_radio_wifi, radios.wifi, loud = radios.wifi) { context.openSettings(Settings.ACTION_WIFI_SETTINGS) }
                RadioRow(R.string.stealth_radio_bluetooth, radios.bluetooth, loud = radios.bluetooth) { context.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
                RadioRow(R.string.stealth_radio_location, radios.location, loud = false) { context.openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
                if (radios.location) RadioRow(R.string.stealth_radio_scanning, radios.scanning, loud = radios.scanning) {
                    context.openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                }
                Text(stringResource(R.string.stealth_note_gps), style = MaterialTheme.typography.bodySmall)
                if (garminWatch) Text(stringResource(R.string.stealth_note_watch), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val turningOn = !current.on
                Stealth.set(context, current.copy(on = turningOn))
                // Once turned on, say that the phone's networks are the user's to switch, unless they asked not to.
                if (turningOn && Stealth.showNotice(context)) notice = true else onDismiss()
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
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.stealth_open_settings)) }
    }
}

/**
 * Shown when stealth mode is turned on: Android lets no app switch airplane mode, Wi-Fi or Bluetooth,
 * so the user has to. Offers the Stealth quick settings tile, which on Android 13+ the app can ask the
 * system to add; before that it says how to add it by hand. "Don't show again" hides it for good.
 */
@Composable
private fun StealthNoticeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var hide by remember { mutableStateOf(false) }
    var tileAdded by remember { mutableStateOf(Stealth.tileAdded(context)) }
    fun close() {
        if (hide) Stealth.hideNotice(context)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::close,
        icon = { Icon(ImageVector.vectorResource(R.drawable.ic_stealth), contentDescription = null) },
        title = { Text(stringResource(R.string.stealth_notice_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.stealth_notice_manual), style = MaterialTheme.typography.bodyMedium)
                IconLabelButton(R.drawable.ic_airplane, R.string.stealth_radio_airplane, Modifier.fillMaxWidth()) {
                    context.openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconLabelButton(R.drawable.ic_wifi, R.string.stealth_radio_wifi, Modifier.weight(1f)) {
                        context.openSettings(Settings.ACTION_WIFI_SETTINGS)
                    }
                    IconLabelButton(R.drawable.ic_bluetooth, R.string.stealth_radio_bluetooth, Modifier.weight(1f)) {
                        context.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                    }
                }
                Text(stringResource(R.string.stealth_notice_airplane), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(if (Stealth.state.value.allowGps) R.string.stealth_notice_gps_on else R.string.stealth_notice_gps_off),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (tileAdded) {
                    Text(stringResource(R.string.stealth_tile_added), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(stringResource(R.string.stealth_notice_tile), style = MaterialTheme.typography.bodyMedium)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        FilledTonalButton(onClick = { requestStealthTile(context) { tileAdded = it } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_stealth), null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.stealth_add_tile))
                        }
                    } else {
                        Text(stringResource(R.string.stealth_notice_tile_manual), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource(R.string.stealth_notice_settings), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().clickable { hide = !hide }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hide, onCheckedChange = { hide = it })
                    Text(stringResource(R.string.action_dont_show_again))
                }
            }
        },
        confirmButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.action_ok)) } },
    )
}

/** Asks the system to add the Stealth tile (Android 13+); [onResult] gets whether it is now there. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestStealthTile(context: Context, onResult: (Boolean) -> Unit) {
    val manager = context.getSystemService(StatusBarManager::class.java) ?: return
    runCatching {
        manager.requestAddTileService(
            ComponentName(context, StealthTileService::class.java),
            context.getString(R.string.stealth_short),
            GraphicsIcon.createWithResource(context, R.drawable.ic_stealth),
            ContextCompat.getMainExecutor(context),
        ) { result ->
            val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            if (added) Stealth.setTileAdded(context, true)
            onResult(added)
        }
    }
}

/** What stealth mode blocks with the current options. */
@Composable
private fun stealthSummary(s: Stealth.Settings, garminWatch: Boolean): String = buildString {
    append(stringResource(R.string.stealth_what))
    append(' ')
    append(stringResource(if (s.allowGps) R.string.stealth_what_gps_on else R.string.stealth_what_gps_off))
    if (garminWatch && !s.allowWatch) append(' ').append(stringResource(R.string.stealth_what_watch_off))
}

/** An outlined button with an icon and a short label, for the shortcuts to the system settings. */
@Composable
private fun IconLabelButton(icon: Int, label: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Icon(ImageVector.vectorResource(icon), null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(label), maxLines = 1)
    }
}

/** Stealth mode's options in Settings: on/off through the dialog, and what it still allows. */
@Composable
fun StealthSettings(garminWatch: Boolean, onOpenStealth: () -> Unit) {
    val context = LocalContext.current
    val s by Stealth.state.collectAsStateWithLifecycle()
    // Both ways go through the dialog, which shows what is blocked and the phone's radios.
    SwitchRow(stringResource(R.string.stealth_title), s.on) { onOpenStealth() }
    SwitchRow(stringResource(R.string.stealth_allow_gps), s.allowGps) { v -> Stealth.set(context, s.copy(allowGps = v)) }
    if (garminWatch) SwitchRow(stringResource(R.string.stealth_allow_watch), s.allowWatch) { v -> Stealth.set(context, s.copy(allowWatch = v)) }
    SwitchRow(stringResource(R.string.stealth_show_bar), s.showBar) { v -> Stealth.set(context, s.copy(showBar = v)) }
}
