package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.core.telemetry.Novelty
import dev.mascwa.pulse.desktop.feature.ledger.SinceYouLeftViewModel
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The Home card, driven end to end against a real ledger written to a real directory and a real
 * settings file.
 *
 * This is the part CI could not otherwise prove: read the presence marker, measure the absence, decide
 * whether it is worth asking about, read the ledger from disk at that lag, score, and assemble a card.
 */
class SinceYouLeftTest {

    private val hour = 60L * 60L * 1000L
    private val now = 1_768_478_400_000L // 2026-01-15T12:00Z

    private fun store(seen: Long): DesktopSettingsStore = runBlocking {
        val s = DesktopSettingsStore(Files.createTempDirectory("sylset").resolve("settings.json"))
        if (seen != 0L) s.update { it.copy(lastSeenMs = seen) }
        s
    }

    private fun ledger() = WorldLedger(Files.createTempDirectory("sylledger"))

    private fun vm(settings: DesktopSettingsStore, ledger: WorldLedger) =
        SinceYouLeftViewModel(CoroutineScope(Dispatchers.Unconfined), settings, ledger)

    /**
     * A series whose six-hour spans are exactly [spans], newest last, so a test can aim a score.
     *
     * The readings between the six-hour marks are interpolated: they set the median gap (which the
     * pairing tolerance is derived from) and are otherwise not part of any span.
     */
    private suspend fun writeSpans(ledger: WorldLedger, id: String, endMs: Long, spans: List<Double>) {
        val marks = mutableListOf(100.0)
        spans.forEach { marks += marks.last() + it }
        val n = spans.size
        val start = endMs - n * 6 * hour
        val obs = (0..n * 6).map { i ->
            val j = i / 6
            val v = if (j >= n) marks[n] else marks[j] + (marks[j + 1] - marks[j]) * ((i % 6) / 6.0)
            Novelty.Observation(start + i * hour, v)
        }
        ledger.appendAll(id, obs)
    }

    /**
     * A hundred history spans of bounded noise.
     *
     * ⚠️ A hundred rather than a handful, and that is not padding. Surprisal is capped at
     * `log2((n+1)/2)`, so fifty spans top out at 4.64 bits — below the card's bar, which would make
     * every "is it listed" assertion below pass because the *ceiling* stopped it rather than the rule
     * under test. At a hundred the ceiling is 5.66 and the bar is what decides.
     */
    private val noise: List<Double> = (1..100).map { (it % 13) - 6.0 }

    // ------------------------------------------------------------------ when there is anything to say

    /**
     * ⚠️ Zero means never, not the epoch. Without this guard a first launch is greeted with a
     * fifty-six-year absence and a card full of records.
     */
    @Test
    fun aFirstLaunchIsNotAFiftySixYearAbsence() = runBlocking {
        val v = vm(store(seen = 0L), ledger())
        v.present(now)
        assertEquals("no card at all", 0L, v.state.value.lagMs)
    }

    /**
     * ⚠️ THE FLOOR. Two hours is measured against the collector rather than chosen: the slowest domain
     * cadence is sixty minutes, so anything shorter gives those domains a single collection — which the
     * wall's rate-of-change reading already covers.
     */
    @Test
    fun aStepAwayIsNotAnAbsence() = runBlocking {
        val v = vm(store(seen = now - 2 * hour + 60_000L), ledger())
        v.present(now)
        assertEquals("one minute under the floor says nothing", 0L, v.state.value.lagMs)

        val w = vm(store(seen = now - 2 * hour - 60_000L), ledger())
        w.present(now)
        assertTrue("one minute over it does", w.state.value.lagMs > 0L)
    }

    /**
     * ⚠️ THE CAP, which is arithmetic rather than taste. Spans do not overlap, so a year of
     * full-resolution history holds about `365 / days` of them — fifty-two at a week, twelve at a month.
     * A longer question could only produce a card of refusals, so the window is capped and the card says
     * how long you were really away.
     */
    @Test
    fun aVeryLongAbsenceIsCappedAndSaysSo() = runBlocking {
        val away = 21L * 24L * hour
        val v = vm(store(seen = now - away), ledger())

        v.present(now)

        val s = v.state.value
        assertEquals(SinceYouLeftViewModel.MAX_ABSENCE_MS, s.lagMs)
        assertEquals(away, s.awayMs)
        assertTrue("and it must not pretend it asked about three weeks", s.capped)
    }

    /** Presence is written down whether or not there was anything to report. */
    @Test
    fun beingHereIsRecorded() = runBlocking {
        val settings = store(seen = now - 30 * hour)
        val v = vm(settings, ledger())

        v.present(now)
        assertEquals(now, settings.current().lastSeenMs)

        v.heartbeat()
        assertTrue(settings.current().lastSeenMs >= now)
    }

    // ------------------------------------------------------------------ what the card holds

