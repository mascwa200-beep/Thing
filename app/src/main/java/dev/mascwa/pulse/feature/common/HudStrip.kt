package dev.mascwa.pulse.feature.common

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.getSystemService
import dev.mascwa.pulse.core.connectivity.LocalIsOnline
import dev.mascwa.pulse.ui.effects.DecryptText
import dev.mascwa.pulse.ui.effects.GlitchText
import dev.mascwa.pulse.ui.effects.LocalGlitchEnabled
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Live data for the always-on HUD strip, provided from the activity root. */
data class HudState(
    val kp: Double?,
    val hasLocation: Boolean,
    val enabled: Boolean,
    val auroraPct: Int? = null,
    val solarWindKmS: Double? = null,
    val bz: Double? = null,
    val stormLevel: String = "None",
    val dataStream: Boolean = true,
)

val LocalHud = staticCompositionLocalOf { HudState(null, false, false) }

private enum class Health { NOMINAL, CAUTION, CRITICAL }

/**
 * Cyberpunk cockpit HUD rendered under every screen's app bar. A bracketed status strip
 * (callsign · live clock · health pulse · STATUS readout · GPS/NET/BAT/KP gauges) over a
 * scrolling data-stream marquee, with a tap-to-scan diagnostics overlay. Every value is read
 * from real hardware/OS state or the live space-weather feed — nothing is fabricated.
 */
@Composable
fun HudStrip(modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    if (!hud.enabled) return
    val online = LocalIsOnline.current
    val glitch = LocalGlitchEnabled.current
    val c = Pulse.colors
    val accent = c.accent
    val context = LocalContext.current

    var clock by remember { mutableStateOf(timeNow()) }
    var battery by remember { mutableStateOf<Int?>(null) }
    var charging by remember { mutableStateOf(false) }
    var tempC by remember { mutableStateOf<Float?>(null) }
    var net by remember { mutableStateOf("—") }
    var netBars by remember { mutableStateOf(-1) }
    var memUsed by remember { mutableStateOf(0L) }
    var memTotal by remember { mutableStateOf(0L) }
    var scanOpen by remember { mutableStateOf(false) }

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
            val temp = bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            tempC = if (temp > 0) temp / 10f else null
            net = netType(context)
            netBars = netBars(context)
            readMem(context)?.let { (u, t) -> memUsed = u; memTotal = t }
            delay(1000)
        }
    }

    val health = computeHealth(battery, charging, tempC, online, hud.kp)
    val healthColor = healthColor(health, c)

    val pulse = rememberInfiniteTransition(label = "hud")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot",
    )
    val lineAlpha by pulse.animateFloat(
        initialValue = 0.12f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "line",
    )

    Column(modifier.fillMaxWidth().background(c.carbon)) {
        Row(
            Modifier
                .fillMaxWidth()
                .let { if (hud.dataStream) it.clickable { scanOpen = true } else it }
                .drawWithContent {
                    drawContent()
                    hudCorners(accent, 9.dp.toPx(), 1.2.dp.toPx(), 2.dp.toPx())
                }
                .padding(horizontal = 14.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("◢NW//SYS", fontFamily = ChakraPetch, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = accent)
                // Alpha read in the layer phase (not composition) so the pulse doesn't recompose the
                // whole always-visible strip every animation frame. Same output: one opaque glyph faded.
                Text("◉", fontSize = 8.sp, color = healthColor, modifier = Modifier.graphicsLayer { alpha = dotAlpha })
                Text(clock, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.ink2)
                DecryptText(
                    text = healthWord(health),
                    fontFamily = ChakraPetch, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    color = healthColor, letterSpacing = 1.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudCell("GPS", if (hud.hasLocation) "LOCK" else "----", if (hud.hasLocation) c.positive else c.faint)
                NetCell(online, net, netBars, c)
                BatteryCell(battery, charging, batteryColor(battery, charging, c), glitch && health == Health.CRITICAL, c)
                KpCell(hud.kp, hud.stormLevel, c)
            }
        }
        // Breathing accent scan-line under the strip. Alpha read in the draw phase (drawBehind), not
        // composition, so the breathing doesn't recompose the strip each frame.
        Box(Modifier.fillMaxWidth().height(1.5.dp).drawBehind { drawRect(accent, alpha = lineAlpha) })

        if (hud.dataStream) {
            val items = remember(hud, tempC, memUsed, memTotal, c) { dataStreamItems(hud, tempC, memUsed, memTotal, c) }
            if (items.isNotEmpty()) Ticker(items)
        }
    }

    if (scanOpen) ScannerDialog(onClose = { scanOpen = false })
}

