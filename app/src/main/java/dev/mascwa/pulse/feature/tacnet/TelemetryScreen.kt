package dev.mascwa.pulse.feature.tacnet

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import dev.mascwa.pulse.core.telemetry.Geodesy
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.mascwa.pulse.data.sensors.Telemetry
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun TelemetryScreen(vm: TelemetryViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Diagnostics",
        onBack = onBack,
    ) { innerPadding ->
        TelemetryBody(vm, Modifier.padding(innerPadding))
    }
}

/** Starts/stops the telemetry controller (device sensors: battery/light/magnetic/gyro/pressure/GPS) with
 *  the composition. A pure read-only device readout — no camera/mic/calendar permissions, no background loops. */
@Composable
private fun TelemetryLifecycle(vm: TelemetryViewModel) {
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
}

/**
 * The STATS ▸ STATUS body — rebuilt from scratch as a "DIAGNOSTIC GRID": an asymmetric bank of solid
 * colour-washed cells (the real Okudagram idiom — filled blocks, not bordered cards), status-banded
 * nominal/caution/critical, topped by a single state-word masthead and closed by one ticking alert line
 * instead of a stacked advisory list. Every real sensor reading the earlier panel-based layout showed still
 * surfaces here — only the structure and every composable drawing it are new. Scaffold-free.
 */
@Composable
fun TelemetryBody(vm: TelemetryViewModel, modifier: Modifier = Modifier) {
    TelemetryLifecycle(vm)
    val t by vm.telemetry.collectAsStateWithLifecycle()
    val gps by vm.gps.collectAsStateWithLifecycle()
    val portraitUri by vm.portraitUri.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickPortrait = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.setPortrait(uri.toString())
        }
    }

    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(6.dp))
        DiagnosticMasthead(t, portraitUri) { runCatching { pickPortrait.launch(arrayOf("image/*")) } }

        Spacer(Modifier.height(3.dp))
        ReactorCell(t, Modifier.fillMaxWidth().height(112.dp))

        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth().height(88.dp)) {
            MemoryCell(t, Modifier.weight(0.55f).fillMaxHeight())
            Spacer(Modifier.width(3.dp))
            UplinkCell(t, Modifier.weight(0.45f).fillMaxHeight())
        }

        Spacer(Modifier.height(3.dp))
        SensorArrayCell(t, Modifier.fillMaxWidth())

        Spacer(Modifier.height(3.dp))
        PositionCell(gps, Modifier.fillMaxWidth().height(84.dp))

        AlertLine(activeFlags(t, gps != null))

        Text(
            "ALL READINGS SOURCED DIRECTLY FROM ON-DEVICE SENSORS AND OS STATE — NO NETWORK REQUIRED.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pulse.colors.faint,
            modifier = Modifier.padding(top = 18.dp, bottom = 24.dp),
        )
    }
}

// ---- Banding: every cell is scored 0..1 from real device state and washed nominal/caution/critical. ----

private enum class DiagBand { NOMINAL, CAUTION, CRITICAL }

private fun DiagBand.wash(c: NightwirePalette): Color = when (this) {
    DiagBand.NOMINAL -> c.positive
    DiagBand.CAUTION -> c.amber
    DiagBand.CRITICAL -> c.negative
}

private fun bandFor(score: Float): DiagBand = when {
    score >= 0.66f -> DiagBand.NOMINAL
    score >= 0.33f -> DiagBand.CAUTION
    else -> DiagBand.CRITICAL
}

private fun reserveScore(t: Telemetry): Float = if (t.charging) 1f else (t.batteryPct ?: 100) / 100f
private fun headroomScore(t: Telemetry): Float =
    if (t.memTotalMb > 0) (1f - t.memUsedMb.toFloat() / t.memTotalMb).coerceIn(0f, 1f) else 1f
private fun coreTempScore(t: Telemetry): Float =
    t.batteryTempC?.let { (1f - (it - 25f) / 25f).coerceIn(0f, 1f) } ?: 1f
