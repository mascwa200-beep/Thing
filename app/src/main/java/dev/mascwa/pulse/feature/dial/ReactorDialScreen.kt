package dev.mascwa.pulse.feature.dial

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Reactor Dial — the arc-reactor reimagined as a rotary app launcher. The reactor blooms open into a
 * ring of app "nodes" (rotary-phone style); tap a node to launch its app, long-press to assign one, tap the
 * core to collapse. Pins live in settings (per-position), so the layout is fully customizable. In-app (not
 * the wallpaper) because that's where touch + animation actually work.
 */
@Composable
fun ReactorDialScreen(vm: ReactorDialViewModel, onClose: () -> Unit) {
    val c = Pulse.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val slots by vm.slots.collectAsState()
    val apps by vm.apps.collectAsState()
    var pickerSlot by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    // Bloom drives the expand (on enter) / collapse (on close) — the nodes fly out from the core.
    val bloom = remember { Animatable(0f) }
    LaunchedEffect(Unit) { bloom.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
    fun close() {
        scope.launch {
            runCatching { bloom.animateTo(0f, tween(340, easing = FastOutSlowInEasing)) }
            onClose()
        }
    }
    BackHandler { close() }

    val spinT = rememberInfiniteTransition(label = "dial")
    val spin by spinT.animateFloat(
        0f, 360f, infiniteRepeatable(tween(36000, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )

    Box(Modifier.fillMaxSize().background(c.void), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val dim = minOf(maxWidth, maxHeight)
            val radiusPx = with(density) { (dim * 0.34f).toPx() }
            val slotSize = dim * 0.16f

            Box(Modifier.size(dim), contentAlignment = Alignment.Center) {
                // Reactor backdrop — rings + core, winding in as it blooms.
                Canvas(
                    Modifier.fillMaxSize().graphicsLayer {
                        val b = bloom.value
                        scaleX = 0.55f + 0.45f * b
                        scaleY = scaleX
                        rotationZ = (1f - b) * -28f
                        alpha = b
                    },
                ) { drawDialReactor(c.sky, c.accent, spin) }

                // Center core hot-spot — collapses the dial.
                Box(Modifier.size(dim * 0.22f).clip(CircleShape).clickable { close() })

                // App nodes around the ring.
                slots.forEachIndexed { i, pkg ->
                    val a = (-90f + i * (360f / ReactorDialViewModel.NUM_SLOTS)) * (Math.PI.toFloat() / 180f)
                    DialNode(
                        packageName = pkg,
                        size = slotSize,
                        modifier = Modifier.align(Alignment.Center).graphicsLayer {
                            translationX = radiusPx * cos(a) * bloom.value
                            translationY = radiusPx * sin(a) * bloom.value
                            alpha = bloom.value
                            rotationZ = (1f - bloom.value) * 40f
                        },
                        onTap = {
                            if (pkg.isEmpty() || !vm.launch(context, pkg)) pickerSlot = i
                        },
                        onLongPress = { pickerSlot = i },
                    )
                }
            }

            Text(
                "TAP CORE TO CLOSE  ·  LONG-PRESS A NODE TO ASSIGN",
                fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.5.sp, color = c.muted,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp)
                    .graphicsLayer { alpha = bloom.value },
            )
        }
    }

    pickerSlot?.let { slot ->
        AppPickerDialog(
            apps = apps,
            hasCurrent = slots.getOrNull(slot)?.isNotEmpty() == true,
            onPick = { pkg -> vm.assign(slot, pkg); pickerSlot = null },
            onClear = { vm.clear(slot); pickerSlot = null },
            onDismiss = { pickerSlot = null },
        )
    }
}

/** Draws the arc-reactor backdrop (tick ring + three arc rings + glowing tri-coil core). */
private fun DrawScope.drawDialReactor(primary: androidx.compose.ui.graphics.Color, accent: androidx.compose.ui.graphics.Color, spin: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val center = Offset(cx, cy)
    val rMax = size.minDimension / 2f * 0.96f

    val tick = primary.copy(alpha = 0.20f)
    var deg = 0
    while (deg < 360) {
        val a = Math.toRadians(deg.toDouble())
        val inner = if (deg % 30 == 0) rMax * 0.9f else rMax * 0.95f
        drawLine(
            tick,
            Offset(cx + (inner * sin(a)).toFloat(), cy - (inner * cos(a)).toFloat()),
            Offset(cx + (rMax * sin(a)).toFloat(), cy - (rMax * cos(a)).toFloat()),
            1.4f,
        )
        deg += 6
    }
    arcRing(center, rMax * 0.86f, primary.copy(alpha = 0.7f), 3f, spin, 3, 0.66f)
    drawCircle(primary.copy(alpha = 0.18f), rMax * 0.7f, center, style = Stroke(1.4f))
    arcRing(center, rMax * 0.7f, accent.copy(alpha = 0.8f), 2.6f, -spin, 6, 0.4f)
    arcRing(center, rMax * 0.5f, primary.copy(alpha = 0.5f), 2.2f, spin * 1.4f, 4, 0.5f)
    // Core glow.
    val coreR = rMax * 0.2f
    drawCircle(primary.copy(alpha = 0.10f), coreR * 1.6f, center)
    drawCircle(accent.copy(alpha = 0.30f), coreR, center)
    drawCircle(primary.copy(alpha = 0.85f), coreR * 0.3f, center)
}

private fun DrawScope.arcRing(
    center: Offset, radius: Float, color: androidx.compose.ui.graphics.Color,
    width: Float, rotation: Float, segments: Int, fill: Float,
) {
    val step = 360f / segments
    val sweep = step * fill
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    for (i in 0 until segments) {
        drawArc(color, rotation + i * step, sweep, false, topLeft, arcSize, style = Stroke(width))
    }
}
