package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The half of [Astrology] that is astronomy is tested as astronomy; the half that is tradition is
 * tested as bookkeeping.
 *
 * ⚠️ **The ascendant is checked as a PROPERTY and not against a remembered chart.** The ascendant is
 * by definition the degree of the ecliptic on the eastern horizon, so the test converts whatever the
 * function returns back into equatorial coordinates and asks [Ephemeris.toHorizontal] where it is:
 * it must be at altitude zero and it must be in the east. That is unfalsifiable by a coincidence and
 * needs no fixture — and it catches the one mistake that matters here, which is returning the
 * DESCENDANT. Half a circle out is not obviously wrong on screen, because every sign is still a
 * plausible sign.
 *
 * [Ephemeris.toHorizontal] applies no refraction, so a correct answer sits at altitude zero exactly
 * rather than the half-degree a real horizon would add. That is what makes the tolerance tight.
 */
class AstrologyTest {

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /**
     * A point ON the ecliptic (latitude zero) as equatorial coordinates — the inverse of what the
     * ascendant calculation does, used to check its answer from the other side.
     */
    private fun eclipticToEquatorial(longitudeDeg: Double, epochMs: Long): Ephemeris.Equatorial {
        val rad = Math.PI / 180.0
        val eps = Ephemeris.trueObliquityDeg(epochMs) * rad
        val lon = longitudeDeg * rad
        val ra = atan2(sin(lon) * cos(eps), cos(lon))
        val dec = asin(sin(eps) * sin(lon))
        return Ephemeris.Equatorial(
            rightAscensionDeg = ((ra / rad) + 360.0) % 360.0,
            declinationDeg = dec / rad,
            distanceKm = 1.0,
        )
    }

    /**
     * ⚠️ The guard for [Ephemeris.eclipticLongitudeOf], which exists because every body's ecliptic
     * longitude is computed on the way to its right ascension and then discarded. It is only worth
     * having if it is the exact inverse of the rotation that discarded it, so this converts a
     * longitude out to equatorial coordinates and back and requires it to land on itself.
     *
     * Every 7 degrees round the whole circle, at two dates far enough apart that the obliquity has
     * moved — because using a stale obliquity in one direction and a current one in the other is
     * precisely how a conversion pair comes to disagree.
     */
    @Test
    fun eclipticLongitudeSurvivesTheRoundTripThroughEquatorialCoordinates() {
        for (iso in listOf("2026-01-01T00:00:00Z", "2044-11-05T13:00:00Z")) {
            val t = at(iso)
            for (deg in 0 until 360 step 7) {
                val there = eclipticToEquatorial(deg.toDouble(), t)
                val back = Ephemeris.eclipticLongitudeOf(there, t)
                assertEquals(
                    "$iso: $deg° came back as $back°",
                    0.0, Astrology.separationDeg(deg.toDouble(), back), 1e-9,
                )
            }
        }
    }

    // ---- the ascendant, as astronomy --------------------------------------------------------------

    @Test
    fun theAscendantIsActuallyOnTheHorizonAndActuallyInTheEast() {
        val sites = listOf(
            Triple("London", 51.5074, -0.1278),
            Triple("Quito", -0.1807, -78.4678),      // on the equator
            Triple("Sydney", -33.8688, 151.2093),    // southern hemisphere
            Triple("Tromso", 69.6492, 18.9553),      // inside the Arctic Circle
            Triple("Dateline", 12.0, 179.5),         // just short of the longitude wrap
        )
        // Eight times of day across two seasons, so the whole ecliptic passes the horizon.
        val times = listOf(
            "2026-03-20T00:00:00Z", "2026-03-20T06:00:00Z",
            "2026-03-20T12:00:00Z", "2026-03-20T18:00:00Z",
            "2026-09-23T03:00:00Z", "2026-09-23T09:00:00Z",
            "2026-09-23T15:00:00Z", "2026-09-23T21:00:00Z",
        ).map(::at)

        var checked = 0
        for ((name, lat, lon) in sites) {
            for (t in times) {
                val asc = Astrology.ascendantDeg(t, lat, lon)
                assertNotNull("$name: no ascendant at $t", asc)
                val where = Ephemeris.toHorizontal(eclipticToEquatorial(asc!!, t), lat, lon, t)
                assertEquals(
                    "$name at $t: the ascendant is not on the horizon",
                    0.0, where.altitudeDeg, 0.02,
                )
                // Azimuth is clockwise from north, so the eastern half is 0 to 180 exclusive.
                assertTrue(
                    "$name at $t: the ascendant is in the WEST (azimuth ${where.azimuthDeg}) — " +
                        "that is the descendant, the other root of the same tangent",
                    where.azimuthDeg > 1.0 && where.azimuthDeg < 179.0,
                )
                checked++
            }
        }
        assertEquals("every site and time must have been checked", 40, checked)
    }

