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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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

/**
 * CONFIG for the LUDICROUS SPEED → PLAID → FULL STOP sequence — every look/feel tunable in one place
 * so the owner can iterate on the Pixel. Palette is the build-spec's movie-accurate hex. (Canon trivia,
 * since it always comes up: Ludicrous Speed is ~65–1,380× light speed; the DVD's "watch the film in
 * Ludicrous Speed" feature clocks ~288× c.)
 */
private object Lud {
    // Shaft grid (the plaid)
    val RAIL_GOLD = Color(0xFFFFD24D)      // corner rails + every-4th sett accent (thickest/brightest)
    val GRID_ORANGE = Color(0xFFFF8A1E)    // primary wall grid
    val GRID_FINE = Color(0xFFFF6A00)      // fine woven cross-hatch
    val WALL_RED = Color(0xFFC0240E)       // wall fill near the rim
    val WALL_RED_DEEP = Color(0xFF4A0C0C)  // wall fill toward the far centre (depth shading)
    // Central starburst
    val BURST_CORE = Color(0xFFFFFFFF)
    val BURST_MID = Color(0xFFFFF4CC)
    val BURST_GLOW = Color(0xFFFFC04D)
    val BURST_EDGE = Color(0xFFFF8800)
    val BURST_RAY = Color(0xFFFFE07A)
    // Act-I hyperspace bloom
    val WARP_BLUE = Color(0xFF2B6BFF)
    val WARP_CYAN = Color(0xFF00D2FF)
    // Geometry / motion
    const val RING_RATIO = 1.20f           // geometric ring spacing (slow far → fast near)
    const val SEED_PX = 3f
    const val SETUP_MS = 8000               // Beat 1: bridge set-up dialogue ("light speed is too slow…")
    const val ENGAGE_MS = 9000              // Beat 2-3: buckle/GO → white light-speed climb → plaid forming
    const val FLYBY_MS = 7000               // Beat 4: exterior plaid flyby past the Winnebago
    const val STOP_MS = 1300                // Beat 5: abrupt stop (the gag is the near-instant halt)
    const val SLAM_MS = 700                 // Helmet's "STOOOOP!" red override before the stop
    const val STOP_HOLD_MS = 900            // FULL STOP plate hold before the aftermath
    const val AFTER_MS = 9000               // Beat 6: the aftermath dialogue ("…smoke if you got 'em")
    const val CRUISE_MS = 1500              // ring-scroll period (lower = faster forward rush)
    const val twoStageBrake = true          // first press warns (Sandurz), second press stops (Helmet)
}

// Saturated streak palette, weighted warm (red/orange/yellow heavier) — the "plaid" rain.
private val StreakPalette = listOf(
    Color(0xFFFF1F1F), Color(0xFFFF1F1F), Color(0xFFFF8A00), Color(0xFFFF8A00),
    Color(0xFFFFE100), Color(0xFFFFE100), Color(0xFF25FF3C), Color(0xFF00E6FF),
    Color(0xFF2A6BFF), Color(0xFFFF2ED1), Color(0xFFFFFFFF),
)

private fun streakColor(s: FloatArray): Color =
    StreakPalette[(s[4] * StreakPalette.size).toInt().coerceIn(0, StreakPalette.size - 1)]

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
 * LUDICROUS SPEED → PLAID → FULL STOP — the Spaceballs (1987) gag, built to the owner's spec and played
 * dead straight. Three acts: ENGAGE (buckle up → "GO!" → a slow white light-speed climb whose bridge
 * signs light LIGHT→RIDICULOUS→LUDICROUS in order, then it turns to plaid), PLAID (woven red/gold shaft +
 * starburst + streak rain, then Barf's "they've gone to plaid!" + "we passed 'em!"; held forever —
 * the ship overshoots because nobody stops it), and FULL STOP (pull the "EMERGENCY STOP — NEVER USE"
 * lever → a visible deceleration ending in a violent impact, Helmet into the console). Honesty: the animation announces nothing
 * to a screen reader — it's purely visual and silent by design; only the trigger and brake are real
 * controls. Honours OS reduce-motion (skips Act I + the snap). Tunables live in the Lud CONFIG above.
 */
