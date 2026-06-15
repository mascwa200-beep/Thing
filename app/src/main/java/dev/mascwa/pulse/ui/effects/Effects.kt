package dev.mascwa.pulse.ui.effects

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.zIndex
import dev.mascwa.pulse.ui.theme.Pulse

/** Whether chromatic glitch FX are enabled (driven by Settings). */
val LocalGlitchEnabled = staticCompositionLocalOf { true }

/**
 * Full-screen CRT scanline + animated sweep overlay. Drawn above content,
 * never intercepts touches (no pointer-input modifier).
 */
@Composable
fun ScanlineOverlay(
    scanlines: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!scanlines) return
    val accent = Pulse.colors.accent
    val transition = rememberInfiniteTransition(label = "sweep")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
        label = "sweepPos",
    )
    Box(modifier.fillMaxSize().zIndex(50f)) {
        Canvas(Modifier.fillMaxSize()) {
            // Static scanlines: a 1px dark line every 3px.
            var y = 0f
            val lineColor = Color.Black.copy(alpha = 0.14f)
            while (y < size.height) {
                drawRect(lineColor, topLeft = Offset(0f, y), size = Size(size.width, 1f))
                y += 3f
            }
            // Moving accent sweep band.
            val bandH = size.height * 0.16f
            val top = -bandH + sweep * (size.height + bandH)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to accent.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                    startY = top, endY = top + bandH,
                ),
                topLeft = Offset(0f, top),
                size = Size(size.width, bandH),
            )
        }
    }
}

/**
 * NIGHTWIRE brand text with an occasional chromatic glitch. When [glitch] is
 * on, two offset coloured copies flash for a beat every few seconds.
 */
@Composable
fun GlitchText(
    text: String,
    style: TextStyle,
    glitch: Boolean,
    baseColor: Color,
    accent: Color,
    magenta: Color,
) {
    if (!glitch) {
        Text(text, style = style, color = baseColor)
        return
    }
    val transition = rememberInfiniteTransition(label = "glitch")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Restart),
        label = "glitchT",
    )
    // Active only in a short window near the end of each cycle.
    val active = t > 0.95f
    val off = if (t > 0.975f) -1.6f else 1.6f
    Box {
        if (active) {
            Text(text, style = style.copy(fontWeight = FontWeight.Bold), color = magenta.copy(alpha = 0.8f),
                modifier = Modifier.graphicsLayer { translationX = off })
            Text(text, style = style.copy(fontWeight = FontWeight.Bold), color = accent.copy(alpha = 0.8f),
                modifier = Modifier.graphicsLayer { translationX = -off })
        }
        Text(text, style = style, color = baseColor)
    }
}
