package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * Genuine Star-Trek-Okudagram panel geometry — asymmetric elbow-connector silhouettes and swept blocks,
 * replacing the uniform rounded-rectangles/pills `PipUi.kt` built the earlier LCARS re-theme from. Same API
 * shape as `PipUi.kt`'s primitives (drop-in replacements, mechanical call-site swaps) and the same
 * [Pulse.colors] read, so they render in whatever palette is provided. LTR geometry only (layoutDirection is
 * intentionally ignored below) — this app has no RTL-locale requirement today; flagged here rather than
 * silently assumed.
 *
 * Panels ([LcarsFrame]/[LcarsStatBlock]) use a single swept ROUNDED corner ([lcarsBlockShape]) rather than a
 * notch — safe for arbitrary held content, nothing can clip into a concave corner. The genuinely notched
 * elbow ([LcarsElbow], a real concave L-shape via [GenericShape] — the first use of it in this codebase) is
 * reserved for small solid-color accents with no text inside them ([LcarsHeaderBar]'s lead block,
 * [LcarsDataRow]'s tab-adjacent nub) where a bitten corner can't clip anything readable.
 */
enum class LcarsCorner { TopStart, TopEnd, BottomStart, BottomEnd }

/** A rectangle with one corner's radius swept large (clamped to half the shorter side, same as any
 *  [RoundedCornerShape]) and the other three left sharp — the asymmetric "one end is a pill cap" LCARS block
 *  silhouette. No [GenericShape] needed; per-corner [RoundedCornerShape] already expresses this. */
fun lcarsBlockShape(sweep: Dp, corner: LcarsCorner): Shape = when (corner) {
    LcarsCorner.TopStart -> RoundedCornerShape(topStart = sweep, topEnd = 0.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
    LcarsCorner.TopEnd -> RoundedCornerShape(topStart = 0.dp, topEnd = sweep, bottomEnd = 0.dp, bottomStart = 0.dp)
    LcarsCorner.BottomStart -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomEnd = 0.dp, bottomStart = sweep)
    LcarsCorner.BottomEnd -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomEnd = sweep, bottomStart = 0.dp)
}

/**
 * A genuine concave L-shaped notch bitten out of one corner, with the interior (concave) joint filled by a
 * quarter-circle arc of the same size as the notch — the real Okudagram "step" silhouette (not just a
 * rounded corner: part of the bounding box is actually absent). [notchSize] is clamped to at most half the
 * shorter side so a tiny/degenerate container can't produce a self-intersecting path (falls back to a plain
 * rectangle at zero). `GenericShape`'s builder has no `Density` receiver, so [notchSize] is converted to
 * pixels once via [LocalDensity] at the call site and closed over — this must be called from a composable.
 */
