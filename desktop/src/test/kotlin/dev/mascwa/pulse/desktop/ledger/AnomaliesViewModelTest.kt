package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.core.telemetry.Novelty
import dev.mascwa.pulse.desktop.feature.ledger.AnomaliesViewModel
import dev.mascwa.pulse.desktop.feature.ledger.Aspect
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The wall, driven end to end against a real ledger written to a real directory.
 *
 * This is the part CI could not otherwise prove: read from disk, score, rank, split into sections. A
 * unit test of `Novelty` alone says nothing about whether the page assembled from it is right.
 */
class AnomaliesViewModelTest {

    private val hour = 60L * 60L * 1000L
    private val start = 1_768_478_400_000L // 2026-01-15T12:00Z

    private fun store(): DesktopSettingsStore =
        DesktopSettingsStore(Files.createTempDirectory("anomvm").resolve("settings.json"))

    /** A steady series with one reading pushed far out at the end. */
    private fun runWith(
        build: suspend (WorldLedger) -> Unit,
        assert: (dev.mascwa.pulse.desktop.feature.ledger.AnomaliesState) -> Unit,
    ) = runBlocking {
        val ledger = WorldLedger(Files.createTempDirectory("anomledger"))
        build(ledger)
        val vm = AnomaliesViewModel(CoroutineScope(Dispatchers.Unconfined), store(), ledger)
        vm.rebuildNow()
        assert(vm.state.value)
    }

    /** ⚠️ Every metric here is one with no place suffix, so the fixture does not depend on a location. */
    private suspend fun write(ledger: WorldLedger, id: String, values: List<Double>) {
        ledger.appendAll(id, values.mapIndexed { i, v -> Novelty.Observation(start + i * hour, v) })
    }

    @Test
    fun anEmptyLedgerSaysSoRatherThanLookingCalm() = runWith({}) { s ->
        assertEquals(0, s.tested)
        assertTrue(s.anomalies.isEmpty())
        assertTrue(s.quiet.isEmpty())
        assertTrue(s.notYet.isEmpty())
        assertEquals("no readings tested means no false alarms to warn about", 0.0, s.falseAlarms, 1e-12)
    }

    /**
     * ⚠️ A metric with too little history must land in its own section, never among the scored ones.
     * On a new install almost everything is here, and sorting it in either direction makes the world
     * look uniformly calm or uniformly alarming.
     */
    @Test
    fun tooLittleHistoryGetsItsOwnSection() = runWith({
        write(it, "space.kp", (0 until 5).map { i -> 2.0 + i })
    }) { s ->
        assertTrue("nothing may be scored off five readings", s.anomalies.isEmpty() && s.quiet.isEmpty())
        assertEquals(1, s.notYet.size)
        assertEquals("space.kp", s.notYet.first().spec.id)
        assertEquals(Novelty.MIN_SAMPLES, s.notYet.first().need)
    }

    /** A quiet series is listed, but below the fold rather than as an anomaly. */
    @Test
    fun anOrdinaryReadingIsFiledAsOrdinary() = runWith({
        write(it, "space.kp", (0 until 60).map { i -> 2.0 + (i % 5) })
    }) { s ->
        assertTrue("a mid-range reading is not an anomaly, got ${s.anomalies.map { a -> a.id }}",
            s.anomalies.none { a -> a.spec.id == "space.kp" && a.aspect == Aspect.LEVEL })
        assertTrue(s.quiet.any { a -> a.spec.id == "space.kp" && a.aspect == Aspect.LEVEL })
    }

    /** ⚠️ The whole point of the page: a reading nothing in its history approaches reaches the top. */
    @Test
    fun aRecordReadingReachesTheWall() = runWith({
        write(it, "space.kp", (0 until 60).map { i -> 2.0 + (i % 5) } + listOf(9.0))
    }) { s ->
        val top = s.anomalies.firstOrNull()
        assertNotNull("a 9 against a history of 2-6 must be listed, got ${s.anomalies.size}", top)
        assertEquals("space.kp", top!!.spec.id)
        assertEquals(Aspect.LEVEL, top.aspect)
        assertEquals(9.0, top.value, 1e-9)
        assertTrue("got '${top.reading.sentence}'", top.reading.sentence.startsWith("Highest on record"))
        assertTrue("the trace is what the row draws", top.trace.isNotEmpty())
    }

