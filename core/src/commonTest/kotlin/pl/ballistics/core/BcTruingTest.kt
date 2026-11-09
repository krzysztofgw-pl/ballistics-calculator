package pl.ballistics.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BcTruingTest {
    private fun ammo(bc: Double) = Ammunition(
        Projectile(BallisticCoefficient(DragFunction.G7, bc), 142 * Units.GRAIN, 0.264 * Units.INCH, 1.37 * Units.INCH),
        830.0,
    )
    private val rifle = Rifle(0.05, 100.0, twistM = 8 * Units.INCH)
    private val options = SolverOptions(spinDrift = false, aerodynamicJump = false, coriolis = false)
    private val air = Atmosphere(altitudeM = 300.0, stationPressurePa = 97_500.0, temperatureC = 8.0, relativeHumidity = 0.6)

    @Test
    fun velocitiesFromTheFullSolverGiveItsBcBack() {
        val points = Solver(options.copy(maxRangeM = 600.0, stepM = 600.0))
            .solve(rifle, ammo(0.30), ShotConditions(atmosphere = air)).points
        val bc = BcTruing.fromVelocities(DragFunction.G7, 0.0, points.first().velocityMps, 600.0, points.last().velocityMps, air)!!
        assertEquals(0.30, bc, 0.003)
    }

    @Test
    fun dropFromTheFullSolverGivesItsBcBack() {
        val conditions = ShotConditions(atmosphere = air)
        val point = Solver(options.copy(maxRangeM = 800.0, stepM = 800.0)).solve(rifle, ammo(0.30), conditions).points.last()
        val bc = BcTruing.fromDrop(rifle, ::ammo, conditions, Atmosphere.icao(), 800.0, -point.dropRad, options)!!
        assertEquals(0.30, bc, 0.0005)
    }

    @Test
    fun moreDropMeansLowerBc() {
        val conditions = ShotConditions(atmosphere = air)
        val point = Solver(options.copy(maxRangeM = 800.0, stepM = 800.0)).solve(rifle, ammo(0.30), conditions).points.last()
        val bc = BcTruing.fromDrop(rifle, ::ammo, conditions, Atmosphere.icao(), 800.0, -point.dropRad + 0.2 * Units.MRAD, options)!!
        assertEquals(true, bc < 0.29)
    }

    @Test
    fun impossibleMeasurementsGiveNoBc() {
        assertNull(BcTruing.fromVelocities(DragFunction.G1, 0.0, 800.0, 300.0, 810.0))
        assertNull(BcTruing.fromVelocities(DragFunction.G1, 0.0, 800.0, 100.0, 799.99))
        assertNull(BcTruing.fromDrop(rifle, ::ammo, ShotConditions(), Atmosphere.icao(), 800.0, -1.0 * Units.MRAD, options))
    }
}
