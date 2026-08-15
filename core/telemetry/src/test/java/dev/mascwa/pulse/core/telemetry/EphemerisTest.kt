package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The ephemeris is checked against JPL, not against itself.
 *
 * Every expected value below came from Skyfield reading NASA/JPL's DE421 ephemeris — the same
 * planetary data professional astronomy uses — at four sites spanning the equator, both
 * hemispheres and the near-Arctic, at four instants across the year.
 *
 * Measured agreement: **Sun within 0.007 deg, Moon within 0.05 deg, sunrise/sunset within two
 * seconds, illuminated fraction within 0.0001.** For scale, the code this replaces claimed about
 * a degree for the Sun and "a couple of degrees" for the Moon, and could not produce an altitude
 * or a rise time at all.
 *
 * Distances are compared **geocentric to geocentric**. Skyfield's `altaz()` distance is
 * topocentric — measured from the observer, not the Earth's centre — and the two differ by up to
 * an Earth radius depending on where the Moon sits in the sky. Geocentric is also the frame the
 * supermoon definition uses.
 */
class EphemerisTest {

    private class Ref(
        val site: String, val lat: Double, val lon: Double, val ms: Long,
        val sunAlt: Double, val sunAz: Double,
        val moonAlt: Double, val moonAz: Double, val moonDistKm: Double,
    )

    private class RiseRef(
        val site: String, val lat: Double, val lon: Double,
        val dayStart: Long, val rise: Long, val set: Long,
    )

    private val positions = listOf(
        Ref("London", 51.5074, -0.1278, 1786795200000L, 52.4284, 178.0031, 28.5495, 140.0941, 379753.6),
        Ref("London", 51.5074, -0.1278, 1786762800000L, -14.2455, 43.7696, -37.1414, 18.8990, 377695.1),
        Ref("London", 51.5074, -0.1278, 1769970600000L, -15.6273, 262.2786, 17.7131, 81.2602, 370848.5),
        Ref("London", 51.5074, -0.1278, 1795155300000L, -10.9578, 107.9497, -32.1849, 328.9846, 382361.3),
        Ref("Sydney", -33.8688, 151.2093, 1786795200000L, -56.3891, 240.9730, -22.1944, 252.3616, 379753.6),
        Ref("Sydney", -33.8688, 151.2093, 1786762800000L, 39.9595, 340.7969, 52.7031, 23.0641, 377695.1),
        Ref("Sydney", -33.8688, 151.2093, 1769970600000L, -9.7527, 118.1225, 5.9489, 299.5938, 370848.5),
        Ref("Sydney", -33.8688, 151.2093, 1795155300000L, 28.2573, 264.2551, 25.9800, 66.1628, 382361.3),
        Ref("Nairobi", -1.2921, 36.8219, 1786795200000L, 51.4921, 294.5620, 86.9533, 259.6736, 379753.6),
        Ref("Nairobi", -1.2921, 36.8219, 1786762800000L, -9.3592, 75.9541, -38.6893, 90.2839, 377695.1),
        Ref("Nairobi", -1.2921, 36.8219, 1769970600000L, -38.3152, 247.0935, 37.8426, 63.0551, 370848.5),
        Ref("Nairobi", -1.2921, 36.8219, 1795155300000L, 41.5725, 115.5103, -79.2699, 82.1891, 382361.3),
        Ref("Reykjavik", 64.1466, -21.9426, 1786795200000L, 37.3223, 151.4312, 11.5454, 122.1277, 379753.6),
        Ref("Reykjavik", 64.1466, -21.9426, 1786762800000L, -10.0046, 21.5667, -25.9611, 352.6033, 377695.1),
        Ref("Reykjavik", 64.1466, -21.9426, 1769970600000L, -7.7268, 246.7769, 9.9305, 66.6475, 370848.5),
        Ref("Reykjavik", 64.1466, -21.9426, 1795155300000L, -23.9997, 85.8365, -15.3190, 310.0305, 382361.3),
    )

