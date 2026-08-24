package dev.mascwa.pulse.desktop.theme

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every chart in the kit, drawn at every shape a pane can give it.
 *
 * ## ⚠️ Why this exists, and why it is a DRAW test rather than a layout one
 *
 * The Windows console froze on SPACE WEATHER for three sessions with
 * `IllegalArgumentException: maxHeight(-12) must be >= than minHeight(0)`. Four separate hunts —
 * a bytecode census of every intrinsic-width query in the shipped Compose stack, a sweep of the
 * real two-pane shell at 225 size/density combinations, an arrangement-spacing investigation, and
 * an audit of window sizing — all came back empty, because **every one of them searched layout**.
 *
 * The throw was in the **draw** phase. `drawText` with the default `size = Size.Unspecified` builds
 * `Constraints(0, ceil(canvasWidth - left), 0, ceil(canvasHeight - top))`, so a text top-left past
 * the far edge of its canvas produces a negative maximum and the constraint factory rejects it.
 * `LcarsGauge` sized its square dial from the canvas **width**, so in a box wider than it is tall —
 * which is every call site — the caption was positioned below the bottom of the canvas.
 *
 * That failure mode is invisible to a layout sweep AND to the eye: layout completes, so the window
 * keeps presenting the last frame it managed to paint and simply stops updating. It looks exactly
 * like a screen that never loaded, which is precisely how it was reported.
 *
 * So: the guard has to render, and it has to render at shapes nobody would choose on purpose.
 *
 * ## What the matrix is for
 *
 * Wide-and-short is the shape that breaks a width-derived square, tall-and-narrow is the shape that
 * breaks a height-derived one, and the tiny sizes stand in for a pane mid-drag. Density is swept
 * because Windows ships at 125–150% scaling and a `dp` is a different pixel count at each — a
 * harness pinned at 1.0 has a blind spot exactly where the reporting machine lives.
 */
class LcarsChartsRenderTest {

    /** The real failing configuration, kept exact so a regression reproduces the owner's report. */
    private val spaceWeatherGauge = 140 to 110

    private val shapes = listOf(
        spaceWeatherGauge,
        300 to 40,    // a wide strip
        40 to 300,    // a tall gutter
        200 to 200,   // square
        24 to 24,     // absurdly small, as a pane mid-drag
        600 to 90,    // full-width banner
    )

    private val densities = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    private fun sweep(name: String, content: @Composable (Modifier) -> Unit) {
        for ((w, h) in shapes) {
            for (d in densities) {
                val outcome = runCatching {
                    // The scene is deliberately larger than the box, so what is being tested is the
                    // chart's own arithmetic and not the scene clipping it out of existence.
                    renderComposeScene(width = 800, height = 600, density = Density(d)) {
                        PulseDesktopTheme {
                            content(Modifier.width(w.dp).height(h.dp))
                        }
                    }
                }
                val e = outcome.exceptionOrNull()
                assertNull(
                    "$name threw at ${w}x${h} dp, density $d: " +
                        generateSequence(e) { it.cause }.lastOrNull()?.message,
                    e,
                )
            }
        }
    }

    @Test
    fun `the gauge draws at every shape, captioned`() {
        // ⚠️ The caption is the part that broke. `label = null` was clean throughout, so a sweep
        // that omitted it would have passed against the shipped defect.
        sweep("LcarsGauge") { m -> LcarsGauge(4.3, 0.0, 9.0, m, label = "Kp") }
    }

    @Test
    fun `the gauge draws at every shape with bands and a unit`() {
        sweep("LcarsGauge banded") { m ->
            LcarsGauge(
                7.1, 0.0, 9.0, m,
                bands = listOf(ChartBand(5.0, 7.0, Color.Yellow), ChartBand(7.0, 9.0, Color.Red)),
                label = "Planetary Kp", unit = " nT",
            )
        }
    }

    @Test
    fun `the time chart draws at every shape`() {
        val points = (0..40).map { (it * 300_000L) to (it % 9).toDouble() }
        sweep("LcarsTimeChart") { m ->
            LcarsTimeChart(listOf(ChartSeries("Kp", points, Color.Cyan, filled = true)), m)
        }
    }

    @Test
    fun `the histogram draws at every shape`() {
        sweep("LcarsHistogram") { m ->
            LcarsHistogram(listOf("A" to 3.0, "B" to 12.0, "C" to 0.0, "M" to 7.5), m)
        }
    }

    @Test
    fun `the sky plot draws at every shape`() {
        sweep("LcarsSkyPlot") { m ->
            LcarsSkyPlot(
                listOf(
                    SkyPoint(azimuthDeg = 130.0, altitudeDeg = 42.0, label = "Sun", color = Color.Yellow),
                    SkyPoint(azimuthDeg = 300.0, altitudeDeg = -5.0, label = "Moon", color = Color.White),
                ),
                m,
            )
        }
    }

    @Test
    fun `the meter draws at every shape`() {
        sweep("LcarsMeter") { m ->
            LcarsMeter(3.4, 0.0, 9.0, m, bands = listOf(ChartBand(5.0, 9.0, Color.Yellow)))
        }
    }

    @Test
    fun `the sparkline draws at every shape`() {
        sweep("LcarsSparkline") { m ->
            LcarsSparkline(listOf(1.0, 4.0, 2.0, 9.0, 3.0), Color.Cyan, m)
        }
    }

    /**
     * A chart handed nothing at all is a real state — every feed here starts empty — and an empty
     * series is a different code path from a populated one (no ticks, no extent, division by a zero
     * span). Squares only; the shapes above already cover the geometry.
     */
    @Test
    fun `an empty chart draws rather than throwing`() {
        for (d in densities) {
            val outcome = runCatching {
                renderComposeScene(width = 400, height = 300, density = Density(d)) {
                    PulseDesktopTheme {
                        LcarsTimeChart(emptyList(), Modifier.size(200.dp, 120.dp))
                        LcarsHistogram(emptyList(), Modifier.size(200.dp, 120.dp))
                        LcarsSkyPlot(emptyList(), Modifier.size(200.dp, 120.dp))
                        LcarsSparkline(emptyList(), Color.Cyan, Modifier.size(200.dp, 40.dp))
                        LcarsGauge(null, 0.0, 9.0, Modifier.size(140.dp, 110.dp), label = "Kp")
                        LcarsMeter(null, 0.0, 9.0, Modifier.size(200.dp, 40.dp))
                    }
                }
            }
            assertNull(
                "an empty chart threw at density $d: " +
                    generateSequence(outcome.exceptionOrNull()) { it.cause }.lastOrNull()?.message,
                outcome.exceptionOrNull(),
            )
        }
    }
}
