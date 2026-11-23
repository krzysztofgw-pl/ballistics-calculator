package pl.ballistics.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.ballistics.app.R
import pl.ballistics.app.data.AltitudeSource
import pl.ballistics.app.data.Conditions
import pl.ballistics.app.data.Stealth
import pl.ballistics.app.data.TerrainHeight
import pl.ballistics.app.data.UiSettings
import pl.ballistics.app.toNumber
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun ConditionsScreen(
    i: Conditions,
    ui: UiSettings,
    update: ((Conditions) -> Conditions) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Wind changes most, so it comes first.
        Section(stringResource(R.string.section_wind)) {
            NumberField(stringResource(R.string.label_wind_speed), i.windSpeed, "m/s") { v -> update { it.copy(windSpeed = v) } }
            WindAndAzimuthDial(i, ui, update)
        }

        Section(stringResource(R.string.section_atmosphere)) {
            FieldRow { m ->
                NumberField(stringResource(R.string.label_temperature), i.temperatureC, "°C", m) { v -> update { it.copy(temperatureC = v) } }
                NumberField(stringResource(R.string.label_humidity), i.humidityPct, "%", m) { v -> update { it.copy(humidityPct = v) } }
            }
            FieldRow { m ->
                NumberField(
                    stringResource(if (i.pressureIsQnh) R.string.label_pressure_qnh else R.string.label_pressure_station),
                    i.pressureHpa, "hPa", m,
                ) { v -> update { it.copy(pressureHpa = v) } }
                NumberField(stringResource(R.string.label_altitude), i.altitudeM, stringResource(R.string.unit_m_asl), m) { v ->
                    update { it.copy(altitudeM = v) }
                }
            }
            if (ui.altitudeFrom != AltitudeSource.MANUAL) AltitudeButton(ui.altitudeFrom, update)
            if (ui.garminWatch) WatchPressureButton(update)
        }

        Section(stringResource(R.string.section_shot)) {
            // Shot angle and cant at equal width, with the camera between them.
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.label_look_angle), i.lookAngleDeg, "°", Modifier.weight(1f)) { v ->
                    update { it.copy(lookAngleDeg = v) }
                }
                ShotAngleButton { deg -> update { it.copy(lookAngleDeg = String.format(Locale.US, "%.1f", deg)) } }
                NumberField(stringResource(R.string.label_cant), i.cantDeg, "°", Modifier.weight(1f)) { v -> update { it.copy(cantDeg = v) } }
            }
        }

        Section(stringResource(R.string.section_advanced)) {
            SwitchRow(stringResource(R.string.label_spin_drift), i.spinDrift) { v -> update { it.copy(spinDrift = v) } }
            SwitchRow(stringResource(R.string.label_aero_jump), i.aerodynamicJump) { v -> update { it.copy(aerodynamicJump = v) } }
            SwitchRow(stringResource(R.string.label_coriolis), i.coriolis) { v -> update { it.copy(coriolis = v) } }
            if (i.coriolis) LatitudeInput(i, update)
        }
    }
}

private fun deg(v: Double): String = v.roundToInt().mod(360).toString()

private fun clockHour(deg: Double): String = clockText(deg, halfHours = true)

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

