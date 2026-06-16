package dev.mascwa.pulse.core.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
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
