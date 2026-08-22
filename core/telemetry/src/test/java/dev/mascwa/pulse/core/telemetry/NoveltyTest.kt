package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected value here is computed from the shipped formula and the arithmetic left in the
 * comment beside it. Guessing at them is how this repo has repeatedly written an assertion that was
 * wrong where the code was right.
 */
class NoveltyTest {

    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour

    /** Midnight UTC exactly: 19675 whole days after the epoch, so hour-of-day arithmetic is clean. */
    private val midnightUtc = 19_675L * 24L * hour

    private fun obs(atMs: Long, v: Double, backfilled: Boolean = false) =
        Novelty.Observation(atMs, v, backfilled)

    private fun reading(s: Novelty.Score): Novelty.Reading {
        assertTrue("expected a score, got $s", s is Novelty.Score.Scored)
        return (s as Novelty.Score.Scored).reading
    }

    // ------------------------------------------------------------------ choice 1: robust statistics

    /**
     * ⚠️ THE LOAD-BEARING ONE. Two outliers must not hide a real anomaly.
     *
     * 30 readings cycling 10..14 (six of each) plus two of 500. Sorted, index 15 and 16 are both 12,
     * so the median is 12.0. The deviations sort to six 0s, twelve 1s, twelve 2s and two 488s, so their
     * median is 1.0 and MAD = 1 × 1.4826.
     *
     * A reading of 20 is then (20 − 12) / 1.4826 = 5.40 — clearly anomalous. The **mean** of the same
     * history is (6×60 + 1000) / 32 = 42.5, so a mean/sd z-score puts 20 *below average* and reports
     * nothing at all. That inversion is the whole reason for choosing the median and the MAD.
     */
    @Test
    fun twoOutliersDoNotHideARealAnomaly() {
        val history = (0 until 30).map { obs(it * hour, 10.0 + (it % 5)) } +
            listOf(obs(30 * hour, 500.0), obs(31 * hour, 500.0))

        val d = Novelty.describe(history)!!
        assertEquals("median", 12.0, d.median, 1e-9)
        assertEquals("MAD", 1.4826, d.mad, 1e-9)

        val r = reading(Novelty.score(history, obs(40 * hour, 20.0)))
        assertEquals("robust z = (20 − 12) / 1.4826", 5.3960, r.robustZ, 1e-3)
        assertTrue("a mean/sd z would be negative here; the robust one must be strongly positive", r.robustZ > 5.0)
    }

    // ------------------------------------------------------------------ choice 3: the ceiling

    /**
     * ⚠️ A reading above everything ever seen is reported at the ceiling, not beyond it.
     *
     * With n = 24 and nothing at or above the reading, the upper tail is (0 + 1)/(24 + 1) = 0.04, the
     * two-sided p is 0.08, and −log₂(0.08) = 3.6439. `ceilingBitsFor(24)` is log₂(25/2) = log₂(12.5),
     * which is the same 3.6439 — they are the same number by construction, which is what stops this
     * ever claiming a rarity the sample cannot resolve.
     */
    @Test
    fun aRecordReadingIsPinnedAtWhatTheSampleCanResolve() {
        val history = (1..24).map { obs(it * hour, it.toDouble()) }
        val r = reading(Novelty.score(history, obs(100 * hour, 100.0)))

        assertEquals("bits", 3.6439, r.bits, 1e-4)
        assertEquals("ceiling for n=24", 3.6439, r.ceilingBits, 1e-4)
        assertEquals("bits must equal the ceiling exactly, not merely approach it", r.ceilingBits, r.bits, 1e-12)
        assertTrue("must report that it is pinned", r.cappedAtCeiling)
        assertTrue("sentence names the sample size", r.sentence.contains("24 readings can show"))
    }

    /** More history resolves rarer events: log₂(201/2) = 6.651 against log₂(25/2) = 3.644. */
    @Test
    fun aLongerHistoryRaisesTheCeiling() {
        assertEquals(3.6439, Novelty.ceilingBitsFor(24), 1e-4)
        assertEquals(6.6511, Novelty.ceilingBitsFor(200), 1e-4)
        assertTrue(Novelty.ceilingBitsFor(200) > Novelty.ceilingBitsFor(24))
    }

    /** Two-sided, so a record low is exactly as surprising as a record high of the same rarity. */
    @Test
    fun aRecordLowScoresTheSameAsARecordHigh() {
        val history = (1..24).map { obs(it * hour, it.toDouble()) }
        val high = reading(Novelty.score(history, obs(100 * hour, 100.0)))
        val low = reading(Novelty.score(history, obs(100 * hour, -100.0)))

        assertEquals("both tails must cost the same", high.bits, low.bits, 1e-12)
        assertEquals(1, high.direction)
        assertEquals(-1, low.direction)
        assertTrue(low.sentence.startsWith("Lowest on record"))
    }