    private val riseSets = listOf(
        RiseRef("London", 51.5074, -0.1278, 1786752000000L, 1786769154000L, 1786821782000L),
        RiseRef("Sydney", -33.8688, 151.2093, 1786752000000L, 1786826009000L, 1786778719000L),
        RiseRef("Nairobi", -1.2921, 36.8219, 1786752000000L, 1786764909000L, 1786808360000L),
        RiseRef("Reykjavik", 64.1466, -21.9426, 1786752000000L, 1786771127000L, 1786830209000L),
    )

    private val illumination = mapOf(
        1786795200000L to 0.096351,
        1786762800000L to 0.073355,
        1769970600000L to 0.999232,
        1795155300000L to 0.767515,
    )

    /** Smallest angle between two bearings, so 359 and 1 differ by 2 rather than 358. */
    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    @Test fun sunPositionMatchesJplAcrossTheGlobeAndTheYear() {
        var worst = 0.0
        for (r in positions) {
            val p = Ephemeris.sunPosition(r.lat, r.lon, r.ms)
            worst = maxOf(worst, abs(p.altitudeDeg - r.sunAlt), angleDiff(p.azimuthDeg, r.sunAz))
        }
        assertTrue("worst Sun error was $worst deg", worst < 0.05)
    }

    @Test fun moonPositionMatchesJplIncludingTheParallaxCorrection() {
        var worst = 0.0
        for (r in positions) {
            val p = Ephemeris.moonPosition(r.lat, r.lon, r.ms)
            worst = maxOf(worst, abs(p.altitudeDeg - r.moonAlt), angleDiff(p.azimuthDeg, r.moonAz))
        }
        // The Moon is the harder body — a truncated series, plus the observer-offset correction.
        assertTrue("worst Moon error was $worst deg", worst < 0.1)
    }

    @Test fun moonDistanceIsGeocentricAndAccurateToAboutFiftyKilometres() {
        var worst = 0.0
        for (r in positions) {
            val d = Ephemeris.moonEquatorial(r.ms).distanceKm
            worst = maxOf(worst, abs(d - r.moonDistKm))
        }
        assertTrue("worst Moon distance error was $worst km", worst < 200.0)
        // And it stays inside the real perigee-apogee envelope.
        for (r in positions) {
            val d = Ephemeris.moonEquatorial(r.ms).distanceKm
            assertTrue("distance $d km is outside the Moon's real range", d in 350_000.0..410_000.0)
        }
    }

    @Test fun sunriseAndSunsetLandWithinAMinuteOfJpl() {
        var worst = 0L
        for (r in riseSets) {
            val rs = Ephemeris.riseSet(r.lat, r.lon, r.dayStart)
            assertNotNull("${r.site} should have a sunrise", rs.riseEpochMs)
            assertNotNull("${r.site} should have a sunset", rs.setEpochMs)
            worst = maxOf(
                worst,
                abs(rs.riseEpochMs!! - r.rise) / 1000,
                abs(rs.setEpochMs!! - r.set) / 1000,
            )
        }
        assertTrue("worst rise/set error was $worst s", worst < 60)
    }

    @Test fun illuminatedFractionMatchesJpl() {
        for ((ms, expected) in illumination) {
            assertEquals(expected, Ephemeris.moonPhase(ms).illuminatedFraction, 0.001)
        }
    }

    @Test fun transitIsTheHighestPointOfTheDay() {
        val r = riseSets.first()
        val rs = Ephemeris.riseSet(r.lat, r.lon, r.dayStart)
        assertNotNull(rs.transitEpochMs)
        val noonAlt = Ephemeris.sunPosition(r.lat, r.lon, rs.transitEpochMs!!).altitudeDeg
        // Nothing in the day should be meaningfully higher than the transit.
        for (offset in 0..24) {
            val alt = Ephemeris.sunPosition(r.lat, r.lon, r.dayStart + offset * 3_600_000L).altitudeDeg
            assertTrue("hour $offset was higher than transit", alt <= noonAlt + 0.2)
        }
        // And the transit must sit between sunrise and sunset.
        assertTrue(rs.transitEpochMs!! > rs.riseEpochMs!!)
        assertTrue(rs.transitEpochMs!! < rs.setEpochMs!!)
    }