/* ----- status strip cells ----- */

@Composable
private fun HudCell(label: String, value: String, color: Color) {
    val c = Pulse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 7.sp, letterSpacing = 0.4.sp, color = c.muted)
        Text(value, fontFamily = JetBrainsMono, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.padding(start = 3.dp))
    }
}

@Composable
private fun NetCell(online: Boolean, net: String, bars: Int, c: NightwirePalette) {
    val color = if (online) c.positive else c.negative
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("NET", fontFamily = JetBrainsMono, fontSize = 7.sp, letterSpacing = 0.4.sp, color = c.muted)
        if (online && bars in 0..4) {
            SegBar(filled = bars, total = 4, color = color, modifier = Modifier.padding(start = 3.dp))
        }
        Text(
            if (online) net else "OFF",
            fontFamily = JetBrainsMono, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = color,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}

@Composable
private fun BatteryCell(pct: Int?, charging: Boolean, color: Color, glitch: Boolean, c: NightwirePalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("BAT", fontFamily = JetBrainsMono, fontSize = 7.sp, letterSpacing = 0.4.sp, color = c.muted)
        SegBar(
            filled = pct?.let { (it / 20).coerceIn(0, 5) } ?: 0, total = 5, color = color,
            modifier = Modifier.padding(start = 3.dp),
        )
        val value = pct?.let { "$it%${if (charging) "+" else ""}" } ?: "--"
        GlitchText(
            text = value,
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 9.sp, fontWeight = FontWeight.Medium),
            glitch = glitch, baseColor = color, accent = c.accent, magenta = c.magenta,
        )
    }
}

