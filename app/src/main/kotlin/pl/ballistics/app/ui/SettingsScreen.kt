package pl.ballistics.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.ballistics.app.BuildConfig
import pl.ballistics.app.R
import pl.ballistics.app.data.AltitudeSource
import pl.ballistics.app.data.Conditions
import pl.ballistics.app.data.PressureSource
import pl.ballistics.app.data.Correction
import pl.ballistics.app.data.SyncState
import pl.ballistics.app.data.ThemeMode
import pl.ballistics.app.data.ChartKind
import pl.ballistics.app.data.UiSettings
import pl.ballistics.app.reticle.Reticles
import java.time.LocalDate

/** Look and layout of the app, sync and backup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ui: UiSettings,
    onUi: ((UiSettings) -> UiSettings) -> Unit,
    pressureIsQnh: Boolean,
    onPressureIsQnh: (Boolean) -> Unit,
    conditions: Conditions,
    onConditions: ((Conditions) -> Conditions) -> Unit,
    sync: SyncState,
    onSignIn: suspend (Context) -> String?,
    onSignOut: () -> Unit,
    onDeleteAccount: suspend (Context) -> String?,
    onExport: () -> String,
    onImport: (String) -> Boolean,
    onCheckUpdate: () -> Unit,
    stealthOn: Boolean,
    onOpenStealth: () -> Unit,
    onOpenProfiles: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasBarometer = remember { DeviceSensors.hasBarometer(context) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(onExport().toByteArray()) } != null
        }.getOrDefault(false)
        Toast.makeText(context, context.getString(if (ok) R.string.toast_exported else R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        val ok = text != null && onImport(text)
        Toast.makeText(context, context.getString(if (ok) R.string.toast_imported else R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        // Keep content clear of system bars, display cutouts and the keyboard (edge-to-edge).
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(onClick = onOpenProfiles, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_profiles))
            }

            Section(stringResource(R.string.stealth_title), ImageVector.vectorResource(R.drawable.ic_stealth)) {
                StealthSettings(ui.garminWatch, onOpenStealth)
            }

            Section(stringResource(R.string.section_appearance)) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onUi { it.copy(theme = mode) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ui.theme == mode, onClick = { onUi { it.copy(theme = mode) } })
                        Text(stringResource(mode.label))
                    }
                }
                SwitchRow(stringResource(R.string.label_keep_screen_on), ui.keepScreenOn) { v -> onUi { it.copy(keepScreenOn = v) } }
            }

            Section(stringResource(R.string.section_layout)) {
                Placement(stringResource(R.string.label_tabs), ui.tabsAtTop) { v -> onUi { it.copy(tabsAtTop = v) } }
                Placement(stringResource(R.string.label_sub_tabs), ui.subTabsAtTop) { v -> onUi { it.copy(subTabsAtTop = v) } }
                // The switch turns all charts on or off; the arrow opens the list of single charts.
                var chartsOpen by rememberSaveable { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).clickable(enabled = ui.showCharts) { chartsOpen = !chartsOpen },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.label_show_charts), Modifier.weight(1f))
                        if (ui.showCharts) {
                            Text(
                                "${ChartKind.entries.count { it !in ui.hiddenCharts }}/${ChartKind.entries.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                if (chartsOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                stringResource(if (chartsOpen) R.string.action_collapse else R.string.action_expand),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = ui.showCharts, onCheckedChange = { v -> onUi { it.copy(showCharts = v) } })
                }
                if (ui.showCharts && chartsOpen) {
                    Column(Modifier.padding(start = 16.dp)) {
                        ChartKind.entries.forEach { kind ->
                            SwitchRow(stringResource(kind.label), kind !in ui.hiddenCharts) { on ->
                                onUi { it.copy(hiddenCharts = if (on) it.hiddenCharts - kind else it.hiddenCharts + kind) }
                            }
                        }
                    }
                }
            }

            Section(stringResource(R.string.section_ballistic_model)) {
                SwitchRow(stringResource(R.string.label_spin_drift), conditions.spinDrift) { v -> onConditions { it.copy(spinDrift = v) } }
                SwitchRow(stringResource(R.string.label_aero_jump), conditions.aerodynamicJump) { v -> onConditions { it.copy(aerodynamicJump = v) } }
                SwitchRow(stringResource(R.string.label_coriolis), conditions.coriolis) { v -> onConditions { it.copy(coriolis = v) } }
            }

            Section(stringResource(R.string.section_measurements)) {
                TwoOptions(
                    stringResource(R.string.label_pressure),
                    stringResource(R.string.option_qnh), stringResource(R.string.option_station),
                    pressureIsQnh, onPressureIsQnh,
                )
                SwitchRow(stringResource(R.string.label_location_latitude), ui.locationSetsLatitude) { v ->
                    onUi { it.copy(locationSetsLatitude = v) }
                }
                // Pressure source with a Test button that reads it without touching the conditions.
                val sources = PressureSource.entries.filter { it != PressureSource.PHONE || hasBarometer || ui.pressureFrom == it }
                Choice(
                    stringResource(R.string.label_pressure_source), ui.pressureFrom, sources,
                    name = { stringResource(it.label) },
                ) { source -> onUi { it.withPressureFrom(source) } }
                when (ui.pressureFrom) {
                    PressureSource.GARMIN -> WatchPressureButton({ }, Modifier.fillMaxWidth(), stringResource(R.string.action_test_pressure))
                    PressureSource.PHONE -> PhonePressureButton({ }, Modifier.fillMaxWidth(), stringResource(R.string.action_test_pressure))
                    PressureSource.NONE -> Unit
                }
                Choice(
                    stringResource(R.string.label_altitude_source), ui.altitudeFrom, AltitudeSource.entries - AltitudeSource.MANUAL,
                    name = { stringResource(it.label) },
                ) { source -> onUi { it.copy(altitudeSource = source) } }
                TwoOptions(
                    stringResource(R.string.label_forecast_direction),
                    stringResource(R.string.option_clock), stringResource(R.string.option_true),
                    !ui.forecastTrueBearing,
                ) { clock -> onUi { it.copy(forecastTrueBearing = !clock) } }
            }

            Collapsible(stringResource(R.string.section_corrections)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.label_elevation), Modifier.width(CHIP_WIDTH), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.label_windage), Modifier.width(CHIP_WIDTH), style = MaterialTheme.typography.labelMedium)
                }
                Reticles.designs.forEach { r ->
                    val key = Reticles.designKey(r)
                    val c = ui.correction(key)
                    fun set(next: Correction) = onUi { it.copy(corrections = it.corrections + (key to next)) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(r.family), Modifier.weight(1f))
                        DialOrHold(c.dialElevation) { set(c.copy(dialElevation = it)) }
                        DialOrHold(c.dialWindage) { set(c.copy(dialWindage = it)) }
                    }
                }
            }

            Section(stringResource(R.string.section_sync)) {
                if (stealthOn && sync !is SyncState.Unavailable) {
                    Text(stringResource(R.string.sync_paused_stealth), style = MaterialTheme.typography.bodyMedium)
                } else when (sync) {
                    SyncState.Unavailable -> Unit
                    SyncState.SignedOut -> GoogleButton(stringResource(R.string.action_sign_in_google)) {
                        scope.launch {
                            onSignIn(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                        }
                    }
                    is SyncState.SignedIn -> SignedInRow(sync, onSignOut) {
                        scope.launch {
                            val error = onDeleteAccount(context)
                            Toast.makeText(context, error ?: context.getString(R.string.toast_account_deleted), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { exportLauncher.launch("ballistics-${LocalDate.now()}.json") }) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_export), null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_export))
                    }
                    FilledTonalButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_import), null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_import))
                    }
                }
            }

            Section(stringResource(R.string.section_about)) {
                val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_version, version), Modifier.weight(1f))
                    // Store builds are updated by the store.
                    if (BuildConfig.SELF_UPDATE) {
                        FilledTonalButton(onClick = onCheckUpdate) { Text(stringResource(R.string.action_check_update)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { context.findActivity()?.rateApp() }) {
                        Icon(Icons.Filled.Star, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_rate_app))
                    }
                    FilledTonalButton(onClick = { context.shareApp() }) {
                        Icon(Icons.Filled.Share, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_share_app))
                    }
                }
                FilledTonalButton(onClick = { context.openUrl(PRIVACY_POLICY_URL) }) { Text(stringResource(R.string.action_privacy_policy)) }
                if (BuildConfig.SELF_UPDATE) {
                    SwitchRow(stringResource(R.string.label_check_update_on_start), ui.checkUpdateOnStart) { v ->
                        onUi { it.copy(checkUpdateOnStart = v) }
                    }
                }
            }
        }
    }
}

private val CHIP_WIDTH = 80.dp

private const val PRIVACY_POLICY_URL = "https://krzysztofgw-pl.github.io/ballistics-calculator/privacy.html"

private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** The icon-only Google button: a white circle with the Google "G" in its own colours. */
@Composable
private fun GoogleButton(description: String, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFF747775)),
    ) {
        Image(
            painterResource(R.drawable.ic_google),
            contentDescription = description,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Signed in: whether the cloud copy is in sync, and the Google button, which asks before
 * signing out of the account and offers deleting it there too. Deleting the account removes
 * the cloud copy and the sign-in; the profiles on this phone stay.
 */
@Composable
private fun SignedInRow(sync: SyncState.SignedIn, onSignOut: () -> Unit, onDelete: () -> Unit) {
    val failed = sync.error != null
    var confirm by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val account = sync.email ?: stringResource(R.string.sync_signed_in)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            ImageVector.vectorResource(R.drawable.ic_cloud_done),
            contentDescription = stringResource(R.string.sync_signed_in),
            modifier = Modifier.size(32.dp),
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            sync.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        GoogleButton(stringResource(R.string.action_sign_out)) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(R.string.sign_out_prompt, account)) },
            // The dialog lays these out as Delete account · Cancel · Sign out, wrapping on narrow screens.
            dismissButton = {
                FilledTonalButton(onClick = { confirm = false; confirmDelete = true }) {
                    Text(stringResource(R.string.action_delete_account), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Row {
                    FilledTonalButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
                    FilledTonalButton(onClick = { confirm = false; onSignOut() }) { Text(stringResource(R.string.action_sign_out)) }
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.action_delete_account)) },
            text = { Text(stringResource(R.string.delete_account_prompt, account)) },
            confirmButton = {
                FilledTonalButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { FilledTonalButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A chip that reads Dial when selected and Hold otherwise; a tap switches it. */
@Composable
private fun DialOrHold(dial: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = dial,
        onClick = { onChange(!dial) },
        label = { Text(stringResource(if (dial) R.string.correction_dial else R.string.correction_hold)) },
        modifier = Modifier.width(CHIP_WIDTH),
    )
}

@Composable
private fun Placement(label: String, atTop: Boolean, onChange: (Boolean) -> Unit) =
    TwoOptions(label, stringResource(R.string.placement_top), stringResource(R.string.placement_bottom), atTop, onChange)
