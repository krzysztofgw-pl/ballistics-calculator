package pl.ballistics.core

import kotlin.math.exp
import kotlin.math.ln

/**
 * Ballistic coefficient from what was observed on the range ("BC truing"): the BC against a
 * standard drag function that makes the model reproduce the measurement.
 */
object BcTruing {
    /** BCs searched, in lb/in²; a measurement that needs one outside this range is rejected. */
    const val MIN_BC = 0.02
    const val MAX_BC = 2.0

    /**
     * BC from two velocities measured along the same flight, e.g. with a chronograph at the
     * muzzle and another one (or a Doppler radar) downrange. Gravity barely changes the speed
     * of a flat shot, so the velocity is integrated along the range alone: dv/dx = −k·ρ·v.
     *
     * @return null when the velocities do not decrease or no BC in [MIN_BC]..[MAX_BC] fits.
     */
    fun fromVelocities(
        function: DragFunction,
        nearRangeM: Double,
        nearVelocityMps: Double,
        farRangeM: Double,
        farVelocityMps: Double,
        atmosphere: Atmosphere = Atmosphere.icao(),
    ): Double? {
        if (farRangeM <= nearRangeM || nearVelocityMps <= farVelocityMps || farVelocityMps <= 0) return null
        val air = atmosphere.stateAt()
        return fit(farVelocityMps, increasing = true) { bc ->
            velocityAfter(BallisticCoefficient(function, bc), nearVelocityMps, farRangeM - nearRangeM, air)
        }
    }

    /**
     * BC from the elevation that actually centred the group at [rangeM]: [correctionRad] is
     * the angle dialled or held up (positive) over the line of sight. The rifle is re-zeroed
     * for every BC tried, as the zero itself depends on it; [withBc] builds the ammunition.
     * Muzzle velocity, sight height and the zero must be right, since drop depends on them too.
     */
    fun fromDrop(
        rifle: Rifle,
        withBc: (Double) -> Ammunition,
        conditions: ShotConditions,
        zeroAtmosphere: Atmosphere,
        rangeM: Double,
        correctionRad: Double,
        options: SolverOptions = SolverOptions(),
    ): Double? {
        if (rangeM <= rifle.zeroRangeM) return null
        val solver = Solver(options.copy(maxRangeM = rangeM, stepM = rangeM))
        return fit(correctionRad, increasing = false) { bc ->
            val point = solver.solve(rifle, withBc(bc), conditions, zeroAtmosphere).points.last()
            if (point.rangeM < rangeM - 1e-6) Double.NaN else -point.dropRad
        }
    }

    /**
     * BC for which [predicted] equals [measured], by bisection on log(BC). [predicted] must be
     * monotonic in the BC: [increasing] when a higher BC gives a larger value (remaining velocity),
     * false when it gives a smaller one (drop correction). Null when nothing in range fits or
     * the prediction fails (the bullet does not get there).
     */
    fun fit(measured: Double, increasing: Boolean, predicted: (Double) -> Double): Double? {
        fun miss(bc: Double): Double? = try {
            predicted(bc).takeIf { it.isFinite() }?.let { (it - measured) * if (increasing) 1 else -1 }
        } catch (e: IllegalArgumentException) {
            null
        }
        var lo = ln(MIN_BC)
        var hi = ln(MAX_BC)
        val missLo = miss(exp(lo))
        val missHi = miss(exp(hi)) ?: return null
        // At the lowest BC the bullet may not reach the distance at all: that counts as too low.
        if (missLo != null && missLo > 0) return null
        if (missHi < 0) return null
        repeat(30) {
            val mid = (lo + hi) / 2
            val m = miss(exp(mid))
            if (m == null || m < 0) lo = mid else hi = mid
        }
        return exp((lo + hi) / 2)
    }

    /** Speed after [distanceM] of flat flight starting at [velocityMps], by RK4 in range. */
    internal fun velocityAfter(drag: DragModel, velocityMps: Double, distanceM: Double, air: AirState): Double {
        fun dvdx(v: Double) = -drag.dragFactor(v / air.speedOfSoundMps, v) * air.densityKgM3 * v
        val steps = (distanceM / 0.5).toInt().coerceAtLeast(1)
        val h = distanceM / steps
        var v = velocityMps
        repeat(steps) {
            val k1 = dvdx(v)
            val k2 = dvdx(v + k1 * h / 2)
            val k3 = dvdx(v + k2 * h / 2)
            val k4 = dvdx(v + k3 * h)
            v += (k1 + 2 * k2 + 2 * k3 + k4) * h / 6
            if (v <= 0) return 0.0
        }
        return v
    }
}
