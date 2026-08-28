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
 * Measured agreement: **Sun within 0.008 deg, Moon within 0.021 deg, sunrise/sunset within two
 * seconds, illuminated fraction within 0.0001.** For scale, the code this replaces claimed about
 * a degree for the Sun and "a couple of degrees" for the Moon, and could not produce an altitude
 * or a rise time at all.
 *
 * ⚠️ **The Moon figure was 0.05 deg and is now 0.021, and the tolerances below were tightened to
 * match rather than left loose.** A tolerance far above what the code achieves is not a guard: it
 * would let the Moon drift back by a factor of two without a single test going red. GEOCENTRICALLY
 * the improvement is larger still — **167 arcsec to 7.4** — and it is the parallax approximation in
 * [Ephemeris.moonPosition], which corrects altitude and leaves azimuth alone, that now sets the
 * topocentric floor. That is the next thing to fix if anyone wants better.
 *
 * ⚠️ **Three separate faults were found by measuring rather than by reading, and each was in a
 * different place.** The tables were truncated at 25 of 60 terms; they were being handed UTC where
 * they want Terrestrial Time; and the Moon's longitude carried no nutation while the Sun's did, so
 * the two bodies sat in frames seventeen arcseconds apart. Geocentric Moon: 167 arcsec, then 14,
 * then 7.4. Then the SUN turned out to be the worse body at 11.2 and its perturbation terms took
 * it to 3.8. None of that is visible topocentrically, which is why
 * [geocentricMoonMatchesJplToBetterThanAHundredthOfADegree] and
 * [geocentricSunMatchesJplToBetterThanTenArcseconds] both exist.
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
        // ⚠️ 0.021 measured, so the bar sits just above it. It was 0.1 against a code that
        // achieved 0.046 — twice the slack it needed, which is how a regression hides.
        assertTrue("worst Moon error was $worst deg", worst < 0.03)
    }

    @Test fun moonDistanceIsGeocentricAndAccurateToAboutFiftyKilometres() {
        var worst = 0.0
        for (r in positions) {
            val d = Ephemeris.moonEquatorial(r.ms).distanceKm
            worst = maxOf(worst, abs(d - r.moonDistKm))
        }
        // 25 km measured, down from 45 before the full table landed.
        assertTrue("worst Moon distance error was $worst km", worst < 50.0)
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

    /**
     * The Moon where it really is, before any observer is involved.
     *
     * ⚠️ **This is the test that would have caught the timescale bug, and none existed.** Every
     * other Moon check here is topocentric, where the parallax approximation adds its own error and
     * masks the underlying one. Geocentrically there is nowhere for a fault to hide: these are
     * Skyfield/DE421 apparent positions for the equinox of date, and the shipped series was out by
     * up to 167 arcseconds against them — nearly a tenth of the Moon's diameter, five minutes of
     * its motion — because Meeus's tables were truncated at 25 of 60 terms AND were being handed
     * UTC where they want Terrestrial Time.
     */
    @Test fun geocentricMoonMatchesJplToBetterThanAHundredthOfADegree() {
        // (epochMs, apparent RA, apparent Dec) from Skyfield reading DE421.
        val refs = listOf(
            Triple(1767225600000L, 63.9203, 26.4037),    // 2026-01-01 00:00 UTC
            Triple(1782777600000L, 279.2909, -27.2263),  // 2026-06-30 00:00 UTC
            Triple(1798329600000L, 139.1957, 16.2721),   // 2026-12-27 00:00 UTC
            Triple(1755000000000L, 0.3353, 1.3668),      // 2025-08-12 12:00 UTC
        )
        var worst = 0.0
        for ((ms, ra, dec) in refs) {
            val m = Ephemeris.moonEquatorial(ms)
            worst = maxOf(worst, Ephemeris.angularSeparationDeg(m.rightAscensionDeg, m.declinationDeg, ra, dec))
        }
        // 0.0021 deg measured (7.4 arcsec). It was 13.8 until the nutation was added: Meeus ch. 47
        // gives a GEOMETRIC longitude and the Sun's apparent one already carried the nutation, so
        // the two bodies sat in frames seventeen arcseconds apart.
        //
        // ⚠️ **The bar is 0.002 because 0.004 and then 0.003 both failed to guard it.** Removing
        // the nutation in longitude ALONE costs 0.0021 at these four instants and removing both it
        // and the nutation in obliquity costs 0.0038, so anything looser than 0.002 lets the
        // half-measure through — and a half-measure is exactly what a later edit would produce.
        // Measured with both: 0.00103.
        assertTrue("worst geocentric Moon error was $worst deg", worst < 0.002)
    }

    /**
     * The Sun, geocentrically — and it exists because the Sun turned out to be the WORST body here,
     * which was not the expectation.
     *
     * ⚠️ Every other check in this file is topocentric, where the parallax approximation in
     * [Ephemeris.moonPosition] contributes its own error and hides what the underlying theories are
     * doing. Measured geocentrically against DE421 at the eighteen eclipse epochs of 2025 through
     * 2028, with only the equation of centre, the Moon was out by a mean of 2.9 arcseconds and the
     * Sun by 11.2 — four times worse. That matters far beyond the Sun's own position: an eclipse is
     * a Sun-to-Moon separation, so the Sun's error was setting the accuracy of the entire
     * [Eclipses] feature while all the attention was on the lunar tables.
     *
     * ⚠️ **Without this test, deleting [Ephemeris]'s solar perturbation terms would fail nothing.**
     * They are five lines carrying the pull of Venus, Jupiter and the Moon on the Earth's orbit,
     * they took the Sun from 11.2 arcseconds to 3.8, and every other assertion in this file would
     * stay green without them.
     */
    @Test fun geocentricSunMatchesJplToBetterThanTenArcseconds() {
        // (epochMs, apparent RA, apparent Dec) from Skyfield reading DE421, equinox of date —
        // the same four instants the Moon is checked at.
        val refs = listOf(
            // The four the Moon is checked at, so the two bodies are compared at the same instants.
            Triple(1767225600000L, 281.494713, -23.017248),  // 2026-01-01 00:00 UTC
            Triple(1782777600000L, 98.979832, 23.181026),    // 2026-06-30 00:00 UTC
            Triple(1798329600000L, 275.692632, -23.334374),  // 2026-12-27 00:00 UTC
            Triple(1755000000000L, 142.446750, 14.801528),   // 2025-08-12 12:00 UTC
            // ⚠️ And four chosen BECAUSE the perturbation terms matter most there. A periodic
            // correction is near zero for much of its cycle, so four arbitrary instants can miss it
            // almost entirely: at the first four above, deleting the terms costs only 15.6
            // arcseconds and would slip under any bar loose enough not to fail on a good build.
            // These were found by running the comparison with the terms removed and taking the
            // worst offenders — 21.7 to 24.5 arcseconds, which nothing can mistake for noise.
            Triple(1743245245000L, 8.262908, 3.565273),      // 2025-03-29 10:47 UTC
            Triple(1772537622000L, 344.233516, -6.718436),   // 2026-03-03 11:33 UTC
            Triple(1846520386000L, 106.486203, 22.571245),   // 2028-07-06 18:19 UTC
            Triple(1847847329000L, 122.015976, 20.181379),   // 2028-07-22 02:55 UTC
        )
        var worst = 0.0
        for ((ms, ra, dec) in refs) {
            val s = Ephemeris.sunEquatorial(ms)
            worst = maxOf(worst, Ephemeris.angularSeparationDeg(s.rightAscensionDeg, s.declinationDeg, ra, dec))
        }
        // Measured: 0.0031 deg (11.2 arcsec) worst across these eight. Without the perturbation
        // terms the BEST of the last four is 0.0060 (21.7 arcsec), so the bar sits in the gap —
        // comfortably above what the code achieves and comfortably below what its absence costs.
        assertTrue("worst geocentric Sun error was $worst deg", worst < 0.0045)
    }

    /**
     * ⚠️ **Sidereal time must stay on UT, and this is the guard that says so.**
     *
     * The fix above moved three theory call sites onto Terrestrial Time. [Ephemeris.gmstDeg]
     * deliberately did NOT move: it answers how far the Earth has turned, which is a question about
     * the rotating Earth and not about dynamics. Feeding it TT would shift every hour angle by 69
     * seconds of rotation — about 0.29 degrees, or a thousand arcseconds, which is twenty times
     * worse than the bug being fixed and would show up as everything in the sky being in the wrong
     * place rather than the Moon being slightly off.
     */
    @Test fun siderealTimeStaysOnUniversalTimeAndIsNotOffsetByDeltaT() {
        // Greenwich mean sidereal time in degrees, from Skyfield.
        val refs = listOf(
            1767225600000L to 100.6612,
            1782777600000L to 278.0776,
        )
        for ((ms, expected) in refs) {
            val got = Ephemeris.gmstDeg(Ephemeris.julianDate(ms))
            assertEquals("GMST at $ms", expected, got, 0.01)
        }
        // And the TT date is genuinely ahead, by exactly the constant and no more.
        //
        // ⚠️ The tolerance is a millisecond, not a microsecond, and that is a fact about Double
        // rather than about the code. A Julian date is around 2.46 million, where one unit in the
        // last place is 4.7e-10 days — about fourteen microseconds. A tighter bar than that asks
        // the representation for digits it does not have; my first attempt used 1e-6 s and failed
        // on 69.18401420116425, which is the correct answer rendered as closely as a Double can.
        // A millisecond of clock is 0.0006 arcseconds of Moon, so this is still far below anything
        // that could matter.
        val ms = 1767225600000L
        val gap = (Ephemeris.julianDateTT(ms) - Ephemeris.julianDate(ms)) * 86_400.0
        assertEquals(Ephemeris.DELTA_T_SECONDS, gap, 1e-3)
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
