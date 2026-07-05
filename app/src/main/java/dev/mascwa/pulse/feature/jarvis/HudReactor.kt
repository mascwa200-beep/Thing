package dev.mascwa.pulse.feature.jarvis

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

// Cardinal node angles (deg) — hoisted so the per-frame reactor draw doesn't allocate a boxed list each frame.
private val CARDINALS = intArrayOf(0, 90, 180, 270)

/**
 * Stark-style arc-reactor HUD ring — the signature J.A.R.V.I.S. "system
 * diagnostics" look: concentric counter-rotating arc rings, a fine tick ring
 * and a pulsing tri-coil core.
 *
 * Deliberately frugal with RAM: the *only* animation state is three [Float]s
 * (two ring rotations + a core pulse ≈ 12 bytes) held by one
 * [rememberInfiniteTransition]. Everything is drawn procedurally on the GPU each
 * frame — no retained bitmaps, particle arrays or offscreen buffers — so the
 * whole effect sits far under a 1 kB animation budget (the project's
 * "no RAM-heavy FX" rule).
 *
 * [active] simply slows the rotation/pulse when J.A.R.V.I.S. is idle, so the HUD
 * reads as "spun up" while it's thinking and "ticking over" at rest.
 */
@Composable
fun HudReactor(
    color: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val t = rememberInfiniteTransition(label = "reactor")
    val spin by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(if (active) 9000 else 24000, easing = LinearEasing), RepeatMode.Restart,
        ),
        label = "spin",
    )
    val spin2 by t.animateFloat(
        initialValue = 0f, targetValue = -360f,
        animationSpec = infiniteRepeatable(
            tween(if (active) 13000 else 30000, easing = LinearEasing), RepeatMode.Restart,
        ),
        label = "spin2",
    )
    val pulse by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (active) 1400 else 3000, easing = FastOutSlowInEasing), RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)
        val rMax = size.minDimension / 2f * 0.94f

        // Fine tick ring around the rim (static graduations, longer every 30°).
        val tick = color.copy(alpha = 0.22f)
        var deg = 0
        while (deg < 360) {
            val a = Math.toRadians(deg.toDouble())
            val inner = if (deg % 30 == 0) rMax * 0.88f else rMax * 0.95f
            drawLine(
                tick,
                Offset(cx + (inner * sin(a)).toFloat(), cy - (inner * cos(a)).toFloat()),
                Offset(cx + (rMax * sin(a)).toFloat(), cy - (rMax * cos(a)).toFloat()),
                1f,
            )
            deg += 6
        }

        // Outer rotating arc ring — three long arcs with gaps.
        arcRing(center, rMax * 0.84f, color.copy(alpha = 0.75f), 2.6f, spin, segments = 3, fill = 0.66f)
        // Counter-rotating accent segments + a thin guide circle.
        drawCircle(color.copy(alpha = 0.22f), rMax * 0.66f, center, style = Stroke(1f))
        arcRing(center, rMax * 0.66f, accent.copy(alpha = 0.85f), 2.2f, spin2, segments = 6, fill = 0.4f)
        // Inner rotating ring (faster).
        arcRing(center, rMax * 0.46f, color.copy(alpha = 0.55f), 2f, spin * 1.6f, segments = 4, fill = 0.5f)

        // Cardinal bright nodes on the outer ring.
        CARDINALS.forEach { d ->
            val a = Math.toRadians(d.toDouble())
            drawCircle(
                accent, 2.4f,
                Offset(cx + (rMax * 0.84f * sin(a)).toFloat(), cy - (rMax * 0.84f * cos(a)).toFloat()),
            )
        }

        // Arc-reactor core — layered glow + a pulsing centre + the tri-coil hint.
        val coreR = rMax * 0.26f
        drawCircle(color.copy(alpha = 0.10f), coreR * 1.5f, center)
        drawCircle(color.copy(alpha = 0.18f), coreR, center)
        drawCircle(accent.copy(alpha = 0.25f + 0.35f * pulse), coreR * (0.55f + 0.12f * pulse), center)
        drawCircle(color.copy(alpha = 0.9f), coreR * 0.18f, center)
        val triR = coreR * 0.7f
        val tri = Path().apply {
            for (i in 0..2) {
                val a = Math.toRadians(90.0 + i * 120.0)
                val x = cx + (triR * cos(a)).toFloat()
                val y = cy - (triR * sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(tri, color.copy(alpha = 0.5f), style = Stroke(1.4f))
    }
}

/** Draws [segments] evenly-spaced arcs of length ([fill]·step) at [radius], rotated by [rotation]°. */
private fun DrawScope.arcRing(
    center: Offset,
    radius: Float,
    color: Color,
    width: Float,
    rotation: Float,
    segments: Int,
    fill: Float,
) {
    val step = 360f / segments
    val sweep = step * fill
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    for (i in 0 until segments) {
        drawArc(
            color = color,
            startAngle = rotation + i * step,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width),
        )
    }
}
