package dev.mascwa.pulse.ui.effects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** One drifting mote of the field — fixed parameters only; its on-screen position is
 *  computed analytically from the animation clock in the draw pass, so there is no
 *  per-frame allocation and the whole field costs ~a few dozen kB (the 2 MB cap is a
 *  ceiling we stay far under). */
private data class Mote(
    val angle: Float,
    val angSpeed: Float,
    val radius: Float,
    val radDrift: Float,
    val size: Float,
    val twinkle: Float,
)

private fun hex(r: Random, digits: Int): String =
    buildString { repeat(digits) { append("0123456789ABCDEF"[r.nextInt(16)]) } }

/**
 * The boot log is **procedurally generated, unique on every launch** — not a fixed list read from a
 * file. A fresh `Random` (seeded from the wall clock) fills secure power-on diagnostics with new hex
 * addresses, module names, seeds, sizes and pass/fail tokens, then a random handful of middle lines is
 * shuffled in, so the terminal "decrypts" a different roll each cold start. POST opens it; the operator
 * handoff always closes it.
 */
private fun generateBootLog(): List<String> {
    val r = Random(System.nanoTime() xor System.currentTimeMillis())
    val modules = listOf("kern", "vault", "telemetry", "geo", "signals", "crypto", "sensorhub", "netd",
        "rtc", "dma", "mmu", "sched", "gnss", "baseband", "watchdog", "keystore")
    val sensors = listOf("inertial", "magnetometer", "barometric", "gyroscopic", "photometric", "proximity")
    val subsystems = listOf("geospatial", "telemetry", "signals-intel", "navigation", "cryptographic", "diagnostics")
    val volumes = listOf("vault", "core", "cache", "secure", "ramdisk", "enclave")
    val ifaces = listOf("eth0", "wlan0", "rmnet0", "mesh0")
    val ok = listOf("OK", "PASS", "READY", "NOMINAL", "ONLINE")

    val head = mutableListOf(
        "> POWER-ON SELF TEST .............. ${ok.random(r)}",
        "boot rom 0x${hex(r, 8)} · stage-2 loader 0x${hex(r, 4)}",
        "secure boot chain verified — ${2 + r.nextInt(4)} signatures valid",
        "mounting encrypted volume /${volumes.random(r)} ... ${ok.random(r)}",
        "crypto online (AES-256-GCM · RNG seeded 0x${hex(r, 8)})",
    )
    val mids = mutableListOf<String>()
    repeat(3 + r.nextInt(3)) { mids += "init ${modules.random(r)} @ 0x${hex(r, 8)} ... ${ok.random(r)}" }
    mids += "calibrating ${sensors.random(r)} array (${256 + r.nextInt(2048)} samples)"
    mids += "runtime heap allocated ${8192 + r.nextInt(8192)} MB"
    mids += "link ${ifaces.random(r)} up — ${50 + r.nextInt(950)} Mbit/s"
    mids += "${1 shl (10 + r.nextInt(6))} sectors verified · CRC 0x${hex(r, 8)}"
    mids += "loading ${subsystems.random(r)} subsystem ... ${ok.random(r)}"
    mids.shuffle(r)

    return head + mids.take(4 + r.nextInt(3)) +
        listOf(
            "system integrity self-check ....... ${ok.random(r)}",
            "all subsystems nominal — operator handoff",
        )
}

// Deliberately unhurried (~half the old pace) so the message can actually be read.
private const val BOOT_MS = 8800
private const val MOTE_COUNT = 800
private const val TAU = 6.2831855f

/**
 * Cinematic secure-boot sequence shown once per launch. A swirling field of motes is drawn
 * inward into a waking eye / seal while an escalating "clearance" arc fills and the power-on
 * diagnostic log decrypts in — the contractor terminal bringing its subsystems up, then naming
 * itself. Deliberately **unskippable** (no tap handler), then fades into the app. All-procedural
 * vector + analytic motes — kilobytes of state, well under the 2 MB budget.
 */
