package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scenario these are written against is the measured one: the HOSPITAL category's 15 km radius
 * over central London, where roughly 1,200 places match and the old fixed quota of 80 returned an
 * arbitrary 80 of them.
 */
class PoiSearchTest {

    private val hospitalRadius = 15_000 // PlaceCategory.HOSPITAL

    @Test fun aBoundQuotaIsNeverTrustedHoweverFullTheListLooks() {
        // The defect in one assertion. 250 rows is more than the 40 the screen shows, so a naive
        // "have we got enough?" check would stop here — and every one of those rows is an arbitrary
        // pick from a larger set, so the nearest are very likely not among them.
        val bound = PoiSearch.Probe(radiusMeters = hospitalRadius, returned = PoiSearch.HARD_CAP)
        assertTrue(PoiSearch.capBound(bound.returned))
        assertFalse("a bound quota can never be shown as nearest", PoiSearch.trustworthy(bound))
        assertEquals(
            "must narrow, not stop, even though it has far more rows than it needs",
            5_000,
            PoiSearch.nextRadius(bound, maxRadius = hospitalRadius),
        )
    }

    @Test fun narrowingRepeatsUntilTheQuotaStopsBinding() {
        // ⚠️ Walked out of the shipped function rather than guessed: 15000 → 5000 → 1666 → 1500.
        // 5000/3 is 1666, which is still above the 1500 floor, so the floor does not bite until the
        // step after — my first version of this test asserted 1500 one rung too early.
        var r = PoiSearch.nextRadius(PoiSearch.Probe(15_000, 250), maxRadius = hospitalRadius)
        assertEquals(5_000, r)
        r = PoiSearch.nextRadius(PoiSearch.Probe(r!!, 250), maxRadius = hospitalRadius)
        assertEquals(1_666, r)
        r = PoiSearch.nextRadius(PoiSearch.Probe(r!!, 250), maxRadius = hospitalRadius)
        assertEquals("never below the floor", PoiSearch.MIN_RADIUS_M, r)
        assertNull(
            "at the floor and still truncated — stop, and let the caller admit it",
            PoiSearch.nextRadius(PoiSearch.Probe(r!!, 250), maxRadius = hospitalRadius),
        )
    }

    @Test fun aCompleteResponseWithEnoughPlacesIsTheAnswer() {
        // 60 places inside 1.5 km with the quota untouched: every match in that circle came back, so
        // its nearest 40 are the true nearest 40 and there is nothing to gain by looking wider.
        val probe = PoiSearch.Probe(PoiSearch.MIN_RADIUS_M, returned = 60)
        assertTrue(PoiSearch.trustworthy(probe))
        assertNull(PoiSearch.nextRadius(probe, maxRadius = hospitalRadius))
    }

    @Test fun aSparseAreaWidensRatherThanGivingUp() {
        // Three clinics within 1.5 km is the countryside, not a truncated response.
        assertEquals(4_500, PoiSearch.nextRadius(PoiSearch.Probe(1_500, 3), maxRadius = hospitalRadius))
        assertEquals(13_500, PoiSearch.nextRadius(PoiSearch.Probe(4_500, 5), maxRadius = hospitalRadius))
        // Widening is clamped to the category's own reach rather than overshooting it.
        assertEquals(15_000, PoiSearch.nextRadius(PoiSearch.Probe(13_500, 9), maxRadius = hospitalRadius))
        // At the widest, a short list is simply the truth.
        assertNull(PoiSearch.nextRadius(PoiSearch.Probe(15_000, 9), maxRadius = hospitalRadius))
    }

    @Test fun theSearchAlwaysTerminates() {
        // Walk every combination that could plausibly loop, and assert the ladder is finite.
        for (returned in listOf(0, 1, 39, 40, 249, 250, 400)) {
            var radius = PoiSearch.startRadius(hospitalRadius)
            var steps = 0
            while (true) {
                val next = PoiSearch.nextRadius(
                    PoiSearch.Probe(radius, returned),
                    maxRadius = hospitalRadius,
                ) ?: break
                assertTrue("radius must actually move, or it is a loop", next != radius)
                radius = next
                steps++
                assertTrue("returned=$returned ran away at $steps steps", steps <= PoiSearch.MAX_PROBES)
            }
        }
    }

    @Test fun aNarrowCategoryDoesNotStartWiderThanItsOwnReach() {
        // COMM_TOWER is 12 km and SHELTER 25 km, but a hypothetical 800 m category must not open
        // with a 1.5 km probe and then have to walk back.
        assertEquals(800, PoiSearch.startRadius(maxRadius = 800))
        assertEquals(PoiSearch.MIN_RADIUS_M, PoiSearch.startRadius(maxRadius = 25_000))
        assertTrue("a degenerate radius must still be positive", PoiSearch.startRadius(0) >= 1)
    }

    @Test fun theBoundaryBetweenTrustedAndNotIsExactlyTheQuota() {
        assertTrue(PoiSearch.trustworthy(PoiSearch.Probe(1_500, PoiSearch.HARD_CAP - 1)))
        assertFalse(PoiSearch.trustworthy(PoiSearch.Probe(1_500, PoiSearch.HARD_CAP)))
        // Over the quota should be impossible from the server, but if it happens it is still
        // truncation and must not be read as "extra results".
        assertFalse(PoiSearch.trustworthy(PoiSearch.Probe(1_500, PoiSearch.HARD_CAP + 10)))
    }
}
