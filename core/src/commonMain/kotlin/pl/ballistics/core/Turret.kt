package pl.ballistics.core

import kotlin.math.roundToInt

/**
 * A scope turret.
 *
 * @param unitRad one marked unit of the turret (Units.MRAD, Units.MOA, Units.SMOA), in radians.
 * @param click one click in marked units; null dials the exact figure.
 * @param factor what the turret really moves per marked unit: measured ÷ dialled, from a
 *   tall-target test. 1 is a perfect turret, 0.98 one that moves 2 % short.
 */
data class Turret(val unitRad: Double, val click: Double? = null, val factor: Double = 1.0) {
    init {
        require(unitRad > 0 && factor > 0)
        require(click == null || click > 0)
    }

    /** The marked figure that moves the point of impact by [rad], before rounding to clicks. */
    fun units(rad: Double): Double = rad / (unitRad * factor)

    /** The figure to dial for a correction of [rad]: whole clicks when the click is known. */
    fun dial(rad: Double): Double {
        val v = units(rad)
        return click?.let { (v / it).roundToInt() * it } ?: v
    }

    /** Whole clicks for a correction of [rad]; null without a click value. */
    fun clicks(rad: Double): Int? = click?.let { (units(rad) / it).roundToInt() }

    /** The real angle the turret moves when [dialled] marked units are set on it. */
    fun angle(dialled: Double): Double = dialled * unitRad * factor
}
