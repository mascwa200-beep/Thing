package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.telemetry.SatellitePasses
import dev.mascwa.pulse.core.telemetry.Tle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Home digest is willing to say about the ISS, and — mostly — what it refuses to say.
 *
 * The line used to be one sentence: if the fetched ground point was within 1,200 km, print the
 * distance. Nothing anywhere asked how old that position was, and it moves about 416 km a minute.
 */
class SkyDigestIssLineTest {

    private val moon = MoonInfo("Waxing gibbous", 0.72, "🌔")

    // The live ISS element set, the same one the pass tests parse.
    private val iss = Tle.parse(
        "1 25544U 98067A   26226.82569810  .00005167  00000+0  10032-3 0  9994",
        "2 25544  51.6328   9.8801 0007568  46.9314 313.2307 15.49444701580849",
        "ISS (ZARYA)",
    )!!

    /** 2026-08-15 00:00:00 UTC, the pass suite's fixture epoch. */
    private val t0 = 1_786_752_000_000L
    private fun at(minutes: Long) = t0 + minutes * 60_000L

    private val nyc = SatellitePasses.Site(40.7128, -74.0060, 10.0)
    private val nairobi = SatellitePasses.Site(-1.2921, 36.8219, 0.0)

    /** A fetched position directly over New York, so distance is never the reason a line is absent. */
    private fun overhead(timestampMs: Long) = OrbitalData(
        iss = IssPosition(
            latitude = 40.7128,
            longitude = -74.0060,
            altitudeKm = 420.0,
            velocityKmh = 27_580.0,
            timestampMs = timestampMs,
        ),
        moon = moon,
    )

    private fun issLineOf(
        data: OrbitalData,
        sighting: SatellitePasses.Sighting? = null,
        nowMs: Long = t0,
    ): String? = SkyDigest
        .lines(data, space = null, lat = 40.7128, lon = -74.0060, sighting = sighting, nowMs = nowMs)
        .firstOrNull { it.contains("ISS") }

    // ---- the fetched position, which has an age --------------------------------------------

    @Test fun aFreshFetchedPositionIsStillUsed() {
        // Nothing about the fallback's judgement changed; only its precondition did.
        val line = issLineOf(overhead(t0 - 5_000L))
        assertEquals("🛰️ ISS passing near — 0 km from its ground point", line)
    }

    @Test fun aPositionOlderThanTheDistanceItCouldHaveTravelledSaysNothing() {
        // Five minutes is what the repository's cache allows, and 2,081 km of travel — further than
        // the 1,200 km the claim is about. A hedge on a number that wrong is still a number.
        assertEquals(null, issLineOf(overhead(t0 - 5 * 60_000L)))
        // The boundary itself, and one millisecond past it.
        assertTrue(issLineOf(overhead(t0 - SkyDigest.FETCHED_FIX_USABLE_MS)) != null)
        assertEquals(null, issLineOf(overhead(t0 - SkyDigest.FETCHED_FIX_USABLE_MS - 1)))
    }

    @Test fun aPositionOfUnknownAgeIsNotTreatedAsFresh() {
        // Zero is a cache entry written before the timestamp was parsed. Unknown age is not the
        // same as no age, and reading it as "now" is exactly the defect being fixed.
        assertEquals(null, issLineOf(overhead(0L)))
    }

    @Test fun nothingIsSaidWhenThereIsNoPositionAtAll() {
        assertEquals(null, issLineOf(OrbitalData(moon = moon)))
    }

    // ---- the propagated sighting, which has none -------------------------------------------

    @Test fun aPropagatedSightingIsUsedHoweverOldTheFetchedPositionIs() {
        // +1990 min over New York: 19.9 degrees up, sunlit, the Sun 10.8 degrees down.
        val sighting = SatellitePasses.sighting(iss, nyc, at(1990))!!
        // The fetched position is both stale AND on the other side of the planet; neither matters.
        val data = OrbitalData(
            iss = IssPosition(-40.0, 100.0, 420.0, 27_580.0, timestampMs = t0 - 600_000L),
            moon = moon,
        )
        assertEquals(
            "🛰️ ISS overhead now — 20° up to the SE, sunlit and naked-eye",
            issLineOf(data, sighting = sighting),
        )
    }

    @Test fun aStationLostInDaylightIsNotOfferedAsSomethingToLookAt() {
        // +987 min: 51 degrees up and in full sunlight — with the Sun 62 degrees up as well.
        // Saying "overhead" here sends somebody outside to stare at an empty bright sky.
        val line = issLineOf(overhead(t0), sighting = SatellitePasses.sighting(iss, nyc, at(987)))
        assertEquals("🛰️ ISS is 51° up to the N — too bright to see it", line)
    }

    @Test fun aStationInEarthsShadowSaysSoRatherThanGoingQuiet() {
        // +27 min over Nairobi: 78 degrees up in a dark sky, and completely unlit. Worth saying —
        // it is the difference between "there is nothing there" and "there is nothing to see".
        val line = SkyDigest
            .lines(
                OrbitalData(moon = moon),
                space = null,
                lat = -1.2921,
                lon = 36.8219,
                sighting = SatellitePasses.sighting(iss, nairobi, at(27)),
                nowMs = at(27),
            )
            .firstOrNull { it.contains("ISS") }
        assertEquals("🛰️ ISS is 78° up to the SSE — in Earth's shadow, nothing to see", line)
    }

    @Test fun aStationScrapingTheHorizonIsNotMentionedAtAll() {
        // +502 min: genuinely visible, and 1.4 degrees up — behind the first building or tree.
        val sighting = SatellitePasses.sighting(iss, nyc, at(502))!!
        assertTrue("the fixture must be above the horizon to test the elevation cut", sighting.look.aboveHorizon)
        assertEquals(null, issLineOf(overhead(t0), sighting = sighting))
    }
}