    // ------------------------------------------------------------------ choice 2: diurnal bucketing

    /**
     * ⚠️ THE OTHER LOAD-BEARING ONE. Thirty days of hourly readings whose value *is* the hour.
     *
     * Judged against the whole day, a 3 a.m. reading of 3 sits near the bottom of a 0..23 spread:
     * 120 of the 720 are at or below it, so p = 2 × 121/721 = 0.3356 and that is 1.57 bits — the
     * machine would report every single night as unusual.
     *
     * Judged against the same hour (±1, so hours 2, 3 and 4 — 90 readings), 60 are at or below and 60
     * at or above, p = 2 × 61/91 which exceeds 1 and clamps, giving 0 bits. Which is correct: 3 a.m. is
     * always like this.
     */
    @Test
    fun aDiurnalMetricIsJudgedAgainstItsOwnHour() {
        val history = (0 until 30).flatMap { d ->
            (0 until 24).map { h -> obs(midnightUtc + d * day + h * hour, h.toDouble()) }
        }
        val latest = obs(midnightUtc + 30 * day + 3 * hour, 3.0)

        val flat = reading(Novelty.score(history, latest, diurnal = false))
        val hourly = reading(Novelty.score(history, latest, diurnal = true, utcOffsetSeconds = 0))

        assertEquals("whole-day comparison", 1.5749, flat.bits, 1e-3)
        assertEquals("same-hour comparison", 0.0, hourly.bits, 1e-9)
        assertEquals("±1 hour of 24 samples a day is 3 × 30", 90, hourly.n)
        assertEquals("the whole day is 24 × 30", 720, flat.n)
        assertTrue("the un-bucketed answer calls an ordinary night unusual", flat.bits > 1.4)
    }

    // ------------------------------------------------------------------ the refusal floor

    @Test
    fun tooLittleHistoryIsARefusalWithACount() {
        val history = (1..23).map { obs(it * hour, it.toDouble()) }
        val s = Novelty.score(history, obs(100 * hour, 99.0))

        assertTrue("23 is below the floor of ${Novelty.MIN_SAMPLES}", s is Novelty.Score.TooLittleHistory)
        val r = s as Novelty.Score.TooLittleHistory
        assertEquals(23, r.have)
        assertEquals(Novelty.MIN_SAMPLES, r.need)
        assertTrue("says how many more are needed", r.sentence.contains("1 more reading"))
    }

    @Test
    fun oneMoreReadingCrossesTheFloor() {
        val history = (1..24).map { obs(it * hour, it.toDouble()) }
        assertTrue(Novelty.score(history, obs(100 * hour, 99.0)) is Novelty.Score.Scored)
    }

    @Test
    fun anEmptyHistoryRefusesRatherThanThrowing() {
        assertTrue(Novelty.score(emptyList(), obs(0, 1.0)) is Novelty.Score.TooLittleHistory)
        assertNull(Novelty.describe(emptyList()))
    }

    /** The reading is never part of the sample it is judged against — that would judge it partly against itself. */
    @Test
    fun theReadingIsExcludedFromItsOwnHistory() {
        val latest = obs(100 * hour, 5.0)
        val history = (1..24).map { obs(it * hour, it.toDouble() ) } + latest
        assertEquals("only the earlier readings count", 24, reading(Novelty.score(history, latest)).n)
    }

    // ------------------------------------------------------------------ choice 4: like-for-like

    @Test
    fun enoughRecordedHistoryIsPreferredOverBackfilled() {
        val backfilled = (1..30).map { obs(it * hour, it.toDouble(), backfilled = true) }
        val recorded = (31..60).map { obs(it * hour, it.toDouble()) }
        val r = reading(Novelty.score(backfilled + recorded, obs(100 * hour, 45.0)))

        assertEquals("the backfilled half must be dropped entirely", Novelty.Basis.RECORDED, r.basis)
        assertEquals(30, r.n)
        assertTrue("nothing to disclose when the basis is clean", !r.sentence.contains("fetched"))
    }

    @Test
    fun tooLittleRecordedHistoryFallsBackAndSaysSo() {
        val backfilled = (1..30).map { obs(it * hour, it.toDouble(), backfilled = true) }
        val recorded = (31..35).map { obs(it * hour, it.toDouble()) }
        val r = reading(Novelty.score(backfilled + recorded, obs(100 * hour, 45.0)))

        assertEquals(Novelty.Basis.MIXED, r.basis)
        assertEquals("all 35 used, because 5 recorded is not enough alone", 35, r.n)
        assertTrue("must disclose the mix", r.sentence.contains("mix of fetched and recorded"))
    }

