package dev.mascwa.pulse.ui.effects

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.PI
import kotlin.math.sin

/**
 * THE single "appearance" effect — a non-invasive **J.A.R.V.I.S.-is-watching** presence drawn over the
 * whole app (above content, never intercepts touch). It is a quiet ambient HUD: faint corner brackets
 * framing the screen, a slow sensor sweep, and a subtle reticle "gaze" that drifts to new positions and
 * occasionally blinks — the feeling that something attentive is always observing, wherever you go.
 *
 * Deliberately cheap and within the ≤1 MB FX budget: **four animated Floats, params-only**, every shape
 * computed in the draw pass from the current screen size — no bitmaps, no retained buffers, low alpha.
 * Tracks [Pulse.colors] accent so it sits inside the theme.
 */
@Composable
fun JarvisPresenceOverlay(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val accent = Pulse.colors.accent
    val t = rememberInfiniteTransition(label = "jarvis")
    val breathe by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse), label = "breathe",
    )
    val sweep by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    val drift by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(17000, easing = LinearEasing), RepeatMode.Restart), label = "drift",
    )
    val blink by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Restart), label = "blink",
    )

    Box(modifier.fillMaxSize().zIndex(45f)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val a = 0.06f + 0.05f * breathe            // breathing master alpha (kept low = non-invasive)
            val bc = accent.copy(alpha = a)

            // --- corner brackets: the screen is "framed" / targeted ---
            val len = minOf(w, h) * 0.05f
            val m = 14.dp.toPx()
            val sw = 1.4.dp.toPx()
            drawLine(bc, Offset(m, m), Offset(m + len, m), sw); drawLine(bc, Offset(m, m), Offset(m, m + len), sw)
            drawLine(bc, Offset(w - m, m), Offset(w - m - len, m), sw); drawLine(bc, Offset(w - m, m), Offset(w - m, m + len), sw)
            drawLine(bc, Offset(m, h - m), Offset(m + len, h - m), sw); drawLine(bc, Offset(m, h - m), Offset(m, h - m - len), sw)
            drawLine(bc, Offset(w - m, h - m), Offset(w - m - len, h - m), sw); drawLine(bc, Offset(w - m, h - m), Offset(w - m, h - m - len), sw)

            // --- slow sensor sweep ---
            val sy = sweep * h
            drawLine(accent.copy(alpha = 0.05f), Offset(0f, sy), Offset(w, sy), 1f)

            // --- the watching reticle: drifts on a slow Lissajous path, occasionally blinks ---
            val ang = drift * 2f * PI.toFloat()
            val cx = w * (0.5f + 0.30f * sin(ang))
            val cy = h * (0.30f + 0.16f * sin(ang * 1.7f + 0.6f))
            val blinkDip = if (blink > 0.93f) 0.25f else 1f          // brief "blink" alpha dip
            val ea = (0.10f + 0.06f * breathe) * blinkDip
            val r = minOf(w, h) * 0.045f
            // soft iris glow
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = ea * 0.5f), Color.Transparent), center = Offset(cx, cy), radius = r * 1.9f),
                radius = r * 1.9f, center = Offset(cx, cy),
            )
            drawCircle(accent.copy(alpha = ea), radius = r, center = Offset(cx, cy), style = Stroke(width = 1.2.dp.toPx()))
            drawCircle(accent.copy(alpha = ea), radius = r * 0.18f, center = Offset(cx, cy))   // pupil
            // crosshair ticks
            val tick = r * 0.5f
            drawLine(accent.copy(alpha = ea), Offset(cx - r - tick, cy), Offset(cx - r, cy), 1f)
            drawLine(accent.copy(alpha = ea), Offset(cx + r, cy), Offset(cx + r + tick, cy), 1f)
            drawLine(accent.copy(alpha = ea), Offset(cx, cy - r - tick), Offset(cx, cy - r), 1f)
            drawLine(accent.copy(alpha = ea), Offset(cx, cy + r), Offset(cx, cy + r + tick), 1f)
        }
    }
}
