package pl.ballistics.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Height of the ground at a point, from an elevation model. In Poland GUGiK's NMT, a 1 m grid
 * from airborne lidar (heights in the national datum, PL-KRON86-NH / PL-EVRF2007-NH, which differ by
 * centimetres); elsewhere the Copernicus 90 m model through Open-Meteo.
 */
object TerrainHeight {
    enum class Source { NMT_PL, COPERNICUS }

    data class Result(val heightM: Double, val source: Source)

    private const val OPEN_METEO = "https://api.open-meteo.com/v1/elevation?latitude=%.5f&longitude=%.5f"
    private const val GUGIK = "https://services.gugik.gov.pl/nmt/?request=GetHbyXY&x=%.2f&y=%.2f"

    /** The lidar model and the 90 m one should agree within this; further apart, the lidar answer is not trusted. */
    private const val AGREEMENT_M = 40.0

    /**
     * GUGiK's documentation leaves open which of x and y is the easting, so both orders are asked
     * and the one that agrees with Copernicus is kept; once known, it is remembered for the process.
     */
    @Volatile private var eastingFirst: Boolean? = null

    suspend fun lookup(latitude: Double, longitude: Double): Result = withContext(Dispatchers.IO) {
        Stealth.checkNetwork()
        coroutineScope {
            val copernicus = async { runCatching { openMeteo(latitude, longitude) }.getOrNull() }
            val nmt = if (Puwg92.covers(latitude, longitude)) {
                val (e, n) = Puwg92.fromWgs84(latitude, longitude)
                val known = eastingFirst
                if (known != null) {
                    val h = runCatching { gugik(if (known) e else n, if (known) n else e) }.getOrNull()
                    val reference = copernicus.await()
                    h?.takeIf { reference == null || abs(it - reference) <= AGREEMENT_M }
                } else {
                    val en = async { runCatching { gugik(e, n) }.getOrNull() }
                    val ne = async { runCatching { gugik(n, e) }.getOrNull() }
                    val candidates = listOf(en.await(), ne.await())
                    val pick = pickAgreeing(candidates, copernicus.await())
                    if (pick != null) eastingFirst = pick == 0
                    pick?.let { candidates[it] }
                }
            } else {
                null
            }
            nmt?.let { Result(it, Source.NMT_PL) }
                ?: copernicus.await()?.let { Result(it, Source.COPERNICUS) }
                ?: throw IOException("No terrain height")
        }
    }

    /**
     * Which of the [candidates] (heights for the two axis orders) to trust: the one closest to
     * [reference], if within [AGREEMENT_M]. Without a reference nothing can be told apart, so null.
     */
    fun pickAgreeing(candidates: List<Double?>, reference: Double?): Int? {
        reference ?: return null
        return candidates.indices
            .filter { candidates[it] != null && abs(candidates[it]!! - reference) <= AGREEMENT_M }
            .minByOrNull { abs(candidates[it]!! - reference) }
    }

    private fun openMeteo(latitude: Double, longitude: Double): Double? =
        parseOpenMeteo(get(String.format(Locale.US, OPEN_METEO, latitude, longitude)))

    private fun gugik(x: Double, y: Double): Double? = parseGugik(get(String.format(Locale.US, GUGIK, x, y)))

    /** Open-Meteo answers {"elevation":[123.0]}. */
    fun parseOpenMeteo(text: String): Double? = runCatching {
        Json.parseToJsonElement(text).jsonObject["elevation"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.doubleOrNull
    }.getOrNull()

    /** GUGiK answers with a bare number, possibly with a decimal comma ("55,8"); anything else is an error. */
    fun parseGugik(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > -50 && it < 3000 }

    private fun get(url: String): String {
        Stealth.checkNetwork()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", WindForecast.USER_AGENT)
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The Polish national grid PL-1992 (EPSG:2180): transverse Mercator on GRS80, central meridian
 * 19°E, scale 0.9993, false easting 500 km, false northing −5300 km. WGS84 and ETRF89 differ
 * by well under a metre here, which does not matter for a height lookup.
 */
object Puwg92 {
    private const val A = 6378137.0
    private const val F = 1 / 298.257222101
    private const val K0 = 0.9993
    private const val LON0 = 19.0
    private const val FALSE_EASTING = 500_000.0
    private const val FALSE_NORTHING = -5_300_000.0

    /** Roughly Poland's extent; NMT has data only inside the country. */
    fun covers(latitude: Double, longitude: Double) = latitude in 49.0..54.9 && longitude in 14.1..24.2

    /** Easting and northing in metres (Krüger series, sub-millimetre at these distances from the meridian). */
    fun fromWgs84(latitude: Double, longitude: Double): Pair<Double, Double> {
        val n = F / (2 - F)
        val a = A / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64)
        val alpha = doubleArrayOf(
            n / 2 - 2 * n * n / 3 + 5 * n * n * n / 16,
            13 * n * n / 48 - 3 * n * n * n / 5,
            61 * n * n * n / 240,
        )
        val phi = Math.toRadians(latitude)
        val dLambda = Math.toRadians(longitude - LON0)
        val k = 2 * sqrt(n) / (1 + n)
        val t = sinh(atanh(sin(phi)) - k * atanh(k * sin(phi)))
        val xi = atan2(t, cos(dLambda))
        val eta = atanh(sin(dLambda) / sqrt(1 + t * t))
        var x = xi
        var y = eta
        for (j in 1..3) {
            x += alpha[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            y += alpha[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        return Pair(K0 * a * y + FALSE_EASTING, K0 * a * x + FALSE_NORTHING)
    }
}