@Composable
private fun LudicrousOverlay(onDone: () -> Unit) {
    // Each star: unit ray (x, y), radial seed, brightness, colour seed — one alloc, reused per frame.
    val stars = remember {
        List(240) {
            val a = kotlin.random.Random.nextDouble(0.0, 2 * PI)
            floatArrayOf(
                cos(a).toFloat(), sin(a).toFloat(),
                kotlin.random.Random.nextFloat(), 0.5f + kotlin.random.Random.nextFloat() * 0.5f,
                kotlin.random.Random.nextFloat(),
            )
        }
    }
    val context = LocalContext.current
    val reduceMotion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        }.getOrDefault(false)
    }
    val setupA = remember { Animatable(0f) }  // Beat 1: bridge set-up dialogue
    val eng = remember { Animatable(0f) }     // Beat 2-3: buckle/GO → jump → signs → plaid
    val fly = remember { Animatable(0f) }     // Beat 4: exterior plaid flyby past the Winnebago
    val stop = remember { Animatable(0f) }    // Beat 5: the emergency stop
    val after = remember { Animatable(0f) }   // Beat 6: aftermath dialogue
    var phase by remember { mutableStateOf(0) }      // 0 setup·1 engage·2 flyby·3 cruise(held)·4 slam·5 stop·6 aftermath
    var brakeStage by remember { mutableStateOf(0) } // 0 armed · 1 warned (override)
    LaunchedEffect(Unit) {
        if (reduceMotion) {
            phase = 3
        } else {
            setupA.animateTo(1f, tween(Lud.SETUP_MS, easing = LinearEasing))
            phase = 1; eng.animateTo(1f, tween(Lud.ENGAGE_MS, easing = LinearEasing))
            phase = 2; fly.animateTo(1f, tween(Lud.FLYBY_MS, easing = LinearEasing))
            phase = 3 // back on the bridge — plaid cruise, held until the brake is pulled
        }
    }
    LaunchedEffect(phase) {
        when (phase) {
            4 -> { delay(Lud.SLAM_MS.toLong()); phase = 5 }       // Helmet's "STOOOOP!" → the stop
            5 -> {
                stop.snapTo(0f)
                stop.animateTo(1f, tween(Lud.STOP_MS, easing = LinearEasing))
                delay(Lud.STOP_HOLD_MS.toLong())
                phase = 6                                          // aftermath
            }
            6 -> { after.animateTo(1f, tween(Lud.AFTER_MS, easing = LinearEasing)); onDone() }
        }
    }
    val inf = rememberInfiniteTransition(label = "plaid")
    // Animation State objects — READ ONLY inside the draw/layer blocks (deferred) so the overlay
    // redraws at 60fps WITHOUT recomposing the whole tree every frame (the smoothness fix).
    val scrollState = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(Lud.CRUISE_MS, easing = LinearEasing)), label = "scroll")
    val pumpState = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1430, easing = LinearEasing)), label = "pump")
    val blinkState = inf.animateFloat(0.25f, 1f, infiniteRepeatable(tween(150), RepeatMode.Reverse), label = "blink")
    // Discrete line/stage indices (derived) so the text layer recomposes only when a line changes, not per frame.
    val engStage by remember {
        derivedStateOf {
            val e = eng.value
            when {
                e < 0.05f -> 0
                e < 0.12f -> 1   // buckle up
                e < 0.20f -> 2   // "Ludicrous speed, GO!"
                e < 0.343f -> 3  // LIGHT SPEED
                e < 0.481f -> 4  // RIDICULOUS SPEED
                e < 0.62f -> 5   // LUDICROUS SPEED (flashing)
                else -> 6
            }
        }
    }
    val setupLine by remember { derivedStateOf { (setupA.value * 6f).toInt().coerceIn(0, 5) } }
    val flyLine by remember { derivedStateOf { (fly.value * 3f).toInt().coerceIn(0, 2) } }
    val afterLine by remember { derivedStateOf { (after.value * 6f).toInt().coerceIn(0, 5) } }

    val braceEnd = 0.20f // engage sub-beats: buckle up + "GO!" before launch
    val lightEnd = 0.62f // white light-speed climb (signs LIGHT→RIDICULOUS→LUDICROUS) → plaid forming

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val base = Offset(size.width / 2, size.height / 2)
            val maxR = size.minDimension * 0.95f
            // Deferred animation reads — happen in the draw phase, so they redraw without recomposing.
            val scroll = if (reduceMotion) 0.4f else scrollState.value
            val breath = 1f + 0.15f * sin((if (reduceMotion) 0f else pumpState.value) * 6.2832f)
            val blink = blinkState.value
            when (phase) {
                0 -> {
                    // SET-UP — a calm drifting starfield while the bridge argues over ludicrous speed.
                    stars.forEach { s ->
                        val drift = if (reduceMotion) 0f else setupA.value * 120f * (0.3f + s[3])
                        val x = ((s[0] * 0.5f + 0.5f) * size.width + drift) % size.width
                        val y = (s[1] * 0.5f + 0.5f) * size.height
                        drawCircle(Color.White.copy(alpha = 0.18f + 0.4f * s[3]), 1f + s[3], Offset(x, y))
                    }
                }
                1 -> {
                    val e = eng.value
                    when {
                        e < braceEnd -> {
                            // Trembling starfield while Lord Helmet shouts "Ludicrous speed, GO!".
                            val warm = e / braceEnd
                            val jit = if (reduceMotion) 0f else warm * warm * 3.dp.toPx()
                            val c = base + Offset(sin(e * 211f) * jit, cos(e * 173f) * jit)
                            stars.forEach { s ->
                                val pos = c + Offset(s[0], s[1]) * (s[2] * maxR)
                                drawCircle(Color.White.copy(alpha = 0.22f + 0.5f * s[3]), 1f + s[3], pos)
                            }
                        }
                        e < lightEnd -> {
                            // THE JUMP (the crux) — the windshield view: a horizontal SWEPT streak field.
                            // Warm amber ceiling bands across the top; white + multicolour streaks rake out
                            // of a HIGH, OFF-CENTRE vanishing point (NOT a centred radial burst). Shake peaks.
                            val a = (e - braceEnd) / (lightEnd - braceEnd)
                            val speed = a * a
                            val jit = if (reduceMotion) 0f else (0.3f + speed) * 13.dp.toPx()
                            val vp = Offset(size.width * 0.68f, size.height * 0.30f) +
                                Offset(sin(e * 47f) * jit, cos(e * 39f) * jit)
                            // warm amber/orange ceiling bands across the top third
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFFF9A2E).copy(alpha = 0.5f * speed),
                                        Color(0xFFCC5A14).copy(alpha = 0.22f * speed),
                                        Color.Transparent,
                                    ),
                                    0f, size.height * 0.36f,
                                ),
                                size = Size(size.width, size.height * 0.36f),
                            )
                            val rush = if (reduceMotion) 0.4f else scroll * 2.4f
                            stars.forEach { s ->
                                val dir = Offset(s[0], s[1])
                                val phaseR = (rush + s[2]) % 1f
                                val out = phaseR * phaseR
                                val r0 = out * maxR * (0.45f + s[2])
                                val len = (18f + 640f * out) * (0.4f + s[2]) * (0.3f + 0.7f * speed)
                                val col = if (s[4] < 0.55f) Color.White else streakColor(s)
                                drawLine(
                                    col.copy(alpha = (1f - phaseR * 0.25f) * (0.4f + 0.6f * speed)),
                                    vp + dir * r0, vp + dir * (r0 + len), 1.5f + speed * 3f * phaseR,
                                    cap = StrokeCap.Round, blendMode = BlendMode.Plus,
                                )
                            }
                        }
                        else -> {
                            // PLAID FORM — over a few seconds the white streaks gain colour and the woven
                            // red tunnel + burst fade in: "...they've gone to plaid."
                            val a = ((e - lightEnd) / (1f - lightEnd)).coerceIn(0f, 1f)
                            val jit = if (reduceMotion) 0f else (1f - a) * 8.dp.toPx()
                            val c = base + Offset(sin(e * 41f) * jit, cos(e * 35f) * jit)
                            drawShaft(scroll, a, c)
                            stars.forEach { s ->
                                val dir = Offset(s[0], s[1])
                                val phaseR = (scroll + s[2]) % 1f
                                val r0 = phaseR * maxR * (0.6f + s[2])
                                val len = (40f + 320f * phaseR) * (0.4f + s[2])
                                drawLine(
                                    lerp(Color.White, streakColor(s), a).copy(alpha = 0.85f * (1f - phaseR * 0.4f)),
                                    c + dir * r0, c + dir * (r0 + len), 2f + s[3] * 1.5f,
                                    cap = StrokeCap.Round, blendMode = BlendMode.Plus,
                                )
                            }
                            drawBurst(c, a, a)
                            drawTartanWash(a) // the translucent crossing tartan — what makes it read as plaid
                        }
                    }
                }
                2 -> drawFlyby(if (reduceMotion) 0.5f else fly.value, stars)
                3, 4 -> {
                    // PLAID cruise (held) — back on the bridge. Phase 4 = Helmet's red override wash.
                    val micro = if (reduceMotion) 0f else 2.dp.toPx()
                    val c = base + Offset(sin(scroll * 12f) * micro, cos(scroll * 9f) * micro)
                    drawShaft(scroll, 1f, c)
                    cruiseStreaks(stars, scroll, c, maxR)
                    drawBurst(c, breath, 1f)
                    drawTartanWash(1f) // the crossing tartan wash layered OVER the tunnel grid = plaid
                    if (phase == 4) drawRect(Lud.WALL_RED.copy(alpha = 0.35f + 0.25f * blink))
                }
                5 -> {
                    // FULL STOP — the abrupt halt (the gag): the tunnel slams shut and recedes to a point,
                    // ending in a violent recoil-lurch + white slam (Helmet headfirst into the console).
                    val s = stop.value
                    val pull = 1f - s
                    val impact = ((s - 0.84f) / 0.16f).coerceIn(0f, 1f)        // ramps 0→1 over the final 16%
                    val hit = (impact * (1f - impact) * 4f).coerceIn(0f, 1f)   // one spike, peaking at the slam
                    val shimmy = if (reduceMotion) 0f else pull * 6.dp.toPx()
                    val lurch = if (reduceMotion) 0f else hit * 30.dp.toPx()
                    val jx = sin(s * 33f) * shimmy + sin(s * 140f) * lurch
                    val jy = cos(s * 27f) * shimmy - lurch * 0.8f
                    val c = base + Offset(jx, jy)
                    drawShaft(scroll, pull, c)
                    stars.forEach { st2 ->
                        val dir = Offset(st2[0], st2[1])
                        val rOut = (0.2f + st2[2]) * maxR * (0.15f + pull)
                        val rIn = rOut * (0.2f + 0.5f * pull)
                        drawLine(streakColor(st2).copy(alpha = 0.6f * pull), c + dir * rOut, c + dir * rIn, 2f, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
                    }
                    drawBurst(c, pull, pull * pull)
                    if (hit > 0f) drawRect(Color.White.copy(alpha = (hit * 0.9f).coerceIn(0f, 1f)))   // the slam
                }
                else -> drawRect(Color.Black) // 6 aftermath: the dialogue plays over black
            }
        }

        when (phase) {
            0 -> SetupDialogue(setupLine)
            1 -> EngageText(engStage, blinkState)
            2 -> FlybyDialogue(flyLine)
            3 -> Column(
                Modifier.fillMaxSize().padding(top = 76.dp, bottom = 54.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    mono("DARK HELMET", Sb.redHot, 9f, FontWeight.Bold, TextAlign.Center, 3f)
                    Spacer(Modifier.height(8.dp))
                    mono("WE PASSED 'EM!", Color.White, 20f, FontWeight.Bold, TextAlign.Center, 2f)
                    Spacer(Modifier.height(8.dp))
                    mono("STOP THIS THING!", Sb.redHot, 14f, FontWeight.Bold, TextAlign.Center, 2f)
                    if (brakeStage == 1) {
                        Spacer(Modifier.height(10.dp))
                        mono("SANDURZ: WE CAN'T STOP — IT'S TOO DANGEROUS — SLOW DOWN FIRST!", Sb.amber, 9f, FontWeight.Bold, TextAlign.Center, 1f)
                    }
                }
                EmergencyStopButton(blinkState, brakeStage) {
                    when {
                        reduceMotion -> onDone()
                        Lud.twoStageBrake && brakeStage == 0 -> brakeStage = 1
                        Lud.twoStageBrake -> phase = 4
                        else -> phase = 5
                    }
                }
            }
            4 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 26.dp, vertical = 18.dp),
                ) {
                    mono("DARK HELMET", Sb.redHot, 9f, FontWeight.Bold, TextAlign.Center, 3f)
                    Spacer(Modifier.height(10.dp))
                    mono("BULLSHIT! STOP THIS THING!", Color.White, 18f, FontWeight.Bold, TextAlign.Center, 1.5f)
                    Spacer(Modifier.height(8.dp))
                    mono("I ORDER YOU!", Color.White, 14f, FontWeight.Bold, TextAlign.Center, 2f)
                    Spacer(Modifier.height(8.dp))
                    mono("S T O O O O O P !", Sb.redHot, 22f, FontWeight.Bold, TextAlign.Center, 4f)
                }
            }
            5 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    mono("⏹  FULL STOP", Sb.redHot, 18f, FontWeight.Bold, TextAlign.Center, 2f)
                    Spacer(Modifier.height(8.dp))
                    mono("ALL STOP · DROPPING TO SUBLIGHT", Sb.silver, 11f, FontWeight.Normal, TextAlign.Center, 1.5f)
                }
            }
            else -> AftermathDialogue(afterLine)
        }
    }
}

