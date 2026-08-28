package dev.mascwa.pulse.core.device

import dev.mascwa.pulse.core.telemetry.DayPart
import dev.mascwa.pulse.core.telemetry.DeviceContext
import dev.mascwa.pulse.core.telemetry.NetworkKind
import dev.mascwa.pulse.core.telemetry.PowerSource

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Calendar
import java.util.Locale

/**
 * Reads a [DeviceContext] from system services.
 *
 * ⚠️ This class lives in `:app`, not beside [DeviceContext] in `:core:telemetry`, and that is the whole
 * reason `:core:telemetry` is a plain Kotlin/JVM module: it was the ONE file of 110 in that module that
 * imported `android.*`, and one Android import is the difference between a core the desktop can depend on
 * and 53 files copied into it by a script. The shape it reads — [DeviceContext], [PowerSource],
 * [NetworkKind], [DayPart] — stays in the core, where it is platform-free and testable; only the reading
 * of system services is here. Provides a one-shot [snapshot] and a cold
 * [updates] flow that re-emits whenever power or connectivity changes. All the broadcasts
 * it listens to are protected system broadcasts, so no exported-receiver flag is required.
 */
class DeviceContextProvider(context: Context) {

    private val appContext = context.applicationContext

    fun snapshot(): DeviceContext {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val source = when (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1) {
            BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.WIRELESS
            else -> if (charging) PowerSource.UNKNOWN else PowerSource.BATTERY
        }
        val powerSave = (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode == true
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return DeviceContext(
            batteryPct = pct,
            isCharging = charging,
            powerSource = source,
            isPowerSave = powerSave,
            network = currentNetwork(),
            dayPart = dayPartOf(hour),
            hour = hour,
            timestamp = System.currentTimeMillis(),
        )
    }

    /**
     * A full human-readable device + app report for J.A.R.V.I.S.: what phone it's on, the OS, the running
     * build, memory, storage, power and network. Best-effort — any field that can't be read is omitted, so
     * it never throws. This is the "what device am I on / deeper phone integration" surface.
     */
    fun deviceReport(): String {
        val c = snapshot()
        val lines = ArrayList<String>()

        val maker = (Build.MANUFACTURER ?: "").replaceFirstChar { it.uppercase() }.trim()
        val model = (Build.MODEL ?: "").trim()
        val codename = (Build.DEVICE ?: "").trim()
        val name = listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "unknown device" }
        lines += "Device: $name" + if (codename.isNotBlank()) " ($codename)" else ""

        val patch = runCatching { Build.VERSION.SECURITY_PATCH }.getOrNull()?.takeIf { it.isNotBlank() }
        lines += "OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}" +
            (if (patch != null) ", security patch $patch" else "") + ")"
        runCatching { Build.SUPPORTED_ABIS.firstOrNull() }.getOrNull()?.let { lines += "ABI: $it" }

        runCatching {
            val pm = appContext.packageManager
            val pi = pm.getPackageInfo(appContext.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            lines += "App: ${pi.versionName} (build #$code) · ${appContext.packageName}"
        }
        runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            lines += "Memory: ${gb(mi.availMem)} free of ${gb(mi.totalMem)}" + if (mi.lowMemory) " (low)" else ""
        }
        runCatching {
            val stat = StatFs(appContext.filesDir.path)
            lines += "Storage: ${gb(stat.availableBytes)} free of ${gb(stat.totalBytes)}"
        }

        val battery = if (c.batteryPct >= 0)
            "${c.batteryPct}%" + (if (c.isCharging) " (charging · ${c.powerSource})" else " · ${c.powerSource}") else "unknown"
        lines += "Power: $battery" + if (c.isPowerSave) " · power-save on" else ""
        lines += "Network: ${c.network} · ${c.hour}:00 (${c.dayPart})"
        return lines.joinToString("\n")
    }

