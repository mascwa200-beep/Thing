package dev.mascwa.pulse.desktop.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The reporter writes to real files, which is the only way it could work at the moment a program is
 * failing — so this exercises real files too, in a temporary directory.
 */
class CrashReporterTest {

    private lateinit var root: File
    private lateinit var reporter: CrashReporter

    @Before
    fun setUp() {
        root = Files.createTempDirectory("crashtest").toFile()
        reporter = CrashReporter(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun record(t: Throwable, atMs: Long) =
        reporter.record(Thread.currentThread(), t, "1.0.0 (test)", atMs)

    @Test
    fun aRecordedFaultCanBeReadBack() {
        record(IllegalStateException("the panel gave up"), 1_700_000_000_000L)

        val entries = reporter.entries()
        assertEquals(1, entries.size)
        assertTrue(entries[0].summary.contains("IllegalStateException"))
        assertTrue(entries[0].summary.contains("the panel gave up"))
        assertEquals(1_700_000_000_000L, entries[0].timeMs)

        val text = reporter.read(entries[0])
        assertTrue("the build label belongs in the report", text.contains("1.0.0 (test)"))
        assertTrue("and so does the machine", text.contains("machine: "))
        assertTrue("and the stack trace itself", text.contains("IllegalStateException"))
    }

    /**
     * ⚠️ The deepest cause, not the outermost wrapper.
     *
     * A wrapper is almost always something generic — "failed to compose", "invocation target" — so a
     * list showing wrappers would show the same line for every unrelated fault, which is the same as
     * showing nothing.
     */
    @Test
    fun theSummaryNamesTheRealCauseRatherThanItsWrapper() {
        val deep = ArithmeticException("divide by zero")
        val wrapped = RuntimeException("failed to compose", IllegalStateException("while drawing", deep))
        record(wrapped, 1_700_000_000_000L)

        val summary = reporter.entries().single().summary
        assertTrue("got: $summary", summary.startsWith("ArithmeticException: divide by zero"))
    }

    /**
     * ⚠️ A cause chain that points back at itself is a real thing some libraries produce, and walking
     * it without a guard hangs the program — while it is already failing.
     */
    @Test(timeout = 5_000)
    fun aSelfReferencingCauseChainDoesNotHang() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        record(b, 1_700_000_000_000L)
        assertEquals(1, reporter.entries().size)
    }

    /** Newest first — the fault being chased is nearly always the last one. */
    @Test
    fun entriesAreNewestFirst() {
        record(RuntimeException("older"), 1_700_000_000_000L)
        record(RuntimeException("newer"), 1_700_000_090_000L)
        val summaries = reporter.entries().map { it.summary }
        assertTrue(summaries[0].contains("newer"))
        assertTrue(summaries[1].contains("older"))
    }

    /**
     * ⚠️ Two faults in the same millisecond must both survive.
     *
     * One failure commonly brings down several threads at once, and a name derived from the instant
     * alone would have the second overwrite the first — losing the corroborating detail that is the
     * whole reason to read a cascade.
     */
    @Test
    fun twoFaultsInTheSameMillisecondAreBothKept() {
        record(RuntimeException("thread one"), 1_700_000_000_000L)
        record(RuntimeException("thread two"), 1_700_000_000_000L)
        val entries = reporter.entries()
        assertEquals(2, entries.size)
        assertEquals("and both stamp to the same instant", 1, entries.map { it.timeMs }.toSet().size)
        assertEquals("and neither report was clobbered", 2, entries.map { it.summary }.toSet().size)
    }

    /**
     * The cap holds and it drops the OLDEST.
     *
     * ⚠️ The stamps here deliberately have differing digit counts either side of a power-of-ten
     * boundary, because ordering by the file NAME rather than the parsed number gives the wrong
     * answer exactly there — and would delete the newest reports while looking like it worked.
     */
    @Test
    fun theCapKeepsTheNewestAndDropsTheOldest() {
        val base = 9_999_999_999L // one digit short of the next power of ten
        repeat(CrashReporter.MAX + 6) { i ->
            record(RuntimeException("fault $i"), base + i)
        }
        val entries = reporter.entries()
        assertEquals(CrashReporter.MAX, entries.size)
        val kept = entries.map { it.timeMs }.toSet()
        assertTrue("the newest must survive", (base + CrashReporter.MAX + 5) in kept)
        assertTrue("the oldest must not", base !in kept)
    }

    @Test
    fun clearingRemovesEverything() {
        record(RuntimeException("gone"), 1_700_000_000_000L)
        assertEquals(1, reporter.entries().size)
        reporter.clear()
        assertTrue(reporter.entries().isEmpty())
    }

    /**
     * ⚠️ A reporter that throws while reporting is worse than no reporter: it replaces a diagnosable
     * fault with an undiagnosable one, at the moment the program is least able to cope.
     */
    @Test
    fun aHostileThrowableIsStillRecordedAndNeverEscapes() {
        val hostile = object : RuntimeException("hostile") {
            override val message: String get() = throw IllegalStateException("no message for you")
        }
        record(hostile, 1_700_000_000_000L) // must not throw
        // Whether a report survives is not the claim — surviving the call is.
        reporter.entries()
    }

    /** A directory with nothing in it is an ordinary state, not an error. */
    @Test
    fun anEmptyDirectoryReadsAsEmpty() {
        assertTrue(reporter.entries().isEmpty())
    }
}
