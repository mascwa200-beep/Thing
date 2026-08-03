package dev.mascwa.pulse.feature.tacnet

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.mascwa.pulse.ui.theme.NightwirePalette
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.sensors.Telemetry
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
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
 * The STATS ▸ STATUS body — the pure device readout (operator portrait, condition, stress, advisories, and
 * the raw vitals / sensors / system / position panels). Scaffold-free.
 */
@Composable
fun TelemetryBody(vm: TelemetryViewModel, modifier: Modifier = Modifier) {
    TelemetryLifecycle(vm)
    val t by vm.telemetry.collectAsStateWithLifecycle()
    val gps by vm.gps.collectAsStateWithLifecycle()
    val portraitUri by vm.portraitUri.collectAsStateWithLifecycle()
    val c = Pulse.colors
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
        LcarsHeaderBar("Operator")
        OperatorPortrait(portraitUri, c) {
            runCatching { pickPortrait.launch(arrayOf("image/*")) }
        }

        LcarsHeaderBar("Condition")
        ConditionPanel(t, c)

        LcarsHeaderBar("Stress")
        StressPanel(t, c)

        LcarsHeaderBar("Advisories")
        AdvisoriesPanel(t, gps != null, c)

        LcarsHeaderBar("Vitals")
        LcarsFrame(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentGauge("Battery", batteryText(t), (t.batteryPct ?: 0) / 100f, if (t.charging) c.positive else c.accent)
                SegmentGauge("Memory", "${t.memUsedMb} / ${t.memTotalMb} MB",
                    if (t.memTotalMb > 0) t.memUsedMb.toFloat() / t.memTotalMb else 0f, c.violet)
                SegmentGauge("Ambient light", t.lightLux?.let { "${it.roundToInt()} lx" } ?: "—",
                    min(1f, (t.lightLux ?: 0f) / 2000f), c.amber)
                SegmentGauge("Magnetic field", t.magneticUt?.let { "${it.roundToInt()} µT" } ?: "—",
                    min(1f, (t.magneticUt ?: 0f) / 100f), c.sky)
                SegmentGauge("G-force", t.accelG?.let { "%.2f g".format(it) } ?: "—",
                    min(1f, (t.accelG ?: 0f) / 2f), c.magenta)
            }
        }

        LcarsHeaderBar("Sensors")
        LcarsFrame(Modifier.fillMaxWidth()) {
            Column {
                TelemetryStatRow("Pressure", t.pressureHpa?.let { "%.1f hPa".format(it) } ?: if (t.hasBarometer) "…" else "no sensor")
                TelemetryStatRow("Baro altitude", t.pressureAltitudeM?.let { "${it.roundToInt()} m" } ?: "—")
                TelemetryStatRow("Tilt (pitch)", t.tiltPitchDeg?.let { "${it.roundToInt()}°" } ?: "—")
                TelemetryStatRow("Tilt (roll)", t.tiltRollDeg?.let { "${it.roundToInt()}°" } ?: "—")
                TelemetryStatRow("Rotation rate", t.gyroDps?.let { "${it.roundToInt()} °/s" } ?: "—")
            }
        }

        LcarsHeaderBar("System")
        LcarsFrame(Modifier.fillMaxWidth()) {
            Column {
                TelemetryStatRow("Battery temp", t.batteryTempC?.let { "%.1f °C".format(it) } ?: "—")
                TelemetryStatRow("Power", if (t.charging) "Charging" else "On battery")
                TelemetryStatRow("Network", t.netType)
                TelemetryStatRow("Signal", t.netSignal)
                TelemetryStatRow("Memory used", "${t.memUsedMb} MB")
            }
        }

        LcarsHeaderBar("Position")
        LcarsFrame(Modifier.fillMaxWidth()) {
            val loc = gps
            if (loc != null) {
                Column {
                    TelemetryStatRow("Latitude", "%.5f".format(loc.latitude))
                    TelemetryStatRow("Longitude", "%.5f".format(loc.longitude))
                    TelemetryStatRow("Place", loc.name)
                }
            } else {
                Text(
                    "No GPS fix yet — grant location and ensure GPS is on. Works without internet.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
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

// ---- CONDITION: an original operator-silhouette figure whose body regions tint by live device health. ----

private fun powerScore(t: Telemetry): Float = if (t.charging) 1f else (t.batteryPct ?: 100) / 100f
private fun memoryScore(t: Telemetry): Float =
    if (t.memTotalMb > 0) (1f - t.memUsedMb.toFloat() / t.memTotalMb).coerceIn(0f, 1f) else 1f
private fun thermalScore(t: Telemetry): Float =
    t.batteryTempC?.let { (1f - (it - 25f) / 25f).coerceIn(0f, 1f) } ?: 1f

private fun condColor(c: NightwirePalette, score: Float): Color = when {
    score >= 0.66f -> c.positive
    score >= 0.33f -> c.amber
    else -> c.negative
}

/** The STATUS condition readout: a tinted humanoid figure + an INTEGRITY score and per-system breakdown. */
@Composable
private fun ConditionPanel(t: Telemetry, c: NightwirePalette) {
    val power = powerScore(t)
    val memory = memoryScore(t)
    val thermal = thermalScore(t)
    val overall = (power + memory + thermal) / 3f
    val overallColor = condColor(c, overall)
    val label = when {
        overall >= 0.66f -> "NOMINAL"
        overall >= 0.33f -> "DEGRADED"
        else -> "CRITICAL"
    }
    LcarsFrame(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConditionFigure(
                Modifier.size(width = 78.dp, height = 116.dp),
                head = condColor(c, thermal),
                torso = condColor(c, memory),
                limbs = overallColor,
                legs = condColor(c, power),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("INTEGRITY ${(overall * 100).roundToInt()}%", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = 22.sp, color = overallColor)
                Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.5.sp, color = c.muted,
                    modifier = Modifier.padding(bottom = 6.dp))
                CondRow("PWR", batteryText(t), condColor(c, power), c)
                CondRow("MEM", "${(memory * 100).roundToInt()}% free", condColor(c, memory), c)
                CondRow("THRM", t.batteryTempC?.let { "%.1f °C".format(it) } ?: "—", condColor(c, thermal), c)
            }
        }
    }
}

/** A per-system condition row: a status dot + label + value. */
@Composable
private fun CondRow(label: String, value: String, dot: Color, c: NightwirePalette) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted,
            modifier = Modifier.padding(start = 7.dp))
        Spacer(Modifier.weight(1f))
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink)
    }
}