    @Test
    fun anEntirelyBackfilledBasisIsLabelled() {
        val backfilled = (1..30).map { obs(it * hour, it.toDouble(), backfilled = true) }
        val r = reading(Novelty.score(backfilled, obs(100 * hour, 45.0)))

        assertEquals(Novelty.Basis.BACKFILLED, r.basis)
        assertTrue("must not pass modelled history off as measured", r.sentence.contains("fetched history"))
    }

    // ------------------------------------------------------------------ the sentence

    /**
     * 59 daily readings around 10 and one of 50, sixty days back. A reading of 40 is beaten only by
     * that one, so it is not a record — but nothing has matched it for two months, which is worth
     * saying. p = 2 × (1 + 1)/(59 + 1 + 1) = 0.0656, and 1/0.0656 ≈ 15.
     *
     * ⚠️ The 59 cycle 10/11/12 rather than sitting at exactly 10, because a dead-flat run is one
     * independent reading however many rows it occupies — see [Novelty.effectiveSampleSize]. A
     * constant fixture would be refused before it ever reached the sentence this test is about.
     */
    @Test
    fun aLongUnmatchedHighSaysHowLongItHasBeen() {
        val history = listOf(obs(midnightUtc, 50.0)) +
            (1..59).map { obs(midnightUtc + it * day, 10.0 + (it % 3)) }
        val r = reading(Novelty.score(history, obs(midnightUtc + 60 * day, 40.0)))

        assertTrue("not a record — one earlier reading beat it", !r.cappedAtCeiling)
        assertTrue("got '${r.sentence}'", r.sentence.startsWith("Highest in 2 months"))
        assertNotNull(r.extremeSinceMs)
        assertEquals("the day-0 peak is what it was last matched by", midnightUtc, r.extremeSinceMs)
    }

    /**
     * ⚠️ A reading above the median can still be completely ordinary — half of them are. Sixty daily
     * readings alternating 10 and 20; a 16 is above the median of 15, but 30 of the 60 are at or above
     * it, so p clamps at 1 and there is nothing to report. Saying "unusually high" here would be a
     * sentence arguing with itself.
     */
    @Test
    fun anAboveMedianButOrdinaryReadingClaimsNothing() {
        val history = (0 until 60).map { obs(midnightUtc + it * day, if (it % 2 == 0) 10.0 else 20.0) }
        val r = reading(Novelty.score(history, obs(midnightUtc + 60 * day, 16.0)))

        assertEquals(1, r.direction)
        assertEquals(0.0, r.bits, 1e-9)
        assertEquals("Right in its usual range.", r.sentence)
    }

    /**
     * ⚠️ FOUND BY REAL DATA, NOT BY A FIXTURE. Running this over a real year of London weather
     * produced "Unusually low — a 1-in-1 reading", which is a sentence arguing with itself.
     *
     * 60 readings of 1..60; a reading of 44 has 17 at or above it, so p = 2 x 18/61 = 0.590 and that
     * is 0.76 bits — inside the middle half of the distribution and not worth a word.
     */
    @Test
    fun anOrdinaryReadingNeverClaimsToBeUnusual() {
        val history = (1..60).map { obs(it * hour, it.toDouble()) }
        val r = reading(Novelty.score(history, obs(100 * hour, 44.0)))

        assertEquals("above the median of 30.5", 1, r.direction)
        assertEquals(0.7607, r.bits, 1e-3)
        assertEquals("Right in its usual range.", r.sentence)
        assertTrue("the absurd phrasing must be unreachable", !r.sentence.contains("1-in-1"))
    }

    /** Past the bar, the direction is worth stating: 2 x 2/61 = 0.0656, which is 3.93 bits, 1-in-15. */
    @Test
    fun aGenuinelyRareReadingDoesClaimToBeUnusual() {
        val history = (1..60).map { obs(it * hour, it.toDouble()) }
        val r = reading(Novelty.score(history, obs(100 * hour, 59.5)))

        assertEquals(3.9307, r.bits, 1e-3)
        assertEquals("Unusually high — a 1-in-15 reading.", r.sentence)
    }