/** Act-I escalating signage: LIGHT → RIDICULOUS → LUDICROUS (flashing) → "GO!" + the seatbelts gag. */
@Composable
private fun EngageText(stage: Int, blink: State<Float>) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (stage) {
            // 1) Strap in (the giant-seatbelt gag) — NO speed signs yet.
            1 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                mono("AW, BUCKLE THIS!", Sb.dim, 11f, FontWeight.Bold, TextAlign.Center, 1.5f)
                Spacer(Modifier.height(8.dp))
                mono("SEATBELTS: FASTENED   ·   ENGINES: LUDICROUS", Sb.dim, 9f, align = TextAlign.Center, spacing = 1.5f)
            }
            // 2) The command: "Ludicrous speed, GO!".
            2 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                mono("▲  LUDICROUS  SPEED  ▲", Sb.amber, 18f, FontWeight.Bold, TextAlign.Center, 3f)
                Spacer(Modifier.height(14.dp))
                mono("G O !", Sb.redHot, 40f, FontWeight.Bold, TextAlign.Center, 8f)
            }
            // 3) After GO the ship climbs — the speed sign pops up ONE tier at a time, in order.
            3 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SpeedSignPanel("LIGHT  SPEED")
                Spacer(Modifier.height(16.dp))
                mono("MY BRAINS ARE GOING INTO MY FEET!", Sb.amber.copy(alpha = 0.85f), 11f, FontWeight.Bold, TextAlign.Center, 1f)
            }
            4 -> SpeedSignPanel("RIDICULOUS  SPEED")
            5 -> SpeedSignPanel("LUDICROUS  SPEED", Modifier.graphicsLayer { alpha = blink.value })
            else -> {}
        }
    }
}