/** A small label and its value on a rounded chip, for read-only figures under the dial. */
@Composable
private fun ValuePill(label: String, value: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * One dial for the wind and the direction of fire: a wind clock inside a compass ring. In the shooter
 * view the line of fire points up and the ring turns; north up, the ring stays and the clock turns.
 * Moving the azimuth keeps the wind where it is in the frame shown: its true bearing north-up, its
 * clock direction otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindAndAzimuthDial(i: Conditions, ui: UiSettings, update: ((Conditions) -> Conditions) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val withPermission = rememberWithLocationPermission()
    var status by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    var forecast by remember { mutableStateOf(false) }

    val relative = i.windDirectionDeg.toNumber()
    val azimuth = i.azimuthDeg.toNumber() ?: 0.0
    val bearing = relative?.let { (it + azimuth).mod(360.0) }
    val trueText = bearing?.let { "${deg(it)}° ${compassPoint(it)}" }
    val clock = relative?.let { clockHour(it) }

    WindDial(
        relativeDeg = relative,
        azimuthDeg = azimuth,
        northUp = i.windDialNorthUp,
        onWind = { v -> update { it.copy(windFromDeg = deg(v)) } },
        onAzimuth = { v ->
            update { c ->
                if (c.windDialNorthUp) {
                    val keep = c.windDirectionDeg.toNumber()?.let { it + (c.azimuthDeg.toNumber() ?: 0.0) }
                    c.copy(azimuthDeg = deg(v), windFromDeg = keep?.let { deg(it - v) } ?: c.windFromDeg)
                } else {
                    c.copy(azimuthDeg = deg(v))
                }
            }
        },
        onNorthUp = { v -> update { it.copy(windDialNorthUp = v) } },
        centerText = (if (i.windDialNorthUp) bearing?.let { "${deg(it)}°" } else clock) ?: "–",
        centerSubText = (if (i.windDialNorthUp) bearing?.let { "${compassPoint(it)} · $clock" } else trueText) ?: "",
        northButtonDescription = stringResource(if (i.windDialNorthUp) R.string.dial_shooter_view else R.string.label_north_up),
        modifier = Modifier.fillMaxWidth(),
    )

    // What the wind does to the shot: across the line of fire and along it.
    val speed = i.windSpeed.toNumber()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ValuePill(stringResource(R.string.label_azimuth_short), "${deg(azimuth)}°")
        if (relative != null && speed != null && speed > 0) {
            val a = Math.toRadians(relative)
            val cross = speed * sin(a)
            val along = speed * cos(a)
            if (abs(cross) >= 0.05) {
                ValuePill(
                    stringResource(R.string.label_crosswind),
                    stringResource(if (cross < 0) R.string.crosswind_from_left else R.string.crosswind_from_right, one(abs(cross))),
                )
            }
            if (abs(along) >= 0.05) {
                ValuePill(stringResource(if (along > 0) R.string.label_headwind else R.string.label_tailwind), stringResource(R.string.value_mps, one(abs(along))))
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = {
            if (Stealth.blocksNetwork) Toast.makeText(context, context.getString(R.string.stealth_blocked), Toast.LENGTH_LONG).show()
            else withPermission { forecast = true }
        }, modifier = Modifier.weight(1f)) {
            Icon(ImageVector.vectorResource(R.drawable.ic_forecast), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.action_forecast_short), maxLines = 1)
        }
        FilledTonalButton(modifier = Modifier.weight(1f), onClick = {
            withPermission {
                scope.launch {
                    warning = null
                    status = context.getString(R.string.compass_reading)
                    val reading = DeviceSensors.magneticHeading(context)
                    if (reading == null) {
                        status = context.getString(R.string.compass_unavailable)
                        return@launch
                    }
                    val location = DeviceSensors.lastKnown(context)
                    val magnetic = reading.headingDeg
                    if (location != null) {
                        val declination = DeviceSensors.declinationDeg(location)
                        val heading = magnetic + declination
                        update { c ->
                            val lat = String.format(Locale.US, "%.2f", location.latitude)
                            c.copy(azimuthDeg = deg(heading)).let { if (ui.locationSetsLatitude) it.copy(latitudeDeg = lat) else it }
                        }
                        status = context.getString(R.string.compass_true, deg(heading), String.format(Locale.US, "%+.1f", declination))
                    } else {
                        update { it.copy(azimuthDeg = deg(magnetic)) }
                        status = context.getString(R.string.compass_magnetic, deg(magnetic))
                    }
                    val expected = location?.let { DeviceSensors.expectedFieldMicroTesla(it) }
                    warning = when (reading.problem(expected)) {
                        DeviceSensors.CompassProblem.UNCALIBRATED -> context.getString(R.string.compass_uncalibrated)
                        DeviceSensors.CompassProblem.INACCURATE ->
                            context.getString(R.string.compass_inaccurate, reading.headingAccuracyDeg?.roundToInt() ?: 0)
                        DeviceSensors.CompassProblem.DISTURBED -> context.getString(
                            R.string.compass_disturbed,
                            reading.fieldMicroTesla?.roundToInt() ?: 0,
                            expected?.roundToInt() ?: 0,
                        )
                        null -> null
                    }
                }
            }
        }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_compass), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.action_compass_short), maxLines = 1)
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    if (forecast) ForecastDialog(i, ui, update) { forecast = false }
}

/** Runs [action] once location permission has been asked for (granted or not). */
@Composable
private fun rememberWithLocationPermission(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.invoke()
        pending = null
    }
    return { action ->
        if (DeviceSensors.hasLocationPermission(context)) {
            action()
        } else {
            pending = action
            launcher.launch(DeviceSensors.locationPermissions)
        }
    }
}

/**
 * Fills the altitude from the terrain map (an elevation model at the phone's position) or from GPS.
 * The map needs the network, so in stealth mode it falls back to GPS, which works offline.
 * Beside the button: where the value came from, or the GPS vertical accuracy.
 */