    /**
     * A deeper, READ-ONLY audit of this phone's substrate for J.A.R.V.I.S.: the sensors it can read,
     * the hardware features present (and notably absent), the display, and which declared permissions
     * the app currently holds vs. lacks. Purely observational — it reads system services and the app's
     * own manifest; it never enables, disables or modifies anything on the device or the OS. Best-effort:
     * any block that can't be read is skipped, so it never throws. This is J.A.R.V.I.S.'s window onto
     * his own substrate — what he is running on, and what of it is reachable.
     */
    fun deviceAudit(): String {
        val out = ArrayList<String>()
        out += "Substrate audit (read-only) for $deviceName"

        // --- Sensors ---
        runCatching {
            val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val all = sm.getSensorList(Sensor.TYPE_ALL)
            if (all.isNotEmpty()) {
                out += "Sensors: ${all.size} on this device"
                val notable = listOf(
                    Sensor.TYPE_ACCELEROMETER to "accelerometer",
                    Sensor.TYPE_GYROSCOPE to "gyroscope",
                    Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
                    Sensor.TYPE_PRESSURE to "barometer",
                    Sensor.TYPE_PROXIMITY to "proximity",
                    Sensor.TYPE_LIGHT to "ambient-light",
                    Sensor.TYPE_AMBIENT_TEMPERATURE to "thermometer",
                    Sensor.TYPE_RELATIVE_HUMIDITY to "humidity",
                    Sensor.TYPE_HEART_RATE to "heart-rate",
                    Sensor.TYPE_STEP_COUNTER to "step-counter",
                    Sensor.TYPE_ROTATION_VECTOR to "rotation-vector",
                    Sensor.TYPE_SIGNIFICANT_MOTION to "significant-motion",
                )
                val present = notable.filter { runCatching { sm.getDefaultSensor(it.first) != null }.getOrDefault(false) }
                    .map { it.second }
                if (present.isNotEmpty()) out += "  usable: ${present.joinToString(", ")}"
            }
        }

        // --- Hardware features ---
        runCatching {
            val pm = appContext.packageManager
            val feats = listOf(
                PackageManager.FEATURE_CAMERA_ANY to "camera",
                PackageManager.FEATURE_CAMERA_FLASH to "flash",
                PackageManager.FEATURE_NFC to "NFC",
                PackageManager.FEATURE_BLUETOOTH_LE to "BLE",
                PackageManager.FEATURE_FINGERPRINT to "fingerprint",
                PackageManager.FEATURE_FACE to "face-unlock",
                PackageManager.FEATURE_TELEPHONY to "telephony",
                PackageManager.FEATURE_LOCATION_GPS to "GPS",
                PackageManager.FEATURE_WIFI to "Wi-Fi",
                PackageManager.FEATURE_SENSOR_BAROMETER to "baro-feature",
                PackageManager.FEATURE_SENSOR_HEART_RATE to "hr-feature",
            )
            val have = feats.filter { pm.hasSystemFeature(it.first) }.map { it.second }
            val lack = feats.filterNot { pm.hasSystemFeature(it.first) }.map { it.second }
            if (have.isNotEmpty()) out += "Hardware present: ${have.joinToString(", ")}"
            if (lack.isNotEmpty()) out += "Hardware absent: ${lack.joinToString(", ")}"
        }

        // --- Display ---
        runCatching {
            val dm = appContext.resources.displayMetrics
            var line = "Display: ${dm.widthPixels}×${dm.heightPixels} @ ${dm.densityDpi} dpi"
            val disp = (appContext.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            disp?.refreshRate?.let { line += " · ${"%.0f".format(it)} Hz" }
            out += line
        }

        // --- Permissions (what of the substrate is reachable vs. grantable) ---
        runCatching {
            val pm = appContext.packageManager
            val pi = pm.getPackageInfo(appContext.packageName, PackageManager.GET_PERMISSIONS)
            val requested = pi.requestedPermissions?.toList().orEmpty()
            if (requested.isNotEmpty()) {
                val granted = ArrayList<String>()
                val denied = ArrayList<String>()
                for (p in requested) {
                    val ok = runCatching { appContext.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
                        .getOrDefault(false)
                    val short = p.substringAfterLast('.')
                    if (ok) granted += short else denied += short
                }
                out += "Permissions: ${granted.size} held, ${denied.size} grantable (of ${requested.size} declared)"
                if (denied.isNotEmpty()) out += "  grantable: ${denied.take(12).joinToString(", ")}"
            }
        }

        out += "I cannot change any of this from here — this is an audit, not a control surface."
        return out.joinToString("\n")
    }

    private val deviceName: String
        get() {
            val maker = (Build.MANUFACTURER ?: "").replaceFirstChar { it.uppercase() }.trim()
            val model = (Build.MODEL ?: "").trim()
            return listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "this device" }
        }

    /**
     * ⚠️ **[Locale.US], because this is a number rather than prose.** The default locale renders
     * "5,7 GB" on most of Europe, and every consumer of this reads it as data: the `device` tool
     * hands it to the model, the dossier puts it on a screen, and the persona carries it as
     * context. A comma decimal there is at best confusing and at worst parsed as a thousands
     * separator by whatever reads it next.
     *
     * Not promoted to `Formatters` alongside `megabytes`, deliberately: this is its only caller, and
     * the unit is a judgement about what this particular readout is describing — RAM and storage in
     * the gigabyte range, where `megabytes` would render "7629.4 MB" and be worse.
     */
    private fun gb(bytes: Long): String =
        if (bytes <= 0) "?" else String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)

    /** Emits the current context immediately, then on every power / connectivity change. */
    val updates: Flow<DeviceContext> = callbackFlow {
        trySend(snapshot())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                trySend(snapshot())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        appContext.registerReceiver(receiver, filter)
        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }

    private fun currentNetwork(): NetworkKind {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkKind.OTHER
        val active = cm.activeNetwork ?: return NetworkKind.OFFLINE
        val caps = cm.getNetworkCapabilities(active) ?: return NetworkKind.OFFLINE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkKind.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            else -> NetworkKind.OTHER
        }
    }

    private fun dayPartOf(hour: Int): DayPart = when (hour) {
        in 5..11 -> DayPart.MORNING
        in 12..16 -> DayPart.AFTERNOON
        in 17..21 -> DayPart.EVENING
        else -> DayPart.NIGHT
    }
}
