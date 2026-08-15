package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-bearing test here is [theTerminatorAgreesWithTheIndependentlyValidatedEphemeris].
 *
 * This core derives daylight from the subsolar point; [Ephemeris] derives it by rotating the Sun's
 * equatorial position into an observer's horizon, and was itself checked against JPL DE421. They
 * share only the underlying solar position, so agreement across the globe is two different routes
 * to the same answer rather than one implementation grading its own homework.
 */
class TerminatorTest {

    /** 2026-08-15 00:00:00 UTC. */
    private val t0 = 1_786_752_000_000L

    @Test fun theSubSolarLatitudeIsTheSunsDeclinationByDefinition() {
        for (offsetDays in listOf(0L, 45L, 90L, 180L, 270L)) {
            val t = t0 + offsetDays * 86_400_000L
            val sub = Terminator.subSolarPoint(t)
            val sun = Ephemeris.sunEquatorial(t)
            assertEquals("declination mismatch at +$offsetDays d", sun.declinationDeg, sub.latitudeDeg, 1e-9)
            assertTrue("longitude not normalised: ${sub.longitudeDeg}", sub.longitudeDeg in -180.0..180.0)
            // The Sun never strays outside the tropics.
            assertTrue("declination out of range: ${sub.latitudeDeg}", abs(sub.latitudeDeg) <= 23.5)
        }
    }

    @Test fun theSubSolarPointCirclesTheGlobeOnceADay() {
        val start = Terminator.subSolarPoint(t0).longitudeDeg
        // Six hours later it should have moved about 90 degrees west.
        val sixHours = Terminator.subSolarPoint(t0 + 6 * 3600_000L).longitudeDeg
        val moved = Geodesy.normalizeLongitude(start - sixHours)
        assertEquals("should track ~15 deg per hour", 90.0, moved, 1.0)
        // And a full day later it is back where it started.
        val nextDay = Terminator.subSolarPoint(t0 + 86_400_000L).longitudeDeg
        assertEquals(0.0, abs(Geodesy.normalizeLongitude(start - nextDay)), 1.5)
    }

    @Test fun theTerminatorAgreesWithTheIndependentlyValidatedEphemeris() {
        var checked = 0
        var disagreements = 0
        for (dayOffset in listOf(0L, 60L, 120L, 200L, 280L)) {
            val t = t0 + dayOffset * 86_400_000L
            var lat = -80.0
            while (lat <= 80.0) {
                var lon = -180.0
                while (lon < 180.0) {
                    val mine = Terminator.sunAltitudeDeg(lat, lon, t)
                    val theirs = Ephemeris.sunPosition(lat, lon, t).altitudeDeg
                    assertEquals(
                        "altitude disagreement at ($lat, $lon)", theirs, mine, 1e-6,
                    )
                    // Near the horizon the two can straddle zero on rounding alone; anywhere else
                    // they must call daylight the same way.
                    if (abs(theirs) > 0.01 && Terminator.isDaylight(lat, lon, t) != (theirs > 0)) {
                        disagreements++
                    }
                    checked++
                    lon += 20.0
                }
                lat += 20.0
            }
        }
        assertTrue("expected a broad sweep, only checked $checked points", checked > 400)
        assertEquals("daylight verdicts must agree", 0, disagreements)
    }

    @Test fun everyPointOnTheCurveHasTheSunOnItsHorizon() {
        for (dayOffset in listOf(0L, 100L, 250L)) {
            val t = t0 + dayOffset * 86_400_000L
            val curve = Terminator.curve(t, stepDeg = 10.0)
            assertTrue(curve.isNotEmpty())
            for ((lat, lon) in curve) {
                val alt = Ephemeris.sunPosition(lat, lon, t).altitudeDeg
                assertEquals("point ($lat, $lon) is not on the terminator", 0.0, alt, 0.05)
            }
        }
    }

    @Test fun theCurveSpansTheWholeWorldAndStaysInBounds() {
        val curve = Terminator.curve(t0, stepDeg = 2.0)
        assertEquals("should sample -180..180 inclusive at 2 deg", 181, curve.size)
        assertTrue(curve.all { it.first in -90.0..90.0 })
        assertTrue(curve.all { it.second in -180.0..180.0 })
        // A finer step gives more points; a coarser one fewer, and both stay bounded.
        assertTrue(Terminator.curve(t0, stepDeg = 1.0).size > curve.size)
        assertTrue(Terminator.curve(t0, stepDeg = 10.0).size < curve.size)
        // Absurd steps are clamped rather than producing a runaway or empty list.
        assertTrue(Terminator.curve(t0, stepDeg = 0.0).isNotEmpty())
        assertTrue(Terminator.curve(t0, stepDeg = 9999.0).isNotEmpty())
    }

    @Test fun theCurveEndsAtBothEndsOfTheWorldRatherThanTwiceAtOne() {
        // The sweep runs from one edge of the map to the other. Normalising its longitudes would
        // fold +180 back onto -180 -- the same place on a globe, the wrong end of a sweep -- and
        // the night polygon would then close its ring at one edge instead of spanning the world.
        val curve = Terminator.curve(t0, stepDeg = 2.0)
        assertEquals(-180.0, curve.first().second, 1e-9)
        assertEquals(180.0, curve.last().second, 1e-9)
        // Longitude increases the whole way; no fold-back anywhere in the middle either.
        for (i in 1 until curve.size) {
            assertTrue("longitude went backwards at $i", curve[i].second > curve[i - 1].second)
        }
    }

