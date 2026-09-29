package dev.mascwa.pulse.feature.lcarsboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsRail
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.Antonio
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.widget.WidgetBoard

/**
 * A whole LCARS screen, solid from edge to edge: the corner and header bar across the top, the rail
 * of blocks down the side, the body beside it, and a bar of controls across the bottom.
 *
 * ⚠️ **Nothing overlaps anything.** Every part has its own place in the layout — no decoration is
 * drawn over text, and the body is the only part that scrolls, so a long board is scrolled to rather
 * than cut off.
 *
 * ⚠️ **The system's own status bar and gesture bar sit on black, never on an LCARS colour.** The
 * frame pads itself inside the safe-drawing insets and paints the ground black behind them, so the
 * clock, the battery and the notification icons Android draws up there keep the contrast Android
 * gave them. The header block starts BELOW the status bar, not behind it.
 *
 * Shared by the lock console and the home screen, so the two are one console.
 */
@Composable
internal fun LcarsConsole(
    board: WidgetBoard.Board?,
    progress: () -> Float,
    onTap: (BoardTap) -> Unit,
    railSeed: String,
    modifier: Modifier = Modifier,
    waitingLine: String? = null,
    bottom: @Composable RowScope.() -> Unit,
    body: @Composable (Modifier) -> Unit,
) {
    val c = Pulse.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.void)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(HeaderHeight), horizontalArrangement = Arrangement.spacedBy(Gutter)) {
            Box(
                Modifier
                    .width(RailWidth)
                    .fillMaxHeight()
                    .sweep(progress, ELEMENT_CORNER)
                    .clip(lcarsBlockShape(CornerCut, LcarsCorner.TopStart))
                    .background(c.accent),
            )
            LcarsBoardHeader(
                board = board,
                block = c.accent,
                onTap = onTap,
                waitingLine = waitingLine,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .sweep(progress, ELEMENT_HEADER)
                    .clip(lcarsBlockShape(CornerCut, LcarsCorner.TopEnd)),
            )
        }
        Spacer(Modifier.height(Gutter))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            LcarsRail(
                railSeed,
                Modifier
                    .width(RailWidth)
                    .fillMaxHeight()
                    .sweep(progress, ELEMENT_RAIL, vertical = true),
            )
            Spacer(Modifier.width(10.dp))
            body(Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(Gutter))
        Row(
            Modifier.fillMaxWidth().height(BottomHeight).sweep(progress, ELEMENT_RAIL),
            horizontalArrangement = Arrangement.spacedBy(Gutter),
            content = bottom,
        )
    }
}

/**
 * A solid LCARS control: a filled capsule, lettered in whichever of black or white reads on it.
 *
 * Solid on purpose. The in-app kit's `LcarsButton` is an outline — right inside the app, and exactly
 * the look this console was asked not to be.
 */
@Composable
internal fun RowScope.ConsoleButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    val cue = rememberLcarsCue()
    Box(
        modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(RoundedCornerShape(percent = 50))
            .background(color)
            .clickable { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            fontFamily = Antonio,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            letterSpacing = 2.sp,
            color = textOnBlock(color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val RailWidth = 56.dp
private val HeaderHeight = 76.dp
private val BottomHeight = 52.dp
private val Gutter = 4.dp
private val CornerCut = 24.dp