@Composable
fun BootScreen(onFinished: () -> Unit) {
    val c = Pulse.colors
    var visible by remember { mutableStateOf(true) }
    val progress = remember { Animatable(0f) }

    // Continuous motion clocks, independent of the one-shot boot timeline.
    val loop = rememberInfiniteTransition(label = "boot")
    val swirl by loop.animateFloat(
        0f, 1f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart), label = "swirl",
    )
    val pulse by loop.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )

    // Fresh, unique boot log generated once for this launch.
    val bootLines = remember { generateBootLog() }

    val motes = remember {
        val r = Random(20260619)
        List(MOTE_COUNT) {
            Mote(
                angle = r.nextFloat() * TAU,
                angSpeed = (0.15f + r.nextFloat() * 0.7f) * if (r.nextBoolean()) 1f else -1f,
                radius = r.nextFloat() * 1.18f,
                radDrift = 0.04f + r.nextFloat() * 0.20f,
                size = 0.7f + r.nextFloat() * 1.9f,
                twinkle = r.nextFloat() * TAU,
            )
        }
    }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(BOOT_MS, easing = LinearEasing))
        delay(500)
        visible = false
    }
    LaunchedEffect(visible) {
        if (!visible) {
            delay(640)
            onFinished()
        }
    }

    AnimatedVisibility(visible = visible, enter = EnterTransition.None, exit = fadeOut(tween(580))) {
        BootContent(c, progress.value, swirl, pulse, motes, bootLines)
    }
}

