package dev.mascwa.pulse.feature.tacnet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.sensors.Telemetry
import dev.mascwa.pulse.feature.common.PipFrame
import dev.mascwa.pulse.feature.common.PipHeader
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun TelemetryScreen(vm: TelemetryViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Telemetry",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        TelemetryBody(vm, Modifier.padding(innerPadding))
    }
}

/** The STATUS feed body (phone telemetry), scaffold-free for hosting as a PIP-BOY sub-tab. */
@Composable
fun TelemetryBody(vm: TelemetryViewModel, modifier: Modifier = Modifier) {
    val t by vm.telemetry.collectAsStateWithLifecycle()
    val gps by vm.gps.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val c = Pulse.colors

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.start()
                Lifecycle.Event.ON_PAUSE -> vm.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        vm.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stop()
        }
    }

    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
            PipHeader("Vitals")
            PipFrame(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FalloutGauge("Battery", batteryText(t), (t.batteryPct ?: 0) / 100f, if (t.charging) c.positive else c.accent)
                    FalloutGauge("Memory", "${t.memUsedMb} / ${t.memTotalMb} MB",
                        if (t.memTotalMb > 0) t.memUsedMb.toFloat() / t.memTotalMb else 0f, c.violet)
                    FalloutGauge("Ambient light", t.lightLux?.let { "${it.roundToInt()} lx" } ?: "—",
                        min(1f, (t.lightLux ?: 0f) / 2000f), c.amber)
                    FalloutGauge("Magnetic field", t.magneticUt?.let { "${it.roundToInt()} µT" } ?: "—",
                        min(1f, (t.magneticUt ?: 0f) / 100f), c.sky)
                    FalloutGauge("G-force", t.accelG?.let { "%.2f g".format(it) } ?: "—",
                        min(1f, (t.accelG ?: 0f) / 2f), c.magenta)
                }
            }

            PipHeader("Sensors")
            PipFrame(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    FalloutStatRow("Pressure", t.pressureHpa?.let { "%.1f hPa".format(it) } ?: if (t.hasBarometer) "…" else "no sensor")
                    FalloutStatRow("Baro altitude", t.pressureAltitudeM?.let { "${it.roundToInt()} m" } ?: "—")
                    FalloutStatRow("Tilt (pitch)", t.tiltPitchDeg?.let { "${it.roundToInt()}°" } ?: "—")
                    FalloutStatRow("Tilt (roll)", t.tiltRollDeg?.let { "${it.roundToInt()}°" } ?: "—")
                    FalloutStatRow("Rotation rate", t.gyroDps?.let { "${it.roundToInt()} °/s" } ?: "—")
                }
            }

            PipHeader("System")
            PipFrame(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    FalloutStatRow("Battery temp", t.batteryTempC?.let { "%.1f °C".format(it) } ?: "—")
                    FalloutStatRow("Power", if (t.charging) "Charging" else "On battery")
                    FalloutStatRow("Network", t.netType)
                    FalloutStatRow("Signal", t.netSignal)
                    FalloutStatRow("Memory used", "${t.memUsedMb} MB")
                }
            }

            PipHeader("Position")
            PipFrame(Modifier.fillMaxWidth()) {
                val loc = gps
                if (loc != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        FalloutStatRow("Latitude", "%.5f".format(loc.latitude))
                        FalloutStatRow("Longitude", "%.5f".format(loc.longitude))
                        FalloutStatRow("Place", loc.name)
                    }
                } else {
                    Text(
                        "No GPS fix yet — grant location and ensure GPS is on. Works without internet.",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    )
                }
            }

            PipHeader("Data stream")
            PipFrame(Modifier.fillMaxWidth()) {
                Column {
                    if (log.isEmpty()) {
                        Text("// initialising sensors…", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
                    } else {
                        log.forEach { line ->
                            Text(line, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.positive, maxLines = 1)
                        }
                    }
                }
            }

            Text(
                "All values read directly from on-device sensors and the OS — no network required.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 14.dp, bottom = 24.dp),
            )
        }
}

private fun batteryText(t: Telemetry): String {
    val pct = t.batteryPct?.let { "$it%" } ?: "—"
    return if (t.charging) "$pct ⚡" else pct
}

/** A VITALS gauge in the Fallout HP/AP idiom: a green-banded label/value header over a notched,
 *  segment-lit bar (Pip-Boy SPECIAL/condition bars are segmented, not smooth). */
@Composable
private fun FalloutGauge(label: String, value: String, fraction: Float, color: Color) {
    val c = Pulse.colors
    val frac = fraction.coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(c.accent.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.ink2)
                Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = color)
            }
            Canvas(Modifier.fillMaxWidth().height(8.dp).padding(top = 4.dp)) {
                val segs = 24
                val gap = 2f
                val segW = (size.width - gap * (segs - 1)) / segs
                val lit = (frac * segs).roundToInt()
                val dim = c.lineSoft.copy(alpha = 0.35f)
                for (i in 0 until segs) {
                    drawRect(
                        if (i < lit) color else dim,
                        topLeft = Offset(i * (segW + gap), 0f),
                        size = Size(segW, size.height),
                    )
                }
            }
        }
    }
}

/** A SENSORS/SYSTEM/POSITION readout as a Fallout DATA>STATS banded row: a faint green band, label
 *  left, value right in bright phosphor. */
@Composable
private fun FalloutStatRow(label: String, value: String) {
    val c = Pulse.colors
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(c.accent.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.8.sp, color = c.ink2)
            Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.accent)
        }
    }
}
