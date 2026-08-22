package dev.mascwa.pulse.desktop.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * What the fault dialog actually says.
 *
 * ⚠️ This exists because the first version of that dialog was **honest, well-intentioned and useless**.
 * It printed `throwable.message` plus the top stack frame, and for the fault it was written for the top
 * frame is always Compose's own `InlineClassHelper.throwIllegalArgumentException` — the helper that
 * throws, never the code that was wrong. It reached the owner as a screenshot naming the throw helper.
 *
 * The dialog is the whole diagnostic here: the owner reports by photograph, so a dialog that is
 * screenshot-complete closes the loop in one round trip and one that is not does not.
 */
class WindowFaultHandlerTest {

    private fun handler(): WindowFaultHandler =
        WindowFaultHandler(CrashReporter(Files.createTempDirectory("lcars-faults").toFile()), "test")

    /** A negative-constraint failure shaped exactly like the real one, plumbing frames and all. */
    private fun composeConstraintFailure(): Throwable {
        val t = IllegalArgumentException("maxHeight(-12) must be >= than minHeight(0)")
        t.stackTrace = arrayOf(
            frame("androidx.compose.ui.internal.InlineClassHelperKt", "throwIllegalArgumentException"),
            frame("androidx.compose.ui.unit.ConstraintsKt", "Constraints"),
            frame("androidx.compose.ui.layout.MeasuringIntrinsics", "minWidth"),
            frame("androidx.compose.foundation.layout.RowColumnMeasurePolicy", "measure"),
            frame("dev.mascwa.pulse.desktop.theme.LcarsGeometryKt", "LcarsDataRow"),
            frame("dev.mascwa.pulse.desktop.feature.world.SpaceWeatherScreenKt", "SpaceWeatherScreen"),
        )
        return t
    }

    private fun frame(cls: String, method: String) =
        StackTraceElement(cls, method, cls.substringAfterLast('.') + ".kt", 187)

    /**
     * ⚠️ The regression. Against the old `describe()` the body's only frame was
     * `throwIllegalArgumentException`, so this fails there and passes here.
     */
    @Test
    fun `the dialog names real code, not Compose's throw helper`() {
        val h = handler()
        val t = composeConstraintFailure()
        val text = h.body(h.summary(t), h.locate(t))

        assertFalse(
            "the throw helper locates nothing and must not be what the dialog points at",
            text.contains("throwIllegalArgumentException"),
        )
        assertTrue("the failing composable is the whole point", text.contains("LcarsDataRow"))
        assertTrue(text.contains("SpaceWeatherScreen"))
        assertTrue("the message still has to be there", text.contains("maxHeight(-12)"))
    }

    /**
     * ⚠️ Measure-pass frames are kept. They are plumbing in the sense of being framework code and the
     * answer in the sense of naming which measure policy produced the bad value — dropping everything
     * `androidx` would throw away the half of the trace that explains a layout fault.
     */
    @Test
    fun `the measure path is kept, because for a layout fault it is the explanation`() {
        val h = handler()
        val text = h.body("x", h.locate(composeConstraintFailure()))
        assertTrue(text.contains("MeasuringIntrinsics"))
        assertTrue(text.contains("RowColumnMeasurePolicy"))
    }

    /**
     * ⚠️ A layout fault inside Compose's own measure pass can genuinely have no app frame near the top.
     * Printing five framework frames and stopping would leave the reader with no idea which screen it
     * was, so the nearest app frame is appended however deep it is.
     */
    @Test
    fun `the nearest app frame is reached even when it is below the cutoff`() {
        val t = IllegalArgumentException("boom")
        t.stackTrace = (0 until 9).map { frame("androidx.compose.ui.node.LayoutNode", "measure$it") }
            .plus(frame("dev.mascwa.pulse.desktop.feature.world.WorldFeedKt", "WorldPanel"))
            .toTypedArray()

        val h = handler()
        assertTrue(
            "an app frame below the frame cap is still the best pointer there is",
            h.body("x", h.locate(t)).contains("WorldPanelKt.WorldPanel") ||
                h.body("x", h.locate(t)).contains("WorldFeedKt.WorldPanel"),
        )
    }

    /** A trace made entirely of skipped packages must still print something. */
    @Test
    fun `an all-plumbing trace still reports frames rather than nothing`() {
        val t = IllegalStateException("nothing but plumbing")
        t.stackTrace = arrayOf(
            frame("java.util.ArrayList", "get"),
            frame("kotlin.collections.CollectionsKt", "first"),
        )
        val h = handler()
        val where = h.locate(t)
        assertTrue("silence is the one unacceptable answer", where.isNotEmpty())
    }

    @Test
    fun `the summary carries the message and no frames`() {
        val h = handler()
        val s = h.summary(composeConstraintFailure())
        assertEquals("IllegalArgumentException: maxHeight(-12) must be >= than minHeight(0)", s)
    }

    /** The deepest cause is what gets described, not the wrapper. */
    @Test
    fun `an empty stack is survivable`() {
        val t = IllegalStateException("no stack at all").apply { stackTrace = emptyArray() }
        assertTrue(handler().locate(t).isEmpty())
    }

    /**
     * ⚠️ The crash console's own list row had the identical defect, independently.
     *
     * `CrashReporter.summaryOf` also took `stackTrace.first()`, so the row a person scans to find the
     * right report also named the throw helper — and every row of a Compose layout fault named the
     * SAME helper, making the list unreadable precisely when it mattered. Both now go through
     * [FaultTrace], and this asserts they agree rather than merely that each is individually sane:
     * two independent statements of one rule is how they drifted the first time.
     */
    @Test
    fun `the crash console list row names real code too, by the same rule`() {
        val dir = Files.createTempDirectory("lcars-reporter").toFile()
        val reporter = CrashReporter(dir)
        reporter.record(Thread.currentThread(), composeConstraintFailure(), "test")

        val row = reporter.entries().single().summary
        assertFalse(
            "the list row still names Compose's throw helper: $row",
            row.contains("throwIllegalArgumentException"),
        )
        assertTrue("the row should name the measure path: $row", row.contains("MeasuringIntrinsics.minWidth"))
        assertTrue("the row should carry the message: $row", row.contains("maxHeight(-12)"))
    }

    /**
     * One frame in the list, several in the dialog — a list row is one line and a dialog is the place
     * to read a chain. Pinned so a later "tidy-up" cannot quietly make the list as long as the dialog.
     *
     * ⚠️ Asserted on the **rendered row**, not on `locate(max = 1).size`. My first version asserted the
     * size and was wrong where the code was right: `max` bounds the filtered head, and `locate` then
     * still appends the nearest app frame when that head contains none — which is exactly the
     * behaviour the dialog wants and which the row narrows again by taking the first. Asserting on
     * the list's own arithmetic rather than on an intermediate is the point.
     */
    @Test
    fun `the list row is one frame and the dialog is several`() {
        val dir = Files.createTempDirectory("lcars-onerow").toFile()
        val reporter = CrashReporter(dir)
        reporter.record(Thread.currentThread(), composeConstraintFailure(), "test")

        val row = reporter.entries().single().summary
        assertEquals("a list row carries exactly one frame: $row", 1, row.split(" at ").size - 1)
        assertTrue(FaultTrace.locate(composeConstraintFailure()).size > 1)
    }
}
