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
import androidx.compose.ui.window.Dialog
import dev.mascwa.pulse.core.telemetry.LcarsCodes
import dev.mascwa.pulse.navigation.LocalConsoleSection
import dev.mascwa.pulse.notifications.AlertCondition
import dev.mascwa.pulse.notifications.AlertStatus
import dev.mascwa.pulse.ui.LocalStardate
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.Antonio
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.LocalConsoleBlocks
import dev.mascwa.pulse.ui.theme.Orbitron
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * Original-series console geometry — cut plates and mitred elbow connectors, rendered in whatever
 * palette is provided via [Pulse.colors]. LTR geometry only (layoutDirection is intentionally ignored
 * below) — this app has no RTL-locale requirement today; flagged here rather than silently assumed.
 *
 * ⚠️ **The `Lcars` prefix on these names is now historical, and deliberately left alone.** The shapes
 * are 1966, not 1987, but the names describe *function* — a rail, a frame, a header bar, a data row —
 * and those roles did not change. Renaming the prefix would be a blind mechanical sweep across every
 * screen in the app for no user-visible gain, and a sweep of exactly that kind has already cost this
 * project a CI failure. It is a separate, gated slice if it is ever worth doing at all.
 *
 * Panels ([LcarsFrame]/[LcarsStatBlock]) use a single CUT corner ([lcarsBlockShape]) rather than a
 * notch — safe for arbitrary held content, since nothing can clip into a concave corner. The genuinely
 * notched elbow ([rememberLcarsElbow], a real concave L-shape via [GenericShape]) is reserved for small
 * solid-colour accents with no text inside them ([LcarsHeaderBar]'s lead block, [LcarsDataRow]'s
 * tab-adjacent nub), where a bitten corner cannot clip anything readable.
 */
enum class LcarsCorner { TopStart, TopEnd, BottomStart, BottomEnd }

/**
 * A console plate: a rectangle with one corner cut away at an angle and the other three left sharp.
 *
 * ⚠️ **This is the shape that carries the era.** It used to be a [RoundedCornerShape] with one corner
 * swept into a large pill cap — the 1987 LCARS block. The 1966 consoles are cut, not swept: hard
 * plates with mitred corners, an angular language rather than a curved one. [CutCornerShape] is the
 * same per-corner API and the same [Dp] parameter, so every call site keeps its argument and simply
 * renders angular — which is what makes the whole console change with one edit.
 *
 * Clamped to half the shorter side by [CutCornerShape] itself, exactly as the rounded version was.
 */
fun lcarsBlockShape(sweep: Dp, corner: LcarsCorner): Shape = when (corner) {
    LcarsCorner.TopStart -> CutCornerShape(topStart = sweep, topEnd = 0.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
    LcarsCorner.TopEnd -> CutCornerShape(topStart = 0.dp, topEnd = sweep, bottomEnd = 0.dp, bottomStart = 0.dp)
    LcarsCorner.BottomStart -> CutCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomEnd = 0.dp, bottomStart = sweep)
    LcarsCorner.BottomEnd -> CutCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomEnd = sweep, bottomStart = 0.dp)
}

/**
 * The "jelly bean" — a full capsule, the 1966 bridge's signature control.
 *
 * A percentage rather than a [Dp] so it stays a true stadium at any height without the caller having
 * to know its own size. Used for the button banks, where a bank of capsules on black is the single
 * most recognisable thing about the original consoles.
 */