/** A green phosphor dot-matrix speed-sign panel (LIGHT/RIDICULOUS/LUDICROUS), scanline-textured. */
@Composable
private fun SpeedSignPanel(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Color(0xFF03100A), RoundedCornerShape(6.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .drawWithContent {
                drawContent()
                var y = 0f
                val gap = 3.dp.toPx()
                while (y < size.height) {
                    drawRect(Color.Black.copy(alpha = 0.3f), Offset(0f, y), Size(size.width, 1.4.dp.toPx()))
                    y += gap
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = Color(0xFF33FF66), fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold,
            fontSize = 22.sp, letterSpacing = 5.sp, textAlign = TextAlign.Center,
        )
    }
}

/**
 * The translucent crossing tartan WASH that actually reads as "plaid" — two diagonal colour families
 * (±25°) in muted tartan tones (amber/red/teal/cream) laid over the scene; crossing the orthogonal
 * tunnel grid is what makes the eye read tartan. `alpha` (0..1) fades the whole wash in.
 */
private fun DrawScope.drawTartanWash(alpha: Float) {
    if (alpha <= 0.01f) return
    val cols = listOf(Color(0xFFE0A33A), Color(0xFFB23A2E), Color(0xFF2C8C8C), Color(0xFFE9DCC2))
    val spacing = 30.dp.toPx()
    val diag = sqrt(size.width * size.width + size.height * size.height)
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (fam in 0..1) {
        val rad = (if (fam == 0) 25f else -25f) * (PI.toFloat() / 180f)
        val dx = cos(rad)
        val dy = sin(rad)
        val px = -dy // perpendicular (the spacing direction)
        val py = dx
        val n = (diag / spacing).toInt() + 1
        var i = -n
        while (i <= n) {
            val off = i * spacing
            val mx = cx + px * off
            val my = cy + py * off
            val col = cols[((i % cols.size) + cols.size) % cols.size]
            val wdt = if (i % 4 == 0) 7.dp.toPx() else 3.dp.toPx()
            drawLine(
                col.copy(alpha = 0.38f * alpha),
                Offset(mx - dx * diag, my - dy * diag), Offset(mx + dx * diag, my + dy * diag), wdt,
            )
            i++
        }
    }
}

/** A speaker-attributed dialogue card, centred — used for the set-up and aftermath exchanges. */
@Composable
private fun DialogueCard(speaker: String, speakerColor: Color, line: String) {
    Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            mono(speaker, speakerColor, 10f, FontWeight.Bold, TextAlign.Center, 3f)
            Spacer(Modifier.height(10.dp))
            mono(line, Color.White, 16f, FontWeight.Bold, TextAlign.Center, 1.5f)
        }
    }
}

