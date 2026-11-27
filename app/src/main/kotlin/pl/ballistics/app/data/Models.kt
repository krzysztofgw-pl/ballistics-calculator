package pl.ballistics.app.data

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import pl.ballistics.app.R
import pl.ballistics.app.toNumber
import pl.ballistics.core.Turret
import pl.ballistics.core.Units
import java.util.UUID

enum class FocalPlane(@StringRes val label: Int) {
    FFP(R.string.focal_plane_ffp),
    SFP(R.string.focal_plane_sfp),
}

/** A reticle's unit, and how a turret unit is stored (see [TurretUnit]). */
enum class AngularUnit(val label: String, val radians: Double) {
    MRAD("MRAD", Units.MRAD),
    MOA("MOA", Units.MOA),
}

/**
 * The unit marked on a scope's turrets. SMOA (shooter's MOA, 1 in at 100 yd) is stored as
 * [AngularUnit.MOA] plus [WeaponProfile.inchMoa], so older versions still read the profile.
 * [decimals] is how finely angles in this unit are shown.
 */
enum class TurretUnit(val label: String, val radians: Double, val reticleUnit: AngularUnit, val defaultClick: String, val decimals: Int) {
    MRAD("MRAD", Units.MRAD, AngularUnit.MRAD, "0.1", 2),
    MOA("MOA", Units.MOA, AngularUnit.MOA, "0.25", 1),
    SMOA("SMOA", Units.SMOA, AngularUnit.MOA, "0.25", 1),
}

/** G1…RA4 map to the standard drag functions; CUSTOM uses a pasted Cd(Mach) curve. */
enum class DragType { G1, G7, G2, G5, G6, G8, GI, GS, RA4, CUSTOM }

fun newId(): String = UUID.randomUUID().toString()

/** A turret correction as typed; blank, unreadable or not positive is 1. */
private fun factor(text: String): Double = text.toNumber()?.takeIf { it > 0 } ?: 1.0

/** Typical change of muzzle velocity with powder temperature, used when a load gives none. */
const val STANDARD_POWDER_SENSITIVITY = 1.0

/*
 * Numeric fields are kept as text so the editors can hold half-typed values
 * ("0," while typing "0,5"). They are parsed by Calculator.
 */