@Composable
private fun KpCell(kp: Double?, stormLevel: String, c: NightwirePalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("KP", fontFamily = JetBrainsMono, fontSize = 7.sp, letterSpacing = 0.4.sp, color = c.muted)
        SegBar(
            filled = kp?.roundToInt()?.coerceIn(0, 9) ?: 0, total = 9, color = kpColor(kp, c),
            modifier = Modifier.padding(start = 3.dp), segWidth = 2.dp,
        )
        gScale(stormLevel)?.let { g ->
            Text(
                g, fontFamily = JetBrainsMono, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = c.magenta,
                modifier = Modifier
                    .padding(start = 3.dp)
                    .border(0.8.dp, c.magenta.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun SegBar(filled: Int, total: Int, color: Color, modifier: Modifier = Modifier, segWidth: androidx.compose.ui.unit.Dp = 4.dp) {
    val c = Pulse.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            Box(
                Modifier
                    .size(width = segWidth, height = 7.dp)
                    .background(if (i < filled) color else c.faint.copy(alpha = 0.35f)),
            )
        }
    }
}

/* ----- data-stream marquee ----- */

private fun dataStreamItems(hud: HudState, tempC: Float?, memUsed: Long, memTotal: Long, c: NightwirePalette): List<TickerItem> {
    val items = mutableListOf<TickerItem>()
    if (memTotal > 0) items += TickerItem("MEM", "%.1f/%.1fG".format(memUsed / 1024.0, memTotal / 1024.0), c.ink2)
    tempC?.let { items += TickerItem("THM", "%.1f°C".format(it), if (it >= 42) c.magenta else if (it >= 38) c.amber else c.ink2) }
    hud.auroraPct?.let { items += TickerItem("AUR", "$it%", auroraColor(it, c)) }
    hud.solarWindKmS?.let { items += TickerItem("SOLWIND", "${it.roundToInt()}km/s", c.sky) }
    hud.bz?.let { items += TickerItem("Bz", "%.0fnT".format(it), if (it <= -8) c.magenta else c.ink2) }
    gScale(hud.stormLevel)?.let { items += TickerItem("STORM", it, c.magenta) }
    return items
}

/* ----- tap-to-scan diagnostics overlay ----- */

@Composable
private fun ScannerDialog(onClose: () -> Unit) {
    val c = Pulse.colors
    val context = LocalContext.current
    val controller = remember {
        (context.applicationContext as dev.mascwa.pulse.PulseApplication).container.newTelemetryController()
    }
    DisposableEffect(Unit) {
        runCatching { controller.start() }
        onDispose { runCatching { controller.stop() } }
    }
    val t by controller.telemetry.collectAsState()

    Dialog(onDismissRequest = onClose) {
        NeonPanel(corners = true, borderColor = c.accent.copy(alpha = 0.6f)) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                DecryptText(
                    "◢ SCANNER // ON-DEVICE TELEMETRY",
                    fontFamily = ChakraPetch, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = c.accent, letterSpacing = 1.sp,
                )
                ScanRow("BARO", t.pressureHpa?.let { "%.1f hPa".format(it) } ?: "—")
                ScanRow("ALT", t.pressureAltitudeM?.let { "%.0f m".format(it) } ?: "—")
                ScanRow("MAG", t.magneticUt?.let { "%.0f µT".format(it) } ?: "—")
                ScanRow("LUX", t.lightLux?.let { "%.0f".format(it) } ?: "—")
                ScanRow("ACCEL", t.accelG?.let { "%.2f g".format(it) } ?: "—")
                ScanRow("GYRO", t.gyroDps?.let { "%.0f °/s".format(it) } ?: "—")
                ScanRow("TILT", if (t.tiltPitchDeg != null && t.tiltRollDeg != null) "P %+.0f° · R %+.0f°".format(t.tiltPitchDeg, t.tiltRollDeg) else "—")
                ScanRow("THERM", t.batteryTempC?.let { "%.1f °C".format(it) } ?: "—")
                ScanRow("MEM", if (t.memTotalMb > 0) "%.1f / %.1f GB".format(t.memUsedMb / 1024.0, t.memTotalMb / 1024.0) else "—")
                ScanRow("NET", "${t.netType} · ${t.netSignal}")
                Text(
                    "tap outside to close",
                    fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ScanRow(label: String, value: String) {
    val c = Pulse.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.muted, modifier = Modifier.width(64.dp))
        Text(value, fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = c.ink)
    }
}

/* ----- helpers ----- */

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

/** 0..4 signal bars from the active network's signal strength (permission-free), or -1 if unknown. */
private fun netBars(context: Context): Int {
    val cm = context.getSystemService<ConnectivityManager>() ?: return -1
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return -1
    val s = caps.signalStrength
    if (s == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED || s >= 0 || s < -120) return -1
    return when {
        s >= -55 -> 4
        s >= -67 -> 3
        s >= -75 -> 2
        s >= -85 -> 1
        else -> 0
    }
}

private fun readMem(context: Context): Pair<Long, Long>? {
    val am = context.getSystemService<ActivityManager>() ?: return null
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val mb = 1024L * 1024L
    return (mi.totalMem - mi.availMem) / mb to mi.totalMem / mb
}

/** "G1 Minor" → "G1"; "None" → null. */
private fun gScale(stormLevel: String): String? =
    stormLevel.takeIf { it.startsWith("G") }?.substringBefore(' ')

private fun computeHealth(pct: Int?, charging: Boolean, tempC: Float?, online: Boolean, kp: Double?): Health {
    val critical = (pct != null && pct <= 10 && !charging) || (tempC != null && tempC >= 45f) || (kp != null && kp >= 7)
    if (critical) return Health.CRITICAL
    val caution = (pct != null && pct <= 25 && !charging) || !online || (tempC != null && tempC >= 40f) || (kp != null && kp >= 5)
    return if (caution) Health.CAUTION else Health.NOMINAL
}

private fun healthWord(h: Health): String = when (h) {
    Health.NOMINAL -> "NOMINAL"
    Health.CAUTION -> "CAUTION"
    Health.CRITICAL -> "CRITICAL"
}

private fun healthColor(h: Health, c: NightwirePalette): Color = when (h) {
    Health.NOMINAL -> c.positive
    Health.CAUTION -> c.amber
    Health.CRITICAL -> c.magenta
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

private fun auroraColor(pct: Int, c: NightwirePalette): Color = when {
    pct >= 50 -> c.magenta
    pct >= 25 -> c.violet
    else -> c.ink2
}
