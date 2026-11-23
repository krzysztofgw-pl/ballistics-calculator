package pl.ballistics.app

import pl.ballistics.app.data.ChartKind
import pl.ballistics.core.Ammunition
import pl.ballistics.core.Atmosphere
import pl.ballistics.core.BallisticCoefficient
import pl.ballistics.core.DragFunction
import pl.ballistics.core.Projectile
import pl.ballistics.core.Rifle
import pl.ballistics.core.ShotConditions
import pl.ballistics.core.Solver
import pl.ballistics.core.SolverOptions
import pl.ballistics.core.Units
import pl.ballistics.core.Wind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetAnalysisTest {
    private val ammo = Ammunition(
        Projectile(BallisticCoefficient(DragFunction.G7, 0.29), 142 * Units.GRAIN, 0.264 * Units.INCH, 1.37 * Units.INCH),
        820.0,
    )
    private val rifle = Rifle(0.05, 100.0, twistM = 8 * Units.INCH)

    private fun setup(latitudeDeg: Double? = 52.0, winds: List<Wind> = listOf(Wind.fromClock(3.0, 3.0))): Pair<Setup, Double> {
        val conditions = ShotConditions(
            atmosphere = Atmosphere.icao(),
            winds = winds,
            latitudeRad = latitudeDeg?.let { Math.toRadians(it) },
        )
        val setup = Setup(rifle, ammo, conditions, Atmosphere.icao(), SolverOptions())
        val zero = Solver(SolverOptions(maxRangeM = 100.0, stepM = 100.0)).solve(rifle, ammo, conditions).zeroElevationRad
        return setup to zero
    }

    private val inputs = TargetInputs(windMinMps = 2.0, windMaxMps = 4.0, chartsToM = 1000.0)

    @Test
    fun spinDriftGrowsWithDistance() {
        val (s, zero) = setup(winds = emptyList())
        val drift = s.trajectory(1000.0, 100.0, zero).points.drop(1).map { it.spinDriftM }
        assertTrue(drift.all { it > 0 }, "a right twist drifts right: $drift")
        assertTrue(drift.zipWithNext().all { (a, b) -> b > a }, "spin drift grows: $drift")
    }

    @Test
    fun shootingEastHitsHigherThanShootingWest() {
        val (s, zero) = setup()
        val a = TargetAnalysis.of(s, zero, 600.0, inputs)!!
        val east = a.byAzimuth.first { it.azimuthDeg == 90.0 }
        val west = a.byAzimuth.first { it.azimuthDeg == 270.0 }
        assertTrue(east.elevationChangeRad < 0, "east needs less elevation: ${east.elevationChangeRad}")
        assertTrue(west.elevationChangeRad > 0, "west needs more elevation: ${west.elevationChangeRad}")
    }

    @Test
    fun noAzimuthChartsWithoutLatitude() {
        val (s, zero) = setup(latitudeDeg = null)
        assertTrue(TargetAnalysis.of(s, zero, 600.0, inputs)!!.byAzimuth.isEmpty())
    }

    @Test
    fun hitProbabilityFallsWithDistance() {
        val (s, zero) = setup()
        val hits = TargetAnalysis.of(s, zero, 500.0, inputs)!!.hitByRange.map { it.second }
        assertTrue(hits.first() > hits.last(), "hits: $hits")
        assertTrue(hits.zipWithNext().all { (a, b) -> b <= a + 1e-6 }, "hits fall: $hits")
    }

    @Test
    fun sweepsAgreeWithSolvingEachDistance() {
        val (s, zero) = setup()
        val a = TargetAnalysis.of(s, zero, 500.0, inputs)!!
        // 500 m is one of the sixteen samples up to 1000 m.
        val swept = a.hitByRange.first { kotlin.math.abs(it.first - 500.0) < 1e-6 }.second
        assertEquals(a.hit.probability, swept, 0.01)
        for (row in a.tolerance.misses.filter { kotlin.math.abs(it.errorM) == 25.0 || kotlin.math.abs(it.errorM) == 50.0 }) {
            val curve = a.rangeCurve.first { it.errorM == row.errorM }
            assertEquals(row.missM, curve.missM, 0.002, "miss at ${row.errorM} m")
        }
    }

    @Test
    fun bracketSweepPassesThroughTheBracket() {
        val (s, zero) = setup()
        val a = TargetAnalysis.of(s, zero, 600.0, inputs)!!
        val b = a.bracket!!
        val sweep = a.bracketSweep!!
        assertEquals(b.minRad, sweep.light.first { it.first == 90.0 }.second, 1e-9)
        assertEquals(b.maxRad, sweep.strong.first { it.first == 90.0 }.second, 1e-9)
    }

    @Test
    fun chartsTurnedOffAreNotWorkedOut() {
        val (s, zero) = setup()
        val a = TargetAnalysis.of(s, zero, 600.0, inputs.copy(charts = setOf(ChartKind.SPIN_DRIFT)))!!
        assertTrue(a.bySpeed.isEmpty())
        assertTrue(a.byDirection.isEmpty())
        assertTrue(a.byAzimuth.isEmpty())
        assertTrue(a.hitByRange.isEmpty())
        assertTrue(a.rangeCurve.isEmpty())
        assertEquals(null, a.bracketSweep)
    }
}
