package dev.mascwa.pulse.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deep-analysis contract.
 *
 * The owner's instruction was exact — a switch inside the panel, default off, never on open, never on
 * a timer — and each clause is a property something can quietly stop honouring. These are the
 * assertions that make them true rather than intended.
 */
class DeepAnalysisTest {

    /**
     * ⚠️ The one that matters most: **opening a panel costs nothing.** With the switch off there is no
     * combination of key and state that starts the expensive work.
     */
    @Test
    fun switchedOffItNeverRuns() {
        listOf(
            DeepState(),
            DeepState(running = true),
            DeepState(readyFor = "a"),
            DeepState(failedFor = "a"),
        ).forEach { state ->
            assertFalse("$state", DeepAnalysis.shouldRun(on = false, key = "a", state = state))
            assertFalse("$state", DeepAnalysis.shouldRun(on = false, key = "b", state = state))
        }
    }

    /** Switched on with something to analyse, it runs — once. */
    @Test
    fun switchedOnItRunsOnceForASubject() {
        val idle = DeepState()
        assertTrue(DeepAnalysis.shouldRun(on = true, key = "AAPL", state = idle))

        val running = DeepAnalysis.started(idle)
        assertFalse("must not start twice", DeepAnalysis.shouldRun(true, "AAPL", running))

        val done = DeepAnalysis.succeeded("AAPL")
        assertFalse("must not re-run for the same subject", DeepAnalysis.shouldRun(true, "AAPL", done))
        assertTrue(DeepAnalysis.isReady("AAPL", done))
    }

    /**
     * ⚠️ **This is what "never on a timer" comes down to in practice.** Nothing here observes a clock;
     * the only things that can make the work start again are the subject changing or the switch being
     * cycled. So a panel left open with the switch on sits still.
     */
    @Test
    fun aHeldResultIsNotRefetchedHoweverLongYouLookAtIt() {
        val done = DeepAnalysis.succeeded("51.5,-0.1")
        repeat(50) {
            assertFalse(DeepAnalysis.shouldRun(on = true, key = "51.5,-0.1", state = done))
        }
    }

    /** A different subject is a different question, and does get asked. */
    @Test
    fun aNewSubjectRunsAgain() {
        val done = DeepAnalysis.succeeded("AAPL")
        assertTrue(DeepAnalysis.shouldRun(on = true, key = "MSFT", state = done))
        assertFalse(DeepAnalysis.isReady("MSFT", done))
    }

    /**
     * ⚠️ A failure must not loop. Without this a source that is down turns a switched-on panel into a
     * request every time the screen redraws — the worst possible response to something already broken.
     */
    @Test
    fun aFailedSubjectIsNotRetriedByItself() {
        val bad = DeepAnalysis.failed("AAPL")
        assertFalse(DeepAnalysis.shouldRun(on = true, key = "AAPL", state = bad))
        // A different subject is unaffected — one dead symbol does not poison the panel.
        assertTrue(DeepAnalysis.shouldRun(on = true, key = "MSFT", state = bad))
    }

    /** Cycling the switch is the retry, and it is a deliberate act. */
    @Test
    fun switchingOffAndOnRetriesAFailure() {
        val bad = DeepAnalysis.failed("AAPL")
        val off = DeepAnalysis.cleared()
        assertTrue(DeepAnalysis.shouldRun(on = true, key = "AAPL", state = off))
        assertEquals(DeepState(), off)
        assertNull(bad.readyFor)
    }

    /**
     * ⚠️ Switching off drops the held answer as well as stopping the work.
     *
     * Keeping it would make flicking the switch back on show a stale result instantly and silently,
     * indistinguishable from a fresh one — and for a price series or a forecast that is the difference
     * between useful and wrong.
     */
    @Test
    fun switchingOffDropsWhatWasHeld() {
        val done = DeepAnalysis.succeeded("AAPL")
        assertTrue(DeepAnalysis.isReady("AAPL", done))

        val off = DeepAnalysis.cleared()
        assertFalse(DeepAnalysis.isReady("AAPL", off))
        assertTrue("and asks again when switched back on", DeepAnalysis.shouldRun(true, "AAPL", off))
    }

    /**
     * Nothing to analyse is not a question. An empty search box or a machine that does not know where
     * it is would otherwise spend a round trip to be told nothing.
     */
    @Test
    fun thereIsNothingToAskAboutNothing() {
        assertFalse(DeepAnalysis.shouldRun(on = true, key = "", state = DeepState()))
        assertFalse(DeepAnalysis.shouldRun(on = true, key = "   ", state = DeepState()))
    }
}
