package dev.mascwa.pulse.data.sensing

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Sensorium's continuous, type-free senses — everything that costs almost nothing and needs no
 * foreground-service camera/mic types: accelerometer (smoothed into the movement EWMA the fusion core
 * expects), light, barometer (with a ~3 h history ring so the core can read storm-front trends),
 * magnetometer, proximity — registered at SENSOR_DELAY_NORMAL with generous batching (the EWMAs don't
 * need 60 ms updates, and unbatched UI-rate 24/7 would needlessly hold the AP awake) — plus on-demand
 * radio-density bursts (WiFi AP count, BLE device count) the engine calls on its own cadence.
 *
 * The movement EWMA carries the recorded perception-era fix: it smooths |accelG − 1| (deviation from
 * rest), never the raw ~1 g magnitude, so a still phone reads ~0 and a handling spike damps out.
 */
/**
 * Which of the continuous senses this phone actually has.
 *
 * ⚠️ **Nothing knew this before, and every reader of a null value had to guess what it meant.**
 * [start] asks for five sensors and quietly skips any the phone does not expose, so a null
 * `lightLux` meant either "no ambient-light sensor on this hardware" or "registered, no event yet"
 * — indistinguishable, and the scanner rendered both by dropping the row entirely, which is a third
 * thing again. Ambient-light and barometer are the two that phones genuinely ship without.
 *
 * Every field is what `getDefaultSensor` ACTUALLY returned, recorded at registration. It is not a
 * capability table written from what a Pixel happens to have.
 */
data class SensorsPresent(
    val accelerometer: Boolean = false,
    val light: Boolean = false,
    val pressure: Boolean = false,
    val magnetometer: Boolean = false,
    val proximity: Boolean = false,
)

data class FusionSnapshot(
    val movement: Float = 0f,
    val lightLux: Float? = null,
    val pressureHpa: Float? = null,
    /** Pressure change vs ~3 h ago; null until the history ring spans at least ~1 h. */
    val pressureDeltaHpa: Float? = null,
    val magneticUt: Float? = null,
    val proximityNear: Boolean? = null,
    /**
     * What this phone has, filled in by [SensorFusionController.start].
     *
     * ⚠️ **Null until `start()` has run, and that is a third state on purpose.** An all-false value
     * would have been read as "this phone has no sensors at all" by any surface that renders it —
     * which is exactly the conflation this field exists to end, reintroduced one layer up. The
     * scanner can be open while the sensing service is stood down, so this case is reached in
     * ordinary use, not only in theory.
     */
    val present: SensorsPresent? = null,
)