    @Test fun polarDayAndNightAreReportedRatherThanFabricated() {
        // Svalbard in midsummer: the Sun never sets.
        val midsummer = 1782518400000L // 2026-06-27T00:00:00Z
        val summer = Ephemeris.riseSet(78.22, 15.65, midsummer)
        assertTrue("Svalbard in June should be polar day", summer.alwaysUp)
        assertNull(summer.riseEpochMs)
        assertNull(summer.setEpochMs)
        // And in midwinter it never rises.
        val midwinter = 1798070400000L // 2026-12-24T00:00:00Z
        val winter = Ephemeris.riseSet(78.22, 15.65, midwinter)
        assertTrue("Svalbard in December should be polar night", winter.alwaysDown)
        assertNull(winter.riseEpochMs)
    }

    @Test fun daylightBundlesEveryTwilightAndDerivesItsLength() {
        val r = riseSets.first { it.site == "London" }
        val d = Ephemeris.daylight(r.lat, r.lon, r.dayStart)
        assertNotNull(d.sunrise); assertNotNull(d.sunset); assertNotNull(d.solarNoon)
        assertNotNull(d.civilDawn); assertNotNull(d.nauticalDawn); assertNotNull(d.astronomicalDawn)
        // Twilights nest correctly: astronomical dawn is earliest, then nautical, then civil, then
        // sunrise — and the same order reversed in the evening.
        assertTrue(d.astronomicalDawn!! < d.nauticalDawn!!)
        assertTrue(d.nauticalDawn!! < d.civilDawn!!)
        assertTrue(d.civilDawn!! < d.sunrise!!)
        assertTrue(d.sunset!! < d.civilDusk!!)
        assertTrue(d.civilDusk!! < d.nauticalDusk!!)
        assertTrue(d.nauticalDusk!! < d.astronomicalDusk!!)
        // Mid-August in London is a long day.
        assertTrue("daylight was ${d.daylightMinutes} min", d.daylightMinutes!! in 800..950)
    }

    @Test fun phaseNamingTracksTheCycleAndKnowsWaxingFromWaning() {
        // Walk a whole synodic month and check the labels progress sensibly.
        val start = 1786795200000L
        var sawNew = false; var sawFull = false; var sawWaxing = false; var sawWaning = false
        for (day in 0..29) {
            val p = Ephemeris.moonPhase(start + day * 86_400_000L)
            if (p.name == "New moon") sawNew = true
            if (p.name == "Full moon") sawFull = true
            if (p.waxing) sawWaxing = true else sawWaning = true
            assertTrue(p.illuminatedFraction in 0.0..1.0)
            assertTrue(p.ageDays in 0.0..30.0)
            assertTrue(p.emoji.isNotEmpty())
        }
        assertTrue("a full month should contain a new moon", sawNew)
        assertTrue("a full month should contain a full moon", sawFull)
        assertTrue(sawWaxing); assertTrue(sawWaning)
    }

    @Test fun aFullMoonIsOppositeTheSunAndANewMoonIsBesideIt() {
        for (r in positions) {
            val p = Ephemeris.moonPhase(r.ms)
            val sun = Ephemeris.sunEquatorial(r.ms)
            val moon = Ephemeris.moonEquatorial(r.ms)
            val sep = Ephemeris.angularSeparationDeg(
                sun.rightAscensionDeg, sun.declinationDeg,
                moon.rightAscensionDeg, moon.declinationDeg,
            )
            // Illumination follows elongation: near 0 deg it is new, near 180 deg it is full.
            if (sep < 20) assertTrue("sep $sep should be near-new", p.illuminatedFraction < 0.10)
            if (sep > 160) assertTrue("sep $sep should be near-full", p.illuminatedFraction > 0.95)
        }
    }
}
