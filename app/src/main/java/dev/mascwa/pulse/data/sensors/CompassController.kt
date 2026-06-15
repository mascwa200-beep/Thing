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

    fun setLocation(lat: Double, lon: Double, altitude: Double) {
        val geo = GeomagneticField(
            lat.toFloat(), lon.toFloat(), altitude.toFloat(), System.currentTimeMillis(),
        )
        _reading.value = _reading.value.copy(declination = geo.declination)
    }

    fun start() {
        val sensor = rotationSensor ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuth = ((Math.toDegrees(orientation[0].toDouble()).toFloat()) % 360f + 360f) % 360f
        _reading.value = _reading.value.copy(magneticAzimuth = azimuth)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val low = accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
            accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
        _reading.value = _reading.value.copy(accuracyLow = low)
    }
}