/** A rifle with its scope and mount; the zero belongs here because it depends on all three. */
@Serializable
data class WeaponProfile(
    val id: String = newId(),
    val name: String = "New rifle",
    val caliber: String = "",
    val twistIn: String = "10",
    val rightHandTwist: Boolean = true,
    /** Stored in cm whatever unit the editor shows. */
    val barrelLengthCm: String = "",
    /** The editor shows the barrel length in inches; off, in cm. */
    val barrelInches: Boolean = true,

    val scopeName: String = "",
    /** The turret unit as stored; read it through [turretUnit]. */
    val angularUnit: AngularUnit = AngularUnit.MRAD,
    /** With [angularUnit] MOA: the turrets are in shooter's MOA (1 in at 100 yd). */
    val inchMoa: Boolean = false,
    /** The elevation click, and the windage one unless [separateWindage]. */
    val clickValue: String = "0.1",
    /** Turret correction: what the elevation turret really moves per marked unit, measured ÷ dialled; blank is 1. */
    val elevationFactor: String = "",
    /** The windage turret has its own click value and correction. */
    val separateWindage: Boolean = false,
    /** Blank uses [clickValue]. */
    val windageClickValue: String = "",
    /** Like [elevationFactor], for the windage turret; blank is 1. */
    val windageFactor: String = "",
    val sightHeightCm: String = "4.5",
    val reticleId: String = "mrad-hash",
    /** The finest step of the scope's reticle scale, in the reticle's unit; blank uses the reticle's own. */
    val reticleGraduation: String = "",
    val focalPlane: FocalPlane = FocalPlane.FFP,
    /** SFP only: magnification at which the reticle subtensions are true. */
    val calibratedMagnification: String = "10",
    val minMagnification: String = "1",
    val maxMagnification: String = "25",

    val zeroRangeM: String = "100",
    val zeroTemperatureC: String = "15",
    val zeroPressureHpa: String = "1013.25",
    /** The zero pressure is QNH, reduced to station pressure with [zeroAltitudeM]; off, it is station pressure. */
    val zeroPressureIsQnh: Boolean = false,
    val zeroAltitudeM: String = "0",

    /** The rifle has a suppressor; the rest of these fields count only then. */
    val suppressor: Boolean = false,
    /** The suppressor is on now. */
    val suppressorMounted: Boolean = true,
    /** The zero was shot with the suppressor on. */
    val zeroSuppressed: Boolean = true,
    /** Where the impact goes in the state the rifle was not zeroed in, against the zero, in [turretUnit]: up and right positive. */
    val suppressorShiftUp: String = "",
    val suppressorShiftRight: String = "",

    /** Ammunition this rifle is zeroed with. */
    val ammoId: String? = null,
    /** Extreme spread of a 5-shot group in MOA; used for the hit probability. */
    val groupMoa: String = "",
    /** Muzzle velocity measured with this rifle, by ammo id; it overrides the load's reference velocity. */
    val muzzleVelocities: Map<String, String> = emptyMap(),
) {
    /** Impact shift (up, right) in [turretUnit] against the zero while the suppressor is not as zeroed; null when it is. */
    fun suppressorShift(): Pair<Double, Double>? {
        if (!suppressor || suppressorMounted == zeroSuppressed) return null
        val up = suppressorShiftUp.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        val right = suppressorShiftRight.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        return if (up == 0.0 && right == 0.0) null else Pair(up, right)
    }

    /** Zeroing in the other state: the shift is now measured from there, so it changes sign. */
    fun withZeroSuppressed(suppressed: Boolean): WeaponProfile {
        if (suppressed == zeroSuppressed) return this
        fun flip(v: String) = v.trim().let { if (it.isEmpty() || it.trimStart('-').replace(',', '.').toDoubleOrNull() == 0.0) it else if (it.startsWith("-")) it.removePrefix("-") else "-$it" }
        return copy(zeroSuppressed = suppressed, suppressorShiftUp = flip(suppressorShiftUp), suppressorShiftRight = flip(suppressorShiftRight))
    }

    val turretUnit: TurretUnit
        get() = when {
            angularUnit == AngularUnit.MRAD -> TurretUnit.MRAD
            inchMoa -> TurretUnit.SMOA
            else -> TurretUnit.MOA
        }

    fun withTurretUnit(unit: TurretUnit): WeaponProfile = copy(angularUnit = unit.reticleUnit, inchMoa = unit == TurretUnit.SMOA)

    val elevationTurret: Turret
        get() = Turret(turretUnit.radians, clickValue.toNumber()?.takeIf { it > 0 }, factor(elevationFactor))

    val windageTurret: Turret
        get() = if (!separateWindage) elevationTurret else Turret(
            turretUnit.radians,
            (windageClickValue.toNumber() ?: clickValue.toNumber())?.takeIf { it > 0 },
            factor(windageFactor),
        )

    fun measuredVelocity(ammo: AmmoProfile): String? = muzzleVelocities[ammo.id]?.takeIf { it.isNotBlank() }

    /** The velocity the solver uses: measured with this rifle if known, else the load's reference velocity. */
    fun muzzleVelocityFor(ammo: AmmoProfile): String = measuredVelocity(ammo) ?: ammo.muzzleVelocity

    fun withMeasuredVelocity(ammo: AmmoProfile, mps: String): WeaponProfile =
        copy(muzzleVelocities = if (mps.isBlank()) muzzleVelocities - ammo.id else muzzleVelocities + (ammo.id to mps))
}

@Serializable
data class AmmoProfile(
    val id: String = newId(),
    val name: String = "New load",
    /** Cartridge name such as "6.5 Creedmoor"; empty means any cartridge with this bullet diameter. */
    val cartridge: String = "",
    val dragType: DragType = DragType.G7,
    /** BC for [dragType]; a number for one drag model is meaningless for another. */
    val bc: String = "0.25",
    /** BCs entered or loaded for the other drag models, restored when the model is switched back. */
    val bcByModel: Map<DragType, String> = emptyMap(),
    val customCurve: String = "",
    val massGr: String = "175",
    val diameterMm: String = "7.82",
    val lengthMm: String = "",
    /** Reference velocity: from the box, or a chronograph, in a barrel of [testBarrelCm]. */
    val muzzleVelocity: String = "800",
    val testBarrelCm: String = "",
    /** m/s per °C; empty (or the old default 0) uses [STANDARD_POWDER_SENSITIVITY]. */
    val powderSensitivity: String = "",
    /** Temperature the reference velocity was measured at; factory data is for 21 °C (SAAMI, CIP). */
    val powderReferenceTemp: String = "21",
    /** Shot-to-shot standard deviation of the muzzle velocity; used for the hit probability. */
    val velocitySd: String = "",
    val notes: String = "",
)