    /**
     * A history with no MAD to divide by must not produce a NaN or an infinity.
     *
     * ⚠️ The fixture cannot simply be one repeated number, however tempting: that is a single
     * independent reading and [Novelty.score] refuses it before the division is ever reached, so the
     * test would pass without exercising anything. Instead, 65 readings where 52 are 5.0 and every
     * fifth is 7.0 — 26 runs, comfortably past the floor, and with more than half the values sitting
     * exactly on the median the median absolute deviation is still zero.
     */
    @Test
    fun aFlatHistoryDoesNotDivideByZero() {
        val history = (0..64).map { obs(it * hour, if (it % 5 == 0) 7.0 else 5.0) }
        assertEquals("13 isolated 7s and 13 runs of 5s", 26, Novelty.effectiveSampleSize(history.map { it.value }))
        assertEquals(0.0, Novelty.describe(history)!!.mad, 1e-12)

        val same = reading(Novelty.score(history, obs(100 * hour, 5.0)))
        assertEquals(0.0, same.robustZ, 1e-12)
        assertEquals(0.0, same.bits, 1e-9)

        val different = reading(Novelty.score(history, obs(100 * hour, 9.0)))
        assertEquals("still zero, because there is no spread to measure against", 0.0, different.robustZ, 1e-12)
        assertTrue("but the empirical tail still reports it as a record", different.cappedAtCeiling)
        assertTrue(different.bits.isFinite())
        // ceilingBitsFor(26) = log2(27/2) = log2(13.5) = 3.7549 — what 26 readings can resolve, not
        // the log2(66/2) = 5.04 that 65 rows would have claimed.
        assertEquals(3.7549, different.bits, 1e-3)
    }

    // ------------------------------------------------------------------ polling faster than the world

    @Test
    fun effectiveSampleSizeCountsRunsNotRows() {
        assertEquals(0, Novelty.effectiveSampleSize(emptyList()))
        assertEquals(1, Novelty.effectiveSampleSize(listOf(5.0)))
        assertEquals("a whole day of polling one daily figure", 1, Novelty.effectiveSampleSize(List(96) { 5.0 }))
        assertEquals(
            "a series that genuinely varies pays nothing at all",
            4,
            Novelty.effectiveSampleSize(listOf(1.0, 2.0, 3.0, 4.0)),
        )
        assertEquals(
            "a value coming back later is a new reading, not the old one",
            3,
            Novelty.effectiveSampleSize(listOf(1.0, 1.0, 2.0, 2.0, 1.0)),
        )
    }

    /**
     * ⚠️ THE OTHER LOAD-BEARING ONE, and the reason [Novelty.effectiveSampleSize] exists.
     *
     * The solar F10.7 flux is measured once a day at Penticton and the collector polls it every fifteen
     * minutes, so forty days of it is 3,840 rows describing forty readings. Repetition leaves the
     * median, the MAD and every percentile exactly where they were and multiplies the row count by
     * ninety-six — so a ceiling taken from rows would report the same spike as a 1-in-1,900 event
     * instead of the 1-in-20 that forty readings can actually resolve.
     *
     * Both series below contain the same forty numbers, so they must produce the same verdict, word
     * for word.
     */
    @Test
    fun pollingFasterThanTheMetricUpdatesBuysNoPrecision() {
        val quarterHour = 15L * 60L * 1000L
        val daily = (0 until 40).map { obs(it * day, 100.0 + it) }
        val polled = (0 until 40).flatMap { d ->
            (0 until 96).map { s -> obs(d * day + s * quarterHour, 100.0 + d) }
        }

        val a = reading(Novelty.score(daily, obs(40 * day, 500.0)))
        val b = reading(Novelty.score(polled, obs(40 * day, 500.0)))

        assertEquals(40, a.n)
        assertEquals("ninety-six times the rows", 3840, b.n)
        assertEquals("but the same forty readings", 40, a.effectiveN)
        assertEquals(40, b.effectiveN)

        assertEquals("so the same verdict", a.bits, b.bits, 1e-12)
        assertEquals(a.ceilingBits, b.ceilingBits, 1e-12)
        assertEquals(a.sentence, b.sentence)

        // ceilingBitsFor(40) = log2(41/2) = log2(20.5) = 4.3576.
        assertEquals(4.3576, b.bits, 1e-3)
        assertEquals(4.3576, b.ceilingBits, 1e-3)
        assertTrue(b.cappedAtCeiling)
        // ⚠️ And the sentence quotes the readings, not the rows. "as rare as 3840 readings can show"
        // would be the same overclaim in words.
        assertEquals("Highest on record — as rare as 40 readings can show.", b.sentence)
    }