    /**
     * The midheaven is on the meridian, which is the whole of what it means.
     *
     * ⚠️ **My first version of this asserted it was the HIGHEST point of the ecliptic, and that is
     * simply false** — the highest point is the nonagesimal, ninety degrees from the ascendant, and
     * the two coincide only where the ecliptic meets the meridian squarely. The test failed, the
     * code was right, and the same overclaim was sitting in `midheavenDeg`'s own KDoc where nothing
     * could have caught it.
     *
     * What IS exactly true is that a point on the meridian has an hour angle of zero, so its right
     * ascension equals the local sidereal time. That is provable rather than approximate: writing
     * the midheaven's longitude through the ecliptic-to-equatorial conversion cancels the obliquity
     * out entirely and leaves the sidereal angle it was built from. An azimuth assertion would have
     * been the wrong instrument — the meridian crossing is due south in London and due NORTH inside
     * the tropics.
     */
    @Test
    fun theMidheavenIsOnTheMeridian() {
        for (h in 0 until 24) {
            val t = at("2026-06-21T00:00:00Z") + h * 3_600_000L
            for (lon in listOf(-78.4678, -0.1278, 151.2093, 179.5)) {
                val mc = Astrology.midheavenDeg(t, lon)
                val ra = eclipticToEquatorial(mc, t).rightAscensionDeg
                val lst = Astrology.localSiderealDeg(t, lon)
                val off = Astrology.separationDeg(ra, lst)
                assertEquals(
                    "at hour $h, longitude $lon, the midheaven is $off° off the meridian",
                    0.0, off, 1e-6,
                )
            }
        }
    }

    /** The ascendant leads the midheaven — the rule that picks the right root of the tangent. */
    @Test
    fun theAscendantAlwaysLeadsTheMidheaven() {
        for (h in 0 until 24) {
            val t = at("2026-05-04T00:00:00Z") + h * 3_600_000L
            val asc = Astrology.ascendantDeg(t, 48.85, 2.35)!!
            val mc = Astrology.midheavenDeg(t, 2.35)
            val lead = ((asc - mc) % 360.0 + 360.0) % 360.0
            assertTrue("at hour $h the ascendant is $lead° from the midheaven", lead in 0.0..180.0)
        }
    }

    @Test
    fun theAscendantIsRefusedAtThePolesRatherThanInvented() {
        val t = at("2026-05-04T00:00:00Z")
        assertNull(Astrology.ascendantDeg(t, 90.0, 0.0))
        assertNull(Astrology.ascendantDeg(t, -90.0, 0.0))
        // and still answers everywhere a person actually lives
        assertNotNull(Astrology.ascendantDeg(t, 78.2, 15.6))   // Longyearbyen
    }

    // ---- the signs, as bookkeeping ----------------------------------------------------------------

    @Test
    fun signBoundariesLandOnTheRightSideAndWrapBothWays() {
        assertEquals(Astrology.Sign.ARIES, Astrology.signOf(0.0))
        assertEquals(Astrology.Sign.ARIES, Astrology.signOf(29.999))
        assertEquals(Astrology.Sign.TAURUS, Astrology.signOf(30.0))
        assertEquals(Astrology.Sign.PISCES, Astrology.signOf(359.999))
        assertEquals(Astrology.Sign.ARIES, Astrology.signOf(360.0))
        // Negative longitudes are ordinary — a sidereal position is the tropical one minus 24°.
        assertEquals(Astrology.Sign.PISCES, Astrology.signOf(-1.0))
        assertEquals(Astrology.Sign.PISCES, Astrology.signOf(-30.0))
        assertEquals(Astrology.Sign.AQUARIUS, Astrology.signOf(-31.0))
    }

