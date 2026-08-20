package dev.mascwa.pulse.desktop.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.mascwa.pulse.core.telemetry.LcarsCodes

// ---------------------------------------------------------------------------------------------
// The SCREEN chrome, ported from the phone's feature/common/LcarsGeometry.kt.
//
// LcarsGeometry.kt in this module draws widgets — a panel, a chip, a data row. Nothing there draws a
// SCREEN, which is why the desktop looked like a different application: LCARS is recognisable at
// arm's length because of its L-shaped chrome, a column of stacked colour blocks down one side
// elbowing into a bar across the top, and this module had none of it.
//
// ⚠️ A COPY, deliberately, and the one place in this arc where copying is right. Compose UI cannot
// live in `:core:telemetry` — that module is plain Kotlin/JVM with no Compose at all, which is
// exactly what lets both platforms depend on it. Sharing this would mean a Compose Multiplatform
// module that `:app` also consumed, which would put the Android app on JetBrains' Compose artifacts
// instead of Google's. That is a real risk for a real app, taken for a frame that changes rarely.
//
// What IS shared is the part that can be: `LcarsCodes` comes out of `:core:telemetry`, so the two
// consoles cannot letter their rails differently.
// ---------------------------------------------------------------------------------------------

/**
 * How much width the rail takes.
 *
 * ⚠️ Wider than the phone's 56dp, and this is the one measurement deliberately NOT copied. On a phone
 * the rail is charged against a 400dp-wide screen and every dp shows; a desktop window is three times
 * that, so the same proportion would leave a stripe rather than a console. Tuned as one constant so a
 * screenshot can settle it.
 */
val LcarsRailWidth = 72.dp

/** Black gutters between blocks. LCARS panels never touch; the ground shows through. */
private val RailGutter = 3.dp
private val HeaderHeight = 58.dp
private val CornerSweep = 24.dp

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
 * draws the same rail — stability matters more than variety here: a rail that reshuffled itself on
 * every recomposition would read as a fault.
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
                        fontSize = 10.sp,
                        // Black on the block: the rail colours are all light, and this is stencilling.
                        color = c.void,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 6.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * A screen, framed.
 *
 * ⚠️ No status-bar inset here, unlike the phone's. A desktop window has no system bar to duck under;
 * the phone's `windowInsetsPadding(WindowInsets.statusBars)` is the one line of that frame that means
 * nothing on this platform, and copying it would have been copying a workaround rather than a design.
 *
 * The corner piece is the back control when [onBack] is supplied. The title sets right, as LCARS
 * titles do, and ellipsises rather than pushing the actions off the end. Set [rail] false for a
 * full-bleed screen (a map) where the horizontal room genuinely cannot be spared.
 */
