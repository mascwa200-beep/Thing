package dev.mascwa.pulse.feature.spaceballs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.settings.ThemeMode
import dev.mascwa.pulse.feature.settings.SettingsViewModel
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/* -------------------------------------------------------------------------------------------------
 * THE BRIDGE — a Spaceballs (1987) starship control console, rendered as believable hardware: beveled
 * riveted metal plating, a blue angular neon monitor over a green oscilloscope crosshair, colored dome
 * buttons, illuminated flip-switches, needle gauges and a dot-matrix readout. Played entirely straight —
 * the joke is that mundane settings are presented as critical starship instrumentation. The big red
 * button engages LUDICROUS SPEED (we've gone to plaid). All real functionality lives in the flip-switch
 * bank; everything else is committed, earnest theater.
 * ------------------------------------------------------------------------------------------------- */

private object Sb {
    val metalDark = Color(0xFF15171B)
    val metalMid = Color(0xFF34383F)
    val metalLight = Color(0xFF5C626B)
    val screen = Color(0xFF04070A)
    val neon = Color(0xFF1E90FF)
    val neonHot = Color(0xFF7FC0FF)
    val green = Color(0xFF39FF14)
    val greenDim = Color(0xFF1C7A0E)
    val amber = Color(0xFFFFB81C)
    val gold = Color(0xFFFFD24A)
    val red = Color(0xFFDC143C)
    val redHot = Color(0xFFFF3B5C)
    val purple = Color(0xFF9B59B6)
    val silver = Color(0xFFD8DCE2)
    val dim = Color(0xFF8A8F97)
}

// --- hardware modifiers --------------------------------------------------------------------------

/** Brushed-metal plate: vertical gradient + faint streaks + hard bevel + corner rivets. Static. */
private fun Modifier.metal(raised: Boolean = true, rivets: Boolean = true): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Sb.metalLight, Sb.metalMid, Sb.metalDark)))
    var x = 0f
    val streak = Color.White.copy(alpha = 0.025f)
    while (x < size.width) { drawLine(streak, Offset(x, 0f), Offset(x, size.height)); x += 3.dp.toPx() }
    val w = 2.dp.toPx()
    val tl = if (raised) Sb.metalLight else Sb.metalDark
    val br = if (raised) Sb.metalDark else Sb.metalLight
    drawRect(tl, Offset.Zero, Size(size.width, w))
    drawRect(tl, Offset.Zero, Size(w, size.height))
    drawRect(br, Offset(0f, size.height - w), Size(size.width, w))
    drawRect(br, Offset(size.width - w, 0f), Size(w, size.height))
    if (rivets) {
        val r = 2.4.dp.toPx(); val p = 7.dp.toPx()
        listOf(
            Offset(p, p), Offset(size.width - p, p),
            Offset(p, size.height - p), Offset(size.width - p, size.height - p),
        ).forEach {
            drawCircle(Sb.metalDark, r, it)
            drawCircle(Sb.metalLight.copy(alpha = 0.8f), r * 0.45f, it - Offset(r * 0.3f, r * 0.3f))
        }
    }
}

/** Recessed (sunken) panel: dark fill + inset bevel (dark top/left, light bottom/right). */
private fun Modifier.recessed(): Modifier = drawBehind {
    drawRect(Sb.metalDark)
    val w = 1.5.dp.toPx()
    drawRect(Color.Black, Offset.Zero, Size(size.width, w))
    drawRect(Color.Black, Offset.Zero, Size(w, size.height))
    drawRect(Sb.metalLight.copy(alpha = 0.5f), Offset(0f, size.height - w), Size(size.width, w))
    drawRect(Sb.metalLight.copy(alpha = 0.5f), Offset(size.width - w, 0f), Size(w, size.height))
}

private fun chamfer(size: Size, cut: Float): Path = Path().apply {
    val w = size.width; val h = size.height
    moveTo(cut, 0f); lineTo(w - cut, 0f); lineTo(w, cut); lineTo(w, h - cut)
    lineTo(w - cut, h); lineTo(cut, h); lineTo(0f, h - cut); lineTo(0f, cut); close()
}