    @Test
    fun everySignIsThirtyDegreesAndTheyTileTheCircleOnce() {
        assertEquals(12, Astrology.Sign.entries.size)
        Astrology.Sign.entries.forEachIndexed { i, s ->
            assertEquals(i * 30.0, s.startDeg, 1e-12)
            // The midpoint of each span must resolve back to that same sign.
            assertEquals(s, Astrology.signOf(s.startDeg + 15.0))
        }
        // Four elements and three modes, each used exactly the traditional number of times.
        assertEquals(3, Astrology.Sign.entries.count { it.element == Astrology.Element.FIRE })
        assertEquals(4, Astrology.Sign.entries.count { it.mode == Astrology.Mode.CARDINAL })
    }

    /**
     * ⚠️ The carry, which is the one place this can print something impossible. 29.999 degrees into
     * a sign rounds to 60 arcminutes, and "29°60′" is not a thing.
     */
    @Test
    fun theArcminuteCarryNeverPrintsSixtyMinutes() {
        assertEquals("14°30′ Taurus", Astrology.format(44.5))
        assertEquals("0°00′ Aries", Astrology.format(0.0))
        assertEquals("29°59′ Aries", Astrology.format(29.99))
        // 29.9999 rounds up through the boundary and must become the next sign, not 29°60′.
        assertEquals("0°00′ Taurus", Astrology.format(29.9999))
        assertTrue(
            "no rendering may contain sixty arcminutes",
            (0..3599).none { Astrology.format(it / 10.0).contains("°60′") },
        )
    }

    // ---- aspects -----------------------------------------------------------------------------------

    /**
     * ⚠️ **The guard for measuring the SHORTER way round the circle.** Two bodies at 359° and 3° are
     * four degrees apart and conjunct. Subtracting them the long way gives 356, which is within the
     * opposition's eight-degree orb — so the single easiest mistake here turns every conjunction
     * into its exact opposite, and the result looks entirely reasonable on screen.
     */
    @Test
    fun aConjunctionAcrossZeroIsNotAnOpposition() {
        val found = Astrology.aspects(mapOf("Sun" to 359.0, "Moon" to 3.0))
        assertEquals(1, found.size)
        assertEquals(Astrology.AspectKind.CONJUNCTION, found[0].kind)
        assertEquals(4.0, found[0].orbDeg, 1e-9)
        assertEquals(4.0, Astrology.separationDeg(359.0, 3.0), 1e-9)
        assertEquals(4.0, Astrology.separationDeg(3.0, 359.0), 1e-9)
        assertEquals(180.0, Astrology.separationDeg(10.0, 190.0), 1e-9)
    }

    @Test
    fun eachTraditionalAngleIsFoundAndNothingOutsideItsOrbIs() {
        for (kind in Astrology.AspectKind.entries) {
            val exact = Astrology.aspects(mapOf("A" to 100.0, "B" to 100.0 + kind.exactDeg))
            assertEquals("$kind should be found exactly", 1, exact.size)
            assertEquals(kind, exact[0].kind)
            assertTrue("an exact angle is exact", exact[0].exact)

            // A whisker outside the orb is no aspect at all.
            val outside = Astrology.aspects(
                mapOf("A" to 100.0, "B" to 100.0 + kind.exactDeg + kind.orbDeg + 0.1),
            )
            assertTrue("$kind must not be found past its orb", outside.none { it.kind == kind })
        }
        // 45 degrees is a real angle in some schools and NOT one of the five Ptolemaic ones.
        assertTrue(Astrology.aspects(mapOf("A" to 0.0, "B" to 45.0)).isEmpty())
    }

