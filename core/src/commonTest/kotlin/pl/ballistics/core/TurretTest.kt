package pl.ballistics.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TurretTest {
    @Test
    fun shootersMoaIsOneInchAtHundredYards() {
        assertEquals(0.0254, Units.SMOA * 91.44, 1e-12)
        assertEquals(1.0472, Units.MOA / Units.SMOA, 1e-4)
    }

    @Test
    fun dialsWholeClicks() {
        val t = Turret(Units.MRAD, 0.1)
        assertEquals(5.3, t.dial(5.27e-3), 1e-9)
        assertEquals(53, t.clicks(5.27e-3))
    }

    @Test
    fun withoutClickDialsTheExactFigure() {
        assertEquals(5.27, Turret(Units.MRAD).dial(5.27e-3), 1e-9)
        assertEquals(null, Turret(Units.MRAD).clicks(5.27e-3))
    }

    @Test
    fun shortTurretNeedsMoreDial() {
        // Dialled 10 MRAD, the tall target showed 9.8: the turret moves 2 % short.
        val t = Turret(Units.MRAD, 0.1, factor = 9.8 / 10.0)
        assertEquals(10.2, t.dial(10e-3), 1e-9)
        assertEquals(10e-3, t.angle(10.0 / 0.98), 1e-12)
    }

    @Test
    fun sameFigureMeansLessInShootersMoa() {
        val moa = Turret(Units.MOA, 0.25)
        val smoa = Turret(Units.SMOA, 0.25)
        val rad = 30 * Units.MOA
        assertEquals(30.0, moa.dial(rad), 1e-9)
        assertEquals(31.5, smoa.dial(rad), 1e-9)
    }
}