class SensorFusionController(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val _snapshot = MutableStateFlow(FusionSnapshot())
    val snapshot: StateFlow<FusionSnapshot> = _snapshot.asStateFlow()

    private var movementEwma = 0f
    /** (epochMs, hPa) samples ~5 min apart, trimmed to the last ~4 h. */
    private val pressureHistory = ArrayDeque<Pair<Long, Float>>()
    private var lastPressureSampleMs = 0L

    fun start() {
        val got = mutableSetOf<Int>()
        listOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE,
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_PROXIMITY,
        ).forEach { type ->
            sensorManager.getDefaultSensor(type)?.let {
                // NORMAL rate + a long batch latency: the hardware FIFO coalesces deliveries so the
                // AP can sleep between batches — the whole point of a 24/7 registration being cheap.
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, BATCH_LATENCY_US)
                got += type
            }
        }
        // ⚠️ Recorded from what the platform actually handed over, not from a table of what a Pixel
        // has. This is the whole difference between a screen that can say "this phone has no
        // barometer" and one that can only show a blank where the reading would have been.
        _snapshot.value = _snapshot.value.copy(
            present = SensorsPresent(
                accelerometer = Sensor.TYPE_ACCELEROMETER in got,
                light = Sensor.TYPE_LIGHT in got,
                pressure = Sensor.TYPE_PRESSURE in got,
                magnetometer = Sensor.TYPE_MAGNETIC_FIELD in got,
                proximity = Sensor.TYPE_PROXIMITY in got,
            ),
        )
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = event.values
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                movementEwma = MOTION_SMOOTH * movementEwma + (1 - MOTION_SMOOTH) * abs(g - 1f)
                _snapshot.value = _snapshot.value.copy(movement = movementEwma)
            }
            Sensor.TYPE_LIGHT ->
                _snapshot.value = _snapshot.value.copy(lightLux = event.values[0])
            Sensor.TYPE_PRESSURE -> {
                val hPa = event.values[0]
                recordPressure(hPa)
                _snapshot.value = _snapshot.value.copy(
                    pressureHpa = hPa,
                    pressureDeltaHpa = pressureDelta(hPa),
                )
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val (x, y, z) = event.values
                _snapshot.value = _snapshot.value.copy(magneticUt = sqrt(x * x + y * y + z * z))
            }
            Sensor.TYPE_PROXIMITY -> {
                val near = event.values[0] < (event.sensor.maximumRange.takeIf { it > 0 } ?: 5f) / 2f
                _snapshot.value = _snapshot.value.copy(proximityNear = near)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun recordPressure(hPa: Float) {
        val now = System.currentTimeMillis()
        if (now - lastPressureSampleMs < PRESSURE_SAMPLE_GAP_MS) return
        lastPressureSampleMs = now
        synchronized(pressureHistory) {
            pressureHistory.addLast(now to hPa)
            while (pressureHistory.isNotEmpty() && now - pressureHistory.first().first > PRESSURE_KEEP_MS) {
                pressureHistory.removeFirst()
            }
        }
    }

    /** Current minus the sample closest to 3 h ago — null until the ring spans at least ~1 h. */
    private fun pressureDelta(current: Float): Float? {
        val now = System.currentTimeMillis()
        val target = now - PRESSURE_TREND_MS
        synchronized(pressureHistory) {
            val oldest = pressureHistory.firstOrNull() ?: return null
            if (now - oldest.first < PRESSURE_MIN_SPAN_MS) return null
            val ref = pressureHistory.minByOrNull { abs(it.first - target) } ?: return null
            return current - ref.second
        }
    }

    // ---- radio density (on-demand; the engine owns cadence + settings gating) ----

    /** Distinct WiFi APs currently visible. Null without fine location (the scan APIs' hard
     *  requirement). Reads the system's cached scan results — any app's scan counts — after a
     *  best-effort scan request that the OS may throttle (~4/2 min foreground); staleness is fine,
     *  this is a density signal, not a survey. */
    fun wifiApCount(): Int? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            runCatching { wifi.startScan() } // best-effort; throttling just means we read cached results
            @Suppress("DEPRECATION")
            wifi.scanResults.mapNotNull { it.BSSID }.toSet().size
        }.getOrNull()
    }

    /** Unique BLE devices heard in one ~[BLE_BURST_MS] burst — the crowd-density proxy. Per-burst
     *  counts only (MAC randomization makes cross-burst identity meaningless); one empty filter
     *  defeats the screen-off unfiltered-scan suppression. Null without BLUETOOTH_SCAN / adapter off.
     *  The engine must not call this while the vitals strap scanner is running. */
    suspend fun bleBurstCount(): Int? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            if (!adapter.isEnabled) return null
            val scanner = adapter.bluetoothLeScanner ?: return null
            val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    seen += result.device.address
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { seen += it.device.address }
                }
            }
            val filters = listOf(ScanFilter.Builder().build())
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            scanner.startScan(filters, settings, callback)
            try {
                delay(BLE_BURST_MS)
            } finally {
                runCatching { scanner.stopScan(callback) }
            }
            seen.size
        }.getOrNull()
    }

    private companion object {
        /** EWMA weight on the previous movement value — heavy smoothing, ~seconds to settle. */
        const val MOTION_SMOOTH = 0.8f
        /** Batch FIFO latency: deliveries may lag up to this — fine for EWMAs and hourly baselines. */
        const val BATCH_LATENCY_US = 10_000_000
        const val PRESSURE_SAMPLE_GAP_MS = 5 * 60_000L
        const val PRESSURE_KEEP_MS = 4 * 60 * 60_000L
        const val PRESSURE_TREND_MS = 3 * 60 * 60_000L
        const val PRESSURE_MIN_SPAN_MS = 60 * 60_000L
        const val BLE_BURST_MS = 12_000L
    }
}
