package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.DayAhead.BeatKind as K
import dev.mascwa.pulse.core.telemetry.DayAhead.Confidence as C
import dev.mascwa.pulse.core.telemetry.DayAhead.TravelSource as T
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DayAhead] tells you when to leave and where your day is impossible, so the cases that matter are
 * the arithmetic behind those two claims and the discipline about how much each one can be trusted.
 *
 * Every expected value here was computed from the constants before the assertion was written, and
 * the working is in the comment beside it. The anchor is an exact UTC midnight so that `at(9, 0)`
 * really is "09:00" in what the formatter prints — checked, because an anchor eight hours out was
 * the first thing that went wrong when these fixtures were built.
 */
class DayAheadTest {

    /** 1_799_971_200_000 = 20833 × 86_400_000, i.e. exactly midnight UTC. */
    private val day = 20_833L * 86_400_000L

    private fun at(h: Int, m: Int = 0) = day + (h * 60 + m) * 60_000L

    private fun event(
        id: String, title: String, from: Int, to: Int,
        lat: Double? = null, lon: Double? = null,
    ) = DayAhead.Commitment(id, title, at(from), at(to), lat, lon)

    private fun road(minutes: Long) = DayAhead.TravelEstimate(minutes * 60, 0.0, T.ROAD)

    // ---- the anchor itself -------------------------------------------------------------------

    @Test
    fun theFixtureClockMeansWhatItSays() {
        assertEquals("09:00", DayAhead.clockOf(at(9, 0)))
        assertEquals("10:30", DayAhead.clockOf(at(10, 30)))
        assertEquals("00:00", DayAhead.clockOf(day))
        // Past midnight must wrap rather than run to 24:00 and beyond.
        assertEquals("01:00", DayAhead.clockOf(at(25, 0)))
    }

    // ---- when to leave -----------------------------------------------------------------------

    @Test
    fun departureIsTheStartLessTravelLessTheArrivalBuffer() {
        // 09:00 − 25 min travel − 8 min buffer = 08:27
        assertEquals(at(8, 27), DayAhead.leaveBy(at(9, 0), road(25)))
    }

    @Test
    fun aDepartureStillFarOffIsGivenAsAClockTime() {
        val beats = DayAhead.plan(
            commitments = listOf(event("a", "Dentist", 9, 10, 51.5, 0.1)),
            nowMs = at(7, 0),
            travelTo = { road(25) },
        )
        val depart = beats.first { it.kind == K.DEPART }
        assertEquals("Leave by 08:27 for Dentist", depart.title)
        assertEquals(C.ESTIMATED, depart.confidence)
        assertTrue("must say what it was computed from: ${depart.detail}", depart.detail.contains("road route"))
    }

    /** Inside the imminent window it counts down instead, because a clock time is no longer useful. */
    @Test
    fun animminentDepartureCountsDown() {
        // depart is 08:27; standing 12 minutes before it is 08:15
        val beats = DayAhead.plan(
            commitments = listOf(event("a", "Dentist", 9, 10, 51.5, 0.1)),
            nowMs = at(8, 15),
            travelTo = { road(25) },
        )
        assertEquals("Leave in 12 min for Dentist", beats.first { it.kind == K.DEPART }.title)
    }

    @Test
    fun aDepartureAlreadyPastSaysSoAndDoesNotSitInThePast() {
        val now = at(8, 40)
        val beats = DayAhead.plan(
            commitments = listOf(event("a", "Dentist", 9, 10, 51.5, 0.1)),
            nowMs = now,
            travelTo = { road(25) },
        )
        val depart = beats.first { it.kind == K.DEPART }
        assertEquals("Leave now for Dentist", depart.title)
        assertEquals("a beat must never be placed before now", now, depart.atMs)
    }

    /**
     * A straight-line guess must never be reported as confidently as a road route.
     *
     * This is the whole reason [DayAhead.TravelSource] exists: the routing service is best-effort, so
     * the fallback is a normal path rather than a rarity, and presenting it as a measured journey
     * time would be exactly the unearned confidence this app keeps having to remove.
     */
    @Test
    fun aStraightLineGuessIsMarkedDownAndSaysSo() {
        val beats = DayAhead.plan(
            commitments = listOf(event("a", "Dentist", 9, 10, 51.5, 0.1)),
            nowMs = at(7, 0),
            travelTo = { DayAhead.straightLineTravel(51.5, 0.0, 51.5, 0.1) },
        )
        val depart = beats.first { it.kind == K.DEPART }
        assertEquals(C.ROUGH, depart.confidence)
        assertTrue("must admit the basis: ${depart.detail}", depart.detail.contains("straight-line estimate"))
    }