    /**
     * ⚠️ The refusal floor counts readings too. Six hours of a fifteen-minute poll clears twenty-four
     * ROWS and knows nothing whatever about the world; scoring it would floor the surprisal at almost
     * nothing and report a genuine record as "right in its usual range", which is worse than refusing.
     */
    @Test
    fun theRefusalFloorCountsReadingsNotRows() {
        val quarterHour = 15L * 60L * 1000L
        val unchanged = (0 until 24).map { obs(it * quarterHour, 77.0) }
        val refused = Novelty.score(unchanged, obs(day, 900.0))
        assertTrue("24 rows is not 24 readings, got $refused", refused is Novelty.Score.TooLittleHistory)
        assertEquals(1, (refused as Novelty.Score.TooLittleHistory).have)

        val real = (0 until 24).map { obs(it * quarterHour, 77.0 + it) }
        assertTrue(Novelty.score(real, obs(day, 900.0)) is Novelty.Score.Scored)
    }

    /**
     * ⚠️ Pinned at the ceiling **without** being a record — the case that separates "is this the
     * highest ever" from "is this number a measurement or a floor", and the reason `cappedAtCeiling` is
     * read off the arithmetic rather than off extremeness.
     *
     * Thirty readings, but the one high day was only polled twenty times because the machine was off
     * for the rest of it — so 2,804 rows. A reading of 300 is beaten by those twenty, which makes it no
     * record at all, yet 2 × 21/2805 = 0.0150 is far under the 2/31 = 0.0645 that thirty readings can
     * resolve, so the floor takes hold and the surprisal reported is the most this history can express.
     * Announcing that as a measurement would be exactly the overclaim the ceiling exists to prevent.
     */
    @Test
    fun aReadingCanBePinnedAtTheCeilingWithoutBeingARecord() {
        val quarterHour = 15L * 60L * 1000L
        val history =
            (0 until 20).map { obs(it * quarterHour, 400.0) } +
                (1..29).flatMap { d -> (0 until 96).map { s -> obs(d * day + s * quarterHour, 100.0 + d) } }

        val r = reading(Novelty.score(history, obs(30 * day, 300.0)))

        assertEquals(2804, r.n)
        assertEquals(30, r.effectiveN)
        assertTrue("twenty earlier rows beat it, so it is not a record", r.extremeSinceMs != null)
        // log2(31/2) = log2(15.5) = 3.9542, and the floor has taken hold, so bits sits exactly there.
        assertEquals(3.9542, r.ceilingBits, 1e-3)
        assertEquals(3.9542, r.bits, 1e-3)
        assertTrue("a floor is not a measurement and must be reported as one", r.cappedAtCeiling)
        // Thirty days back rounds to one month, which the phrasing has to be able to say properly.
        assertTrue("got '${r.sentence}'", r.sentence.startsWith("Highest in 1 month —"))
    }

    /**
     * ⚠️ A run only means anything in time order, and a caller hands over whatever order its store read
     * in. Shuffled, the 2,880 rows below look like 2,880 changes rather than thirty.
     */
    @Test
    fun readingsAreCountedInTimeOrderWhateverOrderTheyArriveIn() {
        val quarterHour = 15L * 60L * 1000L
        val polled = (0 until 30).flatMap { d ->
            (0 until 96).map { s -> obs(d * day + s * quarterHour, 200.0 + d) }
        }
        // A deterministic hash order — nothing like chronological, and nothing like reversed either
        // (reversing a run-length sequence preserves its run count, so it would prove nothing).
        val jumbled = polled.sortedBy { (it.atMs * 2_654_435_761L) % 1_000_003L }

        val ordered = reading(Novelty.score(polled, obs(30 * day, 900.0)))
        val shuffled = reading(Novelty.score(jumbled, obs(30 * day, 900.0)))

        assertEquals(30, ordered.effectiveN)
        assertEquals("sorting inside score is what makes this hold", 30, shuffled.effectiveN)
        assertEquals(ordered.bits, shuffled.bits, 1e-12)
    }

    // ------------------------------------------------------------------ rate of change

    /**
     * ⚠️ Ten readings at fifteen-minute spacing, a six-hour hole, then ten more. The median gap is
     * 15 min, so the tolerance is 37.5 min and the difference spanning the hole is dropped: 19 gaps in,
     * 18 differences out. Keeping it would manufacture a spike out of an outage.
     */
    @Test
    fun aDifferenceAcrossAnOutageIsDiscarded() {
        val step = 15L * 60L * 1000L
        val first = (0 until 10).map { obs(it * step, it.toDouble()) }
        val resumeAt = 9 * step + 6 * hour
        val second = (0 until 10).map { obs(resumeAt + it * step, 100.0 + it) }

        val d = Novelty.changeSeries(first + second)

        assertEquals("19 gaps, one of them too wide to use", 18, d.size)
        assertTrue("the 90-point jump across the hole must not appear", d.none { it.value > 50.0 })
        assertTrue("every surviving difference is one step of the ramp", d.all { it.value == 1.0 })
    }

