package pl.ballistics.app.data

import pl.ballistics.core.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurretSettingsTest {
    @Test
    fun smoaRoundTripsAsItsOwnUnit() {
        val rifle = WeaponProfile(turretUnit = TurretUnit.SMOA)
        val json = Repository.encode(AppData(weapons = listOf(rifle)))
        assertTrue("\"turretUnit\": \"SMOA\"" in json)
        assertEquals(TurretUnit.SMOA, Repository.decode(json)!!.weapons.single().turretUnit)
        assertEquals(Units.SMOA, rifle.elevationTurret.unitRad)
    }

    @Test
    fun bothTurretsShareClickAndCorrection() {
        val rifle = WeaponProfile(clickValue = "0.1", turretFactor = "0,98")
        assertEquals(0.98, rifle.elevationTurret.factor)
        assertEquals(rifle.elevationTurret, rifle.windageTurret)
    }

    @Test
    fun blankOrInvalidCorrectionIsOne() {
        assertEquals(1.0, WeaponProfile().elevationTurret.factor)
        assertEquals(1.0, WeaponProfile(turretFactor = "0").elevationTurret.factor)
    }
}
