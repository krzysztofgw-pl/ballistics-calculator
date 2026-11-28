package pl.ballistics.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataMigrationTest {
    /** A copy as version 1 apps saved it, on the phone and in Firestore. */
    private val v1 = """
        {
          "version": 1,
          "weapons": [
            {"id": "a", "angularUnit": "MRAD", "inchMoa": false, "clickValue": "0.1"},
            {"id": "b", "angularUnit": "MOA", "inchMoa": false, "clickValue": "0.25"},
            {"id": "c", "angularUnit": "MOA", "inchMoa": true, "clickValue": "0.25"}
          ],
          "conditions": {"windClock": "4.5", "windFromDeg": null},
          "ui": {"garminWatch": false, "phoneBarometer": true, "locationSetsAltitude": true, "altitudeSource": "MANUAL"},
          "modifiedAt": 5
        }
    """.trimIndent()

    @Test
    fun version1CopyMigratesEverything() {
        val d = assertNotNull(Repository.decode(v1))
        assertEquals(AppData.CURRENT_VERSION, d.version)
        assertEquals(listOf(TurretUnit.MRAD, TurretUnit.MOA, TurretUnit.SMOA), d.weapons.map { it.turretUnit })
        assertEquals("135", d.conditions.windFromDeg)
        assertEquals(PressureSource.PHONE, d.ui.pressureSource)
        assertEquals(AltitudeSource.TERRAIN, d.ui.altitudeSource)
        assertEquals(5, d.modifiedAt)
    }

    @Test
    fun oldZeroPowderSensitivityBecomesTheStandard() {
        val old = """{"version": 1, "ammo": [{"id": "x", "powderSensitivity": "0"}, {"id": "y", "powderSensitivity": "1,2"}]}"""
        assertEquals(listOf("", "1,2"), Repository.decode(old)!!.ammo.map { it.powderSensitivity })
        // From version 2 on, 0 means the velocity doesn't follow the temperature.
        val now = """{"version": 2, "ammo": [{"id": "x", "powderSensitivity": "0"}]}"""
        assertEquals("0", Repository.decode(now)!!.ammo.single().powderSensitivity)
    }

    @Test
    fun degreesWinOverTheOldClock() {
        val d = assertNotNull(Repository.decode("""{"conditions": {"windClock": "3", "windFromDeg": "200"}, "ui": {"garminWatch": true, "phoneBarometer": true, "altitudeSource": "GPS"}}"""))
        assertEquals("200", d.conditions.windFromDeg)
        assertEquals(PressureSource.GARMIN, d.ui.pressureSource)
        assertEquals(AltitudeSource.GPS, d.ui.altitudeSource)
    }

    @Test
    fun currentFormatHasNoOldKeys() {
        val json = Repository.encode(AppData.sample())
        for (key in listOf("angularUnit", "inchMoa", "windClock", "garminWatch", "phoneBarometer", "locationSetsAltitude")) {
            assertFalse("\"$key\"" in json, key)
        }
    }

    @Test
    fun unknownEnumValueFallsBackInsteadOfLosingTheCopy() {
        val d = assertNotNull(Repository.decode("""{"weapons": [{"id": "a", "turretUnit": "NATO", "name": "X"}], "ui": {"pressureSource": "KESTREL"}}"""))
        assertEquals(TurretUnit.MRAD, d.weapons.single().turretUnit)
        assertEquals("X", d.weapons.single().name)
        assertEquals(PressureSource.NONE, d.ui.pressureSource)
    }

    @Test
    fun garbageIsNotReadable() {
        assertTrue(Repository.decode("not json") == null)
    }
}