/**
 * Switches the drag model without carrying the BC across: a G7 BC read as G1
 * (or the other way round) would be off by a factor of about two. The BC known
 * for the new model is restored, otherwise the field is left empty to be filled in.
 */
fun AmmoProfile.withDragType(type: DragType): AmmoProfile {
    if (type == dragType) return this
    val known = if (dragType == DragType.CUSTOM || bc.isBlank()) bcByModel else bcByModel + (dragType to bc)
    return copy(dragType = type, bcByModel = known, bc = known[type].orEmpty())
}

/** Conditions at the firing point and table settings, shared by all profiles. */
@Serializable
data class Conditions(
    val temperatureC: String = "15",
    val pressureHpa: String = "1013.25",
    val pressureIsQnh: Boolean = true,
    val altitudeM: String = "0",
    val humidityPct: String = "50",
    val windSpeed: String = "0",
    /** Legacy clock direction, read only when [windFromDeg] is not set. */
    val windClock: String = "3",
    /** Where the wind blows from, degrees clockwise from the line of fire (0 = from the target). */
    val windFromDeg: String? = null,
    /** The wind dial is shown north-up, as in weather forecasts; the stored value stays relative. */
    val windDialNorthUp: Boolean = false,
    val lookAngleDeg: String = "0",
    val cantDeg: String = "0",
    val coriolis: Boolean = false,
    val latitudeDeg: String = "52",
    val azimuthDeg: String = "0",
    val spinDrift: Boolean = true,
    val aerodynamicJump: Boolean = true,
    val maxRangeM: String = "1000",
    val stepM: String = "50",
    /** The distance picked on the target screen; blank picks the middle of the table. */
    val targetRangeM: String = "",
    /** Moving target: its speed and the direction it moves, degrees from the line of fire (90 = to the right). */
    val targetMoving: Boolean = false,
    val targetSpeedMps: String = "0",
    val targetDirectionDeg: String = "90",
    val targetWidthCm: String = "50",
    val targetHeightCm: String = "50",
    /** Lightest and strongest wind expected; the forecast fills them in. Blank means not given. */
    val windMinMps: String = "",
    val windMaxMps: String = "",
    /** The forecast's swing of the wind direction, degrees from the line of fire like [windFromDeg]; blank when not given. */
    val windFromMinDeg: String = "",
    val windFromMaxDeg: String = "",
    /** How well the wind and the distance are known (1 σ), for the hit probability. */
    val windUncertaintyMps: String = "1",
    val rangeUncertaintyM: String = "5",
) {
    /** Everything the table depends on; the target settings are solved on the target screen. */
    fun withoutTarget(): Conditions {
        val d = Conditions()
        return copy(
            targetRangeM = d.targetRangeM, targetMoving = d.targetMoving, targetSpeedMps = d.targetSpeedMps, targetDirectionDeg = d.targetDirectionDeg,
            targetWidthCm = d.targetWidthCm, targetHeightCm = d.targetHeightCm, windMinMps = d.windMinMps,
            windMaxMps = d.windMaxMps, windFromMinDeg = d.windFromMinDeg, windFromMaxDeg = d.windFromMaxDeg, windUncertaintyMps = d.windUncertaintyMps, rangeUncertaintyM = d.rangeUncertaintyM,
        )
    }

    /** The conditions with [t]'s distance, angles, motion and size. */
    fun withTarget(t: SavedTarget) = copy(
        targetRangeM = t.rangeM, lookAngleDeg = t.lookAngleDeg, azimuthDeg = t.azimuthDeg,
        targetMoving = t.moving, targetSpeedMps = t.speedMps, targetDirectionDeg = t.directionDeg, targetWidthCm = t.widthCm, targetHeightCm = t.heightCm,
    )

    /** Wind direction in degrees from the line of fire, converted from the clock for older data. */
    val windDirectionDeg: String
        get() = windFromDeg ?: windClock.toNumber()?.let { (it * 30).let { d -> if (d % 1.0 == 0.0) d.toInt().toString() else d.toString() } } ?: windClock
}

