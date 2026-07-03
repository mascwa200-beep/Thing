package dev.mascwa.pulse.core.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

/** One sensor of interest and whether this device actually exposes it (with the underlying hardware name). */
data class SensorPresence(val label: String, val type: Int, val present: Boolean, val name: String)

/**
 * What the device's sensor hardware can actually do — so the activity-sensing (shower / eating / bathroom
 * detection) is built on **real** capabilities, not assumptions. In particular Pixels ship a barometer but
 * typically NO relative-humidity or ambient-temperature sensor, so "a hot shower spikes the humidity" isn't
 * a signal we actually have; the honest signal is sound (running water) + scene + context. This probe settles
 * that per-device instead of guessing. Cheap + synchronous (SensorManager lookups do no I/O).
 */
class SensorCapabilities(private val context: Context) {

    /** The sensors relevant to ambient activity attestation, each probed for presence on THIS device. */
    fun probe(): List<SensorPresence> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return SENSORS.map { (label, type) ->
            val s = sm?.getDefaultSensor(type)
            SensorPresence(label, type, s != null, s?.name ?: "—")
        }
    }

    /** A compact readout: which useful ambient sensors are present vs. missing on this device. */
    fun summary(): String {
        val p = probe()
        val have = p.filter { it.present }.joinToString(", ") { it.label.substringBefore(" (") }
        val miss = p.filter { !it.present }.joinToString(", ") { it.label.substringBefore(" (") }
        return "Present: ${have.ifBlank { "none" }}\nMissing: ${miss.ifBlank { "none" }}"
    }

    private companion object {
        val SENSORS = listOf(
            "Barometer (pressure)" to Sensor.TYPE_PRESSURE,
            "Relative humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
            "Ambient temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
            "Ambient light" to Sensor.TYPE_LIGHT,
            "Proximity" to Sensor.TYPE_PROXIMITY,
            "Step counter" to Sensor.TYPE_STEP_COUNTER,
            "Accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "Gyroscope" to Sensor.TYPE_GYROSCOPE,
            "Magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
        )
    }
}
