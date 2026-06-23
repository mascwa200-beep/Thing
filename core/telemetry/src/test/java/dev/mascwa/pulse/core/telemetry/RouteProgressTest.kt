package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressTest {

    // A straight west→east polyline near the equator (1° lon ~= 111.32 km).
    private val route = listOf(
        0.0 to 0.0,
        0.0 to 0.1,
        0.0 to 0.2,
        0.0 to 0.3,
    )

    @Test
    fun nullWhenTooFewPoints() {
        assertNull(RouteProgress.remainingMeters(emptyList(), 0.0, 0.0))
        assertNull(RouteProgress.remainingMeters(listOf(0.0 to 0.0), 0.0, 0.0))
    }

    @Test
    fun remainingIsFullLengthAtStart() {
        val total = RouteProgress.totalMeters(route)
        val rem = RouteProgress.remainingMeters(route, 0.0, 0.0)!!
        assertEquals(total, rem, 1.0)
    }

    @Test
    fun remainingNearZeroAtDestination() {
        val rem = RouteProgress.remainingMeters(route, 0.0, 0.3)!!
        assertTrue("remaining at end should be ~0 but was $rem", rem < 5.0)
    }

    @Test
    fun remainingNearMidpointIsAboutHalf() {
        val total = RouteProgress.totalMeters(route)
        val rem = RouteProgress.remainingMeters(route, 0.0, 0.15)!!
        // Vertex-snapping rounds to the nearest dense vertex, so allow a generous band around half.
        assertTrue("remaining $rem should be roughly half of $total", rem in (total * 0.4)..(total * 0.9))
    }

    @Test
    fun remainingDecreasesAsUserAdvances() {
        val atQuarter = RouteProgress.remainingMeters(route, 0.0, 0.075)!!
        val atHalf = RouteProgress.remainingMeters(route, 0.0, 0.15)!!
        val atThreeQuarter = RouteProgress.remainingMeters(route, 0.0, 0.225)!!
        assertTrue(atQuarter > atHalf)
        assertTrue(atHalf > atThreeQuarter)
    }

    @Test
    fun totalLengthOfSimpleRoute() {
        // 0.3° of longitude at the equator ~= 33.4 km.
        val total = RouteProgress.totalMeters(route)
        assertEquals(33_400.0, total, 500.0)
    }
}
