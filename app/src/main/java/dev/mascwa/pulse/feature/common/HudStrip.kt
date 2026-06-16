package dev.mascwa.pulse.feature.common

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import dev.mascwa.pulse.core.connectivity.LocalIsOnline
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live data for the always-on HUD strip, provided from the activity root. */
data class HudState(val kp: Double?, val hasLocation: Boolean, val enabled: Boolean)

val LocalHud = staticCompositionLocalOf { HudState(null, false, false) }

/**
 * Slim cockpit telemetry strip rendered under every screen's app bar: clock,
 * GPS-lock, network link, battery and the live planetary K-index. Reads battery
 * and connectivity locally on a 1 s tick; Kp + GPS state come via [LocalHud].
 */
@Composable
fun HudStrip(modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    if (!hud.enabled) return
    val online = LocalIsOnline.current
    val c = Pulse.colors
    val context = LocalContext.current

    var clock by remember { mutableStateOf(timeNow()) }
    var battery by remember { mutableStateOf<Int?>(null) }
    var charging by remember { mutableStateOf(false) }
    var net by remember { mutableStateOf("—") }

    LaunchedEffect(Unit) {
        while (true) {
            clock = timeNow()
            val bm = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val lvl = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bm?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            battery = if (lvl >= 0 && scale > 0) lvl * 100 / scale else null
            val st = bm?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            net = netType(context)
            delay(1000)
        }
    }

    Row(
        modifier.fillMaxWidth().background(c.carbon).padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◢NW", fontFamily = JetBrainsMono, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c.accent)
            Text(clock, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.ink2, modifier = Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HudCell("GPS", if (hud.hasLocation) "LOCK" else "----", if (hud.hasLocation) c.positive else c.faint)
            HudCell("NET", if (online) net else "OFF", if (online) c.positive else c.negative)
            HudCell("BAT", battery?.let { "$it%${if (charging) "+" else ""}" } ?: "--", batteryColor(battery, charging, c))
            HudCell("KP", hud.kp?.let { "%.0f".format(it) } ?: "--", kpColor(hud.kp, c))
        }
    }
}

@Composable
private fun HudCell(label: String, value: String, color: Color) {
    val c = Pulse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 7.sp, letterSpacing = 0.4.sp, color = c.muted)
        Text(value, fontFamily = JetBrainsMono, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.padding(start = 3.dp))
    }
}

private fun timeNow(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

private fun netType(context: Context): String {
    val cm = context.getSystemService<ConnectivityManager>() ?: return "—"
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "OFF"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
        else -> "NET"
    }
}

private fun batteryColor(pct: Int?, charging: Boolean, c: NightwirePalette): Color = when {
    charging -> c.positive
    pct == null -> c.muted
    pct <= 15 -> c.negative
    pct <= 30 -> c.amber
    else -> c.ink2
}

private fun kpColor(kp: Double?, c: NightwirePalette): Color = when {
    kp == null -> c.muted
    kp >= 5 -> c.magenta
    kp >= 4 -> c.amber
    else -> c.positive
}