    @Test
    fun aspectsAreListedTightestFirstAndEachPairAppearsOnce() {
        val found = Astrology.aspects(
            mapOf("Sun" to 0.0, "Moon" to 120.5, "Mars" to 180.0, "Venus" to 61.0),
        )
        assertEquals(
            "orbs must be non-decreasing",
            found.map { it.orbDeg }.sorted(), found.map { it.orbDeg },
        )
        val pairs = found.map { setOf(it.a, it.b) }
        assertEquals("no pair may be reported twice", pairs.size, pairs.toSet().size)
    }

    // ---- tropical against sidereal -----------------------------------------------------------------

    /**
     * The ayanamsa is about 24 degrees now, and that is the whole point of offering both.
     *
     * Lahiri is 23°51′11″ at J2000 and grows by the precession of about 50.29 arcseconds a year, so
     * 2026 lands near 24.21 and 2000 near 23.85. Computed from those two numbers rather than
     * recalled — this project has been wrong that way often enough.
     */
    @Test
    fun theAyanamsaIsAboutTwentyFourDegreesAndGrowing() {
        val y2000 = Astrology.ayanamsaDeg(at("2000-01-01T12:00:00Z"))
        val y2026 = Astrology.ayanamsaDeg(at("2026-01-01T00:00:00Z"))
        assertEquals(23.8531, y2000, 0.001)
        assertEquals(23.8531 + (50.29 / 3600.0) * 26.0, y2026, 0.01)
        assertTrue("it grows", y2026 > y2000)
    }

    /**
     * ⚠️ The consequence somebody actually notices: a birthday in the last three weeks of a tropical
     * sign is in the PREVIOUS one sidereally. 24 degrees of a 30-degree sign, so it is the common
     * case rather than an edge one.
     */
    @Test
    fun theSiderealSignIsUsuallyOneBehindTheTropicalOne() {
        val t = at("2026-04-10T00:00:00Z")
        // 20° into Aries tropically is still inside Pisces once the ayanamsa comes off.
        assertEquals(Astrology.Sign.ARIES, Astrology.signOf(20.0))
        assertEquals(Astrology.Sign.PISCES, Astrology.siderealSignOf(20.0, t))
        // Past the ayanamsa it agrees again.
        assertEquals(Astrology.Sign.ARIES, Astrology.siderealSignOf(28.0, t))

        val behind = (0 until 360 step 7).count {
            Astrology.siderealSignOf(it.toDouble(), t) != Astrology.signOf(it.toDouble())
        }
        assertTrue("most of the circle should disagree between the two conventions", behind > 20)
    }

    // ---- houses -------------------------------------------------------------------------------------

    @Test
    fun equalHousesAreTwelveThirtyDegreeSpansStartingAtTheAscendant() {
        val asc = 187.4
        val houses = Astrology.equalHouses(asc)
        assertEquals(12, houses.size)
        assertEquals(asc, houses[0].cuspDeg, 1e-9)
        houses.forEachIndexed { i, h -> assertEquals(i + 1, h.number) }
        for (i in 0 until 12) {
            val span = Astrology.separationDeg(houses[i].cuspDeg, houses[(i + 1) % 12].cuspDeg)
            assertEquals("house ${i + 1} is not thirty degrees", 30.0, span, 1e-9)
        }
    }

    @Test
    fun wholeSignHousesStartAtTheRisingSignRatherThanTheExactDegree() {
        val houses = Astrology.wholeSignHouses(187.4)   // 7.4° Libra
        assertEquals(Astrology.Sign.LIBRA, houses[0].sign)
        assertEquals(180.0, houses[0].cuspDeg, 1e-9)
        // Every cusp is a sign boundary, which is the defining property of the system.
        houses.forEach { assertEquals(0.0, it.cuspDeg % 30.0, 1e-9) }
    }

