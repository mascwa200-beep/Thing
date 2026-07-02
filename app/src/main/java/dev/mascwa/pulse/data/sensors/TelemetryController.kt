package dev.mascwa.pulse.data.sensors

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.atan2
import kotlin.math.sqrt

/** Live on-device telemetry snapshot. Everything here is read straight from the
 *  hardware / OS — no network, works in airplane mode. */
data class Telemetry(
    val hasBarometer: Boolean = false,
    val pressureHpa: Float? = null,
    val pressureAltitudeM: Float? = null,
    val magneticUt: Float? = null,     // field magnitude (µT)
    val accelG: Float? = null,         // |acceleration| / g
    val gyroDps: Float? = null,        // rotation rate magnitude (°/s)
    val lightLux: Float? = null,
    val tiltPitchDeg: Float? = null,
    val tiltRollDeg: Float? = null,
    // system
    val batteryPct: Int? = null,
    val batteryTempC: Float? = null,
    val charging: Boolean = false,
    val netType: String = "—",
    val netSignal: String = "—",
    val memUsedMb: Long = 0,
    val memTotalMb: Long = 0,
    /** Cumulative steps since last device boot (from TYPE_STEP_COUNTER); null if unavailable/no permission. */
    val stepCounterRaw: Long? = null,
)

class TelemetryController(private val context: Context) : SensorEventListener {

    private val sm = context.getSystemService<SensorManager>()
    private val pressure = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val magnet = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val light = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
    // Steps walked since boot — needs ACTIVITY_RECOGNITION for events to flow (API 29+); no-op otherwise.
    private val stepCounter = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val _t = MutableStateFlow(Telemetry(hasBarometer = pressure != null))
    val telemetry: StateFlow<Telemetry> = _t.asStateFlow()

    fun start() {
        listOf(pressure, magnet, accel, gyro, light).forEach { s ->
            s?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        // The step counter changes rarely; a slow delay is plenty and saves power. Defensive — if the
        // ACTIVITY_RECOGNITION permission isn't granted, registration is a harmless no-op (no events).
        stepCounter?.let { runCatching { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } }
        refreshSystem()
    }

    fun stop() {
        sm?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val p = e.values[0]
                _t.update {
                    it.copy(
                        pressureHpa = p,
                        pressureAltitudeM = SensorManager.getAltitude(
                            SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p,
                        ),
                    )
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                _t.update { it.copy(magneticUt = m) }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = e.values[0]; val ay = e.values[1]; val az = e.values[2]
                val g = sqrt(ax * ax + ay * ay + az * az) / 9.80665f
                val pitch = Math.toDegrees(atan2(-ay.toDouble(), sqrt((ax * ax + az * az).toDouble()))).toFloat()
                val roll = Math.toDegrees(atan2(ax.toDouble(), az.toDouble())).toFloat()
                _t.update { it.copy(accelG = g, tiltPitchDeg = pitch, tiltRollDeg = roll) }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                _t.update { it.copy(gyroDps = Math.toDegrees(mag.toDouble()).toFloat()) }
            }
            Sensor.TYPE_LIGHT -> _t.update { it.copy(lightLux = e.values[0]) }
            Sensor.TYPE_STEP_COUNTER -> _t.update { it.copy(stepCounterRaw = e.values[0].toLong()) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Refresh battery / network / memory (cheap to poll periodically). */
    fun refreshSystem() {
        val bm = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bm?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val tempC = bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10f }
        val status = bm?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService<ConnectivityManager>()
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val netType = when {
            caps == null -> "OFFLINE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "ONLINE"
        }
        val sig = caps?.signalStrength?.takeIf { it != Int.MIN_VALUE && it != 0 }?.let { "$it dBm" } ?: "—"

        val am = context.getSystemService<ActivityManager>()
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val totalMb = mi.totalMem / 1_048_576
        val usedMb = (mi.totalMem - mi.availMem) / 1_048_576

        _t.update {
            it.copy(
                batteryPct = pct, batteryTempC = tempC, charging = charging,
                netType = netType, netSignal = sig, memUsedMb = usedMb, memTotalMb = totalMb,
            )
        }
    }
}
