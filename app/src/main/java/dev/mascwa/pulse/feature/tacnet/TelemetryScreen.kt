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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
import dev.mascwa.pulse.core.telemetry.Character
import dev.mascwa.pulse.core.telemetry.Choice
import dev.mascwa.pulse.core.telemetry.Encounter
import dev.mascwa.pulse.core.telemetry.Resolution
import dev.mascwa.pulse.core.telemetry.Special
import dev.mascwa.pulse.core.telemetry.SpecialGame
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
    val portraitUri by vm.portraitUri.collectAsStateWithLifecycle()
    val character by vm.character.collectAsStateWithLifecycle()
    val encounter by vm.currentEncounter.collectAsStateWithLifecycle()
    val resolution by vm.gameResolution.collectAsStateWithLifecycle()
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
            PipHeader("Operator")
            OperatorPortrait(portraitUri, character.level, c) {
                runCatching { pickPortrait.launch(arrayOf("image/*")) }
            }

            PipHeader("Condition")
            ConditionPanel(t, c)

            PipHeader("Radiation")
            RadiationPanel(t, c)

            PipHeader("Effects")
            EffectsPanel(t, gps != null, c)

            PipHeader("S.P.E.C.I.A.L.")
            SpecialGamePanel(
                character, encounter, resolution, c,
                onVenture = { vm.venture() },
                onChoose = { vm.choose(it) },
                onAllocate = { vm.allocate(it) },
                onRevive = { vm.revive() },
            )

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
                Column {
                    FalloutStatRow("Pressure", t.pressureHpa?.let { "%.1f hPa".format(it) } ?: if (t.hasBarometer) "…" else "no sensor")
                    FalloutStatRow("Baro altitude", t.pressureAltitudeM?.let { "${it.roundToInt()} m" } ?: "—")
                    FalloutStatRow("Tilt (pitch)", t.tiltPitchDeg?.let { "${it.roundToInt()}°" } ?: "—")
                    FalloutStatRow("Tilt (roll)", t.tiltRollDeg?.let { "${it.roundToInt()}°" } ?: "—")
                    FalloutStatRow("Rotation rate", t.gyroDps?.let { "${it.roundToInt()} °/s" } ?: "—")
                }
            }

            PipHeader("System")
            PipFrame(Modifier.fillMaxWidth()) {
                Column {
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
                    Column {
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

// ---- CONDITION: an original Pip-Boy-style figure whose body regions tint by live device health. ----

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

/** The STATUS condition readout: a tinted humanoid figure + a CND score and per-system breakdown. */
@Composable
private fun ConditionPanel(t: Telemetry, c: NightwirePalette) {
    val power = powerScore(t)
    val memory = memoryScore(t)
    val thermal = thermalScore(t)
    val overall = (power + memory + thermal) / 3f
    val overallColor = condColor(c, overall)
    val label = when {
        overall >= 0.66f -> "OPTIMAL"
        overall >= 0.33f -> "FAIR"
        else -> "CRITICAL"
    }
    PipFrame(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConditionFigure(
                Modifier.size(width = 78.dp, height = 116.dp),
                head = condColor(c, thermal),
                torso = condColor(c, memory),
                limbs = overallColor,
                legs = condColor(c, power),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("CND ${(overall * 100).roundToInt()}%", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
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

/** A SENSORS/SYSTEM/POSITION readout — the canonical Fallout DATA>STATS banded row (shared [PipDataRow]). */
@Composable
private fun FalloutStatRow(label: String, value: String) {
    dev.mascwa.pulse.feature.common.PipDataRow(label, value)
}

/** Green-phosphor duotone (luminance → green) so a portrait reads as part of the Pip-Boy CRT. */
private val PortraitDuotone = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.030f, 0.059f, 0.011f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.090f, 0.176f, 0.034f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** The operator portrait (Fallout STATUS character image): tap to pick an image — it persists and renders
 *  in the green CRT duotone with an OPERATIVE · LEVEL line; empty shows the upload prompt. */
@Composable
private fun OperatorPortrait(uri: String, level: Int, c: NightwirePalette, onPick: () -> Unit) {
    PipFrame(Modifier.fillMaxWidth()) {
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
                    colorFilter = PortraitDuotone,
                )
            }
            Text(
                "OPERATIVE · LEVEL $level", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, letterSpacing = 1.5.sp, color = c.ink, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** The RADIATION readout (Fallout STATUS>RAD): "rads" = live system stress — memory pressure plus
 *  thermal load above nominal — on the 0–1000 RADS ruler; RAD RESIST = free headroom. */
@Composable
private fun RadiationPanel(t: Telemetry, c: NightwirePalette) {
    val memPct = if (t.memTotalMb > 0) ((t.memUsedMb * 100) / t.memTotalMb).toInt() else 0
    val temp = t.batteryTempC ?: 25f
    val rads = (memPct * 5 + (maxOf(0f, temp - 25f) * 20f).toInt()).coerceIn(0, 1000)
    val resist = (100 - memPct).coerceIn(0, 100)
    PipFrame(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("RAD RESIST $resist%", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent)
                Text("$rads / 1000 RADS", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink)
            }
            RadRuler(rads, c, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}

/** The Fallout RADS ruler: a tick scale 0…1000 with an arrow pointer at the current reading. */
@Composable
private fun RadRuler(rads: Int, c: NightwirePalette, modifier: Modifier) {
    Canvas(modifier.height(34.dp)) {
        val w = size.width
        val y = size.height * 0.62f
        drawLine(c.accent.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), 1.5.dp.toPx())
        for (i in 0..10) {
            val x = (w * i / 10f).coerceIn(0.5f, w - 0.5f)
            val tall = i % 5 == 0
            drawLine(c.accent.copy(alpha = if (tall) 0.7f else 0.4f), Offset(x, y), Offset(x, y - if (tall) 11f else 6f), 1.dp.toPx())
        }
        val px = (w * (rads / 1000f)).coerceIn(0f, w)
        val arrow = Path().apply {
            moveTo(px, y - 3f)
            lineTo(px - 7f, y + 11f)
            lineTo(px + 7f, y + 11f)
            close()
        }
        drawPath(arrow, c.accent)
    }
}

/** A Fallout-style status effect derived from live device state. */
private data class StatusEffect(val tag: String, val name: String, val desc: String, val good: Boolean)

/** Active "effects" — real device/app state surfaced as Fallout status effects (the STATUS>EFF panel):
 *  charging, low power, thermal, data link, GPS. */
private fun activeEffects(t: Telemetry, hasGps: Boolean): List<StatusEffect> {
    val out = ArrayList<StatusEffect>()
    val bat = t.batteryPct ?: 0
    if (t.charging) out += StatusEffect("⚡", "CHARGING", "Power cell recharging", true)
    if (!t.charging && bat in 1..20) out += StatusEffect("▼", "LOW POWER", "Reserves critical — conserve energy", false)
    val temp = t.batteryTempC
    if (temp != null && temp >= 40f) out += StatusEffect("△", "OVERHEATING", "Core temperature elevated", false)
    if (t.netType.isNotBlank()) out += StatusEffect("≋", "${t.netType.uppercase()} LINK", "Data uplink established", true)
    else out += StatusEffect("⊘", "OFFLINE", "No uplink — running on local cache", false)
    if (hasGps) out += StatusEffect("◎", "GPS LOCK", "Position fix acquired", true)
    return out
}

/** The EFFECTS readout (Fallout STATUS>EFF): active device state as status effects. */
@Composable
private fun EffectsPanel(t: Telemetry, hasGps: Boolean, c: NightwirePalette) {
    val effects = activeEffects(t, hasGps)
    PipFrame(Modifier.fillMaxWidth()) {
        if (effects.isEmpty()) {
            Text("No active effects.", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
        } else {
            Column { effects.forEach { EffectRow(it, c) } }
        }
    }
}

// ---- S.P.E.C.I.A.L. — a playable wasteland RPG. The seven stats gate encounter choices; play to
//      earn XP/caps, level up, and allocate points. The character persists (SpecialGameStore). ----

@Composable
private fun SpecialGamePanel(
    ch: Character,
    encounter: Encounter?,
    resolution: Resolution?,
    c: NightwirePalette,
    onVenture: () -> Unit,
    onChoose: (Int) -> Unit,
    onAllocate: (Special) -> Unit,
    onRevive: () -> Unit,
) {
    PipFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            GameVitals(ch, c)
            Spacer(Modifier.height(4.dp))
            if (ch.unspent > 0) {
                Text(
                    "LEVEL UP — ${ch.unspent} point${if (ch.unspent > 1) "s" else ""} to spend. Tap ＋ to raise a stat.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
            Special.entries.forEach { s ->
                SpecialGameRow(s, ch.stat(s), ch.unspent > 0 && ch.stat(s) < 10, c) { onAllocate(s) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    when {
        ch.down -> DownedPanel(c, onRevive)
        encounter != null -> EncounterPanel(encounter, ch, c, onChoose)
        else -> IdlePanel(resolution, c, onVenture)
    }
}

@Composable
private fun GameVitals(ch: Character, c: NightwirePalette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("LVL ${ch.level}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent)
        Text("◆ ${ch.caps} CAPS", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c.amber)
        Text(
            "HP ${ch.hp}/${ch.maxHp}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            color = if (ch.hp <= ch.maxHp / 4) c.negative else c.positive,
        )
    }
    SegBar((ch.xp.toFloat() / ch.xpToNext).coerceIn(0f, 1f), c.accent, c)
    Text("XP ${ch.xp} / ${ch.xpToNext}", fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
}

@Composable
private fun SegBar(frac: Float, color: Color, c: NightwirePalette) {
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val segs = 24
        val gap = 2f
        val segW = (size.width - gap * (segs - 1)) / segs
        val lit = (frac.coerceIn(0f, 1f) * segs).roundToInt()
        val dim = c.lineSoft.copy(alpha = 0.35f)
        for (i in 0 until segs) {
            drawRect(if (i < lit) color else dim, topLeft = Offset(i * (segW + gap), 0f), size = Size(segW, size.height))
        }
    }
}

@Composable
private fun SpecialGameRow(s: Special, value: Int, canAllocate: Boolean, c: NightwirePalette, onAllocate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                drawRect(c.accent.copy(alpha = 0.08f))
                drawLine(c.accent.copy(alpha = 0.35f), Offset(0f, size.height), Offset(size.width, size.height), 1.2.dp.toPx())
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${s.letter}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = c.accent, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(s.display, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                color = c.ink, letterSpacing = 0.8.sp)
            Text(s.blurb, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
        Text("$value", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = c.accent, modifier = Modifier.padding(start = 10.dp))
        if (canAllocate) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(c.amber.copy(alpha = 0.18f))
                    .border(1.dp, c.amber.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).clickable(onClick = onAllocate),
                contentAlignment = Alignment.Center,
            ) { Text("＋", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.amber) }
        }
    }
}

@Composable
private fun EncounterPanel(e: Encounter, ch: Character, c: NightwirePalette, onChoose: (Int) -> Unit) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.amber) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = c.amber, letterSpacing = 1.sp)
            Text(e.prompt, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink)
            e.choices.forEachIndexed { i, choice -> ChoiceButton(choice, ch, c) { onChoose(i) } }
        }
    }
}

@Composable
private fun ChoiceButton(choice: Choice, ch: Character, c: NightwirePalette, onClick: () -> Unit) {
    val gate = choice.stat
    val tag = if (gate == null) "SAFE" else oddsLabel(ch.stat(gate), choice.difficulty, ch.stat(Special.LUCK))
    val tagColor = when (tag) {
        "SURE", "SAFE" -> c.positive
        "LIKELY" -> c.accent
        "EVEN" -> c.ink
        "RISKY" -> c.amber
        else -> c.negative
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .border(1.dp, c.line, RoundedCornerShape(4.dp)).background(c.accent.copy(alpha = 0.05f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(choice.text, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink)
            Text(
                if (gate == null) "no check" else "${gate.display} check · DC ${choice.difficulty}",
                fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(tag, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = tagColor, letterSpacing = 1.sp)
    }
}

/** A rough pass-odds label for a stat check — how many of the 10 die faces would succeed. */
private fun oddsLabel(stat: Int, difficulty: Int, luck: Int): String {
    val passes = (1..SpecialGame.DIE).count { SpecialGame.check(stat, difficulty, luck, it).success }
    return when {
        passes >= 9 -> "SURE"
        passes >= 6 -> "LIKELY"
        passes == 5 -> "EVEN"
        passes >= 2 -> "RISKY"
        else -> "LONGSHOT"
    }
}

@Composable
private fun IdlePanel(resolution: Resolution?, c: NightwirePalette, onVenture: () -> Unit) {
    PipFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (resolution != null) {
                val o = resolution.outcome
                Text(
                    if (resolution.crit) "CRITICAL SUCCESS" else if (resolution.success) "SUCCESS" else "FAILURE",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
                    color = if (resolution.success) c.positive else c.negative,
                )
                Text(o.text, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink)
                val rewards = buildList {
                    if (o.xp > 0) add("+${o.xp} XP")
                    if (o.caps != 0) add("${if (o.caps > 0) "+" else ""}${o.caps} caps")
                    if (o.hp != 0) add("${if (o.hp > 0) "+" else ""}${o.hp} HP")
                    if (o.statPoint) add("+1 point")
                }.joinToString("    ")
                if (rewards.isNotBlank()) {
                    Text(rewards, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = c.amber)
                }
            } else {
                Text(
                    "The wasteland waits. Venture out — your S.P.E.C.I.A.L. decides how it goes.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                )
            }
            GameButton(if (resolution != null) "VENTURE ON ▸" else "VENTURE OUT ▸", c.accent, onVenture)
        }
    }
}

@Composable
private fun DownedPanel(c: NightwirePalette, onRevive: () -> Unit) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.negative) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YOU WENT DOWN", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = c.negative, letterSpacing = 1.sp)
            Text(
                "The wasteland got the better of you. Patch up to venture again — it'll cost a quarter of your caps.",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
            )
            GameButton("PATCH UP ✚", c.negative, onRevive)
        }
    }
}

@Composable
private fun GameButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp, color = color)
    }
}

/** One status-effect row: a tag glyph, the effect name (bold) and a short description, banded. */
@Composable
private fun EffectRow(e: StatusEffect, c: NightwirePalette) {
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
