package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationRankingTest {

    private fun c(id: String, km: Double, clicks: Int = 0, votes: Int = 0) =
        StationRanking.Candidate(id, km * 1000.0, clicks, votes)

    /**
     * The audit's own New York case, with its measured figures. Every one of these is inside one
     * band, so the station people actually listen to has to come first even though two others are
     * physically nearer.
     */
    @Test fun inADenseCityPopularityOrdersTheList() {
        val out = StationRanking.order(
            listOf(
                c("xanius-a", 0.20),
                c("xanius-b", 0.23),
                c("some-local", 1.4, clicks = 10),
                c("adroit-jazz", 5.03, clicks = 193, votes = 178_774),
            ),
        )
        assertEquals(listOf("adroit-jazz", "some-local", "xanius-a", "xanius-b"), out.map { it.id })
    }

    /** …and it is not cut by the limit any more, which is how it was lost before. */
    @Test fun theLimitIsAppliedAfterOrderingNotBefore() {
        val many = (1..40).map { c("filler-$it", 0.1 * it) } + c("popular", 5.03, clicks = 193)
        val out = StationRanking.order(many, limit = 30)
        assertEquals(30, out.size)
        assertEquals("popular", out.first().id)
    }

    /**
     * The other half: across bands, near still wins outright. A sparse region must not have a
     * far-away city station pushed to the top of a list headed "near you".
     */
    @Test fun acrossBandsNearerWinsHoweverPopularTheFarOneIs() {
        val out = StationRanking.order(
            listOf(
                c("city-giant", 84.0, clicks = 50_000, votes = 900_000),
                c("village-fm", 6.0, clicks = 0),
            ),
        )
        assertEquals(listOf("village-fm", "city-giant"), out.map { it.id })
    }

    /** 10 km is the boundary: 9.9 km is band 0, 10.0 km is band 1. */
    @Test fun theBandBoundaryIsWhereItSays() {
        assertEquals(0, StationRanking.band(9_999.0))
        assertEquals(1, StationRanking.band(10_000.0))
        assertEquals(1, StationRanking.band(19_999.0))
        assertEquals(2, StationRanking.band(20_000.0))
    }

    /**
     * A station whose distance could not be worked out must sort LAST, not first.
     *
     * ⚠️ The values here are chosen so the guard is actually exercised. `Double.NaN.toInt()` and
     * `(-1.0 / BAND_METERS).toInt()` are both 0 in Kotlin, so a version of this test written with
     * `-1.0` passes with the guard deleted — it did, and that is why it says −900 km.
     */
    @Test fun aDistanceThatCannotBeTrustedSortsLast() {
        assertEquals(Int.MAX_VALUE, StationRanking.band(Double.NaN))
        assertEquals(Int.MAX_VALUE, StationRanking.band(Double.POSITIVE_INFINITY))
        assertEquals(0, StationRanking.band(-900_000.0))
        val out = StationRanking.order(
            listOf(
                StationRanking.Candidate("nan", Double.NaN, clicks = 3_000),
                StationRanking.Candidate("infinite", Double.POSITIVE_INFINITY, clicks = 3_000),
                c("real", 2.0, clicks = 9),
            ),
        )
        assertEquals("real", out.first().id)
        assertTrue(out.map { it.id }.containsAll(listOf("nan", "infinite")))
    }

    /** Votes break a click tie, so two never-clicked stations are not ordered arbitrarily. */
    @Test fun votesBreakAClickTie() {
        val out = StationRanking.order(listOf(c("quiet", 1.0, clicks = 0, votes = 2), c("liked", 3.0, clicks = 0, votes = 400)))
        assertEquals(listOf("liked", "quiet"), out.map { it.id })
    }

    /** With everything else equal the nearest is first — the original behaviour, kept as the floor. */
    @Test fun withNothingToDistinguishThemTheNearestIsStillFirst() {
        val out = StationRanking.order(listOf(c("far", 4.0), c("near", 0.5), c("mid", 2.0)))
        assertEquals(listOf("near", "mid", "far"), out.map { it.id })
    }

    @Test fun anEmptyOrZeroLimitedListIsEmptyRatherThanAnError() {
        assertTrue(StationRanking.order(emptyList()).isEmpty())
        assertTrue(StationRanking.order(listOf(c("a", 1.0)), limit = 0).isEmpty())
    }
}
