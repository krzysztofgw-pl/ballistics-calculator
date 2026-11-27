package pl.ballistics.app.data

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainHeightTest {
    @Test
    fun centralMeridianMatchesTheMeridianArc() {
        // Northing at 52°N on 19°E: GRS80 meridian arc (numerically integrated) × 0.9993 − 5300 km.
        val (e, n) = Puwg92.fromWgs84(52.0, 19.0)
        assertEquals(500_000.0, e, 0.001)
        assertEquals(459_309.209, n, 0.01)
    }

    @Test
    fun eastingIsSymmetricAboutTheCentralMeridian() {
        val (west, nw) = Puwg92.fromWgs84(52.23, 17.0)
        val (east, ne) = Puwg92.fromWgs84(52.23, 21.0)
        assertEquals(1_000_000.0, west + east, 0.001)
        assertEquals(nw, ne, 0.001)
        assertTrue(abs(east - 636_548.4) < 1)
    }

    @Test
    fun coversPolandOnly() {
        assertTrue(Puwg92.covers(52.23, 21.01))
        assertFalse(Puwg92.covers(48.2, 16.4))
    }

    @Test
    fun readsGugikNumbersWithEitherDecimalMark() {
        assertEquals(55.8, TerrainHeight.parseGugik("55,8")!!, 1e-9)
        assertEquals(298.2, TerrainHeight.parseGugik(" 298.2\n")!!, 1e-9)
        assertNull(TerrainHeight.parseGugik("Błąd: punkt poza zasięgiem"))
        assertNull(TerrainHeight.parseGugik("-9999"))
    }

    @Test
    fun readsOpenMeteoElevation() {
        assertEquals(38.0, TerrainHeight.parseOpenMeteo("""{"elevation":[38.0]}""")!!, 1e-9)
        assertNull(TerrainHeight.parseOpenMeteo("""{"error":true,"reason":"x"}"""))
    }

    @Test
    fun keepsTheAxisOrderThatAgreesWithTheCoarseModel() {
        assertEquals(1, TerrainHeight.pickAgreeing(listOf(612.0, 221.4), 215.0))
        assertEquals(0, TerrainHeight.pickAgreeing(listOf(221.4, null), 215.0))
        // Neither close enough, or nothing to compare with: no answer from the lidar model.
        assertNull(TerrainHeight.pickAgreeing(listOf(612.0, 95.0), 215.0))
        assertNull(TerrainHeight.pickAgreeing(listOf(221.4, 612.0), null))
    }

    @Test
    fun readsSeaLevelHeightFromGga() {
        assertEquals(545.4, Nmea.ggaMslHeight("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")!!, 1e-9)
        assertEquals(212.3, Nmea.ggaMslHeight("\$GNGGA,083000.00,5213.800,N,02100.600,E,2,12,0.7,212.3,M,33.9,M,,*5A\r\n")!!, 1e-9)
        assertNull(Nmea.ggaMslHeight("\$GPGGA,123519,,,,,0,00,,,M,,M,,*66"))
        assertNull(Nmea.ggaMslHeight("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"))
    }

    @Test
    fun olderSettingsKeepTheirAltitudeChoice() {
        assertEquals(AltitudeSource.TERRAIN, UiSettings(locationSetsAltitude = true).altitudeFrom)
        // Typed in is no longer a choice: old settings without one, or with MANUAL, read the terrain map.
        assertEquals(AltitudeSource.TERRAIN, UiSettings().altitudeFrom)
        assertEquals(AltitudeSource.TERRAIN, UiSettings(altitudeSource = AltitudeSource.MANUAL).altitudeFrom)
        assertEquals(AltitudeSource.GPS, UiSettings(locationSetsAltitude = true, altitudeSource = AltitudeSource.GPS).altitudeFrom)
    }
}