@Composable
fun LcarsScreenFrame(
    title: String,
    modifier: Modifier = Modifier,
    seed: String = title,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    rail: Boolean = true,
    /**
     * How wide the rail column — and therefore the header's corner block — is.
     *
     * ⚠️ ONE number for both: they are the two arms of the same L, and if they differ the corner does
     * not close. The shell passes a wider one, because there the column is the directory rather than
     * decoration. Same additive change as the phone's frame, for the same reason.
     */
    railWidth: Dp = LcarsRailWidth,
    content: @Composable () -> Unit,
) {
    val c = Pulse.colors
    Column(modifier.fillMaxSize().background(c.void)) {
        Row(
            Modifier.fillMaxWidth().height(HeaderHeight),
            horizontalArrangement = Arrangement.spacedBy(RailGutter),
        ) {
            // ⚠️ THE CORNER SAYS WHAT IT IS. With [onBack] the whole block is the control; without
            // it, the block takes the header band's own colour and is visually one piece of chrome
            // with the title. Painting it accent regardless is what made a dead corner look tappable
            // on the phone, and it was the single most reported "inconsistent back button" symptom.
            Box(
                Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .clip(lcarsBlockShape(CornerSweep, LcarsCorner.TopStart))
                    .background(if (onBack != null) c.accent else c.raise)
                    .then(if (onBack != null) Modifier.clickable(onClick = onBack) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (onBack != null) {
                    // A drawn glyph rather than an icon font: the arrow is two strokes, and pulling a
                    // whole icon set across for it would be the wrong trade.
                    Text(
                        "◀",
                        fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        color = c.void,
                    )
                }
            }

            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(lcarsBlockShape(CornerSweep, LcarsCorner.TopEnd))
                    .background(c.raise)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Where you are and when, said the way this console says it. Both read from
                // composition locals rather than passed in, so every framed screen gains them
                // without one of them being edited — the trick that gave the phone's thirty-five
                // screens a location line and a stardate in one commit each.
                //
                // The bare number, not the word STARDATE: that word belongs to the boot reveal,
                // where the console introduces itself once.
                val section = LocalConsoleSection.current
                val stardate = LocalStardate.current
                val locus = listOfNotNull(
                    section.takeIf { it.isNotEmpty() },
                    stardate.takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    if (locus.isNotEmpty()) {
                        Text(
                            locus,
                            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.4.sp,
                            color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        title.uppercase(),
                        fontFamily = Orbitron, fontWeight = FontWeight.Bold,
                        fontSize = 18.sp, letterSpacing = 1.5.sp, color = c.accent,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
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
            if (rail) LcarsRail(seed, Modifier.width(railWidth).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    }
}

/**
 * The condition strip: nothing at all when routine, a band across every screen when not.
 *
 * The palette already turns red on its own, but a colour shift with no words is ambiguous — it could
 * be a theme. This says which condition and, when something told us, what raised it.
 *
 * Deliberately not clickable. A band that looks like a control and does nothing is worse than a band
 * that plainly reports.
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
            .height(22.dp)
            .background(tint.copy(alpha = alpha))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (red) "RED ALERT" else "YELLOW ALERT",
            fontFamily = Orbitron, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, letterSpacing = 1.4.sp, color = c.void, maxLines = 1,
        )
        if (headline.isNotBlank()) {
            Text(
                headline,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.void,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A thick divider of alternating blocks.
 *
 * A hairline rule is a Material habit. An LCARS rule is a run of solid segments with the ground
 * showing between them, and it is one of the cheapest ways to make a surface read as engineered
 * rather than as a list.
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
            Box(Modifier.weight(w).fillMaxHeight().background(blocks[(i + offset) % blocks.size]))
        }
    }
}

/**
 * A dialog.
 *
 * Material's `AlertDialog` brings its own rounded surface, its own typography and its own button row,
 * which is three separate places for the app's own look to leak away — and it is the last thing
 * standing between the user and a decision, so the worst place to look like a different application.
 *
 * The rail runs down the left, the same motif as every screen, so a dialog reads as part of the
 * console rather than as something the window manager put on top of it.
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
            Column(Modifier.weight(1f).padding(end = 16.dp, top = 16.dp, bottom = 16.dp)) {
                Text(
                    title.uppercase(),
                    fontFamily = Orbitron, fontWeight = FontWeight.Bold,
                    fontSize = 15.sp, letterSpacing = 1.2.sp, color = c.accent,
                )
                Box(Modifier.padding(top = 12.dp)) { content() }
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (confirmText != null && onConfirm != null) {
                        LcarsButton(confirmText, onClick = onConfirm, enabled = confirmEnabled, accent = c.accent)
                    }
                    LcarsGhostButton(dismissText, onClick = onDismiss)
                }
            }
        }
    }
}

private val DialogRailWidth = 24.dp

/**
 * Fewer, chunkier blocks than a screen rail — a dialog is short and a seven-block rail reads as noise.
 *
 * ⚠️ Every weight is deliberately under [CODE_MIN_WEIGHT]. Above it the rail letters the block with a
 * numeric code, and a four-digit code is wider than this rail — it would clip, on a control whose
 * whole job is to look deliberate.
 */
private val DialogRailWeights = listOf(1.4f, 0.6f, 1.8f)