    @Test
    fun aDifferenceInheritsBackfilledFromEitherSide() {
        val step = 60L * 1000L
        val d = Novelty.changeSeries(
            listOf(obs(0, 1.0, backfilled = true), obs(step, 2.0), obs(2 * step, 3.0)),
        )
        assertEquals(2, d.size)
        assertTrue("a difference touching fetched history is itself fetched history", d[0].backfilled)
        assertTrue("and one between two recorded readings is not", !d[1].backfilled)
    }

    @Test
    fun aSeriesTooShortToDifferenceYieldsNothing() {
        assertTrue(Novelty.changeSeries(emptyList()).isEmpty())
        assertTrue(Novelty.changeSeries(listOf(obs(0, 1.0))).isEmpty())
    }

    // ------------------------------------------------------------------ ranking a whole wall

    /** 100 metrics at a 6-bit threshold: 100 × 2⁻⁶ = 1.5625 expected to be chance. */
    @Test
    fun theWallCanStateHowManyOfItsOwnFindingsAreChance() {
        assertEquals(1.5625, Novelty.expectedFalseAlarms(100, 6.0), 1e-9)
        assertEquals("nothing scored, nothing to warn about", 0.0, Novelty.expectedFalseAlarms(0, 6.0), 1e-9)
        assertTrue(
            "more metrics tested means more chance findings",
            Novelty.expectedFalseAlarms(200, 6.0) > Novelty.expectedFalseAlarms(100, 6.0),
        )
    }

    @Test
    fun persistenceCreditIsBoundedAndStartsAtNothing() {
        assertEquals("a first sighting earns nothing", 0.0, Novelty.persistenceCredit(1), 1e-12)
        assertEquals(0.5, Novelty.persistenceCredit(2), 1e-12)
        assertEquals(2.0, Novelty.persistenceCredit(5), 1e-12)
        assertEquals("capped, no matter how long it holds", 2.0, Novelty.persistenceCredit(50), 1e-12)
    }

    /**
     * ⚠️ Persistence nudges the order; it must never overturn a far stronger reading. A three-bit
     * finding held for five collections earns 3 + 2 = 5, which still loses to a one-shot eight.
     */
    @Test
    fun persistenceCannotOutrankAMuchStrongerReading() {
        val strong = "strong" to stub(bits = 8.0)
        val persistent = "persistent" to stub(bits = 3.0)

        val order = Novelty.rank(listOf(persistent, strong), mapOf("persistent" to 5))
        assertEquals(listOf("strong", "persistent"), order)
    }

    /** Between two comparable readings, the one that has held wins. */
    @Test
    fun persistenceBreaksATieBetweenComparableReadings() {
        val fleeting = "fleeting" to stub(bits = 5.2)
        val held = "held" to stub(bits = 5.0)

        assertEquals(
            listOf("held", "fleeting"),
            Novelty.rank(listOf(fleeting, held), mapOf("held" to 3)),
        )
        assertEquals(
            "with no persistence recorded, the stronger reading leads",
            listOf("fleeting", "held"),
            Novelty.rank(listOf(fleeting, held)),
        )
    }

    // ------------------------------------------------------------------ movement over a span

    /**
     * A known series with hand-computable spans: `v[i] = 10 + (7i mod 13)`, hourly.
     *
     * ⚠️ Fine for the structural tests below and **useless** for scoring, which is the trap worth
     * naming: any modular ramp has `v[i+k] − v[i]` taking only two values, so every span is one of two
     * numbers and [Novelty.effectiveSampleSize] collapses. Scoring tests use [varied].
     */
    private fun cyc(i: Int) = 10.0 + (7 * i % 13)

    /**
     * Deterministic pseudo-noise in 100.00..139.99, so consecutive spans genuinely differ.
     *
     * Pure `Long` arithmetic, so it is the same sequence on any JVM.
     */
    private fun varied(i: Int): Double {
        var x = (i + 1) * 2_654_435_761L
        x = x xor (x ushr 13)
        x *= 1_274_126_177L
        x = x xor (x ushr 17)
        return 100.0 + Math.floorMod(x, 4000L) / 100.0
    }

