package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.LcarsDataRow
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsScreenFrame
import dev.mascwa.pulse.desktop.theme.PulseDesktopTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import java.nio.file.Files

/**
 * Lays the world-screen chrome out at hostile sizes, with no window and no graphics context.
 *
 * ⚠️ **Why this can exist at all.** `renderComposeScene` composes *and measures and lays out* onto a
 * raster Skia surface, asking for no GL context — the same property that makes the standby display
 * verifiable here (see `HeadlessRenderTest`). Every other desktop render check in this project has
 * had to be deferred to a real machine; a **layout** fault does not, because layout is exactly what
 * this runs. That makes this class the regression gate for a defect that was previously only
 * observable as a photograph of a monitor.
 *
 * ⚠️ **What a failure looks like.** `androidx.compose.ui.unit.Constraints` rejects a negative bound
 * with `maxHeight(-N) must be >= than minHeight(0)`. That message is bare — the framework's own
 * window handler shows it in a Swing box with no stack trace — so the value of running it *here* is
 * the trace, which names the composable.
 *
 * These render deliberately small as well as large. A pane can genuinely be squeezed: the ops wall
 * divides one monitor between six screens, a torn-off window can be dragged to nothing, and a
 * restored window size is read from a settings file with no floor on it.
 */
class WorldPanelLayoutTest {

    private val sizes = listOf(
        1400 to 900,   // the ordinary console
        1000 to 620,
        700 to 400,
        420 to 240,    // roughly an ops-wall cell
        300 to 120,
        240 to 70,     // shorter than the header band plus a row
        200 to 40,
        160 to 12,     // less than the chrome needs, by a lot
        120 to 1,
    )

    /**
     * ⚠️ Density is swept, not fixed at 1.
     *
     * Windows ships at 125% or 150% scaling on most machines, and every `dp` in the kit becomes a
     * different pixel count at each. A layout that survives at 1.0 and fails at 1.5 is invisible to a
     * harness that only ever renders at 1.0 — and the machine that reported this is not running at 1.0.
     */
    private val densities = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

    private fun renderAt(w: Int, h: Int, d: Float, content: @Composable () -> Unit) {
        renderComposeScene(width = w, height = h, density = Density(d)) {
            PulseDesktopTheme { content() }
        }
    }

    private fun sweep(name: String, content: @Composable () -> Unit) {
        for (d in densities) {
            for ((w, h) in sizes) {
                try {
                    renderAt(w, h, d) { content() }
                } catch (e: Throwable) {
                    throw AssertionError("$name threw at ${w}x$h density $d: ${e.message}", e)
                }
            }
        }
    }

    @Test
    fun `the screen frame lays out at every size a pane can be given`() {
        sweep("LcarsScreenFrame") {
            LcarsScreenFrame("Space weather", Modifier.fillMaxSize()) {}
        }
    }

    @Test
    fun `a data row lays out at every size a pane can be given`() {
        sweep("LcarsDataRow in a scroll") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                LcarsDataRow("Planetary Kp", "4.3")
                LcarsDataRow("Storm level", "Quiet")
            }
        }
    }

    /**
     * ⚠️ The same row with **no scroll above it**, so its height is genuinely bounded by the pane.
     *
     * `LcarsDataRow` is the only `IntrinsicSize.Min` in the world screens, and an intrinsic pass is
     * the one unclamped path to a negative `Constraints`. Inside a `verticalScroll` the available
     * height is infinite, which is exactly the case that cannot fail — so testing only that would be
     * a fixture that never reaches the branch.
     */
    @Test
    fun `a data row lays out when the pane bounds its height`() {
        sweep("LcarsDataRow bounded") {
            Column(Modifier.fillMaxSize()) {
                LcarsDataRow("Planetary Kp", "4.3")
                LcarsDataRow("Storm level", "Quiet")
            }
        }
    }

    /**
     * The shell as `App.kt` actually builds it, which is not how the simpler cases above build it.
     *
     * ⚠️ Three differences matter and each is a real one: `rail = false` with a `railWidth` of the
     * directory's own 232.dp (so the header's corner block is that wide), three ghost buttons in the
     * header's `actions` slot, and a fixed-width sibling beside the content in the body row. A
     * reconstruction that omits them is not the thing that failed.
     */
    @Test
    fun `the real two-pane shell lays out at every size`() {
        val dir = Files.createTempDirectory("lcars-shell")
        val settings = DesktopSettingsStore(path = dir.resolve("settings.json"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feed = WorldFeed<String>(scope, settings) { _, _, _ -> Fetched("", fromCache = false) }

        sweep("the two-pane shell") {
            LcarsScreenFrame(
                title = "Space weather",
                seed = "SPACE_WEATHER",
                modifier = Modifier.fillMaxSize(),
                rail = false,
                railWidth = 232.dp,
                actions = {
                    LcarsGhostButton("\u2315 GO TO \u00b7 CTRL+K", onClick = {})
                    LcarsGhostButton("\u29c9 POP OUT", onClick = {})
                    LcarsGhostButton("\u25a6 OPS WALL", onClick = {})
                },
            ) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(232.dp).fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp),
                        ) {
                            WorldPanel(
                                title = "Space weather",
                                feed = feed,
                                state = Async(),
                                located = true,
                            ) { }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the world panel chrome lays out at every size a pane can be given`() {
        val dir = Files.createTempDirectory("lcars-worldpanel")
        val settings = DesktopSettingsStore(path = dir.resolve("settings.json"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Never invoked: the panel is rendered in its empty state on purpose, which is the state the
        // console was actually sitting in when this was reported.
        val feed = WorldFeed<String>(scope, settings) { _, _, _ -> Fetched("", fromCache = false) }

        sweep("WorldPanel chrome") {
            LcarsScreenFrame("Space weather", Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                ) {
                    WorldPanel(
                        title = "Space weather",
                        feed = feed,
                        state = Async(),
                        located = true,
                    ) { }
                }
            }
        }
    }
}
