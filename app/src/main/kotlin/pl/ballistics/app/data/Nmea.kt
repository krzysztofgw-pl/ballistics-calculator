package pl.ballistics.app.data

/** The bits of the GPS receiver's NMEA sentences the app reads. */
object Nmea {
    /**
     * Height above mean sea level (field 9) from a GGA sentence with a fix, e.g.
     * `$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47` gives 545.4.
     * Any talker (GP, GN, GL, ...); null without a fix or for other sentences.
     */
    fun ggaMslHeight(sentence: String): Double? {
        val f = sentence.trim().substringBefore('*').split(',')
        if (f.size < 11 || !f[0].endsWith("GGA")) return null
        val quality = f[6].toIntOrNull() ?: return null
        if (quality == 0) return null
        if (f[10].isNotEmpty() && f[10] != "M") return null
        return f[9].toDoubleOrNull()
    }
}
