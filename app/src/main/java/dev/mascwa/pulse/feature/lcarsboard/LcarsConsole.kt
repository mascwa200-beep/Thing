package dev.mascwa.pulse.feature.lcarsboard

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
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
 * ## The top of the screen belongs to the console
 *
 * Both consoles hide Android's status bar ([hideStatusBarForConsole]), so the corner and the header
 * block run all the way to the top edge rather than sitting under a black strip. The time was
 * already in the header, and the battery joins it ([ConsoleBattery]).
 *
 * ⚠️ **The camera cutout is why only the header's CONTENT is padded down.** With the status bar
 * hidden, `safeDrawing` still carries the display cutout — the camera hole — and padding the whole
 * frame by it would leave an empty black band about as tall as the bar just removed. So the frame
 * pads its sides and bottom only, the two top blocks are drawn from the very edge, and the
 * header's clock and words are pushed below the cutout. The hole sits inside the gold, like the
 * lens it is, and no text is ever under it. That works because activity 1.9.3's edge-to-edge sets
 * the cutout mode to ALWAYS (`EdgeToEdgeApi30`, read out of the shipped class).
 *
 * The gesture bar at the bottom is Android's still, and sits on black: the frame pads the bottom
 * inset and paints the ground behind it.
 *
 * When somebody swipes the status bar back into view for a moment, its icons are drawn in the same
 * ink as the header they land on — black on gold, white on the darkest red-alert block.
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
    // The icons of a status bar swiped into view land on the header, so they take its ink.
    val view = LocalView.current
    val darkIcons = textOnBlock(c.accent).luminance() < 0.5f
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkIcons
        }
    }
    val topInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    Column(
        modifier
            .fillMaxSize()
            .background(c.void)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(HeaderHeight + topInset), horizontalArrangement = Arrangement.spacedBy(Gutter)) {
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
                topInset = topInset,
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