@Composable
private fun BootContent(c: NightwirePalette, p: Float, swirl: Float, pulse: Float, motes: List<Mote>, bootLines: List<String>) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) { drawOmenField(c, p, swirl, pulse, motes) }

        // Force the decrypt effect regardless of the user's reduced-motion setting — the
        // cold-open is a deliberate, one-time cinematic.
        CompositionLocalProvider(LocalGlitchEnabled provides true) {
            val shown = (((p - 0.05f) / 0.70f) * bootLines.size).toInt().coerceIn(0, bootLines.size)
            val logAlpha = (1f - (p - 0.80f) / 0.10f).coerceIn(0f, 1f)
            // The decrypt log lives in its OWN maintained box — a clipped band in the LOWER portion only,
            // strictly below the eye/seal (now in the upper half) and lifted off the bottom. Text appears
            // ONLY inside this band: anything past its top edge is clipped away, so it can never overlap the
            // eye above it, and it never reaches the brand/footer below. No visual overlap, ever.
            Box(Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp, bottom = 60.dp)) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .fillMaxHeight(0.42f)
                        .clipToBounds(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    bootLines.take(shown).forEachIndexed { i, line ->
                        val base = bootLineColor(c, i, bootLines.size)
                        DecryptText(
                            line, JetBrainsMono, 12.sp, base.copy(alpha = base.alpha * logAlpha),
                            Modifier.fillMaxWidth().padding(vertical = 3.dp), durationMs = 640,
                        )
                    }
                }
            }

            // The reveal — the contractor naming itself, over the open eye.
            val brand = ((p - 0.82f) / 0.16f).coerceIn(0f, 1f)
            if (brand > 0f) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row {
                        Text("ARGUS", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 2.sp, color = c.ink.copy(alpha = brand))
                        Text(" DYNAMICS", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 2.sp, color = c.accent.copy(alpha = brand))
                    }
                    Text(
                        "ADVANCED SIGNALS DIVISION",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 3.sp,
                        color = c.sky.copy(alpha = 0.85f * brand),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

/** Calm, readable boot-log colouring: the header line and the final handoff stand out; the rest is
 *  even mono text, the way a real diagnostic roll reads. */
private fun bootLineColor(c: NightwirePalette, i: Int, total: Int): Color = when (i) {
    0 -> c.sky.copy(alpha = 0.9f)
    total - 1 -> c.accent
    else -> c.ink2.copy(alpha = 0.92f)
}

private fun DrawScope.drawOmenField(c: NightwirePalette, p: Float, swirl: Float, pulse: Float, motes: List<Mote>) {
    val w = size.width
    val h = size.height
    // The eye/seal sits in the UPPER portion so the boot-log band below it never overlaps it.
    val center = Offset(w / 2f, h * 0.35f)
    val baseR = minOf(w, h) / 2f

    // Cold radial vignette.
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0xFF04181E), Color(0xFF010A0D), Color.Black),
            center = center, radius = maxOf(w, h) * 0.78f,
        ),
    )

    // Swirling mote field — positions are analytic (no retained per-frame state).
    val fieldAlpha = 0.10f + 0.62f * p
    motes.forEach { m ->
        val a = m.angle + m.angSpeed * swirl * TAU
        var rad = m.radius - m.radDrift * swirl
        rad = ((rad % 1.18f) + 1.18f) % 1.18f
        val rr = (0.06f + rad) * baseR * 1.05f
        val x = center.x + rr * cos(a)
        val y = center.y + rr * sin(a)
        val tw = 0.45f + 0.55f * sin(swirl * 12.566f + m.twinkle)
        val near = 1f - rad / 1.18f
        val alpha = (fieldAlpha * tw * (0.35f + 0.65f * near)).coerceIn(0f, 1f)
        if (alpha > 0.02f) drawCircle(c.sky.copy(alpha = alpha), m.size * (0.7f + 0.5f * near), Offset(x, y))
    }

    val sealR = baseR * 0.34f

    // Assembling dashed rings, counter-rotating.
    if (p > 0.10f) {
        val ringA = ((p - 0.10f) / 0.20f).coerceIn(0f, 1f)
        rotate(swirl * 360f, center) {
            drawCircle(
                c.sky.copy(alpha = 0.5f * ringA), sealR * 1.5f, center,
                style = Stroke(1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 12f))),
            )
        }
        rotate(-swirl * 220f, center) {
            drawCircle(
                c.sky.copy(alpha = 0.32f * ringA), sealR * 1.28f, center,
                style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 9f))),
            )
        }
    }

    // Clearance arc — escalates with the boot timeline.
    val arcBox = Offset(center.x - sealR * 1.5f, center.y - sealR * 1.5f)
    val arcSize = Size(sealR * 3f, sealR * 3f)
    drawArc(c.sky.copy(alpha = 0.20f), -90f, 360f, false, arcBox, arcSize, style = Stroke(2f))
    drawArc(c.accent.copy(alpha = 0.9f), -90f, 360f * p, false, arcBox, arcSize, style = Stroke(2.6f))

    // The waking eye / aperture — opens through the middle of the sequence.
    val open = ((p - 0.32f) / 0.30f).coerceIn(0f, 1f)
    val eyeW = sealR * 1.15f
    val eyeH = sealR * (0.10f + 0.88f * open)
    drawOval(
        c.sky.copy(alpha = 0.08f + 0.22f * open),
        topLeft = Offset(center.x - eyeW, center.y - eyeH), size = Size(eyeW * 2f, eyeH * 2f),
    )
    drawOval(
        c.sky.copy(alpha = 0.6f),
        topLeft = Offset(center.x - eyeW, center.y - eyeH), size = Size(eyeW * 2f, eyeH * 2f),
        style = Stroke(1.6f),
    )

    if (open > 0.05f) {
        val irisR = eyeH.coerceAtMost(sealR * 0.5f)
        drawCircle(c.sky.copy(alpha = 0.5f * open), irisR, center, style = Stroke(1.4f))
        val lines = 16
        for (i in 0 until lines) {
            val ang = i * (TAU / lines) + swirl * 1.5f
            drawLine(
                c.sky.copy(alpha = 0.25f * open),
                Offset(center.x + irisR * 0.35f * cos(ang), center.y + irisR * 0.35f * sin(ang)),
                Offset(center.x + irisR * 0.92f * cos(ang), center.y + irisR * 0.92f * sin(ang)),
                1f,
            )
        }
        val pupil = irisR * (0.34f + 0.06f * pulse)
        drawCircle(Color.Black, pupil * 1.25f, center)
        drawCircle(c.accent.copy(alpha = 0.85f), pupil, center, style = Stroke(2f))
        drawCircle(c.accent.copy(alpha = 0.30f + 0.40f * pulse), pupil * 0.5f, center)
    }

    // Core glow, intensifying with progress.
    val glow = sealR * (0.18f + 0.05f * pulse) * (0.4f + 0.6f * p)
    drawCircle(c.sky.copy(alpha = 0.16f), glow * 2.2f, center)
    drawCircle(c.sky.copy(alpha = 0.5f * p), glow, center)

    // CRT scanlines.
    val sc = Color.Black.copy(alpha = 0.18f)
    var y = 0f
    while (y < h) {
        drawLine(sc, Offset(0f, y), Offset(w, y), 1f)
        y += 3f
    }
}