private fun DrawScope.crosshair() {
    val g = Sb.green.copy(alpha = 0.42f)
    val faint = Sb.green.copy(alpha = 0.10f)
    val cx = size.width / 2; val cy = size.height / 2
    val step = 18.dp.toPx()
    var gx = cx % step; while (gx < size.width) { drawLine(faint, Offset(gx, 0f), Offset(gx, size.height)); gx += step }
    var gy = cy % step; while (gy < size.height) { drawLine(faint, Offset(0f, gy), Offset(size.width, gy)); gy += step }
    drawLine(g, Offset(0f, cy), Offset(size.width, cy), 1.2f)
    drawLine(g, Offset(cx, 0f), Offset(cx, size.height), 1.2f)
    val t = 5.dp.toPx()
    var x = cx % step; while (x < size.width) { drawLine(g, Offset(x, cy - t), Offset(x, cy + t)); x += step }
    var y = cy % step; while (y < size.height) { drawLine(g, Offset(cx - t, y), Offset(cx + t, y)); y += step }
    drawCircle(g, 24.dp.toPx(), Offset(cx, cy), style = Stroke(1.2.dp.toPx()))
}

// --- screen ---------------------------------------------------------------------------------------

@Composable
private fun NeonScreen(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val inf = rememberInfiniteTransition(label = "neon")
    val pulse by inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val scan by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "scan")
    Box(
        modifier.drawBehind {
            val cut = 14.dp.toPx()
            val path = chamfer(size, cut)
            drawPath(path, Sb.screen)
            clipPath(path) {
                crosshair()
                drawRect(Sb.green.copy(alpha = 0.06f), Offset(0f, scan * size.height), Size(size.width, 2.dp.toPx()))
            }
            for (i in 4 downTo 1) {
                drawPath(path, Sb.neon.copy(alpha = pulse * 0.09f * i), style = Stroke((i * 2.4f).dp.toPx()))
            }
            drawPath(path, Sb.neonHot.copy(alpha = pulse), style = Stroke(1.6.dp.toPx()))
        }.padding(16.dp),
        content = content,
    )
}

// --- text helper ----------------------------------------------------------------------------------

@Composable
private fun mono(
    text: String, color: Color = Sb.silver, size: Float = 11f, weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null, spacing: Float = 0.5f, modifier: Modifier = Modifier,
) = Text(text, modifier = modifier, color = color, fontFamily = JetBrainsMono, fontSize = size.sp,
    fontWeight = weight, textAlign = align, letterSpacing = spacing.sp)

// --- screen entry ---------------------------------------------------------------------------------

@Composable
fun SpaceballsScreen(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()

    var tick by remember { mutableStateOf(0) }
    var uptime by remember { mutableStateOf(8_675_309L) }
    LaunchedEffect(Unit) { while (true) { delay(450); tick += 1; uptime += 7 } }

    var activeCmd by remember { mutableStateOf<String?>(null) }
    var cmdPhase by remember { mutableStateOf(0) }
    LaunchedEffect(activeCmd) { if (activeCmd != null) { cmdPhase = 0; delay(2600); cmdPhase = 1 } }

    var ludicrous by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Sb.metalDark)) {
        Column(
            Modifier.fillMaxSize().metal(rivets = true).verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeaderBar(uptime, tick)
            CentralMonitor(tick)
            SubsystemGrid(tick)
            FlipBank(
                themeDark = s.theme == ThemeMode.DARK,
                amoled = s.amoledBlack,
                scanlines = s.scanlines,
                boot = s.bootAnimation,
                onTheme = { vm.update { st -> st.copy(theme = if (st.theme == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK) } },
                onAmoled = { vm.update { st -> st.copy(amoledBlack = !st.amoledBlack) } },
                onScanlines = { vm.update { st -> st.copy(scanlines = !st.scanlines) } },
                onBoot = { vm.update { st -> st.copy(bootAnimation = !st.bootAnimation) } },
            )
            DotMatrix()
            ControlArray(onCommand = { activeCmd = it }, onLudicrous = { ludicrous = true })
            Footer()
            Spacer(Modifier.height(6.dp))
        }

        if (activeCmd != null) {
            ProcessingOverlay(activeCmd!!, cmdPhase, SPINNER[tick % SPINNER.size]) { activeCmd = null }
        }
        if (ludicrous) {
            LudicrousOverlay { ludicrous = false }
        }
    }
}