/** An original, generic operator silhouette (NOT a trademarked character) — head/torso/arms/legs drawn
 *  procedurally, each region tinted by its subsystem's health. Filled at low alpha + a bright outline. */
@Composable
private fun ConditionFigure(modifier: Modifier, head: Color, torso: Color, limbs: Color, legs: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val sw = 3.dp.toPx()
        val a = 0.22f

        // Head.
        val headR = h * 0.095f
        val headC = Offset(cx, h * 0.12f)
        drawCircle(head.copy(alpha = a), headR, headC)
        drawCircle(head, headR, headC, style = Stroke(sw))

        // Torso (a tapered trunk).
        val tTop = h * 0.25f
        val tBot = h * 0.58f
        val half = w * 0.17f
        val torsoPath = Path().apply {
            moveTo(cx - half, tTop)
            lineTo(cx + half, tTop)
            lineTo(cx + half * 0.78f, tBot)
            lineTo(cx - half * 0.78f, tBot)
            close()
        }
        drawPath(torsoPath, torso.copy(alpha = a))
        drawPath(torsoPath, torso, style = Stroke(sw))

        // Arms from the shoulders.
        val shoulderY = tTop + h * 0.015f
        drawLine(limbs, Offset(cx - half, shoulderY), Offset(cx - half * 1.85f, h * 0.52f), sw, StrokeCap.Round)
        drawLine(limbs, Offset(cx + half, shoulderY), Offset(cx + half * 1.85f, h * 0.52f), sw, StrokeCap.Round)

        // Legs from the hips.
        drawLine(legs, Offset(cx - half * 0.55f, tBot), Offset(cx - half * 0.75f, h * 0.96f), sw, StrokeCap.Round)
        drawLine(legs, Offset(cx + half * 0.55f, tBot), Offset(cx + half * 0.75f, h * 0.96f), sw, StrokeCap.Round)
    }
}

/** A VITALS gauge: a banded label/value header over a notched, segment-lit bar (segmented rather than
 *  smooth, for LCARS-style at-a-glance legibility). */