    /**
     * ⚠️ Houses wrap past 360, so the one containing the wrap has to be found by going ROUND rather
     * than by comparing numbers. A house running from 350° to 20° contains 5°, and a naive
     * `lon in from..to` says it contains nothing at all.
     */
    @Test
    fun theHouseThatCrossesZeroStillContainsThingsInsideIt() {
        val houses = Astrology.equalHouses(350.0)
        assertEquals(1, Astrology.houseOf(355.0, houses))
        assertEquals(1, Astrology.houseOf(5.0, houses))       // past the wrap, same house
        assertEquals(1, Astrology.houseOf(19.9, houses))
        assertEquals(2, Astrology.houseOf(20.1, houses))
        assertEquals(12, Astrology.houseOf(349.0, houses))
        // Every degree of the circle lands in exactly one house and none is missed.
        val counts = IntArray(13)
        for (d in 0 until 3600) {
            val h = Astrology.houseOf(d / 10.0, houses)
            assertNotNull("no house contains ${d / 10.0}", h)
            counts[h!!]++
        }
        assertEquals("all twelve houses used", 12, counts.drop(1).count { it > 0 })
    }

    @Test
    fun aMalformedHouseListIsRefusedRatherThanGuessedAt() {
        assertNull(Astrology.houseOf(100.0, emptyList()))
        assertNull(Astrology.houseOf(100.0, Astrology.equalHouses(0.0).take(11)))
    }

    // ---- retrograde ----------------------------------------------------------------------------------

    @Test
    fun retrogradeIsDecidedTheShortWayRoundSoTheWrapIsNotAHugeLeapBackwards() {
        assertFalse("ordinary forward motion", Astrology.isRetrograde(100.0, 100.5))
        assertTrue("ordinary backward motion", Astrology.isRetrograde(100.5, 100.0))
        // 359.8 -> 0.2 is forwards by 0.4, not backwards by 359.6.
        assertFalse("crossing zero forwards", Astrology.isRetrograde(359.8, 0.2))
        assertTrue("crossing zero backwards", Astrology.isRetrograde(0.2, 359.8))
    }

    /**
     * The Sun is never retrograde — it is the Earth's own orbit and only ever advances. A real
     * check against the shipped ephemeris rather than an assertion about the function in isolation.
     */
    @Test
    fun theSunIsNeverRetrograde() {
        var t = at("2026-01-01T00:00:00Z")
        repeat(24) {
            val a = Ephemeris.sunApparentLongitudeDeg(t)
            val b = Ephemeris.sunApparentLongitudeDeg(t + Astrology.RETROGRADE_BASELINE_MS)
            assertFalse(
                "the Sun read as retrograde at $t, which cannot happen",
                Astrology.isRetrograde(a, b),
            )
            t += 15L * 86_400_000L
        }
    }

    /**
     * And the Moon never is either, over a day — it laps the sky in a month, so it advances about
     * thirteen degrees a day and never appears to reverse.
     */
    @Test
    fun theMoonIsNeverRetrogradeOverADay() {
        var t = at("2026-02-01T00:00:00Z")
        var leastAdvance = 360.0
        repeat(30) {
            val a = Ephemeris.moonEquatorial(t)
            val b = Ephemeris.moonEquatorial(t + Astrology.RETROGRADE_BASELINE_MS)
            val advance = ((b.rightAscensionDeg - a.rightAscensionDeg) % 360.0 + 360.0) % 360.0
            leastAdvance = minOf(leastAdvance, advance)
            t += 86_400_000L
        }
        assertTrue("the Moon advanced only $leastAdvance° in a day", leastAdvance > 10.0)
        assertTrue("and never more than a sign", leastAdvance < 30.0)
    }

    // ---- the honest framing ---------------------------------------------------------------------------

    /**
     * ⚠️ Not decoration. The whole defensibility of shipping this rests on the surface saying which
     * half is measurement and which is tradition, and the file's own documentation is where that
     * obligation is recorded for whoever edits it next. A guard on the code cannot enforce a
     * sentence on a screen, but it can stop the reason for it being quietly deleted.
     */
    @Test
    fun theTropicalZodiacAndTheConstellationsReallyHaveComeApart() {
        val now = at("2026-06-01T00:00:00Z")
        val gap = Astrology.ayanamsaDeg(now)
        assertTrue(
            "the two conventions are $gap° apart, which is most of a sign — if this ever drops " +
                "near zero the surface's explanation has stopped being true",
            gap > 20.0 && gap < 30.0,
        )
        assertTrue("which is most of a thirty-degree sign", abs(gap - 30.0) < 10.0)
    }
}