    /**
     * ⚠️ THE LOAD-BEARING ONE. Spans must not overlap, and the one being judged must be last.
     *
     * 24 hourly readings at a 6-hour lag. Walking back from t=23h the pairs are 23←17, 17←11, 11←5;
     * t=5h then has nothing 6 hours before it (the series starts at 0), so the walk stops. Values from
     * `v[i] = 10 + (7i mod 13)`: v5=19, v11=22, v17=12, v23=15 — so +3, −10, +3 in time order.
     *
     * Overlapping spans would share five-sixths of their data while looking like independent samples to
     * every count in the scorer, which is the whole reason for the stride.
     */
    @Test
    fun spansDoNotOverlapAndTheNewestIsLast() {
        val series = (0 until 24).map { obs(it * hour, cyc(it)) }

        val spans = Novelty.spanSeries(series, 6 * hour)

        assertEquals(listOf(11 * hour, 17 * hour, 23 * hour), spans.map { it.atMs })
        assertEquals(listOf(3.0, -10.0, 3.0), spans.map { it.value })
        assertEquals("the span being judged has to be the last element", 23 * hour, spans.last().atMs)
        spans.zipWithNext { a, b ->
            assertTrue(
                "spans ${a.atMs} and ${b.atMs} overlap at a 6-hour lag",
                b.atMs - a.atMs >= 6 * hour,
            )
        }
    }

    /**
     * ⚠️ A hole must make the walk step back, not call seven hours six.
     *
     * The same series with t=17h missing. From t=23h the nearest reading to 17h is t=16h, which would
     * make the "6-hour" span seven hours long — rejected, so the walk drops to t=22h, which pairs
     * exactly with t=16h. v22=21, v16=18, so +3.0 at t=22h.
     *
     * Accepting the wrong pair would report v23−v16 = 15−18 = −3.0 at t=23h: the opposite sign, from a
     * span a seventh longer than the one asked for.
     */
    @Test
    fun aHoleMakesTheWalkStepBackRatherThanCallSevenHoursSix() {
        val series = (0 until 24).filter { it != 17 }.map { obs(it * hour, cyc(it)) }

        val spans = Novelty.spanSeries(series, 6 * hour)

        assertEquals(22 * hour, spans.last().atMs)
        assertEquals(3.0, spans.last().value, 1e-9)
        assertEquals(listOf(10 * hour, 16 * hour, 22 * hour), spans.map { it.atMs })
    }

    /**
     * ⚠️ The tolerance floor, and the branch neither test above reaches.
     *
     * In both of those `0.6 × medianGap` and `SPAN_TOLERANCE × lag` come to the same 36 minutes, so
     * `maxOf` picks either and the floor is never exercised. Here the lag is 2.5 hours against hourly
     * sampling: ten per cent of the lag is 15 minutes, and **no target ever falls within 15 minutes of a
     * reading** because every one of them lands on a half hour. Without the floor this series yields
     * nothing at all.
     *
     * ⚠️ It also documents the honest cost: hourly sampling cannot express two and a half hours, so what
     * comes back is three-hour spans (the tie goes to the older reading, consistently). Measured the
     * same way throughout, they are comparable with each other, which is what the scoring needs — the
     * lag asked for is just a label at that resolution. This is why the caller's floor is two hours
     * rather than one.
     */
    @Test
    fun theToleranceFloorIsAWholeSamplingGapOrNothingWouldEverPair() {
        val series = (0 until 24).map { obs(it * hour, cyc(it)) }

        val spans = Novelty.spanSeries(series, 5 * hour / 2)

        assertEquals(8, spans.size)
        assertEquals(23 * hour, spans.last().atMs)
        assertEquals(
            "consistently rounded to the nearest expressible span",
            listOf(2, 5, 8, 11, 14, 17, 20, 23).map { it * hour },
            spans.map { it.atMs },
        )
    }

    /** Nothing to say when the history is shorter than the question. */
    @Test
    fun aHistoryShorterThanTheLagYieldsNoSpans() {
        val series = (0 until 4).map { obs(it * hour, cyc(it)) }
        assertTrue(Novelty.spanSeries(series, 6 * hour).isEmpty())
        assertTrue("a non-positive lag is not a question", Novelty.spanSeries(series, 0L).isEmpty())
    }

