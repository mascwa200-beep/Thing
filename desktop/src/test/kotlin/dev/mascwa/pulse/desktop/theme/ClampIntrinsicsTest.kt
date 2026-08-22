package dev.mascwa.pulse.desktop.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.desktop.diagnostics.CrashReporter
import dev.mascwa.pulse.desktop.diagnostics.IntrinsicClampWatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The containment for `maxHeight(-N) must be >= than minHeight(0)`, and the proof it reports.
 *
 * ⚠️ **The first test here is the one that makes the rest mean anything.** It reproduces the real
 * fault — the exact message, from real Compose, under `renderComposeScene` — so every later
 * assertion is measured against a case known to fail rather than against a hope. Without it, a
 * clamp test would pass just as happily if the clamp did nothing and nothing had ever thrown.
 *
 * ⚠️ It also **refuted the leading hypothesis** while being written: the `LcarsDataRow` colour tab
 * carries 8 dp of vertical padding, which is exactly 12 px at density 1.5 (the commonest Windows
 * scaling) — a striking coincidence with the reported `-12`. Probed directly, that shape does NOT
 * throw, because `SizeNode` from its `.width(5.dp)` clamps the query first. Recorded here so the
 * next person does not re-chase it.
 */
class ClampIntrinsicsTest {

    @Before fun clean() = IntrinsicClampWatch.resetForTest()

    @After fun tidy() = IntrinsicClampWatch.resetForTest()

    /**
     * Asks its children for an intrinsic width at [at], which is exactly what the framework does
     * internally and exactly where the real fault surfaces.
     */
    private fun askIntrinsicWidth(at: Int, content: @Composable () -> Unit): @Composable () -> Unit = {
        Layout(
            content = content,
            measurePolicy = object : MeasurePolicy {
                override fun MeasureScope.measure(
                    measurables: List<Measurable>,
                    constraints: Constraints,
                ): MeasureResult {
                    measurables.forEach { it.minIntrinsicWidth(at) }
                    val placed = measurables.map { it.measure(constraints) }
                    return layout(constraints.maxWidth, constraints.maxHeight) {
                        placed.forEach { it.place(0, 0) }
                    }
                }
            },
        )
    }

    private fun render(content: @Composable () -> Unit) =
        runCatching {
            renderComposeScene(width = 400, height = 300, density = Density(1.5f)) {
                PulseDesktopTheme { content() }
            }
        }

    @Test
    fun `the real fault reproduces, so the rest of this class is measured against a failing case`() {
        val outcome = render(askIntrinsicWidth(-12) { Box(Modifier) })
        val e = outcome.exceptionOrNull()
        assertNotNull("the negative intrinsic query no longer throws — this class needs rewriting", e)
        assertEquals(
            "maxHeight(-12) must be >= than minHeight(0)",
            e!!.cause?.message ?: e.message,
        )
    }

    @Test
    fun `the clamp turns that exact case into a drawn frame`() {
        val outcome = render(askIntrinsicWidth(-12) { Box(Modifier.clampIntrinsics("probe")) })
        assertNull(
            "the clamp let a negative through: ${outcome.exceptionOrNull()?.message}",
            outcome.exceptionOrNull(),
        )
    }

    /**
     * ⚠️ The half that keeps the clamp honest. Absorbing the fault silently would destroy the only
     * evidence of a producer nobody has identified.
     */
    @Test
    fun `clamping is recorded, naming the site rather than Compose's throw helper`() {
        val dir = Files.createTempDirectory("lcars-clamp").toFile()
        val reporter = CrashReporter(dir)
        IntrinsicClampWatch.install(reporter, "test build")

        render(askIntrinsicWidth(-12) { Box(Modifier.clampIntrinsics("probe")) })

        val entries = reporter.entries()
        assertEquals("exactly one report for one distinct clamp", 1, entries.size)
        val summary = entries.single().summary
        assertTrue("the report should say what was clamped: $summary", summary.contains("-12"))
        assertTrue(
            "the report should not name Compose's throw helper: $summary",
            !summary.contains("throwIllegalArgumentException"),
        )
        val body = reporter.read(entries.single())
        assertTrue("the report should name the clamp site: $body", body.contains("clamp · probe"))
        assertTrue("the report should carry the build: $body", body.contains("test build"))
    }

    /**
     * ⚠️ **Load-bearing, not tidiness.** The clamp runs inside layout, so a layout that clamps once
     * clamps again on every frame afterwards. A report per frame would be a far worse defect than
     * the one being contained.
     */
    @Test
    fun `a second clamp does not write a second report`() {
        val dir = Files.createTempDirectory("lcars-clamp-twice").toFile()
        val reporter = CrashReporter(dir)
        IntrinsicClampWatch.install(reporter, "test build")

        repeat(4) { render(askIntrinsicWidth(-12) { Box(Modifier.clampIntrinsics("probe")) }) }

        assertEquals("one report, however many frames clamped", 1, reporter.entries().size)
    }

    /**
     * Nothing is written before a reporter is installed, so tests, headless renders and any
     * composition before startup cost nothing — but the state is still readable.
     */
    @Test
    fun `with no reporter installed it still notes what happened and writes nothing`() {
        render(askIntrinsicWidth(-12) { Box(Modifier.clampIntrinsics("probe")) })
        val note = IntrinsicClampWatch.last
        assertNotNull("the clamp should still record what it caught in memory", note)
        assertTrue("$note", note!!.contains("probe") && note.contains("-12"))
    }

    /**
     * ⚠️ The clamp must be invisible when nothing is wrong. It sits on `LcarsDataRow`, which appears
     * 28 times across six screens, so a clamp that perturbed ordinary layout would be a regression
     * far bigger than the fault.
     */
    @Test
    fun `a positive query passes through untouched`() {
        val plain = render(askIntrinsicWidth(50) { Box(Modifier) })
        val clamped = render(askIntrinsicWidth(50) { Box(Modifier.clampIntrinsics("probe")) })
        assertNull(plain.exceptionOrNull())
        assertNull(clamped.exceptionOrNull())
        assertNull("nothing was clamped, so nothing should be noted", IntrinsicClampWatch.last)
    }

    /**
     * The shape that looked like the answer and is not — see the class KDoc. Kept as a test so the
     * refutation is durable rather than a sentence someone might doubt.
     */
    @Test
    fun `the LcarsDataRow colour tab does not throw, because its size modifier clamps first`() {
        val outcome = render(
            askIntrinsicWidth(-12) {
                Box(Modifier.width(5.dp).fillMaxHeight().padding(vertical = 4.dp))
            },
        )
        assertNull(
            "the tab shape threw after all, which would make it the leading suspect again",
            outcome.exceptionOrNull(),
        )
    }
}
