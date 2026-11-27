package pl.ballistics.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.location.OnNmeaMessageListener
import android.location.altitude.AltitudeConverter
import android.os.Looper
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import pl.ballistics.app.data.Nmea
import pl.ballistics.app.data.Stealth
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One-shot readings of the phone's position and heading for the conditions screen. */
object DeviceSensors {

    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasLocationPermission(context: Context) = locationPermissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * A fresh fix if one comes within 20 s, otherwise the most recent known one; null without permission.
     * In stealth mode only the satellites are used (the network and fused providers send nearby Wi-Fi
     * and cell towers to a server), with longer to wait, since an unassisted first fix is slow.
     */
    @SuppressLint("MissingPermission")
    suspend fun location(context: Context): Location? {
        if (!hasLocationPermission(context) || Stealth.blocksLocation) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val satellitesOnly = Stealth.satellitesOnly
        val providers = buildList {
            if (!satellitesOnly) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        for (provider in providers) {
            val fix = withTimeoutOrNull(if (satellitesOnly) 90_000 else 20_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) {
                                if (cont.isActive) cont.resume(it)
                            }
                        } else {
                            val listener = object : LocationListener {
                                override fun onLocationChanged(location: Location) {
                                    if (cont.isActive) cont.resume(location)
                                }

                                @Deprecated("Deprecated in Java")
                                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                                override fun onProviderEnabled(provider: String) = Unit
                                override fun onProviderDisabled(provider: String) = Unit
                            }
                            signal.setOnCancelListener { lm.removeUpdates(listener) }
                            @Suppress("DEPRECATION")
                            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        }
                    } catch (e: SecurityException) {
                        cont.resume(null)
                    } catch (e: IllegalArgumentException) {
                        cont.resume(null)
                    }
                }
            }
            if (fix != null) return fix
        }
        return if (satellitesOnly) runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() else lastKnown(context)
    }

    /**
     * Height above sea level from the satellites, and its vertical accuracy when the phone gives one.
     * [ellipsoidal] means it could not be converted and is the raw height above the WGS84 ellipsoid,
     * which in Poland reads some 30-40 m high.
     */
    data class GpsAltitude(val metres: Double, val accuracyM: Double?, val ellipsoidal: Boolean, val latitude: Double)

    /**
     * Listens to GPS_PROVIDER alone (no network help, so it works offline and in stealth mode) for up
     * to [timeoutMs], keeping the fix with the best vertical accuracy and stopping early once it is
     * within [goodEnoughM]. GPS heights are raw heights above the ellipsoid: Android 14+ converts them
     * with its built-in geoid model; before that, the receiver's own NMEA GGA sentence carries the
     * height above sea level. Null without fine location permission, with GPS off, or without a fix.
     */
    @SuppressLint("MissingPermission")
    suspend fun gpsAltitude(context: Context, timeoutMs: Long = 90_000, goodEnoughM: Float = 8f): GpsAltitude? {
        if (Stealth.blocksLocation) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (!runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) return null

        var best: Location? = null
        var ggaMsl: Double? = null
        var ggaAtBest: Double? = null
        fun vertical(l: Location): Float = if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else Float.MAX_VALUE
        val goodEnough = kotlinx.coroutines.CompletableDeferred<Unit>()

        val nmea = OnNmeaMessageListener { message, _ -> Nmea.ggaMslHeight(message)?.let { ggaMsl = it } }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!location.hasAltitude()) return
                val b = best
                if (b == null || vertical(location) <= vertical(b)) {
                    best = location
                    ggaAtBest = ggaMsl
                }
                if (vertical(location) <= goodEnoughM) goodEnough.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            runCatching { lm.addNmeaListener(nmea, Handler(Looper.getMainLooper())) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            withTimeoutOrNull(timeoutMs) { goodEnough.await() }
        } catch (e: SecurityException) {
            return null
        } finally {
            lm.removeUpdates(listener)
            runCatching { lm.removeNmeaListener(nmea) }
        }
        val fix = best ?: return null
        val accuracy = if (fix.hasVerticalAccuracy()) fix.verticalAccuracyMeters.toDouble() else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val converted = withContext(Dispatchers.IO) {
                runCatching {
                    AltitudeConverter().addMslAltitudeToLocation(context, fix)
                    if (fix.hasMslAltitude()) fix.mslAltitudeMeters else null
                }.getOrNull()
            }
            if (converted != null) {
                val mslAccuracy = if (fix.hasMslAltitudeAccuracy()) fix.mslAltitudeAccuracyMeters.toDouble() else accuracy
                return GpsAltitude(converted, mslAccuracy, ellipsoidal = false, latitude = fix.latitude)
            }
        }
        (ggaAtBest ?: ggaMsl)?.let { return GpsAltitude(it, accuracy, ellipsoidal = false, latitude = fix.latitude) }
        return GpsAltitude(fix.altitude, accuracy, ellipsoidal = true, latitude = fix.latitude)
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return lm.allProviders
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /**
     * One compass reading with what is needed to judge it.
     *
     * @param headingAccuracyDeg the sensor's own estimate of the heading error, when it gives one.
     * @param fieldMicroTesla strength of the magnetic field the phone measured.
     * @param magnetometerStatus SensorManager.SENSOR_STATUS_*; low or unreliable means it wants calibrating.
     */
    data class CompassReading(
        val headingDeg: Double,
        val headingAccuracyDeg: Double?,
        val fieldMicroTesla: Double?,
        val magnetometerStatus: Int?,
    ) {
        /**
         * Why the reading cannot be trusted, or null. [expectedMicroTesla] is the Earth's field at the
         * location; a much stronger or weaker field means a badly calibrated compass or metal nearby.
         */
        fun problem(expectedMicroTesla: Double?): CompassProblem? = when {
            magnetometerStatus != null && magnetometerStatus <= SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassProblem.UNCALIBRATED
            headingAccuracyDeg != null && headingAccuracyDeg > 15 -> CompassProblem.INACCURATE
            expectedMicroTesla != null && fieldMicroTesla != null && abs(fieldMicroTesla / expectedMicroTesla - 1) > 0.25 ->
                CompassProblem.DISTURBED
            else -> null
        }
    }

    enum class CompassProblem { UNCALIBRATED, INACCURATE, DISTURBED }

    /**
     * The magnetic heading the phone points along, averaged over half a second: the top edge
     * when the phone lies flat, the back camera when it is held upright. Null without a compass.
     */
    suspend fun magneticHeading(context: Context): CompassReading? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return null
        val magnetometer = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                var sumSin = 0.0
                var sumCos = 0.0
                var count = 0
                var accuracy: Double? = null
                var field: Double? = null
                var status: Int? = null
                val r = FloatArray(9)
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                            val v = event.values
                            field = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
                            status = event.accuracy
                            return
                        }
                        SensorManager.getRotationMatrixFromVector(r, event.values)
                        // The fifth value, when present, is the estimated heading accuracy in radians.
                        if (event.values.size > 4 && event.values[4] >= 0) accuracy = Math.toDegrees(event.values[4].toDouble())
                        // Columns of r are the phone's axes in east-north-up coordinates.
                        val flat = abs(r[7]) < abs(r[8])
                        val east = if (flat) r[1] else -r[2]
                        val north = if (flat) r[4] else -r[5]
                        val a = atan2(east.toDouble(), north.toDouble())
                        sumSin += sin(a); sumCos += cos(a); count++
                        if (count >= 25) {
                            sm.unregisterListener(this)
                            val heading = Math.toDegrees(atan2(sumSin, sumCos)).mod(360.0)
                            if (cont.isActive) cont.resume(CompassReading(heading, accuracy, field, status))
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
                }
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                magnetometer?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
            }
        }
    }

    /** Whether the phone has a barometer. */
    fun hasBarometer(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    /**
     * Station pressure from the phone's own barometer in hPa, averaged over a few readings; null without a
     * barometer or when it gives nothing within three seconds. Passive, so allowed in stealth mode.
     */
    suspend fun barometerHpa(context: Context): Double? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                var sum = 0.0
                var count = 0
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sum += event.values[0]; count++
                        if (count >= 10) {
                            sm.unregisterListener(this)
                            if (cont.isActive) cont.resume(sum / count)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
                }
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    /** Strength of the Earth's magnetic field at [location], in microtesla. */
    fun expectedFieldMicroTesla(location: Location): Double =
        GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis(),
        ).fieldStrength / 1000.0

    /** Magnetic declination (east positive) at [location] now. */
    fun declinationDeg(location: Location): Double =
        GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis(),
        ).declination.toDouble()
}
