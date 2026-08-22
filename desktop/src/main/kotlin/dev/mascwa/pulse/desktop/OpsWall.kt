package dev.mascwa.pulse.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import dev.mascwa.pulse.desktop.settings.LocalUnits
import dev.mascwa.pulse.desktop.settings.UnitPrefs
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsCorner
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LocalConsoleSection
import dev.mascwa.pulse.desktop.theme.Orbitron
import dev.mascwa.pulse.desktop.theme.ProvideStardate
import dev.mascwa.pulse.desktop.theme.Pulse
import dev.mascwa.pulse.desktop.theme.PulseDesktopTheme
import dev.mascwa.pulse.desktop.theme.lcarsBlockShape

/**
 * The ops wall: several instruments at once, filling a monitor.
 *
 * ⚠️ **It is the existing screens in a grid, not a set of new miniature widgets, and that is a design
 * decision rather than a shortcut.** Purpose-built compact tiles would be a second rendering of every
 * feed — a second place for a number to be formatted, a second thing to keep current, and a second
 * opportunity for the wall and the page to disagree about what the market did. Reusing [ScreenHost]
 * means a cell IS the screen: the same view model, the same repository, the same figure.
 *
 * The honest cost is density. A cell is a third of a monitor, and these screens were laid out for a
 * whole one, so a cell shows the top of its list and scrolls for the rest. That is the right trade for
 * a wall you glance at: the top of each of these screens is the part worth glancing at.
 *
 * ⚠️ Nothing here starts anything. Every cell reads a view model that was already running because the
 * main window built it, so raising the wall costs a redraw and no fetch.
 */
@Composable
fun OpsWallWindow(
    wall: List<Screen>,
    vms: DeskViewModels,
    units: UnitPrefs,
    onClose: () -> Unit,
    onSetWall: (List<Screen>) -> Unit,
    onOpenGuide: (String) -> Unit,
    onStudyGuide: (String) -> Unit,
) {
    val state = rememberWindowState(placement = WindowPlacement.Fullscreen)
    Window(onCloseRequest = onClose, state = state, title = "LCARS · OPS WALL") {
        PulseDesktopTheme {
            CompositionLocalProvider(
                LocalConsoleSection provides "OPS WALL",
                LocalUnits provides units,
            ) {
                ProvideStardate {
                    Surface(color = Pulse.colors.void) {
                        OpsWallBody(wall, vms, onClose, onSetWall, onOpenGuide, onStudyGuide)
                    }
                }
            }
        }
    }
}

@Composable
private fun OpsWallBody(
    wall: List<Screen>,
    vms: DeskViewModels,
    onClose: () -> Unit,
    onSetWall: (List<Screen>) -> Unit,
    onOpenGuide: (String) -> Unit,
    onStudyGuide: (String) -> Unit,
) {
    val c = Pulse.colors
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().height(30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "OPS WALL",
                fontFamily = Orbitron, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, letterSpacing = 3.sp, color = c.accent,
            )
            Text(
                // Fullscreen hides the title bar, so the way out has to be on the page. Saying it in
                // words costs a line and removes the one question a full-screen mode always raises.
                "Esc or CLOSE to leave",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
            )
            Box(Modifier.weight(1f))
            LcarsGhostButton(if (picking) "DONE" else "CHOOSE", onClick = { picking = !picking })
            LcarsGhostButton("CLOSE", onClick = onClose)
        }

        if (picking) {
            WallPicker(wall, onSetWall)
        }

        if (wall.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing on the wall. CHOOSE what to put on it.",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                )
            }
            return@Column
        }

        // ⚠️ Rows of at most [PER_ROW], laid out by weight rather than a fixed cell size, so the wall
        // fills whatever monitor it is on. A fixed size would leave a band of empty ground on a wide
        // screen and clip on a small one.
        wall.chunked(PER_ROW).forEach { row ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { s ->
                    key(s) {
                        WallCell(s, vms, onOpenGuide, onStudyGuide, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                // Keeps the last row's cells the same width as the rows above rather than stretching
                // two cells across a whole monitor when the wall holds five.
                repeat(PER_ROW - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun WallCell(
    screen: Screen,
    vms: DeskViewModels,
    onOpenGuide: (String) -> Unit,
    onStudyGuide: (String) -> Unit,
    modifier: Modifier,
) {
    val c = Pulse.colors
    val entry = DESK_ENTRIES[screen]
    Column(modifier.clip(lcarsBlockShape(16.dp, LcarsCorner.TopStart)).background(c.raise)) {
        Text(
            entry?.label?.uppercase() ?: screen.name,
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
            fontSize = 11.sp, letterSpacing = 1.5.sp, color = c.accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 4.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth().background(c.void)) {
            ScreenHost(
                screen = screen,
                vms = vms,
                // ⚠️ A cell does not navigate. The wall is a set of instruments someone arranged; a
                // link that swapped one of them for something else would rearrange a layout the reader
                // chose, and there is no way back to it from inside a cell. The links that would do
                // this live on HOME and ADVISORIES and keep working in the main window.
                onOpenScreen = { },
                onOpenGuide = onOpenGuide,
                onStudyGuide = onStudyGuide,
                onQuitForInstall = { },
            )
        }
    }
}

@Composable
private fun WallPicker(wall: List<Screen>, onSetWall: (List<Screen>) -> Unit) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DESK_GROUPS.flatMap { it.entries }.filter { canPutOnWall(it.screen) }.forEach { e ->
            val on = e.screen in wall
            Text(
                e.label.uppercase(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 10.sp, letterSpacing = 1.sp,
                color = if (on) c.void else c.ink,
                modifier = Modifier
                    .clip(lcarsBlockShape(12.dp, LcarsCorner.TopStart))
                    .background(if (on) c.accent else c.raise)
                    .clickable {
                        // ⚠️ Appended rather than inserted in directory order: the order of the wall is
                        // the order of the cells, and someone choosing a fourth instrument means "put
                        // it in the empty corner", not "reshuffle the three I am watching".
                        onSetWall(if (on) wall - e.screen else (wall + e.screen).take(MAX_CELLS))
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Whether [screen] can be a cell on the wall.
 *
 * The same rule as tearing off, for the same reason — see [canPopOut] — plus the two screens that are
 * about this program rather than about the world. An installer button and a crash log are things you
 * go and look at; putting them on a wall you glance at would be filling a monitor with furniture.
 */
fun canPutOnWall(screen: Screen): Boolean =
    canPopOut(screen) && screen != Screen.ABOUT && screen != Screen.SETTINGS

/** Three across is the most that leaves a list readable at a third of a monitor's width. */
private const val PER_ROW = 3

/** Two rows of three. Past that a cell is too short to show anything above its own header. */
private const val MAX_CELLS = 6