private fun linkScore(t: Telemetry): Float = if (t.netType == "OFFLINE") 0.2f else 1f

private fun formatClock(now: Calendar): String =
    "%02d:%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))

@Composable
private fun tickingClock(): String {
    var text by remember { mutableStateOf(formatClock(Calendar.getInstance())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            text = formatClock(Calendar.getInstance())
        }
    }
    return text
}

// ---- Masthead: one state word + a live clock + a crew-card ID thumbnail, on a solid banded strip. ----

@Composable
private fun DiagnosticMasthead(t: Telemetry, portraitUri: String, onPickPortrait: () -> Unit) {
    val c = Pulse.colors
    val overall = (reserveScore(t) + headroomScore(t) + coreTempScore(t) + linkScore(t)) / 4f
    val band = bandFor(overall)
    val fill = band.wash(c)
    val stateWord = when (band) {
        DiagBand.NOMINAL -> "NOMINAL"
        DiagBand.CAUTION -> "CAUTION"
        DiagBand.CRITICAL -> "CRITICAL"
    }
    val clock = tickingClock()
    val shape = lcarsBlockShape(sweep = 34.dp, corner = LcarsCorner.TopStart)

    Row(
        Modifier.fillMaxWidth().height(96.dp)
            .clip(shape)
            .background(fill)
            .padding(start = 24.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("SHIP STATUS", fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 3.sp, color = c.void.copy(alpha = 0.62f))
            Text(stateWord, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 1.sp, color = c.void)
            Text(clock, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.void.copy(alpha = 0.75f), modifier = Modifier.padding(top = 2.dp))
        }
        CrewCardThumb(portraitUri, onPickPortrait)
    }
}

