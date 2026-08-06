package dev.mascwa.pulse.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.desktop.theme.LcarsCorner
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse
import dev.mascwa.pulse.desktop.theme.PulseDesktopTheme

/** The desktop shell's root. Genuinely minimal on purpose — Phase A's job is standing up the module, the
 *  ported LCARS kit and the settings store, not a real screen; News (Phase B) is the first full vertical
 *  built on top of this. */
@Composable
fun PulseDesktopApp() {
    PulseDesktopTheme {
        val c = Pulse.colors
        Surface(color = c.void) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LcarsHeaderBar("LCARS — DESKTOP", trailing = "FOUNDATION")
                LcarsFrame(corner = LcarsCorner.TopStart) {
                    Text(
                        "Foundation online — palette, shape kit and settings store are wired. " +
                            "News (Phase B) is the first real screen, not yet built.",
                        color = c.ink,
                    )
                }
            }
        }
    }
}