/** Beat 1 — the bridge set-up exchange before "GO!". */
@Composable
private fun SetupDialogue(line: Int) {
    when (line) {
        0 -> DialogueCard("COL. SANDURZ", Sb.amber, "PREPARE SHIP FOR LIGHT SPEED!")
        1 -> DialogueCard("DARK HELMET", Sb.redHot, "NO — LIGHT SPEED IS TOO SLOW.")
        2 -> DialogueCard("DARK HELMET", Sb.redHot, "WE'LL HAVE TO GO RIGHT TO…\nLUDICROUS SPEED!")
        3 -> DialogueCard("COL. SANDURZ", Sb.amber, "LUDICROUS SPEED?! THIS SHIP\nCAN'T TAKE IT!")
        4 -> DialogueCard("DARK HELMET", Sb.redHot, "WHAT'S THE MATTER, SANDURZ?\nCHICKEN?")
        else -> DialogueCard("COL. SANDURZ", Sb.amber, "SIR, HADN'T YOU BETTER\nBUCKLE UP?")
    }
}

/** Beat 6 — the aftermath, ending on "smoke if you got 'em". */
@Composable
private fun AftermathDialogue(line: Int) {
    when (line) {
        0 -> DialogueCard("COL. SANDURZ", Sb.amber, "ARE YOU ALL RIGHT, SIR?")
        1 -> DialogueCard("DARK HELMET", Sb.redHot, "FINE. HOW'VE YOU BEEN?")
        2 -> DialogueCard("COL. SANDURZ", Sb.amber, "IT'S A GOOD THING YOU WERE\nWEARING THAT HELMET.")
        3 -> DialogueCard("COL. SANDURZ", Sb.amber, "WE'RE STOPPED, SIR.")
        4 -> DialogueCard("DARK HELMET", Sb.redHot, "GOOD. LET'S TAKE A\nFIVE-MINUTE BREAK.")
        else -> DialogueCard("DARK HELMET", Sb.redHot, "SMOKE IF YOU GOT 'EM.")
    }
}