    /**
     * ⚠️ THE WHOLE POINT. A move nothing in the record approaches over the same span reaches the card,
     * described as a **move** rather than as a level.
     */
    @Test
    fun aRecordMoveReachesTheCard() = runBlocking {
        val ledger = ledger()
        writeSpans(ledger, "space.kp", now, noise + 200.0)
        val v = vm(store(seen = now - 6 * hour), ledger)

        v.present(now)

        val s = v.state.value
        assertEquals(6 * hour, s.lagMs)
        val top = s.movers.firstOrNull()
        assertNotNull("a 200-unit jump against a ±40 history must be listed", top)
        assertEquals("space.kp", top!!.spec.id)
        assertEquals(
            "a record is worth what the sample behind it is worth, and the sentence must say so",
            "the biggest rise on record over 6 hours — as rare as 100 readings can show",
            top.sentence,
        )
        assertFalse("a span is never described as a level", top.sentence.contains("Highest"))
        assertTrue("the change is the headline number", top.change > 150.0)
        assertEquals("and from → to must be consistent with it", top.to - top.from, top.change, 1e-9)
        assertTrue("the trace draws the absence", top.trace.isNotEmpty())
    }

    /** An ordinary series moves the ordinary amount and earns no card row. */
    @Test
    fun anOrdinaryStretchListsNothing() = runBlocking {
        val ledger = ledger()
        writeSpans(ledger, "space.kp", now, noise + 1.0)
        val v = vm(store(seen = now - 6 * hour), ledger)

        v.present(now)

        assertTrue("nothing unusual happened", v.state.value.movers.isEmpty())
        assertEquals("but it was judged, which is a different thing", 1, v.state.value.judged)
    }

    /**
     * ⚠️ THE BAR, and neither fixture above can reach it.
     *
     * A clearly ordinary move scores under two bits, where [Novelty.spanSentence] already declines to
     * say anything — so removing the threshold check changes nothing and the guard looks awake when it
     * is asleep. This one lands **between** the two, which is the only place the check does work.
     *
     * Ninety-eight history spans cycling −6..+6 plus a +9 and a +10, then a newest of +7.5. Exactly two
     * of the hundred are above it, so `pUpper = 3/101` and `p = 2 × 0.0297 = 0.0594` — that is 4.07
     * bits: past the two at which the sentence starts speaking, short of the five the card asks for,
     * and comfortably under the 5.66-bit ceiling a hundred spans can express.
     */
    @Test
    fun aNotableMoveStillShortOfTheCardsBarIsNotListed() = runBlocking {
        val ledger = ledger()
        writeSpans(ledger, "space.kp", now, (1..98).map { (it % 13) - 6.0 } + listOf(9.0, 10.0, 7.5))
        val v = vm(store(seen = now - 6 * hour), ledger)

        v.present(now)

        assertEquals("it was scored", 1, v.state.value.judged)
        assertTrue("but 4.07 bits is not news", v.state.value.movers.isEmpty())
    }

    /**
     * ⚠️ The honest count. One mover out of forty-odd metrics looks like a quiet world when it really
     * means a short history, and the card says which it is.
     */
    @Test
    fun theCardSaysHowMuchOfTheWorldItCouldNotJudge() = runBlocking {
        val ledger = ledger()
        writeSpans(ledger, "space.kp", now, noise + 1.0)
        val v = vm(store(seen = now - 6 * hour), ledger)

        v.present(now)

        val s = v.state.value
        assertEquals(1, s.judged)
        assertEquals(MetricRegistry.ALL.count { it.scored }, s.total)
        assertTrue("there is much more in the registry than one metric", s.total > s.judged)
    }

    /**
     * ⚠️ A reading that stopped hours ago cannot answer "what changed while I was away" however good the
     * history behind it is — that is a move which finished before the absence began.
     */
    @Test
    fun aSeriesThatStoppedBeforeTheAbsenceIsNotAnAnswer() = runBlocking {
        val ledger = ledger()
        // The same record move, but the collector fell silent four hours ago.
        writeSpans(ledger, "space.kp", now - 4 * hour, noise + 200.0)
        val v = vm(store(seen = now - 6 * hour), ledger)

        v.present(now)

        assertTrue("stale readings are not a mover", v.state.value.movers.isEmpty())
        assertEquals("and they are not counted as judged either", 0, v.state.value.judged)
    }

    /**
     * A short step away leaves an existing card alone — it must not vanish while it is being read — and
     * takes it down once it is older than the floor.
     *
     * ⚠️ Every return here has to be **under** the floor, which is what took two goes to write: my first
     * version jumped three hours from a marker set ten minutes in, which is a genuine two-hour-fifty
     * absence, so the card was recomputed rather than cleared and the code was right where the
     * assertion was wrong. The returns are ten minutes and then one hour fifty-five, by which point the
     * card on screen is two hours and five minutes old.
     */
    @Test
    fun aShortReturnDoesNotClearACardBeingRead() = runBlocking {
        val ledger = ledger()
        writeSpans(ledger, "space.kp", now, noise + 200.0)
        val settings = store(seen = now - 6 * hour)
        val v = vm(settings, ledger)

        v.present(now)
        assertTrue(v.state.value.movers.isNotEmpty())

        v.present(now + 10 * 60_000L)
        assertTrue("ten minutes later it is still on screen", v.state.value.movers.isNotEmpty())

        v.present(now + 2 * hour + 5 * 60_000L)
        assertEquals("past the floor's worth of age it has had its day", 0L, v.state.value.lagMs)
    }

    // ------------------------------------------------------------------ the scan on its own

    /** [scanSince] answers nothing for a question with no span, rather than dividing by it. */
    @Test
    fun aScanWithNoLagIsNotAQuestion() = runBlocking {
        val scan = dev.mascwa.pulse.desktop.feature.ledger.scanSince(ledger(), null, 0L, now)
        assertTrue(scan.movers.isEmpty())
        assertEquals(0, scan.total)
    }
}
