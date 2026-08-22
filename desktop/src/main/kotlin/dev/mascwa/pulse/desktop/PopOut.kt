package dev.mascwa.pulse.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.mascwa.pulse.desktop.settings.LocalUnits
import dev.mascwa.pulse.desktop.settings.UnitPrefs
import dev.mascwa.pulse.desktop.theme.LcarsScreenFrame
import dev.mascwa.pulse.desktop.theme.LocalConsoleSection
import dev.mascwa.pulse.desktop.theme.ProvideStardate
import dev.mascwa.pulse.desktop.theme.Pulse
import dev.mascwa.pulse.desktop.theme.PulseDesktopTheme

/**
 * Screens torn off into windows of their own.
 *
 * ⚠️ **This is what a desktop is for and a phone cannot do at all**, which is why it leads this
 * batch. A second monitor showing the map, or the markets, or the advisories, while you work in the
 * main window is the difference between a program and a ported app — and it needs no new rendering:
 * a torn-off window draws through [ScreenHost], so it is the same screen, reading the same view
 * models, backed by the same repositories with the same caches and rate gates.
 *
 * Composed from inside the main window's content, which Compose Desktop allows: `Window` takes no
 * application scope, so a window can be declared anywhere in the composition and lives exactly as
 * long as the state that declares it. That is what keeps the view models shared without hoisting the
 * whole dependency graph out of the shell.
 */
@Composable
fun PopOutWindows(
    open: List<Screen>,
    vms: DeskViewModels,
    units: UnitPrefs,
    /** Which window to raise, and a tick that changes each time it is asked for. */
    raise: Screen?,
    raiseTick: Int,
    onClose: (Screen) -> Unit,
    onOpenGuide: (String) -> Unit,
    onStudyGuide: (String) -> Unit,
) {
    open.forEach { torn ->
        // ⚠️ Keyed on the screen it was torn off as, so each window keeps its own size, position and
        // internal navigation across recompositions — and closing one cannot shuffle another's state
        // onto the wrong window.
        key(torn) {
            PopOutWindow(
                torn = torn,
                vms = vms,
                units = units,
                raiseTick = if (raise == torn) raiseTick else -1,
                onClose = { onClose(torn) },
                onOpenGuide = onOpenGuide,
                onStudyGuide = onStudyGuide,
            )
        }
    }
}

@Composable
private fun PopOutWindow(
    torn: Screen,
    vms: DeskViewModels,
    units: UnitPrefs,
    raiseTick: Int,
    onClose: () -> Unit,
    onOpenGuide: (String) -> Unit,
    onStudyGuide: (String) -> Unit,
) {
    // ⚠️ A torn-off window navigates ITSELF, and that is not a nicety. HOME and ADVISORIES both carry
    // links that go somewhere — the whole point of an advisory is being able to act on it — and a
    // window whose links did nothing would ship a dead button, while one that reached over and
    // rearranged the MAIN window would be the opposite of what tearing a screen off is for.
    var shown by remember { mutableStateOf(torn) }
    val entry = DESK_ENTRIES[shown]
    val state = rememberWindowState(
        // ⚠️ Platform default, not a computed offset. Windows cascades new windows itself and knows
        // about the taskbar, DPI and where the other monitors are; anything computed here would stack
        // every torn-off window in one place on exactly the multi-monitor setup this exists for.
        position = WindowPosition.PlatformDefault,
        size = DpSize(POPOUT_WIDTH, POPOUT_HEIGHT),
    )
    Window(
        onCloseRequest = onClose,
        state = state,
        title = "LCARS · ${entry?.label ?: shown.name}",
    ) {
        // Raising goes through the AWT frame rather than window state, because `WindowState` has no
        // notion of focus — it holds size, position and placement, and nothing else. `toFront` is the
        // plainest thing a frame does and it is what the platform's window management expects.
        LaunchedEffect(raiseTick) {
            if (raiseTick >= 0) {
                window.toFront()
                window.requestFocus()
            }
        }
        // ⚠️ The theme and the locals are provided again rather than relied upon to reach here. A
        // window's content is its own composition, and whether locals cross that boundary is not
        // something this module can prove without a screen to look at — so it is stated rather than
        // assumed. One object per window, and the question goes away.
        PulseDesktopTheme {
            CompositionLocalProvider(
                LocalConsoleSection provides (DESK_SECTION[shown] ?: ""),
                LocalUnits provides units,
            ) {
                ProvideStardate {
                    Surface(color = Pulse.colors.void) {
                        LcarsScreenFrame(
                            title = entry?.label ?: "LCARS",
                            seed = shown.name,
                            // Back to what the window was torn off as — the only "back" that means
                            // anything here, since this window has no directory to return to.
                            onBack = if (shown != torn) ({ shown = torn }) else null,
                        ) {
                            ScreenHost(
                                screen = shown,
                                vms = vms,
                                onOpenScreen = { target ->
                                    // Belt and braces, the same shape as `screenForRoute`'s null: LIVE
                                    // owns a native surface and its own detached window, and nothing
                                    // that navigates can currently reach it anyway.
                                    if (canPopOut(target)) shown = target
                                },
                                onOpenGuide = onOpenGuide,
                                onStudyGuide = onStudyGuide,
                                // Handing over to an installer from a torn-off window would quit the
                                // whole program out from under the main one. ABOUT lives in the main
                                // window, where quitting is the reader's own deliberate act.
                                onQuitForInstall = { },
                                modifier = Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * ⚠️ Taller than it is wide, because every screen here is a vertical list or a stack of charts —
 * height is what a torn-off window actually needs. It still sits comfortably beside a maximised main
 * window on a single monitor rather than only making sense on two.
 */
private val POPOUT_WIDTH = 720.dp
private val POPOUT_HEIGHT = 880.dp
