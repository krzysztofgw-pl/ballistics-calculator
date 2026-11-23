package pl.ballistics.app

import pl.ballistics.app.data.ChartKind
import pl.ballistics.core.TrajectoryPoint
import pl.ballistics.core.Wind
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** What the target screen knows beyond the solver inputs. */
data class TargetInputs(
    val windMinMps: Double? = null,
    val windMaxMps: Double? = null,
    val widthM: Double = 0.5,
    val heightM: Double = 0.5,
    val velocitySdMps: Double = DEFAULT_VELOCITY_SD,
    val windSdMps: Double = 1.0,
    val rangeSdM: Double = 5.0,
    /** Extreme spread of a 5-shot group, as an angle. */
    val groupRad: Double = DEFAULT_GROUP_MOA * pl.ballistics.core.Units.MOA,
    /** The charts to work out the sweeps for; the hidden ones are skipped. */
    val charts: Set<ChartKind> = ChartKind.entries.toSet(),
    /** The farthest distance the hit probability chart goes to. */
    val chartsToM: Double = 1000.0,
) {
    companion object {
        const val DEFAULT_VELOCITY_SD = 8.0
        const val DEFAULT_GROUP_MOA = 1.0
    }
}

/** Vertical miss (m, positive high) when the distance is off by [errorM] and the hold is for the entered one. */
data class RangeMiss(val errorM: Double, val missM: Double)

/**
 * How far the distance may be off and still hit a target of the given height: [shortM] (negative)
 * and [longM]; null when the target is still hit at the largest error checked.
 */
data class RangeTolerance(val misses: List<RangeMiss>, val shortM: Double?, val longM: Double?)

/** Windage corrections for the lightest and the strongest expected wind, from the current direction. */
data class WindBracket(val minMps: Double, val maxMps: Double, val minRad: Double, val maxRad: Double, val fromDeg: Double = 90.0)

/** Lightest and strongest wind corrections by wind direction (degrees from the line of fire), at the chosen distance. */
data class BracketSweep(val light: List<Pair<Double, Double>>, val strong: List<Pair<Double, Double>>)

/**
 * Without wind, at the chosen distance, by azimuth of fire (degrees from true north): the windage correction
 * (spin drift and Coriolis) and how much more the elevation correction is than when shooting north (Eötvös).
 */
data class AzimuthRow(val azimuthDeg: Double, val windageRad: Double, val elevationChangeRad: Double)

/** The shot with the air (and the powder with it) [deltaC] colder or warmer. */
data class TemperatureRow(val deltaC: Double, val temperatureC: Double, val muzzleVelocityMps: Double, val point: TrajectoryPoint)

/**
 * Chance of a first-round hit on a rectangle centred on the hold, from the spread (1 σ) each way;
 * the parts show where the vertical and horizontal spread comes from.
 */
data class HitChance(
    val probability: Double,
    val verticalSdM: Double,
    val horizontalSdM: Double,
    val fromVelocityM: Double,
    val fromRangeM: Double,
    val fromWindM: Double,
    val fromRifleM: Double,
)

/**
 * The shot at one distance under the current conditions, and how it changes with wind, distance and
 * temperature. Corrections are in radians, positive to the right.
 */