/** A target on the range card: where it is and how it moves, recalled from a sub-tab of the target screen. */
@Serializable
data class SavedTarget(
    val id: String = newId(),
    val name: String = "",
    val rangeM: String = "",
    val lookAngleDeg: String = "0",
    val azimuthDeg: String = "0",
    val moving: Boolean = false,
    val speedMps: String = "0",
    val directionDeg: String = "90",
    val widthCm: String = "50",
    val heightCm: String = "50",
) {
    /** This target with the values currently set in [c]. */
    fun from(c: Conditions) = copy(
        rangeM = c.targetRangeM, lookAngleDeg = c.lookAngleDeg, azimuthDeg = c.azimuthDeg,
        moving = c.targetMoving, speedMps = c.targetSpeedMps, directionDeg = c.targetDirectionDeg, widthCm = c.targetWidthCm, heightCm = c.targetHeightCm,
    )
}

enum class ThemeMode(@StringRes val label: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
}

/** How the app looks; synced with the profiles. */
@Serializable
data class UiSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val tabsAtTop: Boolean = false,
    val subTabsAtTop: Boolean = false,
    val keepScreenOn: Boolean = false,
    /** Charts in the target tools and the Charts card; off saves the work of drawing them. */
    val showCharts: Boolean = true,
    /** Single charts turned off under [showCharts]. */
    val hiddenCharts: Set<ChartKind> = emptySet(),
    /** A location fix (forecast, compass) also sets the latitude for Coriolis. */
    val locationSetsLatitude: Boolean = true,
    /** Older setting: the forecast set the altitude. Read only to choose [altitudeFrom] for settings saved before [altitudeSource]. */
    val locationSetsAltitude: Boolean = false,
    /** Where the altitude comes from; null in settings saved before there was a choice. */
    val altitudeSource: AltitudeSource? = null,
    /** The forecast shows wind directions as true bearings, north up; otherwise relative to the line of fire, as a clock. */
    val forecastTrueBearing: Boolean = false,
    /** Offer reading the station pressure from a Garmin watch on the conditions screen. */
    val garminWatch: Boolean = false,
    /** Look for a newer release each time the app starts. */
    val checkUpdateOnStart: Boolean = false,
    /** Per reticle design (Reticles.designKey): which corrections the target screen advises dialing; the rest are held. */
    val corrections: Map<String, Correction> = emptyMap(),
) {
    /** The charts to draw (and work out). */
    val charts: Set<ChartKind> get() = if (showCharts) ChartKind.entries.toSet() - hiddenCharts else emptySet()

    fun correction(reticleId: String): Correction = corrections[reticleId] ?: Correction.defaultFor(reticleId)

    val altitudeFrom: AltitudeSource
        get() = altitudeSource ?: if (locationSetsAltitude) AltitudeSource.TERRAIN else AltitudeSource.MANUAL
}

/** The charts on the target screen, each of which can be turned off in Settings. */
enum class ChartKind(@StringRes val label: Int) {
    BRACKET_DIRECTION(R.string.chart_kind_bracket_direction),
    RANGE_ERROR(R.string.chart_kind_range_error),
    HIT_RANGE(R.string.chart_kind_hit_range),
    WIND_SPEED(R.string.chart_kind_wind_speed),
    WIND_DIRECTION(R.string.chart_kind_wind_direction),
    SPIN_DRIFT(R.string.chart_kind_spin_drift),
    AZIMUTH_WINDAGE(R.string.chart_kind_azimuth_windage),
    AZIMUTH_ELEVATION(R.string.chart_kind_azimuth_elevation),
    ;

    companion object {
        /** The charts on the Charts card; the others sit in their tools. */
        val card = setOf(WIND_SPEED, WIND_DIRECTION, SPIN_DRIFT, AZIMUTH_WINDAGE, AZIMUTH_ELEVATION)
    }
}