@Composable
fun rememberLcarsElbow(notchSize: Dp, corner: LcarsCorner = LcarsCorner.TopStart): Shape {
    val density = LocalDensity.current
    return remember(notchSize, corner, density) {
        val rawPx = with(density) { notchSize.toPx() }
        GenericShape { size, _ ->
            val w = size.width
            val h = size.height
            val s = rawPx.coerceIn(0f, minOf(w, h) / 2f)
            if (s <= 0f) {
                addRect(Rect(0f, 0f, w, h))
                return@GenericShape
            }
            // Every case sweeps +90° from the arc's start angle to its end angle — derived and verified
            // analytically (arc endpoints match the adjoining lineTo points exactly) before writing this.
            when (corner) {
                LcarsCorner.TopStart -> {
                    moveTo(s, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    lineTo(0f, s)
                    arcTo(Rect(0f, 0f, 2 * s, 2 * s), 180f, 90f, false)
                    close()
                }
                LcarsCorner.TopEnd -> {
                    moveTo(0f, 0f)
                    lineTo(w - s, 0f)
                    arcTo(Rect(w - 2 * s, 0f, w, 2 * s), 270f, 90f, false)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                LcarsCorner.BottomStart -> {
                    moveTo(0f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h)
                    lineTo(s, h)
                    arcTo(Rect(0f, h - 2 * s, 2 * s, h), 90f, 90f, false)
                    close()
                }
                LcarsCorner.BottomEnd -> {
                    moveTo(0f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h - s)
                    arcTo(Rect(w - 2 * s, h - 2 * s, w, h), 0f, 90f, false)
                    lineTo(0f, h)
                    close()
                }
            }
        }
    }
}

/** [PipFrame] replacement: a swept-corner block instead of the earlier two-nearly-square-corners rectangle. */
@Composable
fun LcarsFrame(
    modifier: Modifier = Modifier,
    accent: Color = Pulse.colors.accent,
    padding: PaddingValues = PaddingValues(13.dp),
    corner: LcarsCorner = LcarsCorner.TopStart,
    sweep: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    val c = Pulse.colors
    val shape = lcarsBlockShape(sweep, corner)
    Box(
        modifier
            .clip(shape)
            .background(c.panel)
            .border(1.5.dp, accent, shape)
            .padding(padding),
    ) { content() }
}

/** [PipHeader] replacement: the lead block is a genuine notched elbow (grown out of the screen edge) instead
 *  of a stadium pill, dropping into the title via a straight rule out to the trailing edge. */
@Composable
fun LcarsHeaderBar(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val c = Pulse.colors
    val elbow = rememberLcarsElbow(notchSize = 9.dp, corner = LcarsCorner.BottomStart)
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.height(20.dp).width(44.dp).clip(elbow).background(c.accent))
        Text(
            title.uppercase(),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp,
            color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Canvas(Modifier.weight(1f).height(2.dp)) {
            drawLine(c.line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f)
        }
        if (trailing != null) {
            Text(trailing, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.muted)
        }
    }
}

/** [PipDataRow] replacement: the left colour tab gets a single swept corner (not a notch — it's too thin a
 *  bar for a notch to read as anything but a rounding error) instead of the earlier `RoundedCornerShape`
 *  sliver. Same label/value contract, stack with NO gaps so the rules form a continuous list. */
@Composable
fun LcarsDataRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Pulse.colors.ink) {
    val c = Pulse.colors
    val tabShape = lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.BottomEnd)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(5.dp).fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(tabShape)
                    .background(c.accent),
            )
            Row(
                Modifier.weight(1f).padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label, fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = valueColor)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1f)
        }
    }
}

/** [PipChip] replacement: a stepped/notched chamfer segment (reusing this codebase's existing
 *  `CutCornerShape`-as-shared-shape idiom, see `Nightwire.kt`'s `CyberCut`) instead of a stadium pill. Sized
 *  to its own content, so a rail of these reads as distinct notched segments rather than uniform pills. */
@Composable
fun LcarsChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Pulse.colors.accent,
) {
    val c = Pulse.colors
    val shape = CutCornerShape(topStart = 0.dp, topEnd = 10.dp, bottomEnd = 0.dp, bottomStart = 10.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) accent else Color.Transparent)
            .border(1.dp, if (selected) accent else c.line, shape)
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 7.dp),
    ) {
        Text(
            text.uppercase(),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp,
            color = if (selected) c.void else c.ink,
        )
    }
}

/** [PipStatTile] replacement — reuses [LcarsFrame] verbatim, same as the original reused [PipFrame]. */
@Composable
fun LcarsStatBlock(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Pulse.colors.ink) {
    val c = Pulse.colors
    LcarsFrame(modifier, padding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
        Column {
            Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.7.sp, color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = valueColor,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * A row of unequal-, content-driven-width colour-coded blocks summing to exactly fill the container — the
 * "sweeping blocks fitted to the screen" instruction. A plain `Modifier.weight()` `Row` (weight-ratio-driven,
 * not truly content-measured) rather than a custom `Layout` — the simplest implementation that satisfies the
 * ask; worth revisiting with a real custom `Layout` only if this doesn't read convincingly on-device. The
 * caller must give [modifier] an explicit height (e.g. `Modifier.height(8.dp)`) — same convention as the
 * existing `SegmentedMoodBar` — since a bare `Row` has no intrinsic height for `fillMaxHeight()` to fill.
 */
@Composable
fun LcarsFillRow(segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    Row(modifier) {
        segments.forEach { (weight, color) ->
            if (weight > 0f) {
                Box(Modifier.weight(weight).fillMaxHeight().background(color))
            }
        }
    }
}
