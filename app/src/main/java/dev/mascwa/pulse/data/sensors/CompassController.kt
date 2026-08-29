package dev.mascwa.pulse.data.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.getSystemService
import dev.mascwa.pulse.core.telemetry.SkyPointing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads the device orientation and exposes a compass [heading].
 *
 * Magnetic azimuth comes from the rotation-vector sensor (fused, smooth).
 * True-north declination is computed **offline** with [GeomagneticField] (WMM)
 * once a location is provided — no network required.
 *
 * [cameraUpright] mode remaps the coordinate system for a phone held vertically like a viewfinder (AR): the
 * azimuth then follows the direction the CAMERA points and stays stable as you tilt up/down (the flat-held
 * default lets a tilt corrupt the heading — that's the "tilting drifts sideways" bug), and [Reading.pitch]
 * and [Reading.roll] become meaningful (how far up/down you're aiming, and how far the handset is tipped
 * in its own plane).
 *
 * ⚠️ **The two signs come from [SkyPointing.fromDeviceOrientation] rather than being written here**, so
 * there is one tested definition of what the sensor's numbers mean rather than a convention restated at
 * each reader. `SkyPointingTest` measures both against the real Android orientation maths; this callback
 * has no tests and never will.
 */
class CompassController(
    context: Context,
    private val cameraUpright: Boolean = false,
    /**
     * Whether to low-pass the angles before publishing them.
     *
     * ⚠️ **A planetarium wants this OFF, and the reason is the zenith.** Smoothing three Euler angles
     * separately is right for a compass rose, which is read with the handset roughly level where the
     * azimuth is well-conditioned. Aimed near overhead the azimuth swings through tens of degrees for a
     * centimetre of hand movement — it barely moves the LOOK direction, because `cos(altitude)` is almost
     * zero there, but it spins the picture about its own centre, because which way is up the screen is
     * decided by the azimuth alone. Filtering that number makes the spin lag rather than removing it.
     * `SkyPointing.smooth` blends the two directions instead, which has no such failure; a caller doing
     * that must take the angles unfiltered or the whip is already baked in before it sees them.
     *
     * Defaulted true, so the compass, the HUD and the nav map behave exactly as they always have.
     */
    private val smoothed: Boolean = true,
) : SensorEventListener {

    data class Reading(
        val magneticAzimuth: Float = 0f,   // degrees 0..360
        val declination: Float = 0f,       // degrees east(+)/west(-)
        val accuracyLow: Boolean = false,  // needs figure-8 calibration
        val hasSensor: Boolean = true,
        /**
         * Whether the sensor has actually reported yet, as opposed to this being the seed.
         *
         * ⚠️ **[hasSensor] does NOT answer that and reading it as though it did cost a real
         * defect.** It says the hardware exists, which is true from construction, so the seeded
         * value below carries `hasSensor = true` with every angle at zero — the phone held level
         * and pointed due north. A `StateFlow` hands a new collector its current value at once, so
         * a caller that treats its first emission as a measurement is measuring nothing.
         *
         * The star map was doing exactly that: its "take the FIRST reading whole rather than
         * blending it in" branch spent itself on the seed, and the first real sample then arrived
         * weighted at a quarter, so the sky swept in from due north over about a third of a second
         * every time pointing mode was switched on.
         *
         * ⚠️ **Defaulted false and never written except from a real event**, so a consumer that
         * does not care is unaffected and one that does cannot get it wrong by omission.
         */
        val hasReading: Boolean = false,
        val pitch: Float = 0f,             // degrees up(+)/down(-); only meaningful in cameraUpright mode
        /**
         * Degrees the handset is tipped in its own plane, positive when its TOP goes to the RIGHT,
         * in `(-180, 180]`. Only meaningful in [cameraUpright] mode; zero otherwise.
         */
        val roll: Float = 0f,
    ) {
        val trueAzimuth: Float get() = ((magneticAzimuth + declination) % 360f + 360f) % 360f
    }

    private val sensorManager = context.getSystemService<SensorManager>()
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _reading = MutableStateFlow(Reading(hasSensor = rotationSensor != null))
    val reading: StateFlow<Reading> = _reading.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Low-pass state for the heading (null until the first reading). */
    private var filteredAzimuth: Float? = null
    private var filteredPitch: Float? = null
    private var filteredRoll: Float? = null

    fun setLocation(lat: Double, lon: Double, altitude: Double) {
        val geo = GeomagneticField(
            lat.toFloat(), lon.toFloat(), altitude.toFloat(), System.currentTimeMillis(),
        )
        _reading.value = _reading.value.copy(declination = geo.declination)
    }

    fun start() {
        val sensor = rotationSensor ?: return
        // GAME rate gives smoother, more responsive heading updates than UI.
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        // AR viewfinder: remap so azimuth follows the camera direction and stays stable while tilting up/down.
        val matrix = if (cameraUpright) {
            SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedMatrix,
            )
            remappedMatrix
        } else {
            rotationMatrix
        }
        SensorManager.getOrientation(matrix, orientation)
        // ⚠️ One tested definition of what the sensor's three numbers mean, rather than the two
        // negations written out here. The azimuth is taken from the attitude too so that a change of
        // convention could not reach two of the three and miss the first.
        val attitude = SkyPointing.fromDeviceOrientation(
            azimuthDeg = Math.toDegrees(orientation[0].toDouble()),
            pitchDeg = Math.toDegrees(orientation[1].toDouble()),
            rollDeg = Math.toDegrees(orientation[2].toDouble()),
        )
        val raw = (attitude.azimuthDeg.toFloat() % 360f + 360f) % 360f
        // Circular low-pass: averaging via sin/cos so 359°→1° doesn't smear to 180°.
        val az = if (smoothed) filteredAzimuth?.let { circularLerp(it, raw, SMOOTHING) } ?: raw else raw
        filteredAzimuth = az
        if (cameraUpright) {
            val rawPitch = attitude.altitudeDeg.toFloat()
            val p = if (smoothed) {
                filteredPitch?.let { it + PITCH_SMOOTHING * (rawPitch - it) } ?: rawPitch
            } else {
                rawPitch
            }
            filteredPitch = p
            // ⚠️ Roll takes the CIRCULAR filter and not the pitch's linear one. It runs the whole way
            // round, so a handset turned past upside-down crosses ±180 — and a linear average across
            // that boundary answers zero, snapping the picture upright at exactly the moment it should
            // be inverted. Pitch cannot do this: it is bounded at ±90 and never wraps.
            val rawRoll = attitude.rollDeg.toFloat()
            val r = if (smoothed) {
                filteredRoll?.let { circularLerp(it, rawRoll, ROLL_SMOOTHING) } ?: rawRoll
            } else {
                rawRoll
            }
            filteredRoll = r
            _reading.value = _reading.value.copy(
                magneticAzimuth = az, pitch = p, roll = wrapSigned(r), hasReading = true,
            )
        } else {
            _reading.value = _reading.value.copy(magneticAzimuth = az, hasReading = true)
        }
    }

    /** Into `(-180, 180]`, which is how a tip to one side or the other reads. */
    private fun wrapSigned(deg: Float): Float {
        val d = (deg % 360f + 360f) % 360f
        return if (d > 180f) d - 360f else d
    }

    private fun circularLerp(prev: Float, curr: Float, alpha: Float): Float {
        val p = Math.toRadians(prev.toDouble())
        val c = Math.toRadians(curr.toDouble())
        val sin = (1 - alpha) * Math.sin(p) + alpha * Math.sin(c)
        val cos = (1 - alpha) * Math.cos(p) + alpha * Math.cos(c)
        val deg = Math.toDegrees(Math.atan2(sin, cos)).toFloat()
        return (deg % 360f + 360f) % 360f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val low = accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
            accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
        _reading.value = _reading.value.copy(accuracyLow = low)
    }

    private companion object {
        /** Low-pass weight for new samples (higher = snappier, lower = smoother). */
        const val SMOOTHING = 0.2f
        /** Pitch is less jittery than azimuth — a gentle low-pass keeps the vertical parallax smooth. */
        const val PITCH_SMOOTHING = 0.25f

        /** Roll is as jittery as the azimuth and read the same way, so it gets the same weight. */
        const val ROLL_SMOOTHING = 0.2f
    }
}
