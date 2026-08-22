package dev.mascwa.pulse.desktop.standby

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.collectAsState
import dev.mascwa.pulse.desktop.theme.PulseDesktopTheme
import java.io.File

/**
 * Rung C — the standby display as an always-on-top panel, and the screensaver window.
 */

/**
 * The HUD: the console's standby display, kept in front of whatever else is on the desktop.
 *
 * ⚠️ **Undecorated but not transparent.** Transparency on a Compose Desktop window is supported and
 * would buy non-rectangular corners, but the display paints its own opaque background anyway, so it
 * would buy nothing at the cost of a window property that behaves differently across window
 * managers. Dragging is by the whole surface rather than a title bar, which is what an undecorated
 * panel has instead of one.
 */
@Composable
fun StandbyHudWindow(session: StandbySession, onClose: () -> Unit) {
    val state by session.state.collectAsState()
    val windowState = rememberWindowState(
        size = DpSize(HUD_W.dp, HUD_H.dp),
        position = WindowPosition(Alignment.TopEnd),
    )
    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "LCARS — standby",
        undecorated = true,
        alwaysOnTop = true,
        resizable = true,
    ) {
        PulseDesktopTheme {
            // ⚠️ The whole panel is the drag handle. An undecorated window has no title bar, so
            // without this it can be placed once and never moved — and a panel pinned over whatever
            // you are working on that cannot be moved is worse than no panel.
            WindowDraggableArea(Modifier.fillMaxSize()) {
                StandbyDisplay(
                    state,
                    StandbyLayout.forCanvas(
                        windowState.size.width.value.toInt(),
                        windowState.size.height.value.toInt(),
                    ),
                    Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * The screensaver: the last drawn standby picture, full screen, dismissed by anything at all.
 *
 * ⚠️ **It shows the PNG the session already rendered rather than composing afresh.** A screensaver
 * has to appear the instant the machine goes idle, and this runs as its own short-lived process —
 * one that started an HTTP client and six repositories before drawing anything would be visibly
 * late. The picture carries its own age in the display's freshness line, so a saver showing a
 * five-minute-old console says so.
 */
@Composable
fun StandbyScreenSaverWindow(picture: File, onExit: () -> Unit) {
    val windowState = rememberWindowState(placement = WindowPlacement.Fullscreen)
    Window(
        onCloseRequest = onExit,
        state = windowState,
        title = "LCARS",
        undecorated = true,
        alwaysOnTop = true,
        // Any key at all dismisses it — that is the screensaver convention, and returning true
        // consumes the event so nothing else acts on the keystroke that woke the machine.
        onKeyEvent = { onExit(); true },
    ) {
        PulseDesktopTheme {
            val bitmap = runCatching {
                picture.takeIf { it.isFile }?.inputStream()?.use(::loadImageBitmap)
            }.getOrNull()

            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        // Fit, not Crop: a standby display that has had its edges cut off to fill a
                        // differently-shaped screen has lost rows, and the black margin is honest.
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    // Nothing has been drawn yet — a machine that has only just had the saver
                    // switched on. An empty console beats a blank screen with no explanation.
                    StandbyDisplay(
                        StandbyState(briefing = "The console has not drawn a standby picture yet."),
                        StandbyLayout.forCanvas(1920, 1080),
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** The HUD's opening size — the canvas the display was laid out at. */
const val HUD_W = 460
const val HUD_H = 620