@Composable
private fun CrewCardThumb(uri: String, onClick: () -> Unit) {
    val c = Pulse.colors
    val shape = lcarsBlockShape(sweep = 10.dp, corner = LcarsCorner.BottomEnd)
    Box(
        Modifier.size(56.dp)
            .clip(shape)
            .background(c.void)
            .border(2.dp, c.ink, shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (uri.isBlank()) {
            Text("ID", fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, color = c.ink)
        } else {
            AsyncImage(
                model = uri, contentDescription = "Operator ID",
                modifier = Modifier.fillMaxWidth().fillMaxHeight().clip(shape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

// ---- The cell family: solid colour-washed blocks, dark text — the "filled block" LCARS idiom. ----

@Composable
private fun DiagCell(
    label: String,
    band: DiagBand,
    corner: LcarsCorner,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Pulse.colors
    val fill = band.wash(c)
    val shape = lcarsBlockShape(sweep = 22.dp, corner = corner)
    Column(
        modifier
            .clip(shape)
            .background(fill)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.8.sp, color = c.void.copy(alpha = 0.6f))
        Spacer(Modifier.height(5.dp))
        content()
    }
}

@Composable
private fun ReactorCell(t: Telemetry, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val band = bandFor(reserveScore(t))
    val pctText = t.batteryPct?.let { "$it" } ?: "—"
    val tempText = t.batteryTempC?.let { "%.1f°C CELL TEMP".format(it) } ?: "CELL TEMP —"
    DiagCell("Power Cell", band, LcarsCorner.TopStart, modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(pctText, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 42.sp, color = c.void, lineHeight = 42.sp)
            Text("%", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.void.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = 3.dp, bottom = 5.dp))
            if (t.charging) {
                Text("⚡ CHARGING", fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = c.void,
                    modifier = Modifier.padding(start = 16.dp, bottom = 9.dp))
            }
        }
        Text(tempText, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.void.copy(alpha = 0.68f),
            modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun MemoryCell(t: Telemetry, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val band = bandFor(headroomScore(t))
    val usedPct = if (t.memTotalMb > 0) ((t.memUsedMb * 100) / t.memTotalMb).toInt() else 0
    DiagCell("Memory", band, LcarsCorner.TopStart, modifier) {
        Text("$usedPct%", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.void)
        Text("${t.memUsedMb} / ${t.memTotalMb} MB", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.void.copy(alpha = 0.68f),
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun UplinkCell(t: Telemetry, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val band = bandFor(linkScore(t))
    val label = if (t.netType == "OFFLINE") "NO LINK" else t.netType
    DiagCell("Uplink", band, LcarsCorner.TopEnd, modifier) {
        Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = c.void, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.netSignal, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.void.copy(alpha = 0.68f),
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SensorArrayCell(t: Telemetry, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val band = if (t.hasBarometer) DiagBand.NOMINAL else DiagBand.CAUTION
    val readings = listOf(
        "PRESS" to (t.pressureHpa?.let { "%.0f hPa".format(it) } ?: "—"),
        "ALT" to (t.pressureAltitudeM?.let { "${it.roundToInt()} m" } ?: "—"),
        "PITCH" to (t.tiltPitchDeg?.let { "${it.roundToInt()}°" } ?: "—"),
        "ROLL" to (t.tiltRollDeg?.let { "${it.roundToInt()}°" } ?: "—"),
        "MAG" to (t.magneticUt?.let { "${it.roundToInt()} µT" } ?: "—"),
        "LIGHT" to (t.lightLux?.let { "${it.roundToInt()} lx" } ?: "—"),
    )
    DiagCell("Sensor Array", band, LcarsCorner.BottomStart, modifier) {
        readings.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { (key, value) ->
                    Column {
                        Text(key, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.6.sp, color = c.void.copy(alpha = 0.55f))
                        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.void,
                            modifier = Modifier.padding(top = 1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionCell(gps: DeviceLocation?, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val band = if (gps != null) DiagBand.NOMINAL else DiagBand.CAUTION
    DiagCell("Position Fix", band, LcarsCorner.BottomEnd, modifier) {
        if (gps != null) {
            Text(gps.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.void,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Geodesy.formatDecimal(gps.latitude, gps.longitude), fontFamily = JetBrainsMono, fontSize = 10.sp,
                color = c.void.copy(alpha = 0.68f), modifier = Modifier.padding(top = 2.dp))
        } else {
            Text("NO FIX", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.void)
            Text("awaiting GPS lock", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.void.copy(alpha = 0.68f),
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ---- The alert strip: one ticking line — the classic LCARS single-alert convention — not a stacked list. ----

private fun activeFlags(t: Telemetry, hasGps: Boolean): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    val bat = t.batteryPct ?: 100
    if (!t.charging && bat in 1..20) out += ("POWER RESERVE CRITICAL — $bat% REMAINING" to true)
    val temp = t.batteryTempC
    if (temp != null && temp >= 40f) out += ("CORE TEMPERATURE ELEVATED — %.1f°C".format(temp) to true)
    if (t.netType == "OFFLINE") out += ("NO DATA UPLINK — RUNNING ON LOCAL CACHE" to false)
    if (!hasGps) out += ("NO POSITION FIX — AWAITING GPS LOCK" to false)
    return out
}

@Composable
private fun AlertLine(flags: List<Pair<String, Boolean>>) {
    val c = Pulse.colors
    if (flags.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(c.positive))
            Text(
                "ALL SYSTEMS NOMINAL — NO ACTIVE ALERTS", fontFamily = JetBrainsMono, fontSize = 10.sp,
                letterSpacing = 1.sp, color = c.muted, modifier = Modifier.padding(start = 9.dp),
            )
        }
        return
    }
    var index by remember(flags) { mutableStateOf(0) }
    LaunchedEffect(flags) {
        while (true) {
            delay(3200)
            index = (index + 1) % flags.size
        }
    }
    val (message, urgent) = flags[index % flags.size]
    val tone = if (urgent) c.negative else c.amber
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp)
            .background(tone.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(tone))
        Text(
            "ALERT · $message", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            letterSpacing = 0.6.sp, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 9.dp),
        )
    }
}