    @Test
    fun theStraightLineEstimateInflatesForRealRoads() {
        // 0.1 deg of longitude at 51.5N is 6922.0 m on a 6_371_000 m sphere.
        // x1.35 circuity = 9344.8 m; at 32 km/h that is 1051 s, which reads as 18 min.
        val est = DayAhead.straightLineTravel(51.5, 0.0, 51.5, 0.1)
        assertNotNull(est)
        assertEquals(T.STRAIGHT_LINE, est!!.source)
        assertEquals(1051L, est.seconds)
        assertEquals("18 min", DayAhead.minutesOf(est.seconds))
    }

    @Test
    fun anUnknownEndpointYieldsNoEstimateRatherThanAFabricatedOne() {
        assertNull(DayAhead.straightLineTravel(null, 0.0, 51.5, 0.1))
        assertNull(DayAhead.straightLineTravel(51.5, 0.0, 51.5, null))
        assertNull(DayAhead.straightLineTravel(Double.NaN, 0.0, 51.5, 0.1))
    }

    /** No travel estimate, no departure — the beat is dropped, not guessed. */
    @Test
    fun withoutTravelThereIsNoDepartureBeat() {
        val beats = DayAhead.plan(
            commitments = listOf(event("a", "Dentist", 9, 10, 51.5, 0.1)),
            nowMs = at(7, 0),
            travelTo = { null },
        )
        assertTrue(beats.none { it.kind == K.DEPART })
        assertTrue("the event itself still belongs on the day", beats.any { it.kind == K.EVENT })
    }

    // ---- the impossible day ------------------------------------------------------------------

    /**
     * The output that justifies the feature: two commitments spaced more closely than the journey
     * between them. A calendar app cannot see this, because it does not know how far apart they are.
     */
    @Test
    fun backToBackCommitmentsTooFarApartAreFlagged() {
        val a = DayAhead.Commitment("a", "Standup", at(10, 0), at(10, 30), 51.5, 0.0)
        val b = DayAhead.Commitment("b", "Client", at(11, 0), at(12, 0), 51.5, 0.1)
        // gap 10:30 -> 11:00 is 30 min; the journey needs 30 + 8 buffer = 38; short by 8.
        val beats = DayAhead.plan(
            commitments = listOf(a, b),
            nowMs = at(9, 0),
            travelFrom = { _, _ -> road(30) },
        )
        val clash = beats.first { it.kind == K.CONFLICT }
        assertEquals("\"Standup\" runs into \"Client\"", clash.title)
        assertTrue(clash.detail.contains("30 min"))
        assertTrue(clash.detail.contains("38 min"))
        assertTrue("must quantify the shortfall: ${clash.detail}", clash.detail.contains("8 min short"))
    }

    @Test
    fun aComfortableGapIsNotFlagged() {
        val a = DayAhead.Commitment("a", "Standup", at(10, 0), at(10, 30), 51.5, 0.0)
        val b = DayAhead.Commitment("b", "Client", at(12, 0), at(13, 0), 51.5, 0.1)
        val beats = DayAhead.plan(listOf(a, b), at(9, 0), travelFrom = { _, _ -> road(30) })
        assertTrue(beats.none { it.kind == K.CONFLICT })
    }

    /** Without both ends located there is no journey to reason about, so no claim is made. */
    @Test
    fun anUnlocatedCommitmentNeverProducesAConflict() {
        val a = DayAhead.Commitment("a", "Standup", at(10, 0), at(10, 30))
        val b = DayAhead.Commitment("b", "Client", at(11, 0), at(12, 0), 51.5, 0.1)
        val beats = DayAhead.plan(listOf(a, b), at(9, 0), travelFrom = { _, _ -> road(30) })
        assertTrue(beats.none { it.kind == K.CONFLICT })
    }

    // ---- shape of the day --------------------------------------------------------------------

    @Test
    fun thePastIsDroppedAndTheDayIsChronological() {
        val beats = DayAhead.plan(
            commitments = listOf(
                DayAhead.Commitment("over", "Breakfast", at(7, 0), at(7, 30)),
                DayAhead.Commitment("now", "Standup", at(8, 30), at(11, 0)),
                DayAhead.Commitment("later", "Client", at(13, 0), at(14, 0)),
            ),
            nowMs = at(9, 0),
        )
        assertTrue("a finished commitment is not part of the day ahead", beats.none { it.title == "Breakfast" })
        assertTrue("one under way still shapes the day", beats.any { it.title == "Standup" })
        assertEquals(beats.map { it.atMs }.sorted(), beats.map { it.atMs })
    }

    @Test
    fun theDayEndsAtTheLastCommitment() {
        val beats = DayAhead.plan(
            listOf(
                DayAhead.Commitment("a", "Standup", at(10, 0), at(10, 30)),
                DayAhead.Commitment("b", "Client", at(13, 0), at(14, 0)),
            ),
            at(9, 0),
        )
        val end = beats.first { it.kind == K.DAY_END }
        assertEquals(at(14, 0), end.atMs)
        assertTrue(end.detail.contains("14:00"))
    }