@Composable
private fun SegmentGauge(label: String, value: String, fraction: Float, color: Color) {
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

/** A SENSORS/SYSTEM/POSITION readout row (shared [LcarsDataRow]). */
@Composable
private fun TelemetryStatRow(label: String, value: String) {
    dev.mascwa.pulse.feature.common.LcarsDataRow(label, value)
}

/** The operator portrait: tap to pick an image — it persists and renders in true color inside the LCARS
 *  frame; empty shows the upload prompt. */
@Composable
private fun OperatorPortrait(uri: String, c: NightwirePalette, onPick: () -> Unit) {
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (uri.isBlank()) {
                Box(
                    Modifier.fillMaxWidth().height(150.dp)
                        .border(1.dp, c.line, RoundedCornerShape(4.dp))
                        .clickable { onPick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("CLICK TO UPLOAD AN IMAGE", fontFamily = JetBrainsMono, fontSize = 11.sp,
                        letterSpacing = 1.sp, color = c.muted)
                }
            } else {
                AsyncImage(
                    model = uri,
                    contentDescription = "Operator portrait",
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(4.dp)).clickable { onPick() },
                    contentScale = ContentScale.Crop,
                )
            }
            Text(
                "OPERATOR", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, letterSpacing = 1.5.sp, color = c.ink, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** The STRESS readout: live system load — memory pressure plus thermal load above nominal — as a direct
 *  0–100% gauge; TOLERANCE = free headroom. */
@Composable
private fun StressPanel(t: Telemetry, c: NightwirePalette) {
    val memPct = if (t.memTotalMb > 0) ((t.memUsedMb * 100) / t.memTotalMb).toInt() else 0
    val temp = t.batteryTempC ?: 25f
    val stress = ((memPct * 5 + (maxOf(0f, temp - 25f) * 20f).toInt()) / 10).coerceIn(0, 100)
    val tolerance = (100 - memPct).coerceIn(0, 100)
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("TOLERANCE $tolerance%", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent)
                Text("STRESS $stress%", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink)
            }
            StressGauge(stress, c, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}

/** The STRESS gauge: a tick scale 0…100% with an arrow pointer at the current reading. */
@Composable
private fun StressGauge(stress: Int, c: NightwirePalette, modifier: Modifier) {
    Canvas(modifier.height(34.dp)) {
        val w = size.width
        val y = size.height * 0.62f
        drawLine(c.accent.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), 1.5.dp.toPx())
        for (i in 0..10) {
            val x = (w * i / 10f).coerceIn(0.5f, w - 0.5f)
            val tall = i % 5 == 0
            drawLine(c.accent.copy(alpha = if (tall) 0.7f else 0.4f), Offset(x, y), Offset(x, y - if (tall) 11f else 6f), 1.dp.toPx())
        }
        val px = (w * (stress / 100f)).coerceIn(0f, w)
        val arrow = Path().apply {
            moveTo(px, y - 3f)
            lineTo(px - 7f, y + 11f)
            lineTo(px + 7f, y + 11f)
            close()
        }
        drawPath(arrow, c.accent)
    }
}

/** A system advisory derived from live device state. */
private data class Advisory(val tag: String, val name: String, val desc: String, val good: Boolean)

/** Active advisories — real device/app state surfaced as short flags: charging, low power, thermal,
 *  data link, GPS. */
private fun activeAdvisories(t: Telemetry, hasGps: Boolean): List<Advisory> {
    val out = ArrayList<Advisory>()
    val bat = t.batteryPct ?: 0
    if (t.charging) out += Advisory("⚡", "CHARGING", "Power cell recharging", true)
    if (!t.charging && bat in 1..20) out += Advisory("▼", "LOW POWER", "Reserves critical — conserve energy", false)
    val temp = t.batteryTempC
    if (temp != null && temp >= 40f) out += Advisory("△", "OVERHEATING", "Core temperature elevated", false)
    if (t.netType.isNotBlank()) out += Advisory("≋", "${t.netType.uppercase()} LINK", "Data uplink established", true)
    else out += Advisory("⊘", "OFFLINE", "No uplink — running on local cache", false)
    if (hasGps) out += Advisory("◎", "GPS LOCK", "Position fix acquired", true)
    return out
}

/** The ADVISORIES readout: active device state as short system flags. */
@Composable
private fun AdvisoriesPanel(t: Telemetry, hasGps: Boolean, c: NightwirePalette) {
    val advisories = activeAdvisories(t, hasGps)
    LcarsFrame(Modifier.fillMaxWidth()) {
        if (advisories.isEmpty()) {
            Text("No active advisories.", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
        } else {
            Column { advisories.forEach { AdvisoryRow(it, c) } }
        }
    }
}

/** One advisory row: a tag glyph, the advisory name (bold) and a short description, banded. */
@Composable
private fun AdvisoryRow(e: Advisory, c: NightwirePalette) {
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                drawRect(c.accent.copy(alpha = 0.08f))
                drawLine(c.accent.copy(alpha = 0.35f), Offset(0f, size.height), Offset(size.width, size.height), 1.2.dp.toPx())
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.tag, fontFamily = JetBrainsMono, fontSize = 14.sp, color = if (e.good) c.accent else c.amber,
            modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                color = if (e.good) c.ink else c.amber, letterSpacing = 0.8.sp)
            Text(e.desc, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
    }
}