    /**
     * ⚠️ A perfectly linear series is REFUSED, and that is the rule working rather than a defect.
     *
     * Every 6-hour span of a ramp is the same number, so [Novelty.effectiveSampleSize] counts one
     * independent reading among them and [Novelty.score] declines. Pinned because it is the fixture trap
     * this file has already been caught by twice: a series regular enough to reason about in your head
     * is often too regular to reach the branch under test.
     */
    @Test
    fun everySpanOfARampIsIdenticalSoTheScorerRefuses() {
        val series = (0 until 200).map { obs(it * hour, 50.0 + it * 2.0) }

        val spans = Novelty.spanSeries(series, 6 * hour)
        assertTrue("the ramp does produce spans", spans.size > 24)
        assertEquals("all of them identical", 1, spans.map { it.value }.distinct().size)

        val s = Novelty.score(spans, spans.last())
        assertTrue("thirty identical readings are one reading, so this must refuse", s is Novelty.Score.TooLittleHistory)
    }

    /**
     * The whole path: a move far outside anything this metric has done over the same span is scored as
     * a record, and the sentence says so in the language of a **move**.
     *
     * 300 hourly noisy readings, then one 6 hours later that is 200 above its predecessor — an order of
     * magnitude past the ±40 the series can produce in six hours.
     */
    @Test
    fun aMoveIsJudgedAgainstMovesOverTheSameSpan() {
        val history = (0 until 300).map { obs(it * hour, varied(it)) }
        val series = history + obs(305 * hour, varied(299) + 200.0)

        val spans = Novelty.spanSeries(series, 6 * hour)
        assertEquals(305 * hour, spans.last().atMs)
        assertEquals(200.0, spans.last().value, 1e-9)

        val r = reading(Novelty.score(spans, spans.last()))
        assertNull("nothing in the history came near it", r.extremeSinceMs)
        assertTrue("a record move must clear the wall's threshold, got ${r.bits}", r.bits >= 4.0)
        // ⚠️ The sample the record is worth, said out loud. A year of six-hour spans is 1,460 of them
        // and a year of weekly ones is 52, and "on record" means very different things across that
        // range — measured over a real year of London weather, "biggest fall on record over 7 days"
        // came up on 6.8% of hours, which is exactly the 2/53 that sample can resolve.
        assertEquals(
            "the biggest rise on record over 6 hours — as rare as ${r.effectiveN} readings can show",
            Novelty.spanSentence(r, 6 * hour, spans.last().value),
        )
    }

    /**
     * ⚠️ THE OTHER LOAD-BEARING ONE. A span never borrows the level sentence.
     *
     * `Reading.sentence` says "Highest on record" / "Lowest on record". Over a difference series those
     * mean the largest rise and the largest fall, and "Lowest on record" printed beside a plunging
     * value reads as a claim about the level — the opposite of what happened.
     */
    @Test
    fun aSpanIsNeverDescribedAsAHighOrALow() {
        val fell = stub(bits = 6.0).copy(direction = -1)
        val said = Novelty.spanSentence(fell, 6 * hour, change = -12.0)

        assertEquals("the biggest fall on record over 6 hours — as rare as 100 readings can show", said)
        assertTrue("'lowest' would be read as a statement about the level", said!!.none { it.isUpperCase() })
    }

    /**
     * ⚠️ Direction is measured against the **median span**, not against zero, so on a metric that mostly
     * climbs a flat six hours reads as direction −1 while nothing fell. Where the two disagree the
     * wording must drop to a neutral "move" rather than assert a fall that did not happen.
     */
    @Test
    fun aDirectionTheChangeDisagreesWithIsNotAssertedAsARiseOrAFall() {
        val r = stub(bits = 6.0).copy(direction = -1)

        val said = Novelty.spanSentence(r, 6 * hour, change = 5.0)

        assertEquals("the most unusual move on record over 6 hours — as rare as 100 readings can show", said)
        assertTrue("it rose, so it must not say it fell", !said!!.contains("fall"))
    }

    /** An ordinary move is not news, and the basis is stated when it is not what this machine watched. */
    @Test
    fun anOrdinaryMoveSaysNothingAndAFetchedBasisSaysSo() {
        assertNull(Novelty.spanSentence(stub(bits = 1.5), 6 * hour, change = 2.0))

        val fetched = stub(bits = 6.0).copy(extremeSinceMs = 1L, basis = Novelty.Basis.BACKFILLED)
        assertEquals(
            "an unusually large rise over 6 hours — a 1-in-65 move (against fetched history)",
            Novelty.spanSentence(fetched, 6 * hour, change = 9.0),
        )
    }

    private fun stub(bits: Double) = Novelty.Reading(
        bits = bits,
        cappedAtCeiling = false,
        ceilingBits = 10.0,
        percentile = 0.5,
        robustZ = 0.0,
        direction = 1,
        basis = Novelty.Basis.RECORDED,
        n = 100,
        effectiveN = 100,
        extremeSinceMs = null,
        sentence = "",
    )
}
