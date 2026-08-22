package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.navigation.SiblingRailContext
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * The console rail, doing work.
 *
 * On the six bottom-nav tabs the left column is a stack of coloured blocks that mean nothing — real
 * LCARS, and real decoration. On a screen you reached from the directory it is this instead: the
 * group you are in at the top, then one block per screen filed beside this one, the one you are
 * looking at lit.
 *
 * ⚠️ **This is what changed the layout of twenty-nine screens without editing any of them.** They all
 * draw the same frame, so replacing what that frame puts in its left column moves the content of
 * every one of them at once — which is a change of placement rather than twenty-nine redesigns that
 * would drift apart by the third.
 *
 * ⚠️ And it is not only a rearrangement. Going from Nearby Danger to Nearest Help used to mean back,
 * then the directory, then a group, then the entry; it is one tap now, and the tap is in the same
 * place on every screen in the group. The old column spent that width saying nothing.
 *
 * The idiom is the one the directory itself now uses and the one every settings program on a desktop
 * uses — a column of siblings beside the thing you picked. Nothing here was invented.
 */
@Composable
fun SiblingRail(ctx: SiblingRailContext, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val accent = ctx.group.accent(c)
    val cue = rememberLcarsCue()
    val here = ctx.route.substringBefore('?').substringBefore('/')

    Column(modifier, verticalArrangement = Arrangement.spacedBy(RailGutter)) {
        // The group's own name, in the group's own colour — so the column says which part of the app
        // this is before it says which screen. The menu files it here; this is the same word.
        Box(
            Modifier.fillMaxWidth().height(GroupBlockHeight).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            RailLabel(ctx.group.label, c.void, FontWeight.Bold)
        }

        // ⚠️ Weights, not fixed heights, and this is a correctness choice rather than a stylistic one.
        // The largest group is seven entries; at a comfortable fixed height that column is 373dp,
        // which fits a phone held upright and does NOT fit one turned on its side — where the blocks
        // would simply be clipped off the bottom with nothing to say so. Weighted, the column cannot
        // overflow at any height, and tall colour blocks are what an LCARS rail looks like anyway:
        // the decorative one this replaces uses weights from 1.0 to 4.2.
        ctx.group.entries.forEach { entry ->
            val current = entry.route == here
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(if (current) accent else c.raise)
                    // ⚠️ The current screen is NOT clickable. Navigating to where you already are
                    // would push a second copy onto the back stack, so back would then take you to
                    // the same screen — the shape of "the back button is broken".
                    .then(
                        if (current) Modifier
                        else Modifier.clickable {
                            cue(SoundCue.TAP, HapticCue.TAP_CRISP)
                            ctx.onOpen(entry.route)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                RailLabel(
                    entry.label,
                    // Black on a coloured block, which is how LCARS letters one; muted on the
                    // ground-coloured blocks, so the lit one is unmistakably the lit one.
                    if (current) c.void else c.muted,
                    if (current) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }

        // The block that closes the L at the bottom corner. Every LCARS rail ends in one, and without
        // it the column would stop part-way down with black underneath. Deliberately a fraction of an
        // entry's height — it is punctuation, not another target.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(TailWeight)
                .clip(lcarsBlockShape(CornerSweep, LcarsCorner.BottomStart))
                .background(c.raise),
        )
    }
}

/**
 * ⚠️ Sized by measurement, not by eye.
 *
 * JetBrainsMono is monospaced at exactly 0.6 em, so at 9sp with 0.5sp of tracking a character is
 * 5.9dp. Ten of the twenty-nine labels are twelve characters or more and the longest — "Satellites
 * & Asteroids" — is twenty-two, so the column has to hold fourteen characters a line over two lines
 * to fit the whole directory without ellipsis. 96dp less the 5dp padding either side gives exactly
 * that.
 *
 * ⚠️ It is also 23% of a 411dp phone, which is why the two screens that genuinely cannot spare it —
 * the map and the radar scope, both full-bleed instruments — already pass `rail = false` and keep the
 * whole width. They get no column, which is correct: each carries its own controls, and a quarter of
 * the width off a map is a worse trade than one extra tap.
 */
@Composable
private fun RailLabel(text: String, colour: androidx.compose.ui.graphics.Color, weight: FontWeight) {
    Text(
        text.uppercase(),
        fontFamily = JetBrainsMono,
        fontWeight = weight,
        fontSize = 9.sp,
        letterSpacing = 0.5.sp,
        lineHeight = 11.sp,
        color = colour,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 5.dp),
    )
}

/** Wide enough for the longest entry in the directory over two lines — see [RailLabel]. */
val SiblingRailWidth = 96.dp

private val RailGutter = 3.dp
private val GroupBlockHeight = 30.dp
private const val TailWeight = 0.6f
private val CornerSweep = 22.dp
