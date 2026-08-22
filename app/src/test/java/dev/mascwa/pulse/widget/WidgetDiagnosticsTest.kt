package dev.mascwa.pulse.widget

import dev.mascwa.pulse.widget.WidgetDiagnostics.Outcome
import dev.mascwa.pulse.widget.WidgetDiagnostics.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reason a widget could not draw has to survive as far as a photograph.
 *
 * ⚠️ Every case here is the shipped defect stated as a property. The widget used to render "a feed
 * threw" and "that feed has nothing to say" **identically** — as absence — so it appeared to shrink
 * and nothing recorded why. These assertions are what make that impossible rather than merely
 * unintended.
 *
 * Runs under `:app:testDebugUnitTest`, which CI executes, and touches no Android class, so it cannot
 * fail for an environmental reason and leave a real break indistinguishable from a broken harness.
 */
class WidgetDiagnosticsTest {

    private fun render(
        outcomes: Map<Source, Outcome>,
        fault: String? = null,
    ) = WidgetDiagnostics.Render(atMs = 1_000L, size = "180x160", outcomes = outcomes, elapsedMs = 42L, fault = fault)

    @Test
    fun `a feed that failed is never counted as a feed that had nothing to say`() {
        // THE defect, as a property. Both produce no line on the widget; only one is our fault, and
        // conflating them is what made a broken widget look like a quiet one.
        val r = render(mapOf(Source.MARKETS to Outcome.Empty, Source.NEWS to Outcome.Failed("IOException: no route to host")))

        assertEquals(listOf(Source.NEWS), r.unavailable)
        assertEquals(0, r.drawn)

        val report = WidgetDiagnostics.report(r).orEmpty()
        assertTrue("an empty feed must read as having answered", report.contains("nothing to report"))
        assertTrue("a failed feed must name its exception", report.contains("no route to host"))
    }

    @Test
    fun `a timeout is reported as a timeout, not as a failure and not as silence`() {
        // The all-or-nothing timeout is what emptied the widget. A source that ran out of its own
        // budget has to be visible as such, or the fix cannot be confirmed from a screenshot.
        val r = render(mapOf(Source.WEATHER to Outcome.TimedOut))

        assertEquals(listOf(Source.WEATHER), r.timedOut)
        assertTrue(r.failed.isEmpty())
        assertTrue(WidgetDiagnostics.logLine(r).contains("weather timeout"))
        assertTrue(WidgetDiagnostics.report(r).orEmpty().contains("gave up waiting"))
    }

    @Test
    fun `a healthy widget says nothing about diagnostics at all`() {
        // The degraded row costs a line of a small widget. It must appear only when something could
        // not answer — never merely because a feed had nothing to report.
        val healthy = mapOf(Source.ORACLE to Outcome.Ok, Source.MARKETS to Outcome.Empty, Source.SKY to Outcome.Skipped("no location"))

        assertEquals("", WidgetDiagnostics.degradedLine(healthy))
        assertEquals("", WidgetDiagnostics.degradedLine(render(healthy)))
        assertEquals(1, render(healthy).drawn)
    }

    @Test
    fun `the degraded row names what it can and counts the rest`() {
        val bad = listOf(Source.ORACLE, Source.WEATHER, Source.MARKETS, Source.NEWS, Source.FUEL)
            .associateWith { Outcome.Failed("boom") }

        val line = WidgetDiagnostics.degradedLine(bad)
        // Three named, two counted — a widget row cannot carry five labels, and a bare "5 failed"
        // would send the reader back for the console when the first three are usually the answer.
        assertTrue(line, line.startsWith("⚠ no answer from advisory, weather, markets +2"))
        assertFalse("the fourth and fifth are counted, not listed", line.contains("news"))
    }

    @Test
    fun `an exception is trimmed to something a widget row can hold`() {
        val long = WidgetDiagnostics.describe(IllegalStateException("x".repeat(500)))
        // class name + ": " + the capped message.
        assertEquals("IllegalStateException".length + 2 + WidgetDiagnostics.REASON_CHARS, long.length)

        // A message-less throwable still names its type: "Error" alone is worse than the class.
        assertEquals("IllegalStateException", WidgetDiagnostics.describe(IllegalStateException()))
        assertEquals("IllegalStateException", WidgetDiagnostics.describe(IllegalStateException("   ")))
    }

    @Test
    fun `the fault card always has something to show`() {
        // ⚠️ The card is applied precisely when the rich render failed, so this must never return a
        // blank string: a fault card reading nothing is indistinguishable from the launcher's own
        // "Can't load widget", which is the message this whole file exists to stop being the answer.
        assertTrue(WidgetDiagnostics.faultLine(null).isNotBlank())
        assertTrue(WidgetDiagnostics.faultLine(render(emptyMap())).isNotBlank())
        assertEquals("OutOfMemoryError", WidgetDiagnostics.faultLine(render(emptyMap(), fault = "OutOfMemoryError")))
    }

    @Test
    fun `the log line keeps the interesting minority and drops the rest`() {
        // It goes into a capped activity ring that a debug report carries whole. Listing the healthy
        // majority would push older entries out to say nothing.
        val r = render(
            mapOf(
                Source.ORACLE to Outcome.Ok,
                Source.MARKETS to Outcome.Empty,
                Source.NEWS to Outcome.Failed("SocketTimeoutException"),
                Source.SKY to Outcome.Skipped("no location"),
            ),
        )
        val line = WidgetDiagnostics.logLine(r)

        assertTrue(line, line.startsWith("render 180x160 1/4 drawn in 42ms"))
        assertTrue(line.contains("news failed(SocketTimeoutException)"))
        assertTrue("a skipped source is why a row is missing — keep it", line.contains("sky skipped(no location)"))
        assertFalse("ok and empty are the uninteresting majority", line.contains("advisory"))
        assertFalse(line.contains("markets"))
    }

    @Test
    fun `a whole-render fault is flagged as one, not buried among the sources`() {
        val line = WidgetDiagnostics.logLine(render(mapOf(Source.ORACLE to Outcome.Ok), fault = "BadParcelableException"))
        assertTrue(line, line.startsWith("FAULT "))
        assertTrue(line.contains("BadParcelableException"))
        assertTrue(WidgetDiagnostics.report(render(emptyMap(), fault = "boom")).orEmpty().startsWith("FAULT"))
    }

    @Test
    fun `nothing recorded is distinguishable from a render with nothing in it`() {
        // The console must be able to say "the widget has not drawn since this process started",
        // which is itself a finding — it is what a receiver that never ran looks like.
        assertEquals(null, WidgetDiagnostics.report(null))
        assertTrue(WidgetDiagnostics.report(render(emptyMap())).orEmpty().isNotBlank())
    }

    @Test
    fun `every source has a label a reader can act on`() {
        // The report is read by a person with no source open, so a bare enum name is not enough.
        Source.entries.forEach {
            assertTrue("${it.name} has no label", it.label.isNotBlank())
            assertFalse("${it.name}'s label is the enum name", it.label == it.name)
        }
        assertEquals("labels must be unique or a report is ambiguous", Source.entries.size, Source.entries.map { it.label }.toSet().size)
    }
}