    @Test
    fun anEmptyOrAllDayOnlyCalendarPlansNothing() {
        assertTrue(DayAhead.plan(emptyList(), at(9, 0)).isEmpty())
        val allDay = DayAhead.Commitment("h", "Holiday", at(0, 0), at(23, 59), allDay = true)
        assertTrue(DayAhead.plan(listOf(allDay), at(9, 0)).isEmpty())
    }

    // ---- free time ---------------------------------------------------------------------------

    @Test
    fun theLongestUnclaimedStretchIsOffered() {
        val beats = DayAhead.plan(
            listOf(
                DayAhead.Commitment("a", "Standup", at(10, 0), at(10, 30)),
                DayAhead.Commitment("b", "Client", at(14, 0), at(15, 0)),
            ),
            nowMs = at(9, 0),
            topTask = "the tax return",
        )
        val focus = beats.first { it.kind == K.FOCUS }
        // 10:30 -> 14:00 is 3h30m, longer than 09:00 -> 10:00.
        assertEquals(at(10, 30), focus.atMs)
        assertEquals("Clear stretch for: the tax return", focus.title)
        assertTrue(focus.detail.contains("3h 30m"))
    }

    @Test
    fun overlappingCommitmentsDoNotInventAGapBetweenThem() {
        val windows = DayAhead.freeWindows(
            listOf(
                DayAhead.Commitment("a", "A", at(10, 0), at(12, 0)),
                DayAhead.Commitment("b", "B", at(11, 0), at(13, 0)),
            ),
            fromMs = at(10, 0),
        )
        assertTrue("nothing is free between two overlapping events", windows.isEmpty())
    }

    @Test
    fun aGapShorterThanTheFloorIsNotAWindow() {
        val windows = DayAhead.freeWindows(
            listOf(
                DayAhead.Commitment("a", "A", at(10, 0), at(10, 30)),
                DayAhead.Commitment("b", "B", at(11, 0), at(12, 0)),
            ),
            fromMs = at(10, 0),
        )
        assertTrue("30 minutes is a gap, not a working window", windows.isEmpty())
    }

    // ---- weather -----------------------------------------------------------------------------

    private fun hour(h: Int, precip: Int) = DayAhead.HourSlot(at(h, 0), precipProbability = precip)

    @Test
    fun aWetDepartureIsWarnedAbout() {
        val beats = DayAhead.plan(
            commitments = listOf(event("a", "Dentist", 9, 10, 51.5, 0.1)),
            nowMs = at(7, 0),
            hours = listOf(hour(7, 5), hour(8, 80), hour(9, 10)),
            travelTo = { road(25) },
        )
        // departure is 08:27, which falls in the 08:00 hour at 80%
        val depart = beats.first { it.kind == K.DEPART }
        assertTrue("must mention the rain: ${depart.detail}", depart.detail.contains("80% chance of rain"))
        assertTrue(depart.sources.contains("forecast"))
    }

    @Test
    fun aDryDepartureIsLeftAlone() {
        val beats = DayAhead.plan(
            commitments = listOf(event("a", "Dentist", 9, 10, 51.5, 0.1)),
            nowMs = at(7, 0),
            hours = listOf(hour(7, 5), hour(8, 10), hour(9, 10)),
            travelTo = { road(25) },
        )
        val depart = beats.first { it.kind == K.DEPART }
        assertTrue(!depart.detail.contains("chance of rain"))
        assertTrue(!depart.sources.contains("forecast"))
    }

    @Test
    fun theForecastHourIsTheOneContainingTheInstant() {
        val hours = listOf(hour(8, 10), hour(9, 90))
        assertEquals(10, DayAhead.hourAt(hours, at(8, 59))?.precipProbability)
        assertEquals(90, DayAhead.hourAt(hours, at(9, 0))?.precipProbability)
        assertNull("beyond the forecast is not a guess of zero", DayAhead.hourAt(hours, at(11, 0)))
        assertNull(DayAhead.hourAt(emptyList(), at(9, 0)))
    }

    @Test
    fun theDriestWindowIsOnlyReportedWhenThereIsOne() {
        val mixed = listOf(hour(9, 80), hour(10, 10), hour(11, 70))
        assertEquals(at(10, 0) to at(11, 0), DayAhead.driestWindow(mixed, at(9, 0), at(12, 0)))
        // Uniform rain has no window worth naming.
        assertNull(DayAhead.driestWindow(listOf(hour(9, 60), hour(10, 60)), at(9, 0), at(12, 0)))
        assertNull(DayAhead.driestWindow(emptyList(), at(9, 0), at(12, 0)))
    }

    // ---- durations ---------------------------------------------------------------------------

    @Test
    fun aJourneyNeverRoundsAwayToNothing() {
        assertEquals("under a minute", DayAhead.minutesOf(20))
        assertEquals("1 min", DayAhead.minutesOf(60))
        assertEquals("1h", DayAhead.minutesOf(3600))
        assertEquals("1h 30m", DayAhead.minutesOf(5400))
    }
}
