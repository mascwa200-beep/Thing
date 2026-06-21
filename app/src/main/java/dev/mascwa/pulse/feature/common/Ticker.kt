package dev.mascwa.pulse.feature.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.ceil
import kotlin.math.max

data class TickerItem(val symbol: String, val value: String, val color: Color)

/** Auto-scroll speed multiplier for the [Ticker] (1f = default; higher = faster). Provided app-wide from
 *  the user's setting; defaults to 1f so the ticker works even without a provider. */
val LocalTickerSpeed = androidx.compose.runtime.staticCompositionLocalOf { 1f }

/** Infinite left-scrolling market/news ticker, NIGHTWIRE style. [speedFactor] scales the scroll rate
 *  (1f default; higher = faster). Optional [onClick] (e.g. open Markets). */
@Composable
fun Ticker(
    items: List<TickerItem>,
    modifier: Modifier = Modifier,
    speedFactor: Float = 1f,
    onClick: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val c = Pulse.colors

    // Base sweep duration scales with item count; faster speedFactor = shorter duration. Clamped so the
    // ticker can't freeze (huge duration) or strobe (tiny duration).
    val baseDuration = max(14000, items.size * 1500)
    val durationMs = (baseDuration / speedFactor.coerceIn(0.2f, 4f)).toInt().coerceIn(3000, 120000)
    val transition = rememberInfiniteTransition(label = "ticker")
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMs, easing = LinearEasing), RepeatMode.Restart,
        ),
        label = "tickerProgress",
    )

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(c.carbon)
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Viewport width is known up-front from the constraints (no measure race that could read 0 and
        // leave the bar half-empty). seqPx = measured width of ONE item sequence; re-measured if items change.
        val viewportPx = with(LocalDensity.current) { maxWidth.toPx() }
        var seqPx by remember(items) { mutableIntStateOf(0) }

        // Tile enough identical copies that the row spans the viewport PLUS one whole sequence, so shifting
        // left by exactly one sequence loops seamlessly with NO blank — even when only a few instruments
        // loaded (then it's the same few flowing nonstop, never a half-empty bar).
        val copies = if (seqPx > 0) (ceil(viewportPx / seqPx).toInt() + 2).coerceAtLeast(2) else 2
        Row(Modifier.graphicsLayer { translationX = -progress * seqPx }) {
            TickerSequence(items, c.muted, Modifier.onSizeChanged { if (it.width > 0) seqPx = it.width })
            repeat(copies - 1) { TickerSequence(items, c.muted, Modifier) }
        }
        // Soft edge fades (into the carbon background) so items entering/leaving the viewport melt away
        // instead of being chopped mid-word at the hard clip edge (e.g. "NASDAQ 100" → "ASDAQ 100").
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EDGE_FADE)
                .background(Brush.horizontalGradient(listOf(c.carbon, Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EDGE_FADE)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, c.carbon))),
        )
    }
}

/** Width of the fade-out region at each end of the ticker viewport. */
private val EDGE_FADE = 26.dp

@Composable
private fun TickerSequence(items: List<TickerItem>, symColor: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        items.forEach { item ->
            Row(
                Modifier
                    .padding(horizontal = 14.dp)
                    .height(30.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(item.symbol, fontFamily = JetBrainsMono, fontSize = 11.sp, color = symColor)
                Text(item.value, fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = item.color)
            }
        }
    }
}