private val SPINNER = listOf("|", "/", "—", "\\")

// --- zones ----------------------------------------------------------------------------------------

@Composable
private fun HeaderBar(uptime: Long, tick: Int) {
    Column(Modifier.fillMaxWidth().metal().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IndicatorLight(Sb.green, tick % 2 == 0)
                IndicatorLight(Sb.amber, tick % 3 == 0)
                IndicatorLight(Sb.red, false)
            }
            mono("SPACEBALL ONE", Sb.silver, 13f, FontWeight.Bold, TextAlign.Center, 2f,
                Modifier.weight(1f))
            IndicatorLight(Sb.green, true)
        }
        mono("ARGUS DYNAMICS · BRIDGE CONSOLE · DECK 1", Sb.dim, 7f, align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Readout("CHRONOMETER", "T+ ${uptime}", Sb.green, Modifier.weight(1.2f))
            Readout("SYS LOAD", "87%", Sb.amber, Modifier.weight(1f))
            Readout("STATUS", "NOMINAL", Sb.green, Modifier.weight(1f))
        }
        NeonBar(87, Sb.amber)
    }
}

@Composable
private fun Readout(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.recessed().padding(horizontal = 7.dp, vertical = 5.dp)) {
        mono(label, Sb.dim, 6.5f, spacing = 1f)
        mono(value, color, 12f, FontWeight.Bold)
    }
}

