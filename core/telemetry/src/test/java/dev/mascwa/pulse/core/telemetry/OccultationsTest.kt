package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

/**
 * Occultations are checked against DE421, and the fixtures were chosen to prove the one claim the
 * whole file rests on.
 *
 * ⚠️ **Two events, in opposite directions.** On 2026-05-23 the Moon and Regulus are 0.070 degrees
 * apart seen from the centre of the Earth — well inside the Moon's own 0.26-degree disc, so from the
 * centre it is a clean occultation. **From London it misses by 0.803 degrees**, three times the
 * Moon's radius. On 2026-05-17 the Moon and Alcyone are 0.935 degrees apart geocentrically, a wide
 * miss by any reading — and **from London it is an occultation at 0.060 degrees**. Anything that
 * decided this from the geocentric separation alone would be wrong on both, in opposite directions,
 * and would look entirely plausible either way.
 *
 * Expected values come from Skyfield reading JPL's DE421, with a WGS-84 observer, at five sites
 * spanning both hemispheres and the near-Arctic. Times are the real closest approach from that site;
 * disappearance and reappearance are where the separation crosses the Moon's own apparent radius.
 *
 * ⚠️ Skyfield's positions are APPARENT — they carry aberration, which this ephemeris does not. That
 * displaces both bodies by up to 20.5 arcseconds in the same direction, so it very largely cancels
 * in a separation between them; what is left is inside the seven arcseconds the Moon already
 * carries. The tolerances below are set from what was actually measured rather than from that
 * argument, which is what makes the argument checkable.
 */
class OccultationsTest {

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** J2000 catalogue positions, the same five used to check [Ephemeris.precessFromJ2000]. */
    private val catalogue = mapOf(
        "Regulus" to Pair(152.09296244, 11.96720878),
        "Antares" to Pair(247.35191542, -26.43200261),
        "Alcyone" to Pair(56.87116533, 24.10516667),
        "Aldebaran" to Pair(68.98016279, 16.50930235),
    )

    /**
     * A star as a target: its place precessed to the instant asked for, so it lands in the same
     * frame as the Moon. Two arcseconds of uncertainty, which is what that was measured at.
     */
    private fun star(name: String): Occultations.Target {
        val (ra, dec) = catalogue.getValue(name)
        return Occultations.Target(
            name = name,
            kind = Occultations.Kind.STAR,
            magnitude = 1.0,
            positionUncertaintyDeg = 2.0 / 3600.0,
            positionAt = { ms -> Ephemeris.precessFromJ2000(ra, dec, ms) },
        )
    }

    private class Loc(
        val site: String,
        val lat: Double,
        val lon: Double,
        val bestMs: Long,
        val sepDeg: Double,
        val semiDeg: Double,
        val occulted: Boolean,
        val disappearsMs: Long?,
        val reappearsMs: Long?,
        val moonAltDeg: Double,
        val sunAltDeg: Double,
    )

    /**
     * ⚠️ The two headline events. Regulus is a geocentric HIT that London misses by three Moon-radii;
     * Alcyone is a geocentric MISS that London sees.
     */
    private val regulus = listOf(
        Loc("London", 51.5074, -0.1278, 1779518331873L, 0.80301, 0.26000, false, null, null, -27.00, 22.36),
        Loc("Sydney", -33.8688, 151.2093, 1779523553337L, 0.56294, 0.26483, false, null, null, 43.85, -14.10),
        Loc("Nairobi", -1.2921, 36.8219, 1779516758498L, 0.06818, 0.25869, true, 1779515526934L, 1779518015616L, -50.48, 37.13),
        Loc("Reykjavik", 64.1466, -21.9426, 1779518958529L, 0.94053, 0.26087, false, null, null, -14.44, 14.72),
        Loc("Santiago", -33.4489, -70.6693, 1779524506161L, 0.06673, 0.25838, true, 1779523239916L, 1779525755676L, -50.17, -39.45),
    )

