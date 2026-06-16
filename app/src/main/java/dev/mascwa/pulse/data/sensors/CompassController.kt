package dev.mascwa.pulse.data.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads the device orientation and exposes a compass [heading].
 *
 * Magnetic azimuth comes from the rotation-vector sensor (fused, smooth).
 * True-north declination is computed **offline** with [GeomagneticField] (WMM)
 * once a location is provided — no network required.
 */
class CompassController(context: Context) : SensorEventListener {

    data class Reading(
        val magneticAzimuth: Float = 0f,   // degrees 0..360
        val declination: Float = 0f,       // degrees east(+)/west(-)
        val accuracyLow: Boolean = false,  // needs figure-8 calibration
        val hasSensor: Boolean = true,
    ) {
        val trueAzimuth: Float get() = ((magneticAzimuth + declination) % 360f + 360f) % 360f
    }

    private val sensorManager = context.getSystemService<SensorManager>()
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _reading = MutableStateFlow(Reading(hasSensor = rotationSensor != null))
    val reading: StateFlow<Reading> = _reading.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Low-pass state for the heading (null until the first reading). */
    private var filteredAzimuth: Float? = null

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
        SensorManager.getOrientation(rotationMatrix, orientation)
        val raw = ((Math.toDegrees(orientation[0].toDouble()).toFloat()) % 360f + 360f) % 360f
        // Circular low-pass: averaging via sin/cos so 359°→1° doesn't smear to 180°.
        val smoothed = filteredAzimuth?.let { circularLerp(it, raw, SMOOTHING) } ?: raw
        filteredAzimuth = smoothed
        _reading.value = _reading.value.copy(magneticAzimuth = smoothed)
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
    }
}