    @Test fun theNightPolygonSpansTheWholeWorldAndReachesTheDarkPole() {
        val ring = Terminator.nightPolygon(t0, stepDeg = 5.0)
        // Both bottom corners are present, so the fill covers every longitude down to the pole.
        val darkPole = if (Terminator.subSolarPoint(t0).latitudeDeg >= 0) -90.0 else 90.0
        assertTrue("missing the eastern pole corner", ring.any { it == darkPole to 180.0 })
        assertTrue("missing the western pole corner", ring.any { it == darkPole to -180.0 })
        // Every point on the terminator itself really is a place where the Sun is on the horizon,
        // and the whole ring stays inside the two poles the corners define.
        val onCurve = ring.dropLast(3)
        assertTrue(onCurve.size > 60)
        for ((lat, lon) in onCurve) {
            assertEquals(0.0, Ephemeris.sunPosition(lat, lon, t0).altitudeDeg, 0.05)
        }
        // A degenerate ring (both corners at one edge) would leave the curve's own span short.
        val span = onCurve.maxOf { it.second } - onCurve.minOf { it.second }
        assertEquals("the curve must cover every longitude", 360.0, span, 1e-9)
    }

    @Test fun theFilledRingActuallyContainsTheNightSide() {
        // The strongest statement available without a renderer: take the ring as drawn and ask, for
        // a grid of real places, whether it encloses exactly the ones where the Sun has set. A ring
        // that closes at the wrong edge still passes "has a pole in it" and "first == last", but
        // fails this immediately.
        for (dayOffset in listOf(0L, 95L, 190L, 285L)) {
            val t = t0 + dayOffset * 86_400_000L
            val ring = Terminator.nightPolygon(t, stepDeg = 1.0)
            var checked = 0
            var lat = -84.0
            while (lat <= 84.0) {
                var lon = -175.0
                while (lon <= 175.0) {
                    val altitude = Terminator.sunAltitudeDeg(lat, lon, t)
                    // Skip the horizon itself: a polygon sampled every degree cannot be expected to
                    // resolve which side of the line a place a few hundred metres from it is on.
                    if (kotlin.math.abs(altitude) > 1.0) {
                        assertEquals(
                            "($lat, $lon) at +$dayOffset d: Sun at $altitude deg",
                            altitude < 0,
                            contains(ring, lat, lon),
                        )
                        checked++
                    }
                    lon += 25.0
                }
                lat += 12.0
            }
            assertTrue("expected a real sweep, checked $checked", checked > 90)
        }
    }

    /** Even-odd ray cast in (longitude, latitude) space, where the ring is a simple polygon. */
    private fun contains(ring: List<Pair<Double, Double>>, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (latI, lonI) = ring[i]
            val (latJ, lonJ) = ring[j]
            if ((lonI > lon) != (lonJ > lon)) {
                val crossLat = latI + (lon - lonI) / (lonJ - lonI) * (latJ - latI)
                if (lat < crossLat) inside = !inside
            }
            j = i
        }
        return inside
    }

    @Test fun theNightPolygonClosesAcrossTheDarkPole() {
        // Northern summer: the Sun is north, so the dark pole is the south one.
        val summer = Terminator.nightPolygon(t0, stepDeg = 10.0)
        assertTrue(summer.isNotEmpty())
        assertTrue("declination is northward here", Terminator.subSolarPoint(t0).latitudeDeg > 0)
        assertTrue("must close across the south pole", summer.any { it.first == -90.0 })
        assertEquals("ring must close", summer.first(), summer.last())

        // Half a year later the situation reverses.
        val winter = t0 + 182 * 86_400_000L
        assertTrue(Terminator.subSolarPoint(winter).latitudeDeg < 0)
        assertTrue(
            "must close across the north pole",
            Terminator.nightPolygon(winter, stepDeg = 10.0).any { it.first == 90.0 },
        )
    }

    @Test fun halfTheWorldIsAlwaysLit() {
        // A sphere lit by a distant source is half lit, whatever the season. Anything far from a
        // half means the geometry is wrong somewhere.
        for (dayOffset in listOf(0L, 90L, 180L, 270L)) {
            val fraction = Terminator.daylightFraction(t0 + dayOffset * 86_400_000L, samples = 40)
            assertEquals("daylight fraction at +$dayOffset d", 0.5, fraction, 0.02)
        }
    }

    @Test fun anEquinoxDoesNotBlowUpTheGeometry() {
        // Around an equinox the declination passes through zero and the naive formula divides by
        // tan(0). Sweep an hour either side of the crossing and require finite, in-range output.
        var t = t0
        var crossing = -1L
        var previous = Ephemeris.sunEquatorial(t).declinationDeg
        while (t < t0 + 200L * 86_400_000L) {
            t += 3600_000L
            val dec = Ephemeris.sunEquatorial(t).declinationDeg
            if (previous > 0 && dec <= 0) { crossing = t; break }
            previous = dec
        }
        assertTrue("fixture never found an equinox", crossing > 0)
        for (offsetHours in -2..2) {
            val at = crossing + offsetHours * 3600_000L
            val curve = Terminator.curve(at, stepDeg = 10.0)
            assertTrue(curve.isNotEmpty())
            assertTrue(
                "non-finite latitude at the equinox",
                curve.all { it.first.isFinite() && it.first in -90.0..90.0 },
            )
            assertEquals(0.5, Terminator.daylightFraction(at, samples = 30), 0.03)
        }
    }
}