@Composable
private fun CentralMonitor(tick: Int) {
    val lines = remember { mutableStateListOf<String>() }
    var clock by remember { mutableStateOf(52_327L) }
    LaunchedEffect(Unit) {
        while (true) {
            clock += (1..3).random()
            val h = (clock / 3600) % 24; val m = (clock / 60) % 60; val sec = clock % 60
            lines.add(0, "%02d:%02d:%02d │ %s".format(h, m, sec, LOG_EVENTS.random()))
            if (lines.size > 9) lines.removeAt(lines.size - 1)
            delay(1500)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Nameplate("CENTRAL MONITOR — DIAGNOSTIC FEED")
        NeonScreen(Modifier.fillMaxWidth().height(196.dp)) {
            Column(Modifier.fillMaxSize()) {
                if (lines.isEmpty()) mono("> awaiting telemetry…", Sb.green, 9f)
                lines.forEach { line ->
                    val color = when {
                        "DELETED" in line || "ROGUE" in line -> Sb.red
                        "VERIFIED" in line || "SUCCESS" in line -> Sb.green
                        else -> Sb.green.copy(alpha = 0.82f)
                    }
                    mono(line, color, 8.5f, spacing = 0.3f, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private val LOG_EVENTS = listOf(
    "USER ACCESSED BRIDGE CONSOLE",
    "AUTHORIZATION VERIFIED (PROBABLY)",
    "ENVIRONMENTAL CONTROLS QUERIED",
    "ROGUE PROCESS DETECTED: [DELETED]",
    "COFFEE MAKER PINGED — NO REPLY",
    "AUDIT LOG AUDITED SUCCESSFULLY",
    "MEMO 412 ESCALATED TO COMMITTEE",
    "INERTIAL DAMPENERS RECALIBRATED",
    "PERMISSION ELEVATED, THEN REVOKED",
    "SCHWARTZ RING POLISHED",
    "SUBSYSTEM 7-B HUMMED OMINOUSLY",
)

@Composable
private fun SubsystemGrid(tick: Int) {
    Column(Modifier.fillMaxWidth()) {
        Nameplate("SUBSYSTEM MONITORING")
        Column(Modifier.fillMaxWidth().metal().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Two needle gauges + bar mini-panels.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularGauge("SHIP INTEGRITY", wobble(tick, 0, 80, 96), Sb.green, Modifier.weight(1f))
                CircularGauge("LIFE SUPPORT", wobble(tick, 5, 55, 74), Sb.amber, Modifier.weight(1f))
                CircularGauge("REACTOR", wobble(tick, 9, 60, 88), Sb.green, Modifier.weight(1f))
            }
            SubPanel("CABIN PRESSURE", "${wobble(tick, 2, 70, 92)} kPa", wobble(tick, 2, 70, 92), Sb.green)
            SubPanel("CPU LOAD", "87% — HELD", 87, Sb.amber)
            SubPanel("FUEL RESERVES", "0% — SHIP IN MOTION", 0, Sb.red)
            SubPanel("NAVIGATION", "OFFLINE — AT FTL SPEED", null, Sb.red)
            SubPanel("DIPLOMATIC RELATIONS", "UNKNOWN", null, Sb.dim)
        }
    }
}

private fun wobble(tick: Int, seed: Int, lo: Int, hi: Int): Int {
    val mid = (lo + hi) / 2.0; val amp = (hi - lo) / 2.0
    return (mid + sin((tick + seed) / 4.0) * amp).toInt().coerceIn(0, 100)
}

@Composable
private fun SubPanel(label: String, value: String, pct: Int?, color: Color) {
    Column(Modifier.fillMaxWidth().recessed().padding(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            mono(label, Sb.silver, 8.5f, FontWeight.Bold, spacing = 0.8f)
            mono(stateWord(pct, color), color, 7.5f, FontWeight.Bold)
        }
        mono(value, color, 9.5f, FontWeight.Bold, modifier = Modifier.padding(top = 1.dp))
        if (pct != null) { Spacer(Modifier.height(4.dp)); NeonBar(pct, color) }
    }
}

private fun stateWord(pct: Int?, color: Color): String = when {
    color == Sb.red -> "CRITICAL"
    color == Sb.amber -> "CAUTION"
    pct == null -> "—"
    pct < 25 -> "LOW"
    else -> "NOMINAL"
}

@Composable
private fun NeonBar(pct: Int, color: Color) {
    val cells = 22
    val on = (pct * cells / 100).coerceIn(0, cells)
    Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        repeat(cells) { i ->
            Box(Modifier.weight(1f).fillMaxHeight()
                .background(if (i < on) color else Sb.metalDark)
                .drawBehind { if (i < on) drawRect(Color.White.copy(alpha = 0.18f), size = Size(size.width, size.height * 0.4f)) })
        }
    }
}

@Composable
private fun CircularGauge(label: String, pct: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(60.dp)) {
            val stroke = 5.dp.toPx()
            val d = size.minDimension - stroke
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            val sz = Size(d, d)
            drawArc(Sb.metalDark, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * pct / 100f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            val ang = Math.toRadians((135f + 270f * pct / 100f).toDouble())
            val cx = size.width / 2; val cy = size.height / 2; val nr = d / 2 * 0.86f
            drawLine(Sb.silver, Offset(cx, cy), Offset(cx + (cos(ang) * nr).toFloat(), cy + (sin(ang) * nr).toFloat()), 2.dp.toPx())
            drawCircle(Sb.silver, 3.dp.toPx(), Offset(cx, cy))
        }
        mono("$pct%", color, 11f, FontWeight.Bold)
        mono(label, Sb.silver, 6.5f, align = TextAlign.Center, modifier = Modifier.width(74.dp))
    }
}

@Composable
private fun IndicatorLight(color: Color, on: Boolean) {
    Canvas(Modifier.size(9.dp)) {
        drawCircle(Sb.metalDark, size.minDimension / 2)
        drawCircle(if (on) color else color.copy(alpha = 0.18f), size.minDimension / 2 * 0.72f)
        if (on) drawCircle(color.copy(alpha = 0.4f), size.minDimension / 2, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun Nameplate(text: String) {
    Box(Modifier.fillMaxWidth().background(Sb.metalDark).padding(horizontal = 8.dp, vertical = 3.dp)) {
        mono(text, Sb.amber, 8f, FontWeight.Bold, spacing = 1.6f)
    }
}

// --- flip-switch bank (the ONLY real controls) ---------------------------------------------------

@Composable
private fun FlipBank(
    themeDark: Boolean, amoled: Boolean, scanlines: Boolean, boot: Boolean,
    onTheme: () -> Unit, onAmoled: () -> Unit, onScanlines: () -> Unit, onBoot: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Nameplate("MANUAL OVERRIDE — AUTHORIZED PERSONNEL ONLY")
        Column(Modifier.fillMaxWidth().metal().padding(10.dp)) {
            mono("WARN: these are armed. (they adjust display settings.)", Sb.dim, 7.5f)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FlipSwitch("CASCADE", themeDark, onTheme)
                FlipSwitch("SHUTDOWN", amoled, onAmoled)
                FlipSwitch("DAMPENERS", scanlines, onScanlines)
                FlipSwitch("IGNITION", boot, onBoot)
            }
            Spacer(Modifier.height(4.dp))
            mono("theme · true-black · scanlines · boot-sequence", Sb.dim, 6.5f, align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FlipSwitch(label: String, on: Boolean, onToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onToggle() }) {
        Canvas(Modifier.width(30.dp).height(50.dp)) {
            val cr = CornerRadius(4.dp.toPx())
            drawRoundRect(Sb.metalDark, cornerRadius = cr)
            drawRoundRect(Color.Black, cornerRadius = cr, style = Stroke(1.5.dp.toPx()))
            val pad = 4.dp.toPx()
            val knobH = (size.height - pad * 2) * 0.46f
            val top = if (on) pad else size.height - pad - knobH
            val knob = if (on) Sb.green else Sb.metalLight
            drawRoundRect(knob, topLeft = Offset(pad, top), size = Size(size.width - pad * 2, knobH),
                cornerRadius = CornerRadius(3.dp.toPx()))
            if (on) drawRoundRect(Sb.green.copy(alpha = 0.45f), topLeft = Offset(pad - 2, top - 2),
                size = Size(size.width - pad * 2 + 4, knobH + 4), cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(2.dp.toPx()))
        }
        mono(label, if (on) Sb.green else Sb.silver, 6.5f, FontWeight.Bold, TextAlign.Center,
            modifier = Modifier.width(48.dp))
        mono(if (on) "ARMED" else "SAFE", if (on) Sb.green else Sb.dim, 6f, align = TextAlign.Center)
    }
}

// --- dot-matrix ticker ----------------------------------------------------------------------------

@Composable
private fun DotMatrix() {
    val inf = rememberInfiniteTransition(label = "dm")
    val phase by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(280, easing = LinearEasing)), label = "dm")
    var idx by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2600); idx += 1 } }
    val msg = DM_MSGS[idx % DM_MSGS.size]
    Box(Modifier.fillMaxWidth().recessed().padding(horizontal = 8.dp, vertical = 6.dp)) {
        // dot-matrix texture behind the LED text
        Box(Modifier.fillMaxWidth().height(16.dp).drawBehind {
            val d = 3.dp.toPx()
            var y = 1.dp.toPx()
            while (y < size.height) {
                var x = 0f
                while (x < size.width) { drawCircle(Color.White.copy(alpha = 0.04f), 0.6.dp.toPx(), Offset(x, y)); x += d }
                y += d
            }
        }) {
            mono(msg, if (phase > 0.5f) Sb.amber else Sb.gold, 9f, FontWeight.Bold, spacing = 1.5f,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

private val DM_MSGS = listOf(
    ">> ADVISORY: TUESDAY IS LIKELY TO OCCUR",
    ">> WARRNING: SPELL-CHECK SUBSYSTEM OFFLINE",
    ">> CRITICAL: PRINTER OUT OF TONER (AGAIN)",
    ">> SYS: COMBOLINGUS LEVELS NOMINAL",
    ">> ADVISORY-ADMINISTRATIVE: FORM 27-B/6 UNFILED",
    ">> SCHWARTZ FIELD STABLE — DO NOT INVERT",
)

// --- lower control array --------------------------------------------------------------------------

@Composable
private fun ControlArray(onCommand: (String) -> Unit, onLudicrous: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Nameplate("COMMAND ARRAY")
        Column(Modifier.fillMaxWidth().metal().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DomeButton(Sb.green, "NAV") { onCommand("INITIALIZE NAVIGATION SUBSYSTEM") }
                DomeButton(Sb.amber, "COFFEE") { onCommand("RESET COFFEE MAKER") }
                DomeButton(Sb.purple, "AUDIT") { onCommand("AUDIT THE AUDIT LOG") }
                DomeButton(Sb.neon, "DEEP-SP") { onCommand("QUERY DEEP SPACE PROTOCOLS") }
                DomeButton(Sb.gold, "BEVRG") { onCommand("DISPENSE BEVERAGE MODULE") }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LudicrousButton(onLudicrous)
            }
        }
    }
}

@Composable
private fun DomeButton(color: Color, label: String, diameter: Dp = 46.dp, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) { if (pressed) { delay(150); pressed = false } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(diameter).clickable { pressed = true; onClick() }) {
            val r = size.minDimension / 2; val c = center
            drawCircle(Sb.metalDark, r, c)
            drawCircle(Sb.metalLight.copy(alpha = 0.55f), r, c, style = Stroke(2f))
            val inner = r * (if (pressed) 0.7f else 0.8f)
            drawCircle(
                Brush.radialGradient(
                    listOf(color, color.copy(alpha = 0.55f), color.copy(alpha = 0.18f)),
                    center = c - Offset(r * 0.3f, r * 0.3f), radius = inner * 1.5f,
                ), inner, c,
            )
            if (pressed) drawCircle(Color.White.copy(alpha = 0.35f), inner * 0.55f, c)
            drawCircle(Color.White.copy(alpha = 0.55f), inner * 0.16f, c - Offset(r * 0.32f, r * 0.32f))
        }
        mono(label, Sb.silver, 6.5f, FontWeight.Bold, TextAlign.Center, modifier = Modifier.width(diameter + 8.dp))
    }
}

@Composable
private fun LudicrousButton(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) { if (pressed) { delay(160); pressed = false } }
    val inf = rememberInfiniteTransition(label = "lud")
    val glow by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(680), RepeatMode.Reverse), label = "g")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        mono("⚠  LUDICROUS SPEED  ⚠", Sb.red, 10f, FontWeight.Bold, TextAlign.Center, 1f)
        Spacer(Modifier.height(5.dp))
        Canvas(Modifier.size(98.dp).clickable { pressed = true; onClick() }) {
            val r = size.minDimension / 2; val c = center
            // hazard guard ring
            drawCircle(Sb.gold.copy(alpha = 0.22f), r, c)
            drawCircle(Sb.metalDark, r * 0.88f, c)
            drawCircle(Sb.gold.copy(alpha = glow * 0.85f), r * 0.88f, c, style = Stroke(3.dp.toPx()))
            val inner = r * (if (pressed) 0.58f else 0.64f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Sb.redHot, Sb.red, Color(0xFF7A0A18)),
                    center = c - Offset(r * 0.22f, r * 0.22f), radius = inner * 1.6f,
                ), inner, c,
            )
            drawCircle(Color.White.copy(alpha = 0.22f + glow * 0.2f), inner * 0.5f, c - Offset(r * 0.2f, r * 0.2f))
        }
        Spacer(Modifier.height(3.dp))
        mono("PRESS TO ENGAGE", Sb.dim, 7f, align = TextAlign.Center)
    }
}

