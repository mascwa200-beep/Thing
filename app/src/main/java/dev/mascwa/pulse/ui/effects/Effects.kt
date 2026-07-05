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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
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
    // Scanlines as a single GPU-tiled gradient (drawn once per frame, not ~1000
    // individual rects). The brush is size-independent, so build it once.
    val lineBrush = remember {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.14f),
            0.34f to Color.Black.copy(alpha = 0.14f),
            0.35f to Color.Transparent,
            1f to Color.Transparent,
            startY = 0f, endY = 3f,
            tileMode = TileMode.Repeated,
        )
    }
    Box(modifier.fillMaxSize().zIndex(50f)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(lineBrush)
            // Moving accent sweep band (one op).
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
 * NIGHTWIRE brand text with a working **chromatic aberration**: two colour-split copies (a warm
 * channel pushed right, a cool channel pushed left) ride constantly behind the text with a gentle
 * breathing offset and an occasional stronger burst — the misregistered-RGB look, not an occasional
 * flicker. Off renders plain. Cheap: two extra text layers + two animated floats.
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
    val transition = rememberInfiniteTransition(label = "aberration")
    val wob by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Reverse),
        label = "wob",
    )
    val burst by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "burst",
    )
    // Compute the offset inside each graphicsLayer lambda so wob/burst are read in the layer phase, not
    // composition — otherwise this recomposes every animation frame (it's in the always-visible wordmark).
    Box {
        Text(
            text, style = style.copy(fontWeight = FontWeight.Bold), color = magenta.copy(alpha = 0.55f),
            modifier = Modifier.graphicsLayer { translationX = 1.1f + 0.9f * wob + if (burst > 0.94f) 3.4f else 0f },
        )
        Text(
            text, style = style.copy(fontWeight = FontWeight.Bold), color = accent.copy(alpha = 0.55f),
            modifier = Modifier.graphicsLayer { translationX = -(1.1f + 0.9f * wob + if (burst > 0.94f) 3.4f else 0f) },
        )
        Text(text, style = style, color = baseColor)
    }
}

/**
 * Full-screen chromatic-aberration fringe — subtle red/cyan colour separation that intensifies
 * toward the screen edges (as real lenses do), gently breathing. Drawn above content, never blocks
 * touch. Cheap: four gradient rects per frame, no retained buffers (well under the FX RAM budget).
 */
@Composable
fun ChromaticAberrationOverlay(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val transition = rememberInfiniteTransition(label = "ca")
    val breathe by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val red = Color(0xFFFF2A4D)
    val cyan = Color(0xFF22E0FF)
    Box(modifier.fillMaxSize().zIndex(40f)) {
        Canvas(Modifier.fillMaxSize()) {
            val amp = 0.05f + 0.045f * breathe
            val ex = size.width * 0.085f
            val ey = size.height * 0.06f
            drawRect(
                Brush.horizontalGradient(0f to red.copy(alpha = amp), 1f to Color.Transparent, startX = 0f, endX = ex),
                topLeft = Offset(0f, 0f), size = Size(ex, size.height),
            )
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 1f to cyan.copy(alpha = amp), startX = size.width - ex, endX = size.width),
                topLeft = Offset(size.width - ex, 0f), size = Size(ex, size.height),
            )
            drawRect(
                Brush.verticalGradient(0f to red.copy(alpha = amp * 0.6f), 1f to Color.Transparent, startY = 0f, endY = ey),
                topLeft = Offset(0f, 0f), size = Size(size.width, ey),
            )
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to cyan.copy(alpha = amp * 0.6f), startY = size.height - ey, endY = size.height),
                topLeft = Offset(0f, size.height - ey), size = Size(size.width, ey),
            )
        }
    }
}
