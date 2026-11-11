package pl.ballistics.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.ballistics.app.MainViewModel
import pl.ballistics.app.R
import pl.ballistics.app.data.SyncState
import pl.ballistics.app.data.AmmoProfile
import pl.ballistics.app.data.WeaponProfile

private data class MainTab(@StringRes val title: Int, val icon: @Composable () -> ImageVector)

private val tabs = listOf(
    MainTab(R.string.tab_rifle) { ImageVector.vectorResource(R.drawable.ic_tab_rifle) },
    MainTab(R.string.tab_conditions) { ImageVector.vectorResource(R.drawable.ic_forecast) },
    MainTab(R.string.tab_table) { Icons.AutoMirrored.Filled.List },
    MainTab(R.string.tab_target) { ImageVector.vectorResource(R.drawable.ic_tab_target) },
)

/** What is open on top of the tabs. */
private sealed interface Editor {
    data class Weapon(val profile: WeaponProfile) : Editor
    data class Ammo(val profile: AmmoProfile) : Editor
    data object Settings : Editor
    data object Help : Editor
    data object Profiles : Editor
}

private const val TARGET_TAB = 3
private const val RIFLE_TAB = 0

/** A tab's icon; while the suppressor is on, the Rifle tab's rifle carries it on the muzzle. */
@Composable
private fun TabIcon(t: MainTab, index: Int, suppressorOn: Boolean) {
    if (index == RIFLE_TAB && suppressorOn) {
        Icon(ImageVector.vectorResource(R.drawable.ic_tab_rifle_suppressed), stringResource(R.string.label_suppressor_on))
    } else {
        Icon(t.icon(), contentDescription = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(2) }
    var editor by remember { mutableStateOf<Editor?>(null) }
    // A rifle or load opened from the profile list goes back to the list when closed.
    var fromProfiles by remember { mutableStateOf(false) }
    val closeEditor = { editor = if (fromProfiles) Editor.Profiles else null }
    // The profile list goes back to where it was opened: Settings or the Rifle tab.
    var profilesReturn by remember { mutableStateOf<Editor?>(null) }
    val saved = rememberSaveableStateHolder()
    val updater = rememberAppUpdater()
    // Keyed on the setting, so it still runs when the saved settings load after the first frame.
    LaunchedEffect(data.ui.checkUpdateOnStart) { if (data.ui.checkUpdateOnStart) updater.checkOnStart() }
    AppUpdateDialogs(updater)

    when (val e = editor) {
        is Editor.Weapon -> {
            BackHandler { closeEditor() }
            WeaponEditor(
                initial = e.profile,
                ammo = data.ammo,
                isNew = data.weapons.none { it.id == e.profile.id },
                onSave = { vm.saveWeapon(it); closeEditor() },
                onDelete = { vm.deleteWeapon(e.profile.id); closeEditor() },
                onClose = { closeEditor() },
            )
            return
        }
        is Editor.Ammo -> {
            BackHandler { closeEditor() }
            AmmoEditor(
                initial = e.profile,
                weapons = data.weapons,
                isNew = data.ammo.none { it.id == e.profile.id },
                onSave = { vm.saveAmmo(it); closeEditor() },
                onDelete = { vm.deleteAmmo(e.profile.id); closeEditor() },
                onClose = { closeEditor() },
                signedIn = sync is SyncState.SignedIn,
                onSuggest = vm::suggestBullet,
            )
            return
        }
        Editor.Profiles -> {
            BackHandler { fromProfiles = false; editor = profilesReturn }
            ProfilesListScreen(
                data = data,
                onSelectWeapon = vm::selectWeapon,
                onEditWeapon = { fromProfiles = true; editor = Editor.Weapon(it) },
                onEditAmmo = { fromProfiles = true; editor = Editor.Ammo(it) },
                onClose = { fromProfiles = false; editor = profilesReturn },
            )
            return
        }
        Editor.Help -> {
            BackHandler { editor = null }
            HelpScreen(onClose = { editor = null })
            return
        }
        Editor.Settings -> {
            BackHandler { editor = null }
            SettingsScreen(
                ui = data.ui,
                onUi = vm::updateUi,
                pressureIsQnh = data.conditions.pressureIsQnh,
                onPressureIsQnh = { v -> vm.updateConditions { it.copy(pressureIsQnh = v) } },
                sync = sync,
                onSignIn = vm::signIn,
                onSignOut = vm::signOut,
                onDeleteAccount = vm::deleteAccount,
                onExport = vm::exportData,
                onImport = vm::importData,
                onCheckUpdate = { updater.check(asked = true) },
                onOpenProfiles = { fromProfiles = true; profilesReturn = Editor.Settings; editor = Editor.Profiles },
                onClose = { editor = null },
            )
            return
        }
        null -> Unit
    }

    val view = LocalView.current
    DisposableEffect(data.ui.keepScreenOn) {
        view.keepScreenOn = data.ui.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val targetTabs: @Composable () -> Unit = {
        TargetTabs(
            targets = data.targets,
            activeId = data.activeTargetId,
            onSelect = vm::selectTarget,
            onAdd = vm::addTarget,
            onRename = vm::renameTarget,
            onDelete = vm::deleteTarget,
        )
    }

    val suppressorOn = data.activeWeapon?.let { it.suppressor && it.suppressorMounted } == true
    Scaffold(
        modifier = Modifier.lockDialsOnOutsideTap(),
        topBar = {
            // No title bar: the tabs name the screen, and help and settings sit on the Rifle tab.
            Column {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                if (data.ui.tabsAtTop) {
                    TabRow(selectedTabIndex = tab) {
                        tabs.forEachIndexed { index, t ->
                            Tab(
                                selected = tab == index,
                                onClick = { tab = index },
                                text = { Text(stringResource(t.title)) },
                                icon = { TabIcon(t, index, suppressorOn) },
                            )
                        }
                    }
                }
                if (tab == TARGET_TAB && data.ui.subTabsAtTop) targetTabs()
            }
        },
        bottomBar = {
            Column {
                // Sub-tabs sit right above the main tabs, within reach of the thumb.
                if (tab == TARGET_TAB && !data.ui.subTabsAtTop) targetTabs()
                if (!data.ui.tabsAtTop) {
                    NavigationBar {
                        tabs.forEachIndexed { index, t ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { TabIcon(t, index, suppressorOn) },
                                label = { Text(stringResource(t.title)) },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        // Each tab keeps its scroll position while another one is open.
        saved.SaveableStateProvider(tab) {
        when (tab) {
            0 -> RifleScreen(
                data = data,
                onSelectWeapon = vm::selectWeapon,
                onUpdateWeapon = vm::updateActiveWeapon,
                onEditWeapon = { fromProfiles = false; editor = Editor.Weapon(it) },
                onEditAmmo = { fromProfiles = false; editor = Editor.Ammo(it) },
                onOpenProfiles = { fromProfiles = true; profilesReturn = null; editor = Editor.Profiles },
                onOpenHelp = { editor = Editor.Help },
                onOpenSettings = { editor = Editor.Settings },
                modifier = modifier,
            )
            1 -> ConditionsScreen(data.conditions, data.ui, vm::updateConditions, modifier)
            2 -> TableScreen(result, data.conditions, vm::updateConditions, modifier)
            else -> TargetScreen(result, data.conditions, data.ui, vm::updateConditions, vm::saveAmmo, vm::addAmmoCopy, modifier)
        }
        }
    }
}