@Composable
private fun Footer() {
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        mono("STATUS: UNDER REVIEW (SINCE 2847) · LAST ACCESS: SYSTEM ADMIN (UNAUTHORIZED)", Sb.dim, 7f,
            align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        mono("all systems nominal (citation needed) — we ain't found shit", Sb.metalLight, 6.5f,
            align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

// --- overlays -------------------------------------------------------------------------------------

@Composable
private fun ProcessingOverlay(command: String, phase: Int, spinner: String, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f))
            .clickable(enabled = phase == 1) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.86f).metal().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (phase == 0) {
                mono("$spinner  PROCESSING  $spinner", Sb.green, 18f, FontWeight.Bold, TextAlign.Center, 2f, Modifier.fillMaxWidth())
                mono("EXECUTING: $command", Sb.silver, 10f, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                mono("PLEASE STAND BY · SYSTEM LOAD AT 87%", Sb.amber, 9f, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                mono("ESTIMATED TIME REMAINING: CALCULATING…", Sb.dim, 9f, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp)); NeonBar(87, Sb.green)
            } else {
                mono("OPERATION COMPLETE", Sb.green, 18f, FontWeight.Bold, TextAlign.Center, 2f, Modifier.fillMaxWidth())
                mono(command, Sb.silver, 10f, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                mono("STATUS: NOMINAL · NO EFFECT DETECTED · THIS IS FINE", Sb.amber, 9f, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Box(Modifier.metal().clickable { onDismiss() }.padding(horizontal = 16.dp, vertical = 9.dp)) {
                    mono("PROCEED", Sb.green, 11f, FontWeight.Bold, spacing = 1f)
                }
            }
        }
    }
}

/**
 * LUDICROUS SPEED — the Spaceballs (1987) jump, beat for beat:
 *   BRACE ("Ludicrous speed, GO!", seatbelts fastened, engines charging) → violent g-force
 *   star-streak JUMP with a hyperspace tunnel + screen shake → white BLAST flash → scrolling tartan
 *   PLAID ("They've gone to plaid!") → OVERSHOOT ("We've passed them!") as the ship streaks off to a
 *   vanishing point. Params-only (one star list); every position is computed in the draw pass, so
 *   there is zero per-frame allocation.
 */
@Composable
private fun LudicrousOverlay(onDone: () -> Unit) {
    // Each star: unit ray (x, y), radial seed, brightness — one allocation, reused every frame.
    val stars = remember {
        List(220) {
            val a = kotlin.random.Random.nextDouble(0.0, 2 * PI)
            floatArrayOf(
                cos(a).toFloat(), sin(a).toFloat(),
                kotlin.random.Random.nextFloat(), 0.5f + kotlin.random.Random.nextFloat() * 0.5f,
            )
        }
    }
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(6400, easing = LinearEasing)); onDone() }
    val inf = rememberInfiniteTransition(label = "plaid")
    val scroll by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(680, easing = LinearEasing)), label = "scroll")

    val braceEnd = 0.14f
    val accelEnd = 0.55f
    val flashEnd = 0.60f
    val plaidEnd = 0.86f

    Box(Modifier.fillMaxSize().background(Color.Black).clickable { onDone() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val p = t.value
            val base = Offset(size.width / 2, size.height / 2)
            val maxR = size.minDimension * 0.95f
            when {
                p < braceEnd -> {
                    // Calm starfield, a building rumble before the lever goes forward.
                    val warm = p / braceEnd
                    val jit = warm * warm * 3.dp.toPx()
                    val c = base + Offset(sin(p * 211f) * jit, cos(p * 173f) * jit)
                    stars.forEach { s ->
                        val pos = c + Offset(s[0], s[1]) * (s[2] * maxR)
                        drawCircle(Color.White.copy(alpha = 0.25f + 0.5f * s[3]), 1f + s[3], pos)
                    }
                }
                p < accelEnd -> {
                    val a = (p - braceEnd) / (accelEnd - braceEnd)
                    val speed = a * a
                    // Violent shake at the punch, and a blue hyperspace tunnel bloom out of the centre.
                    val jit = speed * 7.dp.toPx()
                    val c = base + Offset(sin(p * 257f) * jit, cos(p * 193f) * jit)
                    val tunnelR = maxR * (0.25f + speed * 0.7f)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Sb.neonHot.copy(alpha = 0.30f * speed), Color.Transparent), c, tunnelR,
                        ),
                        tunnelR, c,
                    )
                    stars.forEach { s ->
                        val dir = Offset(s[0], s[1])
                        val r0 = (0.12f + speed) * maxR * (0.2f + s[2])
                        val len = (4f + speed * 360f) * (0.4f + s[2])
                        drawLine(
                            Color.White.copy(alpha = (0.35f + 0.6f * speed).coerceAtMost(1f)),
                            c + dir * r0, c + dir * (r0 + len), 1f + speed * 3.5f, cap = StrokeCap.Round,
                        )
                    }
                }
                p < flashEnd -> drawRect(
                    Color.White.copy(alpha = (1f - (p - accelEnd) / (flashEnd - accelEnd)).coerceIn(0f, 1f)),
                )
                p < plaidEnd -> drawPlaid(scroll)
                else -> {
                    // Overshoot: the plaid recedes as the ship streaks off to a vanishing point.
                    val q = (p - plaidEnd) / (1f - plaidEnd)
                    val pull = 1f - q
                    drawPlaid(scroll, alpha = pull)
                    stars.forEach { s ->
                        val dir = Offset(s[0], s[1])
                        val r1 = (0.15f + s[2]) * maxR * (0.3f + pull)
                        val r0 = r1 + 220f * pull * (0.4f + s[2])
                        drawLine(Color.White.copy(alpha = 0.5f * pull), base + dir * r0, base + dir * r1, 2f, cap = StrokeCap.Round)
                    }
                    drawCircle(Color.White.copy(alpha = pull), 2f + 6f * pull, base)
                }
            }
        }
        val p = t.value
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                p < braceEnd -> {
                    mono("▲  LUDICROUS  SPEED  ▲", Sb.amber, 16f, FontWeight.Bold, TextAlign.Center, 3f)
                    Spacer(Modifier.height(8.dp))
                    mono("ENGINES: LUDICROUS   ·   SEATBELTS: FASTENED", Sb.dim, 9f, align = TextAlign.Center, spacing = 1.5f)
                    if (p > braceEnd * 0.62f) {
                        Spacer(Modifier.height(18.dp))
                        mono("G O !", Sb.redHot, 34f, FontWeight.Bold, TextAlign.Center, 8f)
                    }
                }
                p < flashEnd -> {}
                p < plaidEnd -> {
                    mono("★   LUDICROUS  SPEED   ★", Color.White, 20f, FontWeight.Bold, TextAlign.Center, 3f)
                    Spacer(Modifier.height(12.dp))
                    mono("THEY'VE GONE TO PLAID!", Sb.gold, 18f, FontWeight.Bold, TextAlign.Center, 2f)
                    Spacer(Modifier.height(18.dp))
                    mono("tap to disengage", Sb.silver, 9f, align = TextAlign.Center)
                }
                else -> {
                    mono("WE'VE PASSED THEM!", Sb.neonHot, 18f, FontWeight.Bold, TextAlign.Center, 2f)
                    Spacer(Modifier.height(10.dp))
                    mono("...  ALL  STOP", Sb.silver, 12f, FontWeight.Normal, TextAlign.Center, 2f)
                }
            }
        }
    }
}