data class TargetAnalysis(
    val point: TrajectoryPoint,
    /** Wind direction (degrees from the line of fire) → correction by wind speed. */
    val bySpeed: Map<Double, List<Pair<Double, Double>>>,
    /** Wind speed → correction by wind direction in degrees. */
    val byDirection: Map<Double, List<Pair<Double, Double>>>,
    val tolerance: RangeTolerance,
    val bracket: WindBracket?,
    val temperatures: List<TemperatureRow>,
    val hit: HitChance,
    val bracketSweep: BracketSweep? = null,
    /** Empty without Coriolis (no latitude). */
    val byAzimuth: List<AzimuthRow> = emptyList(),
    /** Distance → chance of a first-round hit. */
    val hitByRange: List<Pair<Double, Double>> = emptyList(),
    /** Vertical miss by distance error, finer than [tolerance]'s rows. */
    val rangeCurve: List<RangeMiss> = emptyList(),
) {
    companion object {
        val SPEEDS = (0..10).map { it.toDouble() }
        /** 9, 10:30, 12, 1:30 and 3 o'clock. */
        val DIRECTIONS = listOf(270.0, 315.0, 0.0, 45.0, 90.0)
        val DIRECTION_SPEEDS = listOf(2.0, 4.0, 6.0, 8.0)
        val SWEEP = (0..24).map { it * 15.0 }
        val RANGE_ERRORS = listOf(-50.0, -25.0, -10.0, 10.0, 25.0, 50.0)
        val TEMPERATURE_STEPS = listOf(-10.0, 0.0, 10.0)
        val RANGE_CURVE = (-10..10).map { it * 5.0 }
        const val HIT_SAMPLES = 16
        /** Point spacing of the trajectories the sweeps by distance read from. */
        const val SWEEP_STEP_M = 1.0
        /** Distance error, muzzle velocity change and crosswind the hit spread is worked out from, by finite differences. */
        const val HIT_RANGE_STEP_M = 10.0
        const val HIT_VELOCITY_STEP_MPS = 5.0
        const val HIT_WIND_MPS = 2.0

        /** Null when the bullet does not reach [rangeM]. */
        fun of(setup: Setup, zeroElevationRad: Double, rangeM: Double, inputs: TargetInputs = TargetInputs()): TargetAnalysis? {
            val point = setup.pointAt(rangeM, zeroElevationRad) ?: return null
            val calm = setup.pointAt(rangeM, zeroElevationRad, emptyList())?.let { -it.windageRad } ?: return null
            fun correction(speed: Double, fromDeg: Double): Double =
                if (speed == 0.0) calm
                else setup.pointAt(rangeM, zeroElevationRad, listOf(Wind.fromClock(speed, fromDeg / 30)))?.let { -it.windageRad } ?: Double.NaN

            val misses = RANGE_ERRORS.filter { rangeM + it > 0 }.mapNotNull { e ->
                setup.pointAt(rangeM + e, zeroElevationRad)?.let { RangeMiss(e, (it.dropRad - point.dropRad) * (rangeM + e)) }
            }
            val tolerance = RangeTolerance(misses, edge(misses.filter { it.errorM < 0 }, inputs.heightM), edge(misses.filter { it.errorM > 0 }, inputs.heightM))

            val windFrom = setup.conditions.winds.firstOrNull()?.takeIf { it.speedMps > 0 }?.fromRad?.let { Math.toDegrees(it) } ?: 90.0
            val bracket = if (inputs.windMinMps != null || inputs.windMaxMps != null) {
                val now = setup.conditions.winds.firstOrNull()?.speedMps ?: 0.0
                val lo = inputs.windMinMps ?: now
                val hi = inputs.windMaxMps ?: now
                WindBracket(lo, hi, correction(lo, windFrom), correction(hi, windFrom), windFrom.mod(360.0))
            } else null

            val air = setup.conditions.atmosphere
            val temperatures = TEMPERATURE_STEPS.mapNotNull { dt ->
                val c = setup.conditions.copy(atmosphere = air.copy(temperatureC = air.temperatureC + dt))
                val powder = (setup.conditions.powderTempC ?: air.temperatureC) + dt
                setup.pointAt(rangeM, zeroElevationRad, conditions = c)?.let {
                    TemperatureRow(dt, air.temperatureC + dt, setup.ammo.muzzleVelocityAt(powder), it)
                }
            }

            val hit = hitChance(setup, zeroElevationRad, rangeM, inputs) ?: return null
            val charts = inputs.charts

            val bracketSweep = if (ChartKind.BRACKET_DIRECTION in charts && bracket != null) {
                BracketSweep(
                    SWEEP.map { it to correction(bracket.minMps, it) }.filterNot { it.second.isNaN() },
                    SWEEP.map { it to correction(bracket.maxMps, it) }.filterNot { it.second.isNaN() },
                )
            } else null

            val byAzimuth = if ((ChartKind.AZIMUTH_WINDAGE in charts || ChartKind.AZIMUTH_ELEVATION in charts) && setup.conditions.latitudeRad != null) {
                fun at(azDeg: Double) = setup.pointAt(
                    rangeM, zeroElevationRad, emptyList(),
                    conditions = setup.conditions.copy(azimuthRad = Math.toRadians(azDeg)),
                )
                val north = at(0.0)
                if (north == null) emptyList()
                else SWEEP.mapNotNull { az ->
                    val p = if (az == 0.0 || az == 360.0) north else at(az)
                    p?.let { AzimuthRow(az, -it.windageRad, -(it.dropRad - north.dropRad)) }
                }
            } else emptyList()

            // The sweeps by distance come from a few whole trajectories instead of a solve per distance.
            val hitByRange = if (ChartKind.HIT_RANGE in charts) {
                val to = inputs.chartsToM + HIT_RANGE_STEP_M
                val base = setup.trajectory(to, SWEEP_STEP_M, zeroElevationRad)
                val dv = HIT_VELOCITY_STEP_MPS
                val fast = setup.trajectory(to, SWEEP_STEP_M, zeroElevationRad, ammo = setup.ammo.copy(muzzleVelocityMps = setup.ammo.muzzleVelocityMps + dv))
                val slow = setup.trajectory(to, SWEEP_STEP_M, zeroElevationRad, ammo = setup.ammo.copy(muzzleVelocityMps = setup.ammo.muzzleVelocityMps - dv))
                val right = setup.trajectory(to, SWEEP_STEP_M, zeroElevationRad, listOf(Wind.fromClock(HIT_WIND_MPS, 3.0)))
                val left = setup.trajectory(to, SWEEP_STEP_M, zeroElevationRad, listOf(Wind.fromClock(HIT_WIND_MPS, 9.0)))
                (1..HIT_SAMPLES).map { inputs.chartsToM * it / HIT_SAMPLES }.mapNotNull { r ->
                    hitFrom(
                        r, inputs, { base.at(it) { p -> p.dropRad } },
                        fast.at(r) { it.dropRad }, slow.at(r) { it.dropRad },
                        right.at(r) { -it.windageRad }, left.at(r) { -it.windageRad },
                    )?.let { r to it.probability }
                }
            } else emptyList()

            val rangeCurve = if (ChartKind.RANGE_ERROR in charts) {
                val near = setup.trajectory(rangeM + RANGE_CURVE.last(), SWEEP_STEP_M, zeroElevationRad)
                val here = near.at(rangeM) { it.dropRad }
                if (here == null) emptyList()
                else RANGE_CURVE.filter { rangeM + it > 0 }.mapNotNull { e ->
                    near.at(rangeM + e) { it.dropRad }?.let { RangeMiss(e, (it - here) * (rangeM + e)) }
                }
            } else emptyList()

            return TargetAnalysis(
                point = point,
                bySpeed = if (ChartKind.WIND_SPEED in charts) DIRECTIONS.associateWith { d -> SPEEDS.map { it to correction(it, d) }.filterNot { it.second.isNaN() } } else emptyMap(),
                byDirection = if (ChartKind.WIND_DIRECTION in charts) DIRECTION_SPEEDS.associateWith { s -> SWEEP.map { it to correction(s, it) }.filterNot { it.second.isNaN() } } else emptyMap(),
                tolerance = tolerance,
                bracket = bracket,
                temperatures = temperatures,
                hit = hit,
                bracketSweep = bracketSweep,
                byAzimuth = byAzimuth,
                hitByRange = hitByRange,
                rangeCurve = rangeCurve,
            )
        }

        /** Hit chance at [rangeM], solving each variant of the shot to that distance. */
        private fun hitChance(setup: Setup, zeroElevationRad: Double, rangeM: Double, inputs: TargetInputs): HitChance? {
            fun windage(hour: Double) =
                setup.pointAt(rangeM, zeroElevationRad, listOf(Wind.fromClock(HIT_WIND_MPS, hour)))?.let { -it.windageRad }
            val dv = HIT_VELOCITY_STEP_MPS
            return hitFrom(
                rangeM, inputs, { r -> setup.pointAt(r, zeroElevationRad)?.dropRad },
                setup.pointAt(rangeM, zeroElevationRad, ammo = setup.ammo.copy(muzzleVelocityMps = setup.ammo.muzzleVelocityMps + dv))?.dropRad,
                setup.pointAt(rangeM, zeroElevationRad, ammo = setup.ammo.copy(muzzleVelocityMps = setup.ammo.muzzleVelocityMps - dv))?.dropRad,
                windage(3.0), windage(9.0),
            )
        }

        /**
         * Hit chance at [rangeM] from the spread at the target from each source, by finite differences:
         * [dropAt] gives the drop angle at a distance, [fastDrop]/[slowDrop] with the muzzle velocity
         * ±[HIT_VELOCITY_STEP_MPS], [rightCorrection]/[leftCorrection] the windage correction with
         * [HIT_WIND_MPS] from 3 and from 9 o'clock.
         */
        internal fun hitFrom(
            rangeM: Double,
            inputs: TargetInputs,
            dropAt: (Double) -> Double?,
            fastDrop: Double?,
            slowDrop: Double?,
            rightCorrection: Double?,
            leftCorrection: Double?,
        ): HitChance? {
            val here = dropAt(rangeM) ?: return null
            val dv = HIT_VELOCITY_STEP_MPS
            val perMps = if (fastDrop != null && slowDrop != null) abs(fastDrop - slowDrop) / (2 * dv) * rangeM else 0.0
            fun miss(e: Double) = if (rangeM + e <= 0) null else dropAt(rangeM + e)?.let { (it - here) * (rangeM + e) }
            val near = miss(-HIT_RANGE_STEP_M)
            val far = miss(HIT_RANGE_STEP_M)
            val perMetre = if (near != null && far != null) abs(far - near) / (2 * HIT_RANGE_STEP_M) else 0.0
            val crossPerMps = if (rightCorrection != null && leftCorrection != null) abs(rightCorrection - leftCorrection) / (2 * HIT_WIND_MPS) * rangeM else 0.0
            val fromVelocity = perMps * inputs.velocitySdMps
            val fromRange = perMetre * inputs.rangeSdM
            val fromWind = crossPerMps * inputs.windSdMps
            // A 5-shot group spans about three standard deviations each way.
            val fromRifle = inputs.groupRad / 3 * rangeM
            val sv = sqrt(fromVelocity * fromVelocity + fromRange * fromRange + fromRifle * fromRifle)
            val sh = hypot(fromWind, fromRifle)
            return HitChance(
                probability = within(inputs.heightM / 2, sv) * within(inputs.widthM / 2, sh),
                verticalSdM = sv, horizontalSdM = sh,
                fromVelocityM = fromVelocity, fromRangeM = fromRange, fromWindM = fromWind, fromRifleM = fromRifle,
            )
        }

        /** The error at which the miss reaches half the target height, interpolated between samples. */
        private fun edge(side: List<RangeMiss>, heightM: Double): Double? {
            var prev = RangeMiss(0.0, 0.0)
            for (m in side.sortedBy { abs(it.errorM) }) {
                if (abs(m.missM) > heightM / 2) {
                    val f = (heightM / 2 - abs(prev.missM)) / (abs(m.missM) - abs(prev.missM))
                    return prev.errorM + f * (m.errorM - prev.errorM)
                }
                prev = m
            }
            return null
        }

        /** Probability that a normal error with standard deviation [sd] stays within ±[half]. */
        private fun within(half: Double, sd: Double): Double = if (sd <= 0) 1.0 else erf(half / (sd * sqrt(2.0)))

        /** Abramowitz and Stegun 7.1.26; error below 1.5e-7. */
        internal fun erf(x: Double): Double {
            val t = 1 / (1 + 0.3275911 * abs(x))
            val y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
            return if (x >= 0) y else -y
        }
    }
}