@Composable
private fun AltitudeButton(source: AltitudeSource, update: ((Conditions) -> Conditions) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val withPermission = rememberWithLocationPermission()
    var reading by remember { mutableStateOf(false) }
    var note by remember(source) { mutableStateOf<String?>(null) }
    fun toast(id: Int) = Toast.makeText(context, context.getString(id), Toast.LENGTH_LONG).show()
    fun store(metres: Double) = update { it.copy(altitudeM = metres.roundToInt().toString()) }

    suspend fun fromGps() {
        toast(R.string.altitude_gps_searching)
        val gps = DeviceSensors.gpsAltitude(context)
        if (gps == null) {
            toast(R.string.altitude_gps_unavailable)
            return
        }
        store(gps.metres)
        note = gps.accuracyM?.let { context.getString(R.string.location_accuracy, it.roundToInt()) }
        if (gps.ellipsoidal) toast(R.string.altitude_gps_ellipsoid)
    }

    suspend fun fromTerrain() {
        val location = DeviceSensors.location(context)
        if (location == null) {
            toast(R.string.location_unavailable)
            return
        }
        try {
            val r = TerrainHeight.lookup(location.latitude, location.longitude)
            store(r.heightM)
            note = context.getString(if (r.source == TerrainHeight.Source.NMT_PL) R.string.altitude_source_nmt else R.string.altitude_source_dem)
        } catch (e: java.io.IOException) {
            Toast.makeText(context, context.getString(R.string.altitude_terrain_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        FilledTonalButton(enabled = !reading, contentPadding = PaddingValues(horizontal = 12.dp), onClick = {
            if (Stealth.blocksLocation && (source == AltitudeSource.GPS || Stealth.blocksNetwork)) {
                toast(R.string.stealth_location_blocked)
                return@FilledTonalButton
            }
            withPermission {
                scope.launch {
                    reading = true
                    note = null
                    if (source == AltitudeSource.GPS) {
                        fromGps()
                    } else if (Stealth.blocksNetwork) {
                        toast(R.string.altitude_stealth_gps)
                        fromGps()
                    } else {
                        fromTerrain()
                    }
                    reading = false
                }
            }
        }) {
            if (reading) CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
            else Icon(if (source == AltitudeSource.GPS) Icons.Filled.LocationOn else Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                stringResource(if (source == AltitudeSource.GPS) R.string.action_altitude_gps_short else R.string.action_altitude_terrain_short),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LatitudeInput(i: Conditions, update: ((Conditions) -> Conditions) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val withPermission = rememberWithLocationPermission()
    var reading by remember { mutableStateOf(false) }
    var accuracyM by remember { mutableStateOf<Int?>(null) }

    // The field holds the size of the latitude and its unit the hemisphere (tap to switch), so south needs no minus key.
    val raw = i.latitudeDeg.trim()
    var south by remember { mutableStateOf(raw.startsWith("-")) }
    LaunchedEffect(raw) { if (raw.isNotEmpty() && raw != "-") south = raw.startsWith("-") }
    fun store(size: String, isSouth: Boolean) {
        south = isSouth
        update { it.copy(latitudeDeg = if (isSouth && size.isNotBlank()) "-$size" else size) }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumberField(
            stringResource(R.string.label_latitude), raw.removePrefix("-"),
            "°" + stringResource(if (south) R.string.hemisphere_south else R.string.hemisphere_north), Modifier.weight(1f),
            onUnitClick = { accuracyM = null; store(raw.removePrefix("-"), !south) },
        ) { v ->
            accuracyM = null
            if (v.startsWith("-")) store(v.removePrefix("-"), true) else store(v, south)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalButton(enabled = !reading, contentPadding = PaddingValues(horizontal = 12.dp), onClick = {
                if (Stealth.blocksLocation) {
                    Toast.makeText(context, context.getString(R.string.stealth_location_blocked), Toast.LENGTH_LONG).show()
                    return@FilledTonalButton
                }
                withPermission {
                    scope.launch {
                        reading = true
                        val location = DeviceSensors.location(context)
                        reading = false
                        if (location == null) {
                            accuracyM = null
                            Toast.makeText(context, context.getString(R.string.location_unavailable), Toast.LENGTH_LONG).show()
                        } else {
                            val size = String.format(Locale.US, "%.2f", abs(location.latitude))
                            store(size, location.latitude < 0)
                            accuracyM = location.accuracy.roundToInt()
                        }
                    }
                }
            }) {
                if (reading) CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                else Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_locate_short), maxLines = 1)
            }
            accuracyM?.let { Text(stringResource(R.string.location_accuracy, it), style = MaterialTheme.typography.labelSmall) }
        }
    }
}
