package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProfileTest {

    /** A due-north line from the equator: one degree of latitude is very nearly 111 km. */
    private fun northLine(points: Int, degSpan: Double = 0.1) =
        (0 until points).map { 0.0 + degSpan * it / (points - 1) to 0.0 }

    @Test fun samplesAreEvenlySpacedByDistanceNotByShapePoint() {
        // A route whose shape points are deliberately lopsided: nine of them crammed into the
        // first tenth, one covering the rest. Sampling the points directly would spend nine tenths
        // of the chart on a tenth of the journey.
        val lopsided = buildList {
            for (i in 0 until 9) add(0.0 + 0.001 * i to 0.0)
            add(0.1 to 0.0)
        }
        val samples = RouteProfile.sample(lopsided, 11)
        assertEquals(11, samples.size)
        val step = samples[1].distanceM - samples[0].distanceM
        for (i in 2 until samples.size) {
            assertEquals("gap $i", step, samples[i].distanceM - samples[i - 1].distanceM, 1.0)
        }
        // Ends are included exactly.
        assertEquals(0.0, samples.first().distanceM, 1e-6)
        assertEquals(0.0, samples.first().latitudeDeg, 1e-9)
        assertEquals(0.1, samples.last().latitudeDeg, 1e-6)
    }

    @Test fun theSampledLengthMatchesTheRoute() {
        val route = northLine(12)
        val samples = RouteProfile.sample(route, 24)
        var measured = 0.0
        for (i in 1 until route.size) {
            measured += Geodesy.distanceMeters(
                route[i - 1].first, route[i - 1].second, route[i].first, route[i].second,
            )
        }
        assertEquals(measured, samples.last().distanceM, 1.0)
        // 0.1 degrees of latitude on Geodesy's sphere: 0.1 * pi/180 * 6 371 000 m. Not the 11 057 m
        // of a WGS-84 meridional degree -- the whole core is haversine on the IUGG mean radius, and
        // a test that expected the ellipsoidal figure would be marking the wrong answer correct.
        assertTrue("unexpected length ${samples.last().distanceM}", abs(samples.last().distanceM - 11_119.5) < 5.0)
    }

    @Test fun degenerateRoutesDoNotCrash() {
        assertTrue(RouteProfile.sample(emptyList(), 10).isEmpty())
        assertEquals(1, RouteProfile.sample(listOf(1.0 to 2.0), 10).size)
        // Every point in the same place: a zero-length route is one sample, not a division by zero.
        assertEquals(1, RouteProfile.sample(List(5) { 1.0 to 2.0 }, 10).size)
        // Asking for fewer than two samples degrades to the start rather than misbehaving.
        assertEquals(1, RouteProfile.sample(northLine(5), 1).size)
    }

    @Test fun climbIgnoresTheTerrainGridsOwnNoise() {
        val samples = RouteProfile.sample(northLine(5), 6)
        // A flat road whose sampled heights alternate by a metre or two, which is what stepping
        // between neighbouring cells of a terrain grid looks like.
        val flat = listOf(40.0, 41.5, 39.0, 41.0, 40.5, 39.5)
        val summary = RouteProfile.summarise(samples, flat)
        assertNotNull(summary)
        assertEquals(0.0, summary!!.ascentM, 0.001)
        assertEquals(0.0, summary.descentM, 0.001)
        assertEquals(39.0, summary.minElevationM, 0.001)
        assertEquals(41.5, summary.maxElevationM, 0.001)
        // Under ten metres of relief there is nothing worth saying.
        assertNull(summary.describe())
    }

    @Test fun aRealClimbIsCounted() {
        val samples = RouteProfile.sample(northLine(5), 6)
        val hill = listOf(100.0, 140.0, 180.0, 220.0, 180.0, 150.0)
        val summary = RouteProfile.summarise(samples, hill)!!
        assertEquals(120.0, summary.ascentM, 0.001)
        assertEquals(70.0, summary.descentM, 0.001)
        assertEquals(100.0, summary.minElevationM, 0.001)
        assertEquals(220.0, summary.maxElevationM, 0.001)
        assertNotNull(summary.describe())
    }

    @Test fun aShortOrRaggedElevationReplyIsUsedAsFarAsItGoes() {
        val samples = RouteProfile.sample(northLine(5), 6)
        // The service answered for only part of the route.
        val partial = listOf(10.0, 60.0, 110.0)
        val summary = RouteProfile.summarise(samples, partial)!!
        assertEquals(100.0, summary.ascentM, 0.001)
        // The reported length is that of the part actually covered, not the whole route.
        assertEquals(samples[2].distanceM, summary.lengthM, 0.001)
        // Nothing usable at all yields null rather than a summary of one point.
        assertNull(RouteProfile.summarise(samples, listOf(10.0)))
        assertNull(RouteProfile.summarise(samples, listOf(Double.NaN, Double.NaN)))
        assertNull(RouteProfile.summarise(emptyList(), listOf(1.0, 2.0)))
    }
}