/** Beat 4 — the Eagle 5 crew reacting as Spaceball One blasts past in plaid (caption sits low). */
@Composable
private fun FlybyDialogue(line: Int) {
    Box(Modifier.fillMaxSize().padding(bottom = 72.dp), contentAlignment = Alignment.BottomCenter) {
        val speaker: String
        val speakerColor: Color
        val text: String
        when (line) {
            0 -> { speaker = "BARF · EAGLE 5"; speakerColor = Sb.green; text = "WHAT THE HELL WAS THAT?!" }
            1 -> { speaker = "LONE STARR"; speakerColor = Sb.neonHot; text = "SPACEBALL ONE." }
            else -> { speaker = "BARF · EAGLE 5"; speakerColor = Sb.green; text = "THEY'VE GONE TO PLAID!" }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            mono(speaker, speakerColor, 10f, FontWeight.Bold, TextAlign.Center, 3f)
            Spacer(Modifier.height(8.dp))
            mono(text, Color.White, if (line == 2) 20f else 16f, FontWeight.Bold, TextAlign.Center, 1.5f)
        }
    }
}

/**
 * Beat 4 — the "they've gone to plaid" view: Spaceball One streaks past (three circular engine glows)
 * over a starfield, with the translucent crossing tartan WASH (the plaid) composited over the frame.
 */
private fun DrawScope.drawFlyby(p: Float, stars: List<FloatArray>) {
    drawRect(Color.Black)
    stars.forEach { s ->
        val x = ((s[0] * 0.5f + 0.5f) * size.width + p * 120f * (0.3f + s[3])) % size.width
        val y = (s[1] * 0.5f + 0.5f) * size.height
        drawCircle(Color.White.copy(alpha = 0.18f + 0.4f * s[3]), 1f + s[3], Offset(x, y))
    }
    // Spaceball One streaking left→right, three circular engine glows flaring at the tail.
    val shipY = size.height * 0.5f
    val shipLen = size.minDimension * 0.38f
    val shipH = size.minDimension * 0.05f
    val noseX = -shipLen + (size.width + shipLen * 2f) * p
    val tailX = noseX - shipLen
    if (noseX > -shipLen * 0.5f && tailX < size.width) {
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFFB8BDC4), Color(0xFF6A6F77), Color(0xFF2C3036))),
            topLeft = Offset(tailX, shipY - shipH / 2f), size = Size(shipLen, shipH),
            cornerRadius = CornerRadius(shipH / 2f, shipH / 2f),
        )
        for (k in 0..2) {
            val gy = shipY + (k - 1) * shipH * 0.55f
            val gR = shipH * 0.5f
            drawCircle(
                Brush.radialGradient(listOf(Color.White, Color(0xFFFFC04D), Color.Transparent), Offset(tailX, gy), gR),
                gR, Offset(tailX, gy), blendMode = BlendMode.Plus,
            )
        }
    }
    // THE PLAID — the translucent crossing tartan wash, fading in as the ship passes.
    drawTartanWash((p * 2.5f).coerceIn(0f, 1f))
}