    private val alcyone = listOf(
        Loc("London", 51.5074, -0.1278, 1778985603915L, 0.06001, 0.27723, true, 1778984342589L, 1778986878294L, -7.53, -10.72),
        Loc("Sydney", -33.8688, 151.2093, 1778985049263L, 1.81926, 0.28037, false, null, null, 29.84, 36.01),
        Loc("Nairobi", -1.2921, 36.8219, 1778981756926L, 0.54440, 0.27547, false, null, null, -28.94, -27.04),
        Loc("Reykjavik", 64.1466, -21.9426, 1778986937498L, 0.08063, 0.27787, true, 1778985649867L, 1778988230222L, -0.20, -4.41),
        Loc("Santiago", -33.4489, -70.6693, 1778988625161L, 1.16516, 0.27329, false, null, null, -70.59, -69.18),
    )

    private val antares = listOf(
        Loc("London", 51.5074, -0.1278, 1780220901975L, 0.83185, 0.24195, false, null, null, -56.00, 51.23),
        Loc("Sydney", -33.8688, 151.2093, 1780213917572L, 0.16211, 0.24611, true, 1780212638667L, 1780215243440L, 14.48, -11.85),
        Loc("Nairobi", -1.2921, 36.8219, 1780219278028L, 0.02386, 0.24175, true, 1780218068973L, 1780220484316L, -62.19, 66.66),
        Loc("Reykjavik", 64.1466, -21.9426, 1780220952037L, 1.03620, 0.24265, false, null, null, -40.26, 35.01),
        Loc("Santiago", -33.4489, -70.6693, 1780224600729L, 0.03934, 0.24590, true, 1780222811114L, 1780226302636L, 11.94, -9.99),
    )

    /** Find the one candidate nearest a known instant, so the fixtures name the event unambiguously. */
    private fun eventNear(name: String, iso: String): Occultations.Event {
        val t = at(iso)
        val found = Occultations.upcoming(t - 5 * 86_400_000L, t + 5 * 86_400_000L, listOf(star(name)))
        assertTrue("$name: nothing found around $iso", found.isNotEmpty())
        return found.minByOrNull { abs(it.greatestEpochMs - t) }!!
    }

    @Test
    fun theGeocentricConjunctionIsFoundAndTimedAgainstJpl() {
        val cases = listOf(
            Triple("Regulus", "2026-05-23T07:17:53Z", 0.0704),
            Triple("Alcyone", "2026-05-17T02:56:02Z", 0.9345),
            Triple("Antares", "2026-05-31T09:10:59Z", 0.3869),
        )
        for ((name, iso, sep) in cases) {
            val e = eventNear(name, iso)
            val offSec = abs(e.greatestEpochMs - at(iso)) / 1000
            assertTrue("$name greatest was $offSec s from DE421", offSec < 90)
            assertEquals("$name geocentric separation", sep, e.separationDeg, 0.004)
        }
    }

    /**
     * ⚠️ The claim the whole file exists for. Two events, five sites, ten answers, and the geocentric
     * separation predicts the wrong one on both events for at least one site.
     */
    @Test
    fun parallaxDecidesIt_andTheGeocentricAnswerIsNotTheLocalOne() {
        val checks = listOf(
            Triple("Regulus", "2026-05-23T07:17:53Z", regulus),
            Triple("Alcyone", "2026-05-17T02:56:02Z", alcyone),
            Triple("Antares", "2026-05-31T09:10:59Z", antares),
        )
        var worstSep = 0.0
        var worstTime = 0L
        for ((name, iso, sites) in checks) {
            val e = eventNear(name, iso)
            for (s in sites) {
                val l = Occultations.local(e, s.lat, s.lon)
                assertEquals("$name at ${s.site}: occulted", s.occulted, l.occulted)
                worstSep = maxOf(worstSep, abs(l.minSeparationDeg - s.sepDeg))
                worstTime = maxOf(worstTime, abs(l.bestEpochMs - s.bestMs) / 1000)
                assertEquals("$name at ${s.site}: Moon radius", s.semiDeg, l.moonSemiDeg, 0.0005)
                assertEquals("$name at ${s.site}: Moon altitude", s.moonAltDeg, l.moonAltitudeDeg, 0.15)
                assertEquals("$name at ${s.site}: Sun altitude", s.sunAltDeg, l.sunAltitudeDeg, 0.15)
            }
        }
        // Measured: 0.0010 degrees (3.6 arcseconds) and 85 seconds. The bars sit just above,
        // because a tolerance far above what the code achieves is not a guard.
        //
        // ⚠️ The TIME is looser than the separation by more than the separation error explains, and
        // that is real rather than sloppy: at a shallow miss the closest approach is a flat minimum,
        // so the separation is pinned and the instant it happens is not. At Sydney's 1.8-degree miss
        // of Alcyone the curve is almost level for minutes either side. Where it matters — a real
        // occultation — the contacts below are good to under a minute.
        assertTrue("worst local separation error was $worstSep deg", worstSep < 0.002)
        assertTrue("worst local timing error was $worstTime s", worstTime < 150)
    }