/** The hold for a moving target: the impact at the distance it will be at, and how far to lead it. */
data class Lead(val point: TrajectoryPoint, val leadM: Double) {
    /** Lead angle, positive when the target moves right. */
    val leadRad: Double get() = atan2(leadM, point.rangeM)
}

/**
 * A target at [rangeM] moving at [speedMps] in [directionDeg] from the line of fire (0 = away, 90 = to the right).
 * The bullet meets it where it will be after the time of flight, so the distance is iterated with that time.
 */
fun Setup.lead(rangeM: Double, zeroElevationRad: Double, speedMps: Double, directionDeg: Double): Lead? {
    val radial = speedMps * cos(Math.toRadians(directionDeg))
    val crossing = speedMps * sin(Math.toRadians(directionDeg))
    var p = pointAt(rangeM, zeroElevationRad) ?: return null
    repeat(3) {
        if (abs(radial) < 1e-3) return@repeat
        p = pointAt(rangeM + radial * p.timeS, zeroElevationRad) ?: return null
    }
    return Lead(p, crossing * p.timeS)
}

/** Distance to a target of [sizeM] that spans [angleRad] in the reticle. */
fun rangeFromSize(sizeM: Double, angleRad: Double): Double? = if (sizeM > 0 && angleRad > 0) sizeM / angleRad else null
