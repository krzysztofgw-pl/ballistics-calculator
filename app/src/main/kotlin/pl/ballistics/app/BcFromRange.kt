package pl.ballistics.app

import pl.ballistics.core.BallisticCoefficient
import pl.ballistics.core.BcTruing
import pl.ballistics.core.DragFunction

/** A trued BC with the BCs the measurement would give if it were off by its typical error each way. */
data class TruedBc(val bc: Double, val low: Double?, val high: Double?)

/** BC from two velocities; the spread is for the far velocity off by [velocityErrorMps]. */
fun Setup.bcFromVelocities(
    function: DragFunction,
    nearRangeM: Double,
    nearMps: Double,
    farRangeM: Double,
    farMps: Double,
    velocityErrorMps: Double = 3.0,
): TruedBc? {
    val air = conditions.atmosphere
    fun fit(far: Double) = BcTruing.fromVelocities(function, nearRangeM, nearMps, farRangeM, far, air)
    val bc = fit(farMps) ?: return null
    return TruedBc(bc, fit(farMps - velocityErrorMps), fit(farMps + velocityErrorMps))
}

/**
 * BC from the elevation [correctionRad] (up, positive) that centred the group at [rangeM], with the
 * rifle, zero and conditions of this setup; the spread is for the correction off by [correctionErrorRad].
 */
fun Setup.bcFromDrop(function: DragFunction, rangeM: Double, correctionRad: Double, correctionErrorRad: Double): TruedBc? {
    fun fit(correction: Double) = BcTruing.fromDrop(
        rifle,
        { bc -> ammo.copy(projectile = ammo.projectile.copy(drag = BallisticCoefficient(function, bc))) },
        conditions,
        zeroAtmosphere,
        rangeM,
        // A suppressor shift moves the impact, so the dialled correction includes it.
        correction + shiftUpRad,
        options,
    )
    val bc = fit(correctionRad) ?: return null
    // More elevation means more drop and so a lower BC.
    return TruedBc(bc, fit(correctionRad + correctionErrorRad), fit(correctionRad - correctionErrorRad))
}