/**
 * The movie-exact brake lever: "EMERGENCY STOP — NEVER USE", hazard-striped, pulsing. Two-stage —
 * `stage` 0 shows the dire label; pressing once warns (Sandurz) and flips to OVERRIDE / PRESS AGAIN.
 */
@Composable
private fun EmergencyStopButton(pulse: State<Float>, stage: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .width(280.dp)
            .height(84.dp)
            .clickable { onClick() }
            .drawBehind {
                val r = 16.dp.toPx()
                drawRoundRect(
                    Brush.verticalGradient(listOf(Sb.redHot, Sb.red, Color(0xFF6E0A16))),
                    cornerRadius = CornerRadius(r, r),
                )
                val bw = 5.dp.toPx()
                drawRoundRect(Lud.RAIL_GOLD.copy(alpha = 0.9f), cornerRadius = CornerRadius(r, r), style = Stroke(bw))
                // Black hazard notches along the top & bottom edges (caution-tape look).
                val step = 15.dp.toPx(); val skew = 7.dp.toPx()
                var x = step
                while (x < size.width - step * 0.5f) {
                    drawLine(Color.Black.copy(alpha = 0.85f), Offset(x, 1f), Offset(x - skew, bw * 1.7f), 3.dp.toPx())
                    drawLine(Color.Black.copy(alpha = 0.85f), Offset(x, size.height - 1f), Offset(x - skew, size.height - bw * 1.7f), 3.dp.toPx())
                    x += step
                }
                // Pulsing alert glow (read in the draw phase — no recomposition).
                drawRoundRect(Sb.redHot.copy(alpha = 0.25f + 0.55f * pulse.value), cornerRadius = CornerRadius(r, r), style = Stroke(2.5.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (stage == 0) {
                mono("⚠  EMERGENCY STOP  ⚠", Color.White, 15f, FontWeight.Bold, TextAlign.Center, 2f)
                Spacer(Modifier.height(2.dp))
                mono("NEVER USE", Color.White.copy(alpha = 0.85f), 8f, FontWeight.Bold, TextAlign.Center, 4f)
            } else {
                mono("OVERRIDE", Color.White, 16f, FontWeight.Bold, TextAlign.Center, 3f)
                Spacer(Modifier.height(2.dp))
                mono("PRESS AGAIN TO STOP", Color.White.copy(alpha = 0.9f), 8f, FontWeight.Bold, TextAlign.Center, 2f)
            }
        }
    }
}

/** A point on the tunnel-mouth square for rail `f` (0..1) along wall `sIdx`. */
private fun railEnd(sIdx: Int, f: Float, h: Float, p: Offset): Offset = when (sIdx) {
    0 -> Offset(p.x - h + 2 * h * f, p.y - h)
    1 -> Offset(p.x + h, p.y - h + 2 * h * f)
    2 -> Offset(p.x + h - 2 * h * f, p.y + h)
    else -> Offset(p.x - h, p.y + h - 2 * h * f)
}

/**
 * The plaid shaft (Act II core): a dark-red tunnel whose walls are a woven orange/gold grid in
 * perspective, converging on the centre. Two line families build it — STATIC radial rails (the wall
 * columns; the four corners are the box edges, gold + thickest) and MOVING nested squares (rings) that
 * grow geometrically out of the centre to read as forward flight. Every 4th line is a gold "sett"
 * accent + a thin GRID_FINE cross-hatch sits between the bold lines — that heavy/light banding is what
 * makes the eye read tartan plaid rather than graph paper. `intensity` (0..1) fades the whole shaft.
 */
private fun DrawScope.drawShaft(flow: Float, intensity: Float, pivot: Offset) {
    drawRect(Color.Black)
    if (intensity <= 0.01f) return
    val diag = sqrt(size.width * size.width + size.height * size.height) / 2f
    val outerHalf = size.maxDimension * 0.62f
    // Dark-red ground, deepest at the vanishing point, brighter toward the rim (depth shading).
    drawRect(
        Brush.radialGradient(
            listOf(Lud.WALL_RED_DEEP.copy(alpha = intensity), Lud.WALL_RED.copy(alpha = 0.9f * intensity)),
            center = pivot, radius = diag,
        ),
    )
    // Rails — static perspective columns. 4 gold corners (thickest) + intermediates, every 4th gold.
    val perSide = 8
    for (sIdx in 0 until 4) {
        for (i in 0..perSide) {
            val end = railEnd(sIdx, i.toFloat() / perSide, outerHalf, pivot)
            val corner = i == 0
            val sett = i % 4 == 0
            val col = if (corner || sett) Lud.RAIL_GOLD else Lud.GRID_ORANGE
            val w = if (corner) 3.6f else if (sett) 2.6f else 1.6f
            drawLine(col.copy(alpha = (0.5f * intensity).coerceIn(0f, 1f)), pivot, end, w, cap = StrokeCap.Round)
            if (i < perSide) {
                val fe = railEnd(sIdx, (i + 0.5f) / perSide, outerHalf, pivot)
                drawLine(Lud.GRID_FINE.copy(alpha = 0.28f * intensity), pivot, fe, 1f, cap = StrokeCap.Round)
            }
        }
    }
    // Rings — nested squares scrolling outward (geometric spacing → slow far, fast near).
    var k = 0
    while (true) {
        val half = Lud.SEED_PX * Lud.RING_RATIO.pow(k + flow)
        if (half > outerHalf) break
        k++
        if (half < 14f) continue
        val near = 1f - half / outerHalf
        val sett = k % 4 == 0
        val col = if (sett) Lud.RAIL_GOLD else Lud.GRID_ORANGE
        val w = if (sett) 2.8f else 1.8f
        drawRect(
            col.copy(alpha = (intensity * (0.45f + 0.45f * near)).coerceIn(0f, 1f)),
            topLeft = Offset(pivot.x - half, pivot.y - half), size = Size(half * 2, half * 2),
            style = Stroke(w),
        )
        val fh = half * Lud.RING_RATIO.pow(0.5f)
        if (fh < outerHalf) drawRect(
            Lud.GRID_FINE.copy(alpha = 0.24f * intensity),
            topLeft = Offset(pivot.x - fh, pivot.y - fh), size = Size(fh * 2, fh * 2), style = Stroke(1f),
        )
    }
}

/** The vanishing-point starburst — additive white→gold bloom + 8 sharp rays, breathing. */
private fun DrawScope.drawBurst(pivot: Offset, scale: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    val baseR = (size.minDimension * 0.07f * scale).coerceAtLeast(1f)
    val r = baseR * 2.6f
    drawCircle(
        Brush.radialGradient(
            listOf(
                Lud.BURST_CORE.copy(alpha = alpha), Lud.BURST_MID.copy(alpha = alpha),
                Lud.BURST_GLOW.copy(alpha = 0.7f * alpha), Lud.BURST_EDGE.copy(alpha = 0.3f * alpha),
                Color.Transparent,
            ),
            pivot, r,
        ),
        r, pivot, blendMode = BlendMode.Plus,
    )
    val len = size.minDimension * 0.24f * scale
    for (j in 0 until 8) {
        val ang = (PI * j / 4).toFloat()
        val tip = Offset(pivot.x + cos(ang) * len, pivot.y + sin(ang) * len)
        drawLine(
            Lud.BURST_RAY.copy(alpha = (0.8f * alpha).coerceIn(0f, 1f)), pivot, tip,
            if (j % 2 == 0) 2.5f else 1.5f, cap = StrokeCap.Round, blendMode = BlendMode.Plus,
        )
    }
    drawCircle(Lud.BURST_CORE.copy(alpha = alpha.coerceIn(0f, 1f)), baseR * 0.5f, pivot, blendMode = BlendMode.Plus)
}

/** The multicolour "plaid" rain — streaks born near the centre, stretching as they fly outward. */
private fun DrawScope.cruiseStreaks(stars: List<FloatArray>, scroll: Float, c: Offset, maxR: Float) {
    stars.forEach { s ->
        val dir = Offset(s[0], s[1])
        val phaseR = (scroll + s[2]) % 1f
        val r0 = phaseR * maxR * (0.6f + s[2])
        val len = (40f + 240f * phaseR) * (0.4f + s[2])
        drawLine(
            streakColor(s).copy(alpha = 0.85f * (1f - phaseR * 0.4f)),
            c + dir * r0, c + dir * (r0 + len), 2f + s[3] * 1.5f, cap = StrokeCap.Round, blendMode = BlendMode.Plus,
        )
    }
}
