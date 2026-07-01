package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [TravelFilter] — the fix for "distance climbs while I'm standing still". */
class TravelFilterTest {

    // ~1.11 m per 0.00001° latitude, so it's easy to build fixes a known distance apart.
    private val baseLat = 40.0
    private val baseLon = -74.0

    private fun north(meters: Double): Double = baseLat + meters / 111_320.0

    @Test fun firstFixJustSetsAnchorNoDistance() {
        val s = TravelFilter.step(null, null, baseLat, baseLon, 8.0)
        assertEquals(0.0, s.addedM, 0.001)
        assertEquals(baseLat, s.anchorLat!!, 1e-9)
        assertEquals(baseLon, s.anchorLon!!, 1e-9)
    }

    @Test fun stationaryJitterAddsNothing() {
        // Sit still with a good fix; feed 100 small wanders (each < the 12 m floor). Distance must stay 0.
        var anchorLat: Double? = null
        var anchorLon: Double? = null
        var total = 0.0
        val jitter = doubleArrayOf(3.0, -4.0, 6.0, -2.0, 5.0, -6.0, 4.0, -3.0, 7.0, -5.0)
        repeat(100) { i ->
            val lat = north(jitter[i % jitter.size]) // ±2–7 m around the anchor
            val s = TravelFilter.step(anchorLat, anchorLon, lat, baseLon, 8.0)
            total += s.addedM
            anchorLat = s.anchorLat; anchorLon = s.anchorLon
        }
        assertEquals(0.0, total, 0.001) // the reported bug: this used to grow without bound
    }

    @Test fun realWalkAccumulates() {
        // Walk north in ~15 m steps (above the 12 m floor); each should count.
        var anchorLat: Double? = null
        var anchorLon: Double? = null
        var total = 0.0
        var pos = 0.0
        repeat(10) {
            pos += 15.0
            val s = TravelFilter.step(anchorLat, anchorLon, north(pos), baseLon, 8.0)
            total += s.addedM
            anchorLat = s.anchorLat; anchorLon = s.anchorLon
        }
        // The first fix only sets the anchor, so 9 counted 15 m steps ≈ 135 m (haversine, small tolerance).
        assertEquals(135.0, total, 5.0)
    }

    @Test fun noisyFixIsIgnoredAndKeepsAnchor() {
        // A wildly inaccurate fix must not move the anchor or add distance.
        val s = TravelFilter.step(baseLat, baseLon, north(500.0), baseLon, 80.0) // accuracy 80 m > cap
        assertEquals(0.0, s.addedM, 0.001)
        assertEquals(baseLat, s.anchorLat!!, 1e-9) // anchor unchanged
    }

    @Test fun teleportReanchorsWithoutCounting() {
        // A 1 km jump between fixes is a glitch, not walking — re-anchor, count nothing.
        val s = TravelFilter.step(baseLat, baseLon, north(1000.0), baseLon, 8.0)
        assertEquals(0.0, s.addedM, 0.001)
        assertEquals(north(1000.0), s.anchorLat!!, 1e-9) // re-anchored to the new spot
    }

    @Test fun poorAccuracyRaisesTheStepThreshold() {
        // With accuracy 25 m, a 20 m move is within the uncertainty → ignored; a 30 m move counts.
        val ignored = TravelFilter.step(baseLat, baseLon, north(20.0), baseLon, 25.0)
        assertEquals(0.0, ignored.addedM, 0.001)
        assertEquals(baseLat, ignored.anchorLat!!, 1e-9)
        val counted = TravelFilter.step(baseLat, baseLon, north(30.0), baseLon, 25.0)
        assertTrue(counted.addedM > 25.0)
    }

    @Test fun distanceMetersMatchesKnownSpan() {
        assertEquals(111.32, TravelFilter.distanceMeters(baseLat, baseLon, north(111.32), baseLon), 1.0)
    }
}
