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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.max

data class TickerItem(val symbol: String, val value: String, val color: Color)

/** Infinite left-scrolling market/news ticker, NIGHTWIRE style. Optional [onClick] (e.g. open Markets). */
@Composable
fun Ticker(items: List<TickerItem>, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    if (items.isEmpty()) return
    val c = Pulse.colors
    var singleWidth by remember { mutableIntStateOf(0) }
    var viewportWidth by remember { mutableIntStateOf(0) }

    val transition = rememberInfiniteTransition(label = "ticker")
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(max(14000, items.size * 1500), easing = LinearEasing), RepeatMode.Restart,
        ),
        label = "tickerProgress",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(c.carbon)
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .clipToBounds()
            .onSizeChanged { viewportWidth = it.width },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Tile enough identical copies to always overflow the viewport, so the loop (which shifts by ONE
        // sequence width) never leaves a trailing gap — a continuous stream regardless of item count.
        val repeats = if (singleWidth > 0) (viewportWidth / singleWidth + 2).coerceAtLeast(2) else 2
        Row(Modifier.graphicsLayer { translationX = -progress * singleWidth }) {
            TickerSequence(items, c.muted, Modifier.onSizeChanged { if (it.width > 0) singleWidth = it.width })
            repeat(repeats - 1) { TickerSequence(items, c.muted, Modifier) }
        }
    }
}

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
