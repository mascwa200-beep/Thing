package dev.mascwa.pulse.core.telemetry

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

/**
 * Reads a [DeviceContext] from system services. Provides a one-shot [snapshot] and a cold
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

    private fun gb(bytes: Long): String =
        if (bytes <= 0) "?" else String.format("%.1f GB", bytes / 1_073_741_824.0)

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
