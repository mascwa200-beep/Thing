package dev.mascwa.pulse.feature.lockboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.lcarsboard.BoardTap
import dev.mascwa.pulse.feature.lcarsboard.ConsoleButton
import dev.mascwa.pulse.feature.lcarsboard.LcarsBoardPanels
import dev.mascwa.pulse.feature.lcarsboard.LcarsConsole
import dev.mascwa.pulse.feature.lcarsboard.legibleOn
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.widget.WidgetBoard

/**
 * The lock screen as a full LCARS console: solid corner, header and rail, the board's regions in
 * their own panels, and UNLOCK as a solid bar across the bottom.
 *
 * [onTap] is given null for "just unlock" — the UNLOCK bar, or a tap on any empty part of the
 * console, which is the ordinary lock-screen gesture.
 */
@Composable
internal fun LockConsole(
    board: WidgetBoard.Board?,
    progress: () -> Float,
    onTap: (BoardTap?) -> Unit,
) {
    val c = Pulse.colors
    val quiet = remember { MutableInteractionSource() }
    LcarsConsole(
        board = board,
        progress = progress,
        onTap = onTap,
        railSeed = RAIL_SEED,
        waitingLine = if (board == null) WAITING_LINE else null,
        // No ripple on the ground: tapping empty space is a gesture, not a button.
        modifier = Modifier.clickable(interactionSource = quiet, indication = null) { onTap(null) },
        bottom = {
            ConsoleButton(UNLOCK_LABEL, c.accent) { onTap(null) }
        },
        body = { modifier ->
            Column(modifier.verticalScroll(rememberScrollState()).padding(end = 4.dp, bottom = 8.dp)) {
                if (board != null) {
                    LcarsBoardPanels(board, progress, onTap, Modifier.fillMaxWidth())
                } else {
                    Text(
                        WAITING_LINE,
                        fontFamily = JetBrainsMono,
                        fontSize = 12.sp,
                        color = legibleOn(c.muted, c.void),
                    )
                }
            }
        },
    )
}

private const val RAIL_SEED = "lock-console"
private const val UNLOCK_LABEL = "▲  Unlock"
private const val WAITING_LINE = "Reading the board…"
