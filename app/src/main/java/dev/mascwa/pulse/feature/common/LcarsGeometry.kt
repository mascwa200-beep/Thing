package dev.mascwa.pulse.feature.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.LcarsCodes
import dev.mascwa.pulse.notifications.AlertCondition
import dev.mascwa.pulse.notifications.AlertStatus
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.Antonio
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.LocalLcarsBlocks
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * Genuine Star-Trek-Okudagram panel geometry — asymmetric elbow-connector silhouettes and swept blocks,
 * replacing the uniform rounded-rectangles/pills the earlier LCARS re-theme was built from (that kit is
 * gone; these took over its call sites wholesale, which is why the signatures echo it) and the same
 * [Pulse.colors] read, so they render in whatever palette is provided. LTR geometry only (layoutDirection is
 * intentionally ignored below) — this app has no RTL-locale requirement today; flagged here rather than
 * silently assumed.
 *
 * Panels ([LcarsFrame]/[LcarsStatBlock]) use a single swept ROUNDED corner ([lcarsBlockShape]) rather than a
 * notch — safe for arbitrary held content, nothing can clip into a concave corner. The genuinely notched
 * elbow ([rememberLcarsElbow], a real concave L-shape via [GenericShape] — the first use of it in this codebase) is
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

/** A panel: a swept-corner block, not the near-square rectangle the earlier kit drew. */
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

/** A section header whose lead block is a genuine notched elbow, grown out of the screen edge, instead
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

/** A data row whose left colour tab gets a single swept corner (not a notch — it's too thin a
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

/** A chip: a stepped/notched chamfer segment (reusing this codebase's existing
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
    // Wired at the kit rather than at the call site: the app has a full 13-cue haptic vocabulary that
    // reached exactly one screen, and putting the cue here means every chip in every screen gains it
    // without a 109-file sweep.
    val cue = rememberLcarsCue()
    val shape = CutCornerShape(topStart = 0.dp, topEnd = 10.dp, bottomEnd = 0.dp, bottomStart = 10.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) accent else Color.Transparent)
            .border(1.dp, if (selected) accent else c.line, shape)
            .clickable { cue(SoundCue.TAP, HapticCue.TAP_LIGHT); onClick() }
            .padding(horizontal = 15.dp, vertical = 7.dp),
    ) {
        Text(
            text.uppercase(),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp,
            color = if (selected) c.void else c.ink,
        )
    }
}

/** A stat tile — reuses [LcarsFrame] verbatim, exactly as the kit it replaced reused its own panel. */
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
 * caller must give [modifier] an explicit height (e.g. `Modifier.height(8.dp)`) since a bare `Row` has no
 * intrinsic height for `fillMaxHeight()` to fill. [gap] defaults to `0.dp` — seamless-touching blocks, pixel-
 * identical to this composable's behaviour before [gap] existed (the one pre-existing call site,
 * `GuidesScreen`'s read-progress bar, passes no [gap] and is unaffected). A positive [gap] renders each
 * weighted block with real breathing room from its neighbour — a "chicklet"/discrete-segment LCARS read.
 */
