package pl.ballistics.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RifleScreen(
    data: AppData,
    onSelectWeapon: (String) -> Unit,
    onUpdateWeapon: ((WeaponProfile) -> WeaponProfile) -> Unit,
    onEditWeapon: (WeaponProfile) -> Unit,
    onEditAmmo: (AmmoProfile) -> Unit,
    onOpenProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val w = data.activeWeapon
    val load = w?.let { data.ammoFor(it) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (w == null) {
                    Text(stringResource(R.string.error_no_weapon))
                } else {
                    Choice(stringResource(R.string.label_rifle), w, data.weapons, { it.name }) { onSelectWeapon(it.id) }
                    val fitting = data.ammo.filter { it.fits(w) }
                    val shown = (fitting + listOfNotNull(load?.takeIf { it !in fitting }))
                        .sortedWith(compareBy({ it.diameterInOrMax() }, { it.caliberGroup() }, { it.name }))
                    Choice(
                        stringResource(R.string.label_load),
                        load,
                        listOf<AmmoProfile?>(null) + shown,
                        { it?.name ?: stringResource(R.string.choose) },
                    ) { a -> onUpdateWeapon { it.copy(ammoId = a?.id) } }
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

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (w != null) IconLabelButton(Icons.Filled.Edit, stringResource(R.string.action_edit_rifle)) { onEditWeapon(w) }
            if (load != null) IconLabelButton(Icons.Filled.Edit, stringResource(R.string.action_edit_load)) { onEditAmmo(load) }
            IconLabelButton(Icons.Filled.Person, stringResource(R.string.action_profiles), onOpenProfiles)
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