val tosCapsule: Shape = RoundedCornerShape(percent = 50)

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
            // Every case mitres straight across the corner. These were quarter-circle arcs for LCARS;
            // the 1966 consoles are cut rather than swept, so each arc became a straight line to the
            // very same end point — derived from the arc's start angle and its +90° sweep, so the
            // silhouette keeps its exact bounding box and only the joint changes from curved to angular.
            when (corner) {
                LcarsCorner.TopStart -> {
                    moveTo(s, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    lineTo(0f, s)
                    lineTo(s, 0f)
                    close()
                }
                LcarsCorner.TopEnd -> {
                    moveTo(0f, 0f)
                    lineTo(w - s, 0f)
                    lineTo(w, s)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                LcarsCorner.BottomStart -> {
                    moveTo(0f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h)
                    lineTo(s, h)
                    lineTo(0f, h - s)
                    close()
                }
                LcarsCorner.BottomEnd -> {
                    moveTo(0f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h - s)
                    lineTo(w - s, h)
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
    /** Additive, for the NeonPanel shim's few tinted callers; everything else keeps the panel colour. */
    background: Color = Pulse.colors.panel,
    content: @Composable () -> Unit,
) {
    val shape = lcarsBlockShape(sweep, corner)
    Box(
        modifier
            .clip(shape)
            .background(background)
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
    blocks: List<Color> = LocalConsoleBlocks.current,
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
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    rail: Boolean = true,
    /**
     * How wide the rail column — and therefore the header's corner block — is.
     *
     * ⚠️ ONE number for both on purpose: the corner and the rail beneath it are the two arms of the
     * same L, and if they differ the corner does not close. Additive, defaulting to the value every
     * screen already gets, so this changes nothing anywhere it is not passed.
     *
     * The MENU passes a wider one, because there the column is not decoration — it holds the group
     * names, and "YOUR THINGS" does not fit in 56dp at a legible size. Setting it here rather than
     * letting that screen draw a mismatched column is the difference between a console and two
     * things that happen to be next to each other.
     */
    railWidth: Dp = LcarsRailWidth,
    /**
     * What goes in the rail column, when the decorative stack is not what belongs there.
     *
     * Null — the default, and what every screen gets unless something above it says otherwise — draws
     * [LcarsRail]. Additive, so no call site changes.
     */
    railContent: (@Composable (Modifier) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
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
            // ⚠️ THE CORNER SAYS WHAT IT IS. Three honest states, one back idiom app-wide:
            //  - [onBack] set  -> the WHOLE 56×54 block is the back control (accent, tappable,
            //    kit cue/haptics, black glyph — LCARS puts dark glyphs on coloured blocks). A far
            //    bigger target than the 24dp IconButton every screen used to hand-roll.
            //  - legacy [navigationIcon] -> the old contract, unchanged, until the sweep retires it.
            //  - neither -> the block renders in the HEADER BAND's own colour, visually one piece
            //    of chrome with the title block. The old behaviour painted it accent regardless,
            //    which made a dead corner look tappable on every tab screen — the single most
            //    reported "inconsistent back button" symptom.
            val cornerActive = onBack != null || navigationIcon != null
            Box(
                Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .clip(lcarsBlockShape(CornerSweep, LcarsCorner.TopStart))
                    .background(if (cornerActive) c.accent else c.raise)
                    .then(
                        if (onBack != null) {
                            Modifier.clickable { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onBack() }
                        } else {
                            // No clickable otherwise: a legacy icon brings its own handler, and a
                            // second no-op one would give the corner a ripple that does nothing.
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (onBack != null) {
                    Icon(LcarsIcons.ArrowBack, contentDescription = "Back", tint = c.void)
                } else {
                    navigationIcon?.invoke()
                }
            }

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
                // Where you are, above what you are looking at. A 1966 console is banks of unlabelled
                // colour, which is exactly why this is here: the hardware idiom is the original
                // series, but a screen that does not say where it sits would make the app harder to
                // navigate rather than easier, and legibility is not the part being stylised.
                //
                // Read from a composition local rather than passed in, so all thirty-five screens
                // gained it without one of them being edited. Absent for anything the directory does
                // not list, and the title simply keeps the whole block as it did before.
                val section = LocalConsoleSection.current
                // And when, said the way this console says it. One read of a composition local the
                // app root refreshes on the hour, so all thirty-three framed screens gained a
                // stardate without one of them being edited — the same trick as the section above.
                //
                // The bare number, not `Stardate.stamp`: the word "STARDATE" is the boot reveal's,
                // where the console introduces itself once. Repeating it on every screen would spend
                // the header's scarcest resource on a label nobody needs twice.
                //
                // Width was measured rather than eyeballed, the same way the title size below was.
                // JetBrainsMono is monospaced at exactly 0.6 em, so at 8sp with 1.4sp of tracking a
                // character is 6.2dp; the longest section label in the whole directory is
                // "YOUR THINGS", and "YOUR THINGS · 26621.5" is 21 characters -> 130dp of the same
                // ~230dp the 16sp title fits 203dp of. It does not crowd.
                val stardate = LocalStardate.current
                val locus = listOfNotNull(section, stardate.takeIf { it.isNotEmpty() })
                    .joinToString(" · ")
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    if (locus.isNotEmpty()) {
                        Text(
                            locus,
                            fontFamily = JetBrainsMono,
                            fontSize = 8.sp,
                            letterSpacing = 1.4.sp,
                            color = c.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        title.uppercase(),
                        fontFamily = Orbitron,
                        fontWeight = FontWeight.Bold,
                        // 16sp, not the 19 the condensed face used. Measured over every real screen
                        // title in the app, the longest lands at 203dp here against roughly 230dp of
                        // header — where the same string at 19sp would have needed 241dp and simply
                        // ellipsised. Longer titles still degrade gracefully, as they always did.
                        fontSize = 16.sp,
                        letterSpacing = 1.5.sp,
                        color = c.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                }
                actions()
            }
        }

        AlertStrip()

        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(RailGutter),
        ) {
            if (rail) {
                // ⚠️ A SLOT, not a flag. The kit knows what a rail looks like and nothing about what
                // the app might want to put in one — teaching it about the directory would invert the
                // layering, and the whole reason twenty-nine screens could change shape at once is
                // that this decision is made one level up, in `PulseScaffold`, where it belongs.
                val column = railContent
                if (column != null) column(Modifier.width(railWidth).fillMaxHeight())
                else LcarsRail(seed, Modifier.width(railWidth).fillMaxHeight())
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
            fontFamily = Orbitron,
            fontWeight = FontWeight.Bold,
            // 11sp: the strip is 20dp tall and shares its width with a headline, so the wide face
            // has to give some back. "YELLOW ALERT" renders at 120dp, leaving the headline the rest.
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
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
    blocks: List<Color> = LocalConsoleBlocks.current,
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
    blocks: List<Color> = LocalConsoleBlocks.current,
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
                    // ⚠️ The one place that keeps the condensed face while the rest of the console
                    // moved to the wide one, and the reason is measurable rather than aesthetic. Six
                    // labels share the width of the phone, so each slot is 64.5dp on a 411dp screen
                    // and 56dp on a 360dp one. COMPUTER renders at 37dp in Antonio at 9sp and 64dp
                    // in Orbitron — inside the slot on one phone and outside it on the other.
                    //
                    // Clipping rather than ellipsising: a truncated word still reads, "MARKE…" does
                    // not.
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

// ---------------------------------------------------------------------------------------------
// Controls. The kit could draw panels, rails, headers, chips and a nav bar, and had no button, no
// switch and no dialog — so every screen that needed one either reached for Material or hand-rolled
// its own. The good hand-rolled button was private inside one screen with a dozen call sites nobody
// else could reach; these three exist so that stops being the reason a screen stays Material.
// ---------------------------------------------------------------------------------------------

/**
 * A button.
 *
 * The shape is the one `JarvisSetupScreen` had already arrived at privately — a swept-corner block,
 * outlined, with the fill at a low alpha so it reads as a control rather than as a filled panel.
 * Disabled goes to the muted hue rather than to a transparency, because a translucent control on a
 * dark ground stops being legible before it stops being tappable.
 */
@Composable
fun LcarsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Pulse.colors.accent,
    corner: LcarsCorner = LcarsCorner.TopStart,
) {
    val tint = if (enabled) color else Pulse.colors.muted
    val cue = rememberLcarsCue()
    val shape = lcarsBlockShape(ControlSweep, corner)
    Box(
        modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.6f), shape)
            .clickable(enabled = enabled) { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = tint,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A two-state control.
 *
 * Material's switch is a rounded pill with a circular thumb, which is the most conspicuously
 * un-LCARS shape the app had left — this vocabulary has no curves anywhere else. Two blocks in a
 * track: the lit one is where the state is, and the whole control is the tap target.
 *
 * Labelled ON/OFF rather than relying on colour alone. A block that is orange when on and grey when
 * off is unreadable to anyone who cannot tell those apart, and ambiguous to everyone at a glance in
 * a row of eighty settings.
 */
@Composable
fun LcarsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Pulse.colors.accent,
) {
    val c = Pulse.colors
    val tint = if (!enabled) c.muted else if (checked) color else c.line
    val cue = rememberLcarsCue()
    Row(
        modifier
            .clip(lcarsBlockShape(ControlSweep, LcarsCorner.TopStart))
            .background(c.raise)
            .clickable(enabled = enabled) {
                cue(if (checked) SoundCue.TAP else SoundCue.SELECT, HapticCue.SELECT)
                onCheckedChange(!checked)
            }
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Off-block then on-block, so the lit half sits where the eye expects the "further along"
        // state to be, the way a physical rocker does.
        SwitchHalf("OFF", lit = !checked, tint = if (checked) c.line else c.muted)
        SwitchHalf("ON", lit = checked, tint = tint)
    }
}

@Composable
private fun SwitchHalf(label: String, lit: Boolean, tint: Color) {
    val c = Pulse.colors
    Box(
        Modifier
            .width(SwitchHalfWidth)
            .height(SwitchHeight)
            .background(if (lit) tint else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.8.sp,
            color = if (lit) c.void else c.muted,
        )
    }
}

/**
 * A dialog.
 *
 * Material's `AlertDialog` brings its own rounded surface, its own typography and its own button
 * row, which is three separate places for the app's own look to leak away — and it is the last
 * thing standing between the user and a decision, so it is the worst place to look like a different
 * application.
 *
 * The rail runs down the left, the same motif as every screen, so a dialog reads as part of the
 * console rather than as something the system put on top of it.
 */
@Composable
fun LcarsDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    /** Whether the confirm action is currently valid — a dialog that gathers input needs the guard. */
    confirmEnabled: Boolean = true,
    dismissText: String = "CLOSE",
    seed: String = title,
    content: @Composable () -> Unit,
) {
    val c = Pulse.colors
    Dialog(onDismissRequest = onDismiss) {
        Row(
            modifier
                .fillMaxWidth()
                .clip(lcarsBlockShape(CornerSweep, LcarsCorner.TopStart))
                .background(c.panel)
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(RailGutter),
        ) {
            // A short rail, not the full screen one: enough to carry the motif without turning a
            // dialog into a scale model of a screen.
            LcarsRail(seed, Modifier.width(DialogRailWidth).fillMaxHeight(), weights = DialogRailWeights)
            Column(Modifier.weight(1f).padding(end = 14.dp, top = 14.dp, bottom = 14.dp)) {
                Text(
                    title.uppercase(),
                    fontFamily = Orbitron, fontWeight = FontWeight.Bold,
                    fontSize = 14.sp, letterSpacing = 1.2.sp, color = c.accent,
                )
                Box(Modifier.padding(top = 10.dp)) { content() }
                Row(
                    Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (confirmText != null && onConfirm != null) {
                        LcarsButton(confirmText, onClick = onConfirm, enabled = confirmEnabled, color = c.accent)
                    }
                    LcarsButton(dismissText, onClick = onDismiss, color = c.muted)
                }
            }
        }
    }
}

private val ControlSweep = 8.dp
private val SwitchHalfWidth = 30.dp
private val SwitchHeight = 22.dp
private val DialogRailWidth = 22.dp

/**
 * Fewer, chunkier blocks than a screen rail — a dialog is short and a seven-block rail reads as noise.
 *
 * ⚠️ Every weight is deliberately under [CODE_MIN_WEIGHT]. Above it the rail letters the block with a
 * numeric code, and a four-digit code at 9sp is wider than this rail — it would clip, on a control
 * whose whole job is to look deliberate.
 */
private val DialogRailWeights = listOf(1.4f, 0.6f, 1.8f)
