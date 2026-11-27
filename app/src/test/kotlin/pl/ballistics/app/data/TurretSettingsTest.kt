package pl.ballistics.app.data

import pl.ballistics.core.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurretSettingsTest {
    @Test
    fun smoaIsStoredAsMoaSoOlderVersionsStillReadIt() {
        val rifle = WeaponProfile().withTurretUnit(TurretUnit.SMOA)
        val json = Repository.encode(AppData(weapons = listOf(rifle)))
        assertTrue("\"angularUnit\": \"MOA\"" in json)
        assertTrue("\"inchMoa\": true" in json)
        assertEquals(TurretUnit.SMOA, Repository.decode(json)!!.weapons.single().turretUnit)
        assertEquals(Units.SMOA, rifle.elevationTurret.unitRad)
    }

    @Test
    fun switchingBackToMradClearsTheInchFlag() {
        val rifle = WeaponProfile().withTurretUnit(TurretUnit.SMOA).withTurretUnit(TurretUnit.MRAD)
        assertEquals(AngularUnit.MRAD, rifle.angularUnit)
        assertFalse(rifle.inchMoa)
    }

    @Test
    fun windageFollowsElevationUnlessSeparate() {
        val rifle = WeaponProfile(clickValue = "0.1", elevationFactor = "0,98", windageClickValue = "0.05", windageFactor = "1.01")
        assertEquals(0.1, rifle.windageTurret.click)
        assertEquals(0.98, rifle.windageTurret.factor)

        val separate = rifle.copy(separateWindage = true)
        assertEquals(0.05, separate.windageTurret.click)
        assertEquals(1.01, separate.windageTurret.factor)
        assertEquals(0.98, separate.elevationTurret.factor)
    }

    @Test
    fun blankWindageClickAndFactorsFallBack() {
        val rifle = WeaponProfile(clickValue = "0.1", separateWindage = true)
        assertEquals(0.1, rifle.windageTurret.click)
        assertEquals(1.0, rifle.windageTurret.factor)
        assertEquals(1.0, rifle.copy(elevationFactor = "0").elevationTurret.factor)
    }
}