    /**
     * Contact times, which [Eclipses] refuses to give and this can. The Moon crosses the sky at
     * about half an arcsecond a second, so seven arcseconds of Moon error is fifteen seconds of
     * contact — a large fraction of a two-minute totality and a small one of a forty-minute
     * occultation. That is the whole reason the two files answer this question differently.
     */
    @Test
    fun disappearanceAndReappearanceLandWithinAMinuteOfJpl() {
        var worst = 0L
        var checked = 0
        for ((name, iso, sites) in listOf(
            Triple("Regulus", "2026-05-23T07:17:53Z", regulus),
            Triple("Alcyone", "2026-05-17T02:56:02Z", alcyone),
            Triple("Antares", "2026-05-31T09:10:59Z", antares),
        )) {
            val e = eventNear(name, iso)
            for (s in sites) {
                val l = Occultations.local(e, s.lat, s.lon)
                if (s.disappearsMs == null) {
                    assertNull("$name at ${s.site} does not disappear", l.disappearsEpochMs)
                    assertNull("$name at ${s.site} does not reappear", l.reappearsEpochMs)
                    continue
                }
                assertNotNull("$name at ${s.site} should disappear", l.disappearsEpochMs)
                assertNotNull("$name at ${s.site} should reappear", l.reappearsEpochMs)
                worst = maxOf(
                    worst,
                    abs(l.disappearsEpochMs!! - s.disappearsMs) / 1000,
                    abs(l.reappearsEpochMs!! - s.reappearsMs!!) / 1000,
                )
                assertTrue(
                    "$name at ${s.site}: it must reappear after it disappears",
                    l.reappearsEpochMs!! > l.disappearsEpochMs!!,
                )
                checked++
            }
        }
        assertTrue("at least one occultation must have had contacts to check", checked >= 5)
        // Measured: 38 seconds, against about fifteen predicted from the Moon's own 7.4
        // arcseconds at half an arcsecond a second. The rest is the aberration this does not apply.
        assertTrue("worst contact error was $worst s", worst < 60)
    }

    /**
     * ⚠️ The uncertainty travels with the target, and a planet's is ninety times a star's. A target
     * declared as badly known must refuse to call an answer that a well-known one would give
     * confidently — otherwise the whole point of carrying the number is lost.
     */
    @Test
    fun aBadlyKnownTargetRefusesWhereAWellKnownOneAnswers() {
        val iso = "2026-05-23T07:17:53Z"
        val (ra, dec) = catalogue.getValue("Regulus")
        // A site near the edge of this occultation's track: closest approach lands within a couple
        // of arcminutes of the Moon's limb, which is nothing to a star and everything to a planet.
        val lat = 19.0
        val lon = 36.8219

        fun asTarget(uncertaintyDeg: Double) = Occultations.Target(
            "Regulus", Occultations.Kind.STAR, 1.35, uncertaintyDeg,
        ) { ms -> Ephemeris.precessFromJ2000(ra, dec, ms) }

        val t = at(iso)
        val sharp = Occultations.upcoming(t - 86_400_000L, t + 86_400_000L, listOf(asTarget(2.0 / 3600.0)))
        val blunt = Occultations.upcoming(t - 86_400_000L, t + 86_400_000L, listOf(asTarget(180.0 / 3600.0)))
        val a = Occultations.local(sharp.first(), lat, lon)
        val b = Occultations.local(blunt.first(), lat, lon)

        // Same geometry either way -- only the willingness to call it changes.
        assertEquals(a.minSeparationDeg, b.minSeparationDeg, 0.0)
        assertTrue(
            "this site must sit near the limb for the test to mean anything: " +
                "separation ${a.minSeparationDeg} against radius ${a.moonSemiDeg}",
            abs(a.minSeparationDeg - a.moonSemiDeg) < 100.0 / 3600.0,
        )
        assertTrue("a star this well known should not be called a graze", !a.grazing)
        assertTrue("a target known only to three arcminutes must refuse", b.grazing)
    }