@Composable
fun LcarsFillRow(segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier, gap: Dp = 0.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        segments.forEach { (weight, color) ->
            if (weight > 0f) {
                Box(Modifier.weight(weight).fillMaxHeight().background(color))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The frame.
//
// Everything above draws a widget. Nothing above draws a SCREEN, and that was the gap: LCARS is
// recognisable at arm's length because of its L-shaped chrome — a column of stacked colour blocks
// down one side, elbowing into a bar across the top — and the app had none of it. The elbow shape
// existed and was used once, as a 44x20dp nub inside a section header.
// ---------------------------------------------------------------------------------------------

/**
 * How much width the rail takes.
 *
 * The honest cost of this whole idea, in one number. A phone is not a widescreen console and this
 * comes off the content, so it is deliberately a single constant to tune from a screenshot rather
 * than a value scattered through the kit. It buys back more than it costs: the top block is the
 * back control, at roughly six times the touch area of the 24dp icon every screen was drawing.
 */
val LcarsRailWidth = 56.dp

/** Black gutters between blocks. LCARS panels never touch; the ground shows through. */
private val RailGutter = 3.dp
private val HeaderHeight = 54.dp
private val CornerSweep = 22.dp

/**
 * Block heights down the rail, as weights.
 *
 * Unequal on purpose and fixed rather than random — an LCARS rail is irregular, but it is the same
 * irregularity every time you look at it.
 */
private val RailWeights = listOf(2.2f, 1f, 3.4f, 1.3f, 2.6f, 1f, 4.2f)

/** Below this weight a block is too short to letter, so it carries no code. */
private const val CODE_MIN_WEIGHT = 1.9f

/**
 * The vertical column of stacked colour blocks.
 *
 * [seed] fixes both the colours' starting offset and the numeric codes, so a given screen always
 * draws the same rail — see [LcarsCodes] for why stability matters more than variety here.
 */
@Composable
fun LcarsRail(
    seed: String,
    modifier: Modifier = Modifier,
    blocks: List<Color> = LocalLcarsBlocks.current,
    weights: List<Float> = RailWeights,
) {
    if (blocks.isEmpty() || weights.isEmpty()) {
        Box(modifier)
        return
    }
    val c = Pulse.colors
    val codes = remember(seed, weights.size) { LcarsCodes.column(seed, weights.size) }
    // Offset the colour cycle by the screen, so two screens side by side in memory don't read as
    // the same panel.
    val offset = remember(seed) { LcarsCodes.of(seed, 1301).sumOf { it.code } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(RailGutter)) {
        weights.forEachIndexed { i, w ->
            val last = i == weights.lastIndex
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(w)
                    .clip(
                        if (last) lcarsBlockShape(CornerSweep, LcarsCorner.BottomStart)
                        else RoundedCornerShape(0.dp),
                    )
                    .background(blocks[(i + offset) % blocks.size]),
                contentAlignment = Alignment.BottomEnd,
            ) {
                if (w >= CODE_MIN_WEIGHT) {
                    Text(
                        codes.getOrElse(i) { "" },
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        // Black on the block: the rail colours are all light, and this is stencilling.
                        color = c.void,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 5.dp, bottom = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * A screen, framed.
 *
 * The corner piece is the back control when [navigationIcon] is supplied — which is the argument for
 * the rail paying its way rather than merely decorating: every screen in this app was hand-rolling
 * its own small back arrow, and the frame absorbs them.
 *
 * The title sets right, as LCARS titles do, and ellipsises rather than pushing the actions off the
 * end. Set [rail] false for a full-bleed screen (a map) where the horizontal room genuinely cannot
 * be spared.
 */
@Composable
fun LcarsScreenFrame(
    title: String,
    modifier: Modifier = Modifier,
    seed: String = title,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    rail: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = Pulse.colors
    // The status bar is this frame's to consume. `PulseApp`'s outer Scaffold sets
    // contentWindowInsets to zero, so the NavHost gets no top padding and each screen's own top bar
    // has always owned it — Home's custom bar applies exactly this by hand. Miss it and all 35
    // headers slide under the clock while compiling perfectly.
    Column(
        modifier
            .fillMaxSize()
            .background(c.void)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier.fillMaxWidth().height(HeaderHeight),
            horizontalArrangement = Arrangement.spacedBy(RailGutter),
        ) {
            Box(
                Modifier
                    .width(LcarsRailWidth)
                    .fillMaxHeight()
                    .clip(lcarsBlockShape(CornerSweep, LcarsCorner.TopStart))
                    .background(c.accent),
                contentAlignment = Alignment.Center,
                // No clickable on the block itself: the icon passed in brings its own handler, and
                // wrapping it in a second no-op one would give the whole corner a ripple that does
                // nothing. The size win is real regardless — the icon centres in a 56x54 block.
            ) { navigationIcon?.invoke() }

            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(lcarsBlockShape(CornerSweep, LcarsCorner.TopEnd))
                    .background(c.raise)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title.uppercase(),
                    fontFamily = Antonio,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    letterSpacing = 2.sp,
                    color = c.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                actions()
            }
        }

        AlertStrip()

        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(RailGutter),
        ) {
            if (rail) {
                LcarsRail(seed, Modifier.width(LcarsRailWidth).fillMaxHeight())
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    }
}

/**
 * The condition strip: nothing at all when routine, a band across every screen when not.
 *
 * The palette already turns red on its own, but a colour shift with no words is ambiguous — it could
 * be a theme. This says which condition and, when the board has told us, what raised it.
 *
 * Deliberately not tappable. The frame has no navigation, and a band that looks like a control and
 * does nothing is worse than a band that plainly reports. The detail is one pull-down away in the
 * notification that raised it.
 */
@Composable
private fun AlertStrip() {
    val condition by AlertStatus.condition.collectAsState()
    if (condition == AlertCondition.ROUTINE) return

    val c = Pulse.colors
    val red = condition == AlertCondition.RED
    val headline by AlertStatus.headline.collectAsState()
    // Under red the palette's own accent IS the alert red, so this reads correctly in both.
    val tint = if (red) c.accent else c.amber
    // Only red pulses. A yellow alert that throbbed would be a distraction proportional to nothing,
    // and this animates on every screen for as long as the condition lasts.
    val alpha = if (red) {
        val t = rememberInfiniteTransition(label = "alert")
        val a by t.animateFloat(
            initialValue = 0.42f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
            label = "alertAlpha",
        )
        a
    } else {
        1f
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = RailGutter)
            .height(20.dp)
            .background(tint.copy(alpha = alpha))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (red) "RED ALERT" else "YELLOW ALERT",
            fontFamily = Antonio,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 2.sp,
            color = c.void,
            maxLines = 1,
        )
        if (headline.isNotBlank()) {
            Text(
                headline,
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = c.void,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A thick divider of alternating blocks.
 *
 * The app's dividers are all 1px hairlines, which is a Material habit. An LCARS rule is a run of
 * solid segments with the ground showing between them, and it is one of the cheapest ways to make a
 * surface read as engineered rather than as a list.
 */
@Composable
fun LcarsSegmentBar(
    seed: String,
    modifier: Modifier = Modifier,
    segments: Int = 5,
    blocks: List<Color> = LocalLcarsBlocks.current,
) {
    if (segments <= 0 || blocks.isEmpty()) {
        Box(modifier)
        return
    }
    // Widths vary but are fixed per seed, for the same reason the codes are.
    val widths = remember(seed, segments) {
        (0 until segments).map { 1f + (LcarsCodes.of(seed, it + 4700).last().digitToInt() % 4) }
    }
    val offset = remember(seed) { LcarsCodes.of(seed, 88).sumOf { it.code } }
    Row(modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(RailGutter)) {
        widths.forEachIndexed { i, w ->
            Box(
                Modifier
                    .weight(w)
                    .fillMaxHeight()
                    .background(blocks[(i + offset) % blocks.size]),
            )
        }
    }
}

/**
 * One destination in [LcarsNavBar].
 *
 * A plain data holder rather than a reference to the navigation module's own type, so the kit does
 * not depend on the app's route table.
 */
data class LcarsNavItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * The bottom bar as LCARS: a run of solid blocks, not a Material nav bar.
 *
 * This was the last stock Material surface in the chrome, and it sat on every screen — a row of
 * tinted icons with a pill indicator, directly beneath a frame built entirely out of blocks. The
 * blocks read as one console; the pill read as Android.
 *
 * The leading stub continues the rail's foot and carries its swept bottom-left corner, so the L
 * closes at the corner instead of stopping short of it. Each destination gets its own hue from the
 * rail palette — which means the bar goes red with everything else under an alert, and no code here
 * knows about alerts at all.
 *
 * Labels sit on the block in the ground colour, because that is how LCARS letters a filled block and
 * because it survives every hue in the palette. Icons stay: six words at phone width is a lot to
 * read at a glance, and the icon is what the eye actually lands on.
 */
@Composable
fun LcarsNavBar(
    items: List<LcarsNavItem>,
    modifier: Modifier = Modifier,
    blocks: List<Color> = LocalLcarsBlocks.current,
) {
    if (items.isEmpty() || blocks.isEmpty()) {
        Box(modifier)
        return
    }
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    Row(
        modifier
            .fillMaxWidth()
            .background(c.void)
            .height(NavBarHeight),
        horizontalArrangement = Arrangement.spacedBy(RailGutter),
    ) {
        // The rail's foot. Not a control: the rail above it is not one either, and a corner block
        // that navigated somewhere would be a surprise every time a thumb brushed it.
        Box(
            Modifier
                .width(LcarsRailWidth)
                .fillMaxHeight()
                .clip(lcarsBlockShape(CornerSweep, LcarsCorner.BottomStart))
                .background(c.accent),
        )
        items.forEachIndexed { i, item ->
            val last = i == items.lastIndex
            // Offset into the palette so the first tab is not the same orange as the corner stub
            // beside it — two identical blocks touching read as one wide block.
            val tint = if (item.selected) c.accent else blocks[(i + HUE_OFFSET) % blocks.size]
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(
                        if (last) lcarsBlockShape(CornerSweep, LcarsCorner.BottomEnd)
                        else RoundedCornerShape(0.dp),
                    )
                    // An unselected block is the same hue at a fraction of its strength, so the
                    // selected one reads instantly without the row becoming a colour chart.
                    .background(if (item.selected) tint else tint.copy(alpha = 0.34f))
                    .clickable {
                        cue(SoundCue.SELECT, HapticCue.SELECT)
                        item.onClick()
                    }
                    .padding(horizontal = 2.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.label,
                    tint = c.void,
                    modifier = Modifier.height(NavIconSize).width(NavIconSize),
                )
                Text(
                    item.label,
                    fontFamily = Antonio,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 0.6.sp,
                    color = c.void,
                    maxLines = 1,
                    // Antonio is condensed, which is most of why six words fit at all. Clipping
                    // rather than ellipsising: a truncated word still reads, "MARKE…" does not.
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                )
            }
        }
    }
}

private const val HUE_OFFSET = 2
private val NavBarHeight = 52.dp
private val NavIconSize = 17.dp
