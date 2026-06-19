package dev.mascwa.pulse.feature.tacnet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.CornerRadius
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
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.SectionBar
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
            SectionBar("Vitals")
            NeonPanel(Modifier.fillMaxWidth(), corners = true) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GaugeBar("Battery", batteryText(t), (t.batteryPct ?: 0) / 100f, if (t.charging) c.positive else c.accent)
                    GaugeBar("Memory", "${t.memUsedMb} / ${t.memTotalMb} MB",
                        if (t.memTotalMb > 0) t.memUsedMb.toFloat() / t.memTotalMb else 0f, c.violet)
                    GaugeBar("Ambient light", t.lightLux?.let { "${it.roundToInt()} lx" } ?: "—",
                        min(1f, (t.lightLux ?: 0f) / 2000f), c.amber)
                    GaugeBar("Magnetic field", t.magneticUt?.let { "${it.roundToInt()} µT" } ?: "—",
                        min(1f, (t.magneticUt ?: 0f) / 100f), c.sky)
                    GaugeBar("G-force", t.accelG?.let { "%.2f g".format(it) } ?: "—",
                        min(1f, (t.accelG ?: 0f) / 2f), c.magenta)
                }
            }

            SectionBar("Sensors")
            NeonPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    KV("Pressure", t.pressureHpa?.let { "%.1f hPa".format(it) } ?: if (t.hasBarometer) "…" else "no sensor")
                    KV("Baro altitude", t.pressureAltitudeM?.let { "${it.roundToInt()} m" } ?: "—")
                    KV("Tilt (pitch)", t.tiltPitchDeg?.let { "${it.roundToInt()}°" } ?: "—")
                    KV("Tilt (roll)", t.tiltRollDeg?.let { "${it.roundToInt()}°" } ?: "—")
                    KV("Rotation rate", t.gyroDps?.let { "${it.roundToInt()} °/s" } ?: "—")
                }
            }

            SectionBar("System")
            NeonPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    KV("Battery temp", t.batteryTempC?.let { "%.1f °C".format(it) } ?: "—")
                    KV("Power", if (t.charging) "Charging" else "On battery")
                    KV("Network", t.netType)
                    KV("Signal", t.netSignal)
                    KV("Memory used", "${t.memUsedMb} MB")
                }
            }

            SectionBar("Position")
            NeonPanel(Modifier.fillMaxWidth()) {
                val loc = gps
                if (loc != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        KV("Latitude", "%.5f".format(loc.latitude))
                        KV("Longitude", "%.5f".format(loc.longitude))
                        KV("Place", loc.name)
                    }
                } else {
                    Text(
                        "No GPS fix yet — grant location and ensure GPS is on. Works without internet.",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    )
                }
            }

            SectionBar("Data stream")
            NeonPanel(Modifier.fillMaxWidth(), background = c.carbon) {
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

@Composable
private fun GaugeBar(label: String, value: String, fraction: Float, color: Color) {
    val c = Pulse.colors
    val frac = fraction.coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.6.sp, color = c.muted)
            Text(value, fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c.ink)
        }
        Canvas(Modifier.fillMaxWidth().height(7.dp).padding(top = 3.dp)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(c.lineSoft, cornerRadius = r)
            if (frac > 0f) {
                drawRoundRect(color, size = Size(size.width * frac, size.height), cornerRadius = r)
            }
        }
    }
}

@Composable
private fun KV(label: String, value: String) {
    val c = Pulse.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink)
    }
}