    @Test
    fun aTargetWhosePositionIsUnknownIsSkippedRatherThanGuessedAt() {
        val silent = Occultations.Target("Nothing", Occultations.Kind.PLANET, 0.0, 0.01) { null }
        val found = Occultations.upcoming(
            at("2026-01-01T00:00:00Z"), at("2026-06-01T00:00:00Z"), listOf(silent),
        )
        assertTrue("a target that never reports a position cannot produce events", found.isEmpty())
    }

    /**
     * ⚠️ The window's own ends are never reported. A sample lower than the one before it may still be
     * falling; only one with a higher neighbour on BOTH sides is a real closest approach, and neither
     * end of the scan has that.
     */
    @Test
    fun theEndsOfTheWindowAreNotReportedAsConjunctions() {
        val t = at("2026-05-23T07:17:53Z")
        // A window starting exactly at a known conjunction: it cannot be bracketed, so it is absent.
        // ⚠️ Forty days, not twenty: the Moon returns to a fixed star every SIDEREAL month, 27.3
        // days, so a twenty-day window contains no second conjunction at all and would have proved
        // the second assertion by accident. The next one after this is 2026-06-19, checked.
        val found = Occultations.upcoming(t, t + 40L * 86_400_000L, listOf(star("Regulus")))
        assertTrue(
            "the conjunction at the window's own start must not be reported",
            found.none { abs(it.greatestEpochMs - t) < 3_600_000L },
        )
        assertEquals("exactly one conjunction sits inside forty days", 1, found.size)
    }

    @Test
    fun everyCandidateIsCloseEnoughForSomebodyOnEarthToSeeIt() {
        val found = Occultations.upcoming(
            at("2026-01-01T00:00:00Z"), at("2026-12-31T00:00:00Z"),
            listOf(star("Regulus"), star("Antares"), star("Alcyone")),
        )
        assertTrue("a year should turn up plenty", found.size > 20)
        for (e in found) {
            assertTrue("${e.target.name} at ${e.separationDeg} deg is too far to matter", e.separationDeg < 1.5)
            assertTrue("the Moon's radius should be about a quarter of a degree", e.moonSemiDeg in 0.24..0.29)
            assertTrue("illumination must be a fraction", e.moonIlluminatedFraction in 0.0..1.0)
        }
        // And they come back in time order, which is what a screen wants.
        assertEquals(found.map { it.greatestEpochMs }.sorted(), found.map { it.greatestEpochMs })
    }

    /**
     * ⚠️ **Nothing is an answer, and Aldebaran in 2026 is the case that proves it.**
     *
     * The Moon occults a given star only during a season, because its orbit is tilted five degrees
     * to the ecliptic and the nodes go round in 18.6 years. Aldebaran's last series ended in 2018
     * and the next begins in the 2030s, so through the whole of 2026 the Moon's closest approach to
     * it is **10.28 degrees** — measured against DE421, and forty times the Moon's own radius. A
     * conjunction search that reported those as events would be reporting the Moon being roughly in
     * the same part of the sky.
     *
     * This is also the fixture that makes the candidate limit testable at all: the other three stars
     * happen never to exceed 1.5 degrees at their monthly minima, so with them alone the filter has
     * nothing to reject and a test asserting it works would pass with it deleted.
     */
    @Test
    fun aStarOutsideItsOccultationSeasonProducesNothing() {
        val found = Occultations.upcoming(
            at("2026-01-01T00:00:00Z"), at("2027-01-01T00:00:00Z"), listOf(star("Aldebaran")),
        )
        assertTrue(
            "Aldebaran is ten degrees away all year, so these are not conjunctions: " +
                found.joinToString { "%.2f".format(it.separationDeg) },
            found.isEmpty(),
        )
    }
}