    /**
     * ⚠️ Level and rate are judged separately, and the rate is the whole reason to bother: a value can
     * be entirely unremarkable where it sits and remarkable in how fast it got there. A pressure that
     * walks steadily and then drops sharply is inside its own range the whole time.
     */
    /**
     * A pressure that climbs from 1000 to 1045 in varied small steps and then drops 22 in one hour.
     * 1023 is a completely ordinary pressure for this history — bang in the middle, 0.0 bits — and
     * −22 against sixty steps of between +0.2 and +1.3 is the most extreme move on record, 4.93 bits
     * against a ceiling of 4.93.
     *
     * ⚠️ The steps have to be **varied**. My first fixture climbed by exactly +1 every hour, which is
     * three distinct rates over sixty readings, and [Novelty.effectiveSampleSize] correctly refused to
     * judge it at all — the rule working, and a reminder that a fixture regular enough to reason about
     * in your head is often too regular to reach the branch.
     */
    @Test
    fun aSharpMoveIsCaughtEvenWhereTheLevelIsOrdinary() = runWith({
        val steps = listOf(0.3, 0.9, 0.5, 1.2, 0.4, 1.0, 0.7, 0.2, 1.1, 0.6, 0.8, 1.3)
        val climb = mutableListOf(1000.0)
        repeat(60) { i -> climb += (climb.last() + steps[i % steps.size]) }
        write(it, "weather.pressure", climb + (climb.last() - 22.0))
    }) { s ->
        val rate = (s.anomalies + s.quiet).firstOrNull { it.spec.id == "weather.pressure" && it.aspect == Aspect.CHANGE }
        assertNotNull("the rate of change must be judged too", rate)
        assertTrue("the drop is the anomaly, got ${s.anomalies.map { a -> a.id }}",
            s.anomalies.any { it.spec.id == "weather.pressure" && it.aspect == Aspect.CHANGE })
        assertTrue("and the pressure it landed on is not",
            s.anomalies.none { it.spec.id == "weather.pressure" && it.aspect == Aspect.LEVEL })
    }

    /**
     * ⚠️ The false-alarm count must be arithmetic over what was actually tested, because it is the
     * line that separates this from a horoscope. Two metrics tested at both level and rate is four
     * readings, and 4 × 2^-4 is 0.25.
     */
    @Test
    fun theFalseAlarmCountIsArithmeticOverWhatWasTested() = runWith({
        write(it, "space.kp", (0 until 60).map { i -> 2.0 + (i % 5) })
        write(it, "space.solar-wind", (0 until 60).map { i -> 400.0 + (i % 7) })
    }) { s ->
        assertEquals("two metrics, level and rate each", 4, s.tested)
        assertEquals(4.0 * Math.pow(2.0, -AnomaliesViewModel.THRESHOLD_BITS), s.falseAlarms, 1e-12)
    }

    /**
     * ⚠️ THE TIME MACHINE. Scrubbed back to before the spike, the wall must not know about it —
     * which is the property that makes "as it stood then" true rather than decorative.
     */
    @Test
    fun scrubbingBackHidesWhatHadNotHappenedYet() = runBlocking {
        val ledger = WorldLedger(Files.createTempDirectory("anomscrub"))
        val values = (0 until 60).map { i -> 2.0 + (i % 5) } + listOf(9.0)
        ledger.appendAll("space.kp", values.mapIndexed { i, v -> Novelty.Observation(start + i * hour, v) })

        val vm = AnomaliesViewModel(CoroutineScope(Dispatchers.Unconfined), store(), ledger)
        vm.rebuildNow()
        assertTrue("the spike is the newest reading", vm.state.value.anomalies.any { it.value == 9.0 })

        // One hour before the spike was recorded.
        vm.rebuildNow(start + 59 * hour)
        val then = vm.state.value
        assertTrue("nothing from the future may appear", then.anomalies.none { it.value == 9.0 })
        assertTrue("but the metric is still judged", (then.anomalies + then.quiet).any { it.spec.id == "space.kp" })

        vm.rebuildNow(null)
        assertTrue("and going back to now restores it", vm.state.value.anomalies.any { it.value == 9.0 })
    }

    /**
     * Ranking is the core's, so persistence and bits both reach it.
     *
     * ⚠️ The two records deliberately have **different** histories — sixty readings against forty — so
     * their ceilings differ (4.93 bits against 4.36) and the order is a real claim. My first fixture
     * produced a single anomaly, and a one-element list is sorted in every direction: the assertion
     * passed against a deliberately reversed sort, which is the fourth recorded way a green test
     * proves nothing.
     */
    @Test
    fun theWallIsOrderedBySurprise() = runWith({
        write(it, "space.kp", (0 until 60).map { i -> 2.0 + (i % 5) } + listOf(9.0))
        write(it, "space.aurora", (0 until 40).map { i -> 20.0 + (i % 7) } + listOf(95.0))
    }) { s ->
        val bits = s.anomalies.map { it.reading.bits }
        assertTrue("two records with different ceilings, got ${s.anomalies.map { a -> a.id }}", bits.size >= 2)
        assertTrue("and they must not tie, or the ordering claim is empty", bits[0] > bits[1])
        assertEquals("most surprising first", bits.sortedDescending(), bits)
    }

}