/** Where the altitude of the firing point comes from. */
enum class AltitudeSource(@StringRes val label: Int) {
    /** Typed in. */
    MANUAL(R.string.altitude_manual),
    /** Terrain height at the location from an elevation model: GUGiK's 1 m lidar model in Poland, Copernicus elsewhere. Needs the network. */
    TERRAIN(R.string.altitude_terrain),
    /** The phone's own GPS, converted to height above sea level; works offline. */
    GPS(R.string.altitude_gps),
}

/** Whether to dial each axis on the turrets or hold it on the reticle. */
@Serializable
data class Correction(val dialElevation: Boolean = false, val dialWindage: Boolean = false) {
    companion object {
        /** Tree reticles are made for holding both; on the others elevation is dialed and wind held. */
        fun defaultFor(reticleId: String) = if (reticleId.endsWith("-tree")) Correction() else Correction(dialElevation = true)
    }
}

/** Everything the app stores; also the export/import file format. */
@Serializable
data class AppData(
    val version: Int = 1,
    val weapons: List<WeaponProfile> = emptyList(),
    val ammo: List<AmmoProfile> = emptyList(),
    val activeWeaponId: String? = null,
    val conditions: Conditions = Conditions(),
    val targets: List<SavedTarget> = emptyList(),
    /** The range-card target being edited; changes on the target screen are saved to it. */
    val activeTargetId: String? = null,
    val ui: UiSettings = UiSettings(),
    /** Wall-clock time of the last local edit; the newer copy wins when syncing. */
    val modifiedAt: Long = 0,
) {
    val activeWeapon: WeaponProfile? get() = weapons.find { it.id == activeWeaponId } ?: weapons.firstOrNull()

    fun ammoFor(weapon: WeaponProfile): AmmoProfile? = ammo.find { it.id == weapon.ammoId }

    /**
     * The first cloud copy after signing in on this phone. Last-write-wins would let
     * whichever side was touched last replace the other, so a fresh install with the
     * sample rifle could wipe the profiles in the cloud. Instead rifles, loads and saved
     * targets from both sides are kept (the cloud copy wins for the same id), the
     * untouched sample is dropped, and the cloud's settings are taken.
     */
    fun mergedWithCloud(cloud: AppData, now: Long): AppData {
        fun <T> union(remote: List<T>, local: List<T>, id: (T) -> String, isSample: (T) -> Boolean): List<T> {
            val ids = remote.map(id).toSet()
            return remote + local.filter { id(it) !in ids && !isSample(it) }
        }
        val sample = sample()
        val sampleWeapon = sample.weapons.single().copy(id = "", ammoId = null)
        val sampleAmmo = sample.ammo.single().copy(id = "")
        val weapons = union(cloud.weapons, this.weapons, { it.id }, { it.copy(id = "", ammoId = null) == sampleWeapon })
        val ammo = union(cloud.ammo, this.ammo, { it.id }, { it.copy(id = "") == sampleAmmo })
        val targets = union(cloud.targets, this.targets, { it.id }, { false })
        if (weapons == cloud.weapons && ammo == cloud.ammo && targets == cloud.targets) return cloud
        val ammoIds = ammo.map { it.id }.toSet()
        return cloud.copy(
            // A sample rifle that was kept would still point at the dropped sample load.
            weapons = weapons.map { if (it.ammoId != null && it.ammoId !in ammoIds) it.copy(ammoId = null) else it },
            ammo = ammo,
            targets = targets,
            activeWeaponId = cloud.activeWeaponId?.takeIf { id -> weapons.any { it.id == id } } ?: weapons.firstOrNull()?.id,
            modifiedAt = now,
        )
    }

    companion object {
        fun sample(): AppData {
            val ammo = AmmoProfile(
                name = "Sierra MatchKing 175 gr",
                dragType = DragType.G7,
                bc = "0.243",
                massGr = "175",
                diameterMm = "7.82",
                lengthMm = "31.5",
                muzzleVelocity = "790",
            )
            val weapon = WeaponProfile(name = ".308 rifle", caliber = ".308 Win", ammoId = ammo.id)
            return AppData(weapons = listOf(weapon), ammo = listOf(ammo), activeWeaponId = weapon.id)
        }
    }
}
