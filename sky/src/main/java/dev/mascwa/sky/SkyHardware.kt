package dev.mascwa.sky

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dev.mascwa.pulse.core.telemetry.SkyPointing
import dev.mascwa.pulse.sky.SkyAttitude
import dev.mascwa.pulse.sky.SkyDeps
import dev.mascwa.pulse.sky.SkySite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where this phone is and where it is aimed — the only two things the star map cannot compute.
 *
 * ⚠️ **Written here rather than sharing the LCARS application's `CompassController`, and the
 * reasoning is worth keeping because it cuts the other way from most of this module.** That class
 * is 180 lines carrying two modes this map does not want (a flat-held reading for a compass rose,
 * and a three-angle low-pass the map deliberately switches off — it blends the two DIRECTIONS
 * instead, because near the zenith the azimuth swings wildly for a centimetre of hand movement).
 * What would have been shared is `SensorManager` registration and a `GeomagneticField` lookup,
 * which is platform boilerplate; **the part that could actually be got wrong is already shared** —
 * `SkyPointing.fromDeviceOrientation` is the one tested definition of what the sensor's three
 * numbers mean, and it has a test measuring it against the real Android orientation maths.
 *
 * ⚠️ **Everything here is best-effort and nothing throws.** No sensor, no permission, no fix: each
 * answers absent, the map says so, and the sky still draws — every star, planet and line in it
 * comes from a bundled catalogue and arithmetic, so the only thing lost is knowing which way is up.
 */
class SkyHardware(private val context: Context) : SkyDeps, SensorEventListener {

    private val sensors = context.getSystemService<SensorManager>()
    private val rotation = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /**
     * ⚠️ **Starts null and is only ever written from a real sensor event.** Never a zeroed reading:
     * see [SkyAttitude], where the cost of publishing one is measured. Because this class holds no
     * seed at all, the defect the interface was shaped to prevent cannot be written here.
     */
    private val _attitude = MutableStateFlow<SkyAttitude?>(null)
    override val attitude: StateFlow<SkyAttitude?> = _attitude.asStateFlow()

    /** Declination east of true north, from the world magnetic model. Zero until a site is known. */
    @Volatile private var declination = 0f

    private val matrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val angles = FloatArray(3)

    override suspend fun site(): SkySite? {
        val manager = context.getSystemService<LocationManager>() ?: return null
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            return null
        }
        // ⚠️ The LAST KNOWN fix, never a live request, and for a star map that is not a compromise.
        // A degree of latitude moves the sky by a degree, so a kilometre of error is under a
        // hundredth of one — far below anything visible even at the quarter-degree field floor.
        // Asking for a fresh fix would spin the radio for an answer no better than the one already
        // in memory, on a screen somebody opens outdoors at night to look at stars.
        val best = runCatching {
            manager.getProviders(true).mapNotNull { p ->
                @Suppress("MissingPermission") manager.getLastKnownLocation(p)
            }.maxByOrNull { it.time }
        }.getOrNull() ?: return null
        declination = runCatching {
            GeomagneticField(
                best.latitude.toFloat(), best.longitude.toFloat(),
                best.altitude.toFloat(), System.currentTimeMillis(),
            ).declination
        }.getOrDefault(0f)
        return SkySite(best.latitude, best.longitude)
    }

    override fun startAttitude(samplingPeriodUs: Int) {
        val sensor = rotation ?: return
        // ⚠️ The period comes from the device budget rather than being fixed at SENSOR_DELAY_GAME,
        // which is what it always was. On a phone with room that is still 20,000 us — the same
        // number, by construction — and on a weak one it is longer, because asking for fifty frames
        // a second from something that can draw fifteen spends battery producing frames nobody sees.
        sensors?.registerListener(this, sensor, samplingPeriodUs)
    }

    override fun stopAttitude() {
        sensors?.unregisterListener(this)
        // ⚠️ Cleared, so a later enable begins from "nothing measured yet" rather than from
        // wherever the phone was last pointed — a stale aim would be taken whole as the first
        // reading, which is the defect this whole shape exists to remove.
        _attitude.value = null
    }

    override fun declinationAt(latitude: Double, longitude: Double, altitudeMetres: Double) {
        declination = runCatching {
            GeomagneticField(
                latitude.toFloat(), longitude.toFloat(), altitudeMetres.toFloat(),
                System.currentTimeMillis(),
            ).declination
        }.getOrDefault(0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // ⚠️ Remapped for a handset held UP like a viewfinder, which is how anybody uses a star
        // map. Without it a tilt corrupts the heading — the flat-held convention reads the azimuth
        // off an axis that stops meaning anything once the phone is aimed at the sky.
        SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, angles)
        // ⚠️ The two sign conventions come from the shared, tested core rather than being written
        // out here, so a change of convention cannot reach one reader and miss another.
        val a = SkyPointing.fromDeviceOrientation(
            azimuthDeg = Math.toDegrees(angles[0].toDouble()),
            pitchDeg = Math.toDegrees(angles[1].toDouble()),
            rollDeg = Math.toDegrees(angles[2].toDouble()),
        )
        _attitude.value = SkyAttitude(
            trueAzimuthDeg = ((a.azimuthDeg + declination) % 360.0 + 360.0) % 360.0,
            altitudeDeg = a.altitudeDeg,
            rollDeg = a.rollDeg,
            accuracyLow = lowAccuracy,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        lowAccuracy = accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
            accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
    }

    /**
     * ⚠️ Kept beside the attitude rather than published on its own, because accuracy can be
     * reported BEFORE the first reading — and a flag with no attitude to attach it to is exactly
     * the seeded value this design refuses to emit.
     */
    @Volatile private var lowAccuracy = false

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Whether this phone can follow where it is pointed at all. The screen says so if it cannot.
     *
     * ⚠️ On the interface now rather than on this class alone, because the view model consults it
     * before honouring a request to follow — a listener that can never fire leaves a sky nothing can
     * turn. See [SkyDeps.hasAttitudeSensor].
     */
    override val hasAttitudeSensor: Boolean get() = rotation != null
}