/**
 * Authentic scrolling tartan — a repeating "sett" of coloured bands woven warp × weft. The vertical
 * pass is drawn at half alpha so crossings blend like a real weave (two thread colours meeting), which
 * is what reads as plaid rather than a flat grid. Royal-Stewart-flavoured bright sett; scrolls diagonally.
 */
private fun DrawScope.drawPlaid(scroll: Float, alpha: Float = 1f) {
    val red = Color(0xFFC8102E)
    val navy = Color(0xFF1B2A6B)
    val green = Color(0xFF0F7A34)
    val yellow = Color(0xFFF2C200)
    val white = Color(0xFFF5F5F5)
    val black = Color(0xFF0A0A0A)
    // (colour, width in dp) — one repeat of the sett, symmetric like a real tartan.
    val sett = listOf(
        red to 24f, black to 2f, yellow to 2f, black to 2f, red to 8f,
        navy to 14f, white to 2f, navy to 14f, red to 8f,
        green to 16f, yellow to 2f, green to 16f, red to 8f,
    )
    val period = (sett.sumOf { it.second.toDouble() }).toFloat() * 1.dp.toPx()
    drawRect(Color(0xFF120406).copy(alpha = alpha))
    val off = (scroll * period) % period
    // Weft (horizontal bands) — opaque ground.
    var y = off - period
    var i = 0
    while (y < size.height) {
        val (col, w) = sett[i % sett.size]
        val wp = w.dp.toPx()
        drawRect(col.copy(alpha = alpha), Offset(0f, y), Size(size.width, wp + 0.5f))
        y += wp; i++
    }
    // Warp (vertical bands) — half alpha so crossings blend into the weave.
    var x = off - period
    i = 0
    while (x < size.width) {
        val (col, w) = sett[i % sett.size]
        val wp = w.dp.toPx()
        drawRect(col.copy(alpha = 0.5f * alpha), Offset(x, 0f), Size(wp + 0.5f, size.height))
        x += wp; i++
    }
}
