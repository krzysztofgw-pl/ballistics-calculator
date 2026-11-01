package pl.ballistics.app.ui

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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import pl.ballistics.app.R
import pl.ballistics.app.data.AmmoProfile
import pl.ballistics.app.data.AppData
import pl.ballistics.app.data.WeaponProfile
import pl.ballistics.app.data.caliberGroup
import pl.ballistics.app.data.diameterInOrMax
import pl.ballistics.app.data.fits
import java.util.Locale
import kotlin.math.abs

/** The rifle and load in use, picked from the profiles, and whether the suppressor is on. */
@Composable
fun RifleScreen(
    data: AppData,
    onSelectWeapon: (String) -> Unit,
    onUpdateWeapon: ((WeaponProfile) -> WeaponProfile) -> Unit,
    onEditWeapon: (WeaponProfile) -> Unit,
    onEditAmmo: (AmmoProfile) -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val w = data.activeWeapon
    val load = w?.let { data.ammoFor(it) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Help and settings live here now that there is no title bar.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onOpenHelp) {
                Icon(ImageVector.vectorResource(R.drawable.ic_help), stringResource(R.string.title_help))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, stringResource(R.string.title_settings))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (w == null) {
                    Text(stringResource(R.string.error_no_weapon))
                } else {
                    Picker(
                        stringResource(R.string.label_rifle), w, data.weapons, { it.name },
                        onSelect = { onSelectWeapon(it.id) },
                        editLabel = stringResource(R.string.action_edit_rifle),
                        onEdit = { onEditWeapon(w) },
                    )
                    val fitting = data.ammo.filter { it.fits(w) }
                    val shown = (fitting + listOfNotNull(load?.takeIf { it !in fitting }))
                        .sortedWith(compareBy({ it.diameterInOrMax() }, { it.caliberGroup() }, { it.name }))
                    val choose = stringResource(R.string.choose)
                    Picker(
                        stringResource(R.string.label_load),
                        load,
                        listOf<AmmoProfile?>(null) + shown,
                        { it?.name ?: choose },
                        onSelect = { a -> onUpdateWeapon { it.copy(ammoId = a?.id) } },
                        editLabel = stringResource(R.string.action_edit_load),
                        onEdit = load?.let { l -> { onEditAmmo(l) } },
                    )
                    Text(summary(w, load, stringResource(R.string.zero_short, w.zeroRangeM)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (w != null && w.suppressor) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onUpdateWeapon { it.copy(suppressorMounted = !it.suppressorMounted) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_suppressor), contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.label_suppressor_on), Modifier.weight(1f))
                        Switch(checked = w.suppressorMounted, onCheckedChange = { v -> onUpdateWeapon { it.copy(suppressorMounted = v) } })
                    }
                    w.suppressorShift()?.let { (up, right) ->
                        Text(
                            stringResource(
                                R.string.suppressor_correction,
                                stringResource(if (up >= 0) R.string.up else R.string.down), abs(up).fmt2(),
                                stringResource(if (right >= 0) R.string.right else R.string.left), abs(right).fmt2(),
                                w.angularUnit.label,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        IconLabelButton(Icons.Filled.Person, stringResource(R.string.action_profiles), onOpenProfiles)
    }
}

/** A full-width dropdown that opens under its field, with a wrench to its right that edits the picked item. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(
    label: String,
    selected: T,
    options: List<T>,
    name: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    editLabel: String,
    onEdit: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = name(selected),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(name(option)) },
                        onClick = { open = false; onSelect(option) },
                    )
                }
            }
        }
        IconButton(onClick = { onEdit?.invoke() }, enabled = onEdit != null) {
            Icon(Icons.Filled.Build, editLabel)
        }
    }
}

@Composable
private fun IconLabelButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label, maxLines = 1)
    }
}

/** Caliber, scope, zero and the muzzle velocity used, in one line. */
private fun summary(w: WeaponProfile, load: AmmoProfile?, zero: String): String = listOfNotNull(
    w.caliber.takeIf { it.isNotBlank() },
    w.scopeName.takeIf { it.isNotBlank() },
    zero,
    load?.let { "${w.muzzleVelocityFor(it)} m/s" },
).joinToString(" · ")

private fun Double.fmt2() = String.format(Locale.getDefault(), "%.2f", this)
