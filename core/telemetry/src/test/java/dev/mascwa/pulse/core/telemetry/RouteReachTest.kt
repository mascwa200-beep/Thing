package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every fixture below is a snap distance measured against the live OSRM demo server, not a value
 * chosen to make the thresholds look good:
 *
 * ```
 * London Charing Cross -> Gresham Street   14 m / 44 m    3.1 km route     (ordinary)
 * London -> New York                       5,534,803 m    2,149 km "route" (Ok, snapped to Portugal)
 * Ben Nevis                                2,161 m                          (drive, then walk up)
 * Snowdon                                  2,755 m                          (drive, then walk up)
 * Lundy (island)                           19,256 m                         (no road at all)
 * ```
 */
class RouteReachTest {

    @Test fun anOrdinaryCityRouteIsUnremarkable() {
        // 44 m is kerb geometry. Nothing should appear on screen for this.
        val reach = RouteReach.classify(snapMeters = 44.0, routeMeters = 3_100.0)
        assertEquals(RouteReach.Reach.ON_ROAD, reach)
        assertNull("an ordinary route needs no caveat", RouteReach.describe(reach, 44.0))
        assertTrue(RouteReach.trustworthy(reach))
    }

    @Test fun theWrongContinentIsNotARoute() {
        // The measured case: Ok, 2,149 km, 28 h, destination snapped onto the coast of Portugal.
        val reach = RouteReach.classify(snapMeters = 5_534_803.0, routeMeters = 2_149_200.0)
        assertEquals(RouteReach.Reach.UNREACHABLE, reach)
        assertFalse("its distance and ETA are for somewhere else", RouteReach.trustworthy(reach))
        assertTrue(RouteReach.describe(reach, 5_534_803.0)!!.contains("No road goes to this spot"))
    }

    @Test fun aMountainSummitStillRoutesToItsTrailhead() {
        // The rule that stops this being a blunt instrument. Both are real destinations a navigation
        // app must still route to — you drive to the car park and walk the rest.
        for ((name, snap) in listOf("Ben Nevis" to 2_161.0, "Snowdon" to 2_755.0)) {
            val reach = RouteReach.classify(snapMeters = snap, routeMeters = 200_000.0)
            assertEquals("$name must still route", RouteReach.Reach.WALK_LAST_LEG, reach)
            assertTrue("$name is still usable", RouteReach.trustworthy(reach))
            assertTrue(RouteReach.describe(reach, snap)!!.contains("on foot"))
        }
    }

    @Test fun anIslandIsNotReachableByRoad() {
        // Lundy: far past the walk limit even though the ratio rule would not catch it on a long drive.
        val reach = RouteReach.classify(snapMeters = 19_256.0, routeMeters = 334_000.0)
        assertEquals(RouteReach.Reach.UNREACHABLE, reach)
    }

    @Test fun aShortHopIsNotCondemnedByTheRatioRule() {
        // ⚠️ The reason the absurdity rule compares against the WHOLE distance and not half of it.
        // 260 m of snap on a 500 m journey is an ordinary park-and-walk; a half-distance rule would
        // call it unreachable, and wrongly refusing a reachable place is the worse failure.
        val reach = RouteReach.classify(snapMeters = 260.0, routeMeters = 500.0)
        assertEquals(RouteReach.Reach.WALK_LAST_LEG, reach)
        assertTrue(RouteReach.trustworthy(reach))
        // But a gap genuinely longer than the drive is still caught, at any scale.
        assertEquals(
            RouteReach.Reach.UNREACHABLE,
            RouteReach.classify(snapMeters = 600.0, routeMeters = 500.0),
        )
    }

    @Test fun anAbsentSnapMakesNoClaimEitherWay() {
        // A server that does not report waypoints tells us nothing. It must not be read as a problem,
        // and it must not be read as an all-clear that suppresses a caveat we would otherwise show.
        val reach = RouteReach.classify(snapMeters = null, routeMeters = 3_100.0)
        assertEquals(RouteReach.Reach.UNKNOWN, reach)
        assertNull(RouteReach.describe(reach, null))
        assertTrue("silence must not block the route", RouteReach.trustworthy(reach))
        // Nonsense values are treated the same way rather than crashing or being believed.
        assertEquals(RouteReach.Reach.UNKNOWN, RouteReach.classify(-1.0, 3_100.0))
        assertEquals(RouteReach.Reach.UNKNOWN, RouteReach.classify(Double.NaN, 3_100.0))
    }

    @Test fun theBoundariesAreWhereTheyClaimToBe() {
        assertEquals(RouteReach.Reach.ON_ROAD, RouteReach.classify(RouteReach.ON_ROAD_M, 100_000.0))
        assertEquals(
            RouteReach.Reach.WALK_LAST_LEG,
            RouteReach.classify(RouteReach.ON_ROAD_M + 0.1, 100_000.0),
        )
        assertEquals(
            RouteReach.Reach.WALK_LAST_LEG,
            RouteReach.classify(RouteReach.WALK_LIMIT_M - 0.1, 100_000.0),
        )
        assertEquals(
            RouteReach.Reach.UNREACHABLE,
            RouteReach.classify(RouteReach.WALK_LIMIT_M, 100_000.0),
        )
    }

    @Test fun theCaveatReadsLikeSomethingAPersonWouldSay() {
        assertEquals("450 m", RouteReach.roundedDistance(470.0))
        assertEquals("2.7 km", RouteReach.roundedDistance(2_755.0))
        assertEquals("19 km", RouteReach.roundedDistance(19_256.0))
    }
}
