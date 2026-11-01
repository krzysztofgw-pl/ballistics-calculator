package pl.ballistics.app.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.ballistics.app.R
import pl.ballistics.app.data.Correction
import pl.ballistics.app.data.SyncState
import pl.ballistics.app.data.ThemeMode
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
    sync: SyncState,
    onSignIn: suspend (Context) -> String?,
    onSignOut: () -> Unit,
    onExport: () -> String,
    onImport: (String) -> Boolean,
    onCheckUpdate: () -> Unit,
    onOpenProfiles: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        topBar = {
            TopAppBar(
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
            OutlinedButton(onClick = onOpenProfiles, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_profiles))
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
                SwitchRow(stringResource(R.string.label_location_altitude), ui.locationSetsAltitude) { v ->
                    onUi { it.copy(locationSetsAltitude = v) }
                }
            }

            Section(stringResource(R.string.section_devices)) {
                SwitchRow(stringResource(R.string.label_garmin_watch), ui.garminWatch) { v -> onUi { it.copy(garminWatch = v) } }
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
                when (sync) {
                    SyncState.Unavailable -> Unit
                    SyncState.SignedOut -> GoogleSignInButton {
                        scope.launch {
                            onSignIn(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                        }
                    }
                    is SyncState.SignedIn -> SignedInRow(sync, onSignOut)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("ballistics-${LocalDate.now()}.json") }) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_export), null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_export))
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
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
                    OutlinedButton(onClick = onCheckUpdate) { Text(stringResource(R.string.action_check_update)) }
                }
                SwitchRow(stringResource(R.string.label_check_update_on_start), ui.checkUpdateOnStart) { v ->
                    onUi { it.copy(checkUpdateOnStart = v) }
                }
            }
        }
    }
}

private val CHIP_WIDTH = 80.dp

/** The standard Sign in with Google button: white, the Google "G" in its own colours, and the fixed wording. */
@Composable
private fun GoogleSignInButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
        border = BorderStroke(1.dp, Color(0xFF747775)),
    ) {
        Image(painterResource(R.drawable.ic_google), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.action_sign_in_google))
    }
}

/** The signed-in account with a synced (or failed) cloud, and sign-out as an icon. */
@Composable
private fun SignedInRow(sync: SyncState.SignedIn, onSignOut: () -> Unit) {
    val failed = sync.error != null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            ImageVector.vectorResource(R.drawable.ic_cloud_done),
            contentDescription = stringResource(R.string.sync_signed_in),
            modifier = Modifier.size(32.dp),
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(sync.email ?: stringResource(R.string.sync_signed_in), style = MaterialTheme.typography.bodyLarge)
            sync.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        IconButton(onClick = onSignOut) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, stringResource(R.string.action_sign_out))
        }
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
