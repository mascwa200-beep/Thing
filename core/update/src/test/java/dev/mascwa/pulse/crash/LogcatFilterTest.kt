package dev.mascwa.pulse.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **Run over a real dump off the owner's phone, not a fixture shaped to pass.**
 * `real-report-1787195884334.log` is the logcat section of report `1787195884334-crash.md` on the
 * `debug-reports` branch, verbatim: a Pixel 10 Pro XL on 2026-08-19, build #1866, three pids, and a
 * `FATAL EXCEPTION` belonging to a launch that had already ended by the time the report was sent.
 *
 * That is the whole reason the filter exists, and the reason it keeps warnings and errors from every
 * launch rather than only the current one: the crash is pid 5178 and the reporter is pid 7549. A
 * filter scoped to "this process" would have thrown the crash away and kept the location-service
 * chatter that came after it.
 */
class LogcatFilterTest {

    /** The pid the report was SENT from — the later launch, not the one that crashed. */
    private val reportingPid = 7549

    private val real: String by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("real-report-1787195884334.log")
        requireNotNull(stream) { "the real dump is missing from test resources" }
            .bufferedReader().use { it.readText() }
    }

    // ------------------------------------------------------------------------- over the real dump

    @Test
    fun `the fixture really is the dump this claims to be`() {
        // ⚠️ Guards everything below: assertions over a fixture that quietly stopped being the real
        // report would all still pass while checking nothing of interest.
        assertTrue("the dump should be substantial", real.length > 4_000)
        val pids = LogcatFilter.parse(real).map { it.pid }.toSet()
        assertEquals("three launches, which is the point of it", setOf(24811, 5178, 7549), pids)
        assertTrue("the crash must be in here", real.contains("FATAL EXCEPTION"))
    }

    @Test
    fun `the crash survives, and it is from a launch that had already ended`() {
        val out = LogcatFilter.report(real, reportingPid)
        assertTrue("the fatal line was dropped", out.contains("FATAL EXCEPTION"))
        assertTrue(
            "the real cause was dropped",
            out.contains("Key \"search\" was already used"),
        )
        assertTrue("its frames were dropped", out.contains("androidx.compose.foundation.gestures"))
        assertTrue(
            "a line from an earlier launch must say so",
            out.contains("an earlier launch (pid 5178)"),
        )
    }

    @Test
    fun `the keyboard chatter is set aside, and counted rather than hidden`() {
        val out = LogcatFilter.report(real, reportingPid)
        // 40 of the 58 lines in this dump are ImeTracker and InsetsController; none of them belongs
        // to the reporting pid, so what this really pins is that the recent-tail section is not
        // simply the tail.
        assertFalse("ImeTracker reached the recent section", out.substringAfter("### This run").contains("ImeTracker"))
        assertTrue("nothing may be dropped silently", out.contains("routine framework line"))
    }

    @Test
    fun `a line this app's own code printed is kept`() {
        // The one WARNING from the reporting launch — an SELinux denial against `RenderThread`. It is
        // level W, so the loud section keeps it whatever its tag.
        val out = LogcatFilter.report(real, reportingPid)
        assertTrue("the app's own warning was lost", out.contains("RenderThread"))
    }

    @Test
    fun `it fits inside the budget it is given`() {
        // ⚠️ A budget that is quietly exceeded would be the same defect as no budget: the uploader
        // puts this into a report with a fault and breadcrumbs beside it.
        val out = LogcatFilter.report(real, reportingPid, budget = 1_200)
        assertTrue("the budget was blown: ${out.length}", out.length < 2_400)
        assertTrue("the crash is what must survive a squeeze", out.contains("FATAL EXCEPTION"))
    }

    @Test
    fun `the newest trouble is what survives a squeeze, not the oldest`() {
        // Trimmed from the FRONT, so the most recent lines are the ones kept.
        val loud = (1..40).joinToString("\n") {
            "08-19 21:05:%02d.000  5178  5178 E Thing: error number %d".format(it, it)
        }
        val out = LogcatFilter.report(loud, 5178, budget = 400)
        assertTrue("the newest error was trimmed away", out.contains("error number 40"))
        assertFalse("the oldest error should have gone first", out.contains("error number 1 "))
    }

    // ------------------------------------------------------------------------------ the mechanics

    @Test
    fun `a multi-line message stays with the record it belongs to`() {
        // ⚠️ Defensive rather than load-bearing — see the note on `LogcatFilter`. `AndroidRuntime`
        // gives every frame its own prefix, so the real dump above needs none of this. What it
        // handles is anything printing several lines in one call.
        val dump = """
            08-19 21:05:39.169  5178  5178 E Thing: something went wrong
            	caused by: a second line with no prefix at all
            	and a third
            08-19 21:05:40.000  5178  5178 I Thing: after
        """.trimIndent()
        val entries = LogcatFilter.parse(dump)
        assertEquals(2, entries.size)
        assertEquals(3, entries[0].lines.size)
        assertTrue(entries[0].text.contains("and a third"))
    }

    @Test
    fun `a separator line belongs to no record`() {
        val dump = """
            --------- beginning of crash
            08-19 21:05:39.169  5178  5178 E Thing: boom
        """.trimIndent()
        val entries = LogcatFilter.parse(dump)
        assertEquals(1, entries.size)
        assertEquals(1, entries.single().lines.size)
    }

    @Test
    fun `an orphaned continuation before the window is dropped rather than guessed at`() {
        val entries = LogcatFilter.parse("\tat some.Frame(File.kt:1)\n")
        assertEquals(emptyList<LogcatFilter.Entry>(), entries)
    }

    @Test
    fun `an empty buffer says so rather than pretending`() {
        assertTrue(LogcatFilter.report("", 1).contains("nothing in the log buffer"))
    }

    @Test
    fun `a chattering tag with an instance in it is still recognised`() {
        // ⚠️ `VRI[MainActivity]@c2a7eee` is a different string every launch, which is why the list is
        // matched as prefixes. An exact list would have let the single commonest tag straight
        // through — it was 78 of the 254 lines counted.
        val dump = "08-19 21:05:39.169  5178  5178 D VRI[MainActivity]@c2a7eee: relayout\n" +
            "08-19 21:05:40.000  5178  5178 D Thing: a line worth keeping\n"
        val out = LogcatFilter.report(dump, 5178)
        assertFalse("the instance-tagged chatter got through", out.contains("relayout"))
        assertTrue(out.contains("a line worth keeping"))
    }

    @Test
    fun `a framework complaint is kept whatever its tag`() {
        // ⚠️ The chatter list only ever screens the quiet tail. A window leak or an abandoned surface
        // comes from exactly these tags and is a real finding.
        val dump = "08-19 21:05:39.169  5178  5178 E ViewRootImpl: your window leaked\n"
        assertTrue(LogcatFilter.report(dump, 5178).contains("your window leaked"))
    }
}
