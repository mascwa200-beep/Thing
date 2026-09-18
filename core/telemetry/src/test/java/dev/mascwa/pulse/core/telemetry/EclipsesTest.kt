package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pinned against JPL DE421, because nothing weaker would settle it.
 *
 * ⚠️ **These fixtures are not recollections and not my arithmetic.** Every date, kind and gamma
 * below was computed in this container from DE421 via Skyfield 1.55 — the lunar ones straight out
 * of `skyfield.eclipselib.lunar_eclipses`, the solar ones by computing the shadow axis's distance
 * from the Earth's centre independently and classifying on it. That matters because this project
 * has now had roughly eighteen occasions where an expectation of mine was wrong and the shipped
 * code was right, and an eclipse catalogue is exactly the sort of thing that feels rememberable
 * and is not.
 *
 * ⚠️ **It also caught a real defect.** The first version of [Eclipses] decided total-versus-partial
 * by asking whether the Moon looked big enough, which called 2025-03-29 a TOTAL solar eclipse. That
 * event was partial everywhere on Earth: the Moon was easily large enough and the shadow cone
 * simply missed the planet, passing north of it. [everySolarEclipseOfFourYearsMatchesJpl] is the
 * regression, and 2025-03-29 is the first row in it.
 *
 * Where the tolerances come from: they were MEASURED against DE421 over these same eighteen
 * events rather than picked. Timing differs by a mean of +8 s and a worst case of 30 s, and
 * magnitude by ±0.004 across ten sites — see [timingToleranceMs] and
 * [localCircumstancesMatchJplToAboutAPerCent] for what each of those numbers is and where it
 * came from.
 */
class EclipsesTest {

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private val from = at("2025-01-01T00:00:00Z")
    private val through = at("2029-01-01T00:00:00Z")

    /**
     * Fifty seconds, and the number is measured rather than chosen.
     *
     * ⚠️ Across these eighteen events the greatest moment this file computes differs from DE421 by
     * a mean of +8 s and a worst case of 30 s. It was +38/76 before the Moon was given its
     * nutation, and +20/53 before the Sun was given its planetary perturbations — both of those
     * defects were found by running this very comparison. The bar sits a little under twice the
     * worst case: tight enough that a real regression trips it, loose enough that an honest theory
     * is not accused of one.
     *
     * ⚠️ **My first version of this file put the bar at 60 s and wrote the fixtures from
     * minute-truncated output**, so three of them were up to 79 s from the value they claimed to
     * be. The failures were mine and not the code's — which is why every timestamp below now
     * carries its seconds.
     */
    private val timingToleranceMs = 50_000L

    private fun found(iso: String, all: List<Eclipses.Eclipse>): Eclipses.Eclipse? =
        all.minByOrNull { kotlin.math.abs(it.greatestEpochMs - at(iso)) }
            ?.takeIf { kotlin.math.abs(it.greatestEpochMs - at(iso)) <= timingToleranceMs }

    // ---- the catalogue ---------------------------------------------------------------------------

    /**
     * Every lunar eclipse of 2025 through 2028, from `skyfield.eclipselib.lunar_eclipses` over
     * DE421 — ten of them, with the library's own penumbral/partial/total verdict.
     */
    @Test
    fun everyLunarEclipseOfFourYearsMatchesJpl() {
        val expected = listOf(
            "2025-03-14T06:58:46Z" to Eclipses.Kind.TOTAL_LUNAR,
            "2025-09-07T18:11:48Z" to Eclipses.Kind.TOTAL_LUNAR,
            "2026-03-03T11:33:42Z" to Eclipses.Kind.TOTAL_LUNAR,
            "2026-08-28T04:12:53Z" to Eclipses.Kind.PARTIAL_LUNAR,
            "2027-02-20T23:12:55Z" to Eclipses.Kind.PENUMBRAL_LUNAR,
            "2027-07-18T16:02:59Z" to Eclipses.Kind.PENUMBRAL_LUNAR,
            "2027-08-17T07:13:47Z" to Eclipses.Kind.PENUMBRAL_LUNAR,
            "2028-01-12T04:13:03Z" to Eclipses.Kind.PARTIAL_LUNAR,
            "2028-07-06T18:19:46Z" to Eclipses.Kind.PARTIAL_LUNAR,
            "2028-12-31T16:52:04Z" to Eclipses.Kind.TOTAL_LUNAR,
        )
        val all = Eclipses.upcoming(from, through).filter { !it.isSolar }

        assertEquals("one lunar eclipse per JPL event, and no extras", expected.size, all.size)
        for ((iso, kind) in expected) {
            val e = found(iso, all)
            assertNotNull("no lunar eclipse found near $iso", e)
            assertEquals("wrong kind for the eclipse of $iso", kind, e!!.kind)
        }
    }

    /**
     * Every solar eclipse of the same four years, classified from DE421 by gamma — the least
     * distance of the shadow axis from the Earth's centre, in Earth radii. The values are recorded
     * beside each row because they are what decides the kind: under 0.9972 the axis lands and the
     * eclipse is central for somebody, beyond it the penumbra alone grazes the planet.
     */
    @Test
    fun everySolarEclipseOfFourYearsMatchesJpl() {
        val expected = listOf(
            // iso                      kind                        gamma from DE421
            Triple("2025-03-29T10:47:25Z", Eclipses.Kind.PARTIAL_SOLAR, 1.0405),
            Triple("2025-09-21T19:42:00Z", Eclipses.Kind.PARTIAL_SOLAR, 1.0651),
            Triple("2026-02-17T12:11:52Z", Eclipses.Kind.ANNULAR_SOLAR, 0.9743),
            Triple("2026-08-12T17:46:00Z", Eclipses.Kind.TOTAL_SOLAR, 0.8977),
            Triple("2027-02-06T15:59:38Z", Eclipses.Kind.ANNULAR_SOLAR, 0.2952),
            Triple("2027-08-02T10:06:41Z", Eclipses.Kind.TOTAL_SOLAR, 0.1421),
            Triple("2028-01-26T15:07:50Z", Eclipses.Kind.ANNULAR_SOLAR, 0.3901),
            Triple("2028-07-22T02:55:29Z", Eclipses.Kind.TOTAL_SOLAR, 0.6056),
        )
        val all = Eclipses.upcoming(from, through).filter { it.isSolar }

        assertEquals("one solar eclipse per JPL event, and no extras", expected.size, all.size)
        for ((iso, kind, _) in expected) {
            val e = found(iso, all)
            assertNotNull("no solar eclipse found near $iso", e)
            assertEquals("wrong kind for the eclipse of $iso", kind, e!!.kind)
        }
    }

    /**
     * The defect that made the gamma classification necessary, kept as its own test so a
     * regression names itself rather than being one row of eighteen.
     *
     * 2025-03-29: the Moon's apparent radius comfortably exceeds the Sun's, so "is the Moon big
     * enough" says TOTAL. Gamma is 1.0405, so the cone misses the Earth entirely and the eclipse is
     * partial for every person on the planet.
     */
    @Test
    fun aMoonBigEnoughToCoverTheSunIsStillOnlyAPartialEclipseIfTheShadowMissesTheEarth() {
        val e = found("2025-03-29T10:47:25Z", Eclipses.upcoming(from, through))
        assertNotNull(e)
        assertEquals(Eclipses.Kind.PARTIAL_SOLAR, e!!.kind)
        assertFalse("nobody on Earth sees totality on this date", e.isTotal)
    }

    /**
     * A month with no eclipse in it returns nothing.
     *
     * ⚠️ **This does NOT guard the both-sides bracket in `minima`, and its first version claimed it
     * did.** Negative-testing showed why: removing the bracket makes the finder report a minimum
     * wherever the separation is merely descending, but `solarAt`/`lunarAt` then reject every one
     * of them as too far apart to be an eclipse, so a quiet month still comes back empty. What
     * actually catches that defect are the "and no extras" counts in
     * [everyLunarEclipseOfFourYearsMatchesJpl] and [everySolarEclipseOfFourYearsMatchesJpl], where
     * the spurious minima land on real eclipses and duplicate them.
     */
    @Test
    fun theEndsOfTheWindowAreNotReportedAsEclipses() {
        // Centred on nothing in particular: April 2026 holds no eclipse of either kind.
        val quiet = Eclipses.upcoming(at("2026-04-01T00:00:00Z"), at("2026-05-01T00:00:00Z"))
        assertTrue("April 2026 has no eclipse, so the finder must return none", quiet.isEmpty())
    }

    /** A backwards or empty window asks for nothing and gets nothing. */
    @Test
    fun anEmptyOrBackwardsWindowReturnsNothing() {
        assertTrue(Eclipses.upcoming(through, from).isEmpty())
        assertTrue(Eclipses.upcoming(from, from).isEmpty())
    }

    // ---- what one place sees ---------------------------------------------------------------------

    /**
     * Local circumstances for 2026-08-12, against DE421 topocentric positions on the WGS84
     * ellipsoid at five sites spread from inside the path to the far side of the world.
     *
     * ⚠️ The magnitudes here are what MEASURED the error budget rather than assuming it: this file
     * agrees with DE421 to ±0.008 at every one of them, in both directions, which is the
     * ephemeris's 14 arcseconds expressed along a diameter. That is why [Eclipses] states a
     * separation uncertainty at all.
     */
    @Test
    fun localCircumstancesMatchJplToAboutAPerCent() {
        val e = found("2026-08-12T17:46:00Z", Eclipses.upcoming(from, through))!!
        // site,        lat,      lon,      DE421 magnitude, DE421 altitude
        val sites = listOf(
            Quint("London", 51.5074, -0.1278, 0.9251, 10.33),
            Quint("Reykjavik", 64.1466, -21.9426, 1.0021, 24.49),
            Quint("Valencia", 39.4699, -0.3763, 1.0034, 4.36),
            Quint("Zaragoza", 41.6488, -0.8891, 1.0073, 5.91),
            Quint("Sydney", -33.8688, 151.2093, 0.0000, -48.06),
        )
        for (s in sites) {
            val l = Eclipses.local(e, s.lat, s.lon)
            assertEquals(
                "${s.name}: magnitude is outside the ephemeris's own error budget",
                s.magnitude, l.magnitude, 0.01,
            )
            assertEquals("${s.name}: the Sun is not where JPL puts it", s.altitude, l.altitudeDeg, 0.1)
        }
    }

    /**
     * The places where this file cannot tell, and says so.
     *
     * ⚠️ **DE421 puts Reykjavik INSIDE the 2026 path of totality and this file puts it a hair
     * outside.** The disagreement is 0.004 of magnitude — the whole error budget, landing on a
     * boolean. Zaragoza used to flip the same way and no longer does, which is the solar
     * perturbation terms earning their place; it is kept here because it still sits inside the
     * band, and a place that is genuinely six arcseconds from the edge SHOULD be flagged whichever
     * side this file happens to put it.
     *
     * So the boolean itself is not asserted for either. What IS asserted is that both are flagged
     * and that [Eclipses.advice] refuses to announce a totality it cannot stand behind. Announcing
     * one would be the app being more confident than its data, which is the defect class this
     * project keeps finding.
     */
    @Test
    fun aPlaceOnTheEdgeOfThePathIsFlaggedRatherThanAnswered() {
        val e = found("2026-08-12T17:46:00Z", Eclipses.upcoming(from, through))!!
        for ((name, lat, lon) in listOf(
            Triple("Reykjavik", 64.1466, -21.9426),
            Triple("Zaragoza", 41.6488, -0.8891),
        )) {
            val l = Eclipses.local(e, lat, lon)
            assertTrue("$name sits inside the error of the path edge and must be flagged", l.borderline)
            val advice = Eclipses.advice(e, l)
            assertTrue(
                "$name: the advice must say the answer is uncertain, not assert one",
                advice.contains("edge of the path"),
            )
            assertFalse(
                "$name: must not announce totality on a figure inside its own error",
                advice.startsWith("Totality from here"),
            )
        }
    }

    /**
     * Deep inside the path, though, it answers plainly. Luxor sits within a few kilometres of the
     * 2027-08-02 centre line and gets over six minutes of totality; DE421 gives magnitude 1.0352,
     * eighteen times the error budget clear of the boundary.
     */
    @Test
    fun deepInsideThePathItSaysTotalityWithoutHedging() {
        val e = found("2027-08-02T10:06:41Z", Eclipses.upcoming(from, through))!!
        val l = Eclipses.local(e, 25.6872, 32.6396)
        assertTrue("Luxor is on the centre line", l.totalHere)
        assertFalse("and nowhere near the edge, so nothing to hedge", l.borderline)
        assertEquals(1.0352, l.magnitude, 0.01)
        assertEquals(1.0, l.obscuration, 1e-9)
        assertTrue(Eclipses.advice(e, l).startsWith("Totality from here"))
    }

    /**
     * The annular half, which a magnitude-based edge test would have missed entirely.
     *
     * ⚠️ At the internal contact of an ANNULAR eclipse the two discs are tangent while the Moon is
     * the SMALLER of the two, so the magnitude there is the ratio of the radii — about 0.93 for
     * this event, nowhere near 1. Bahía Blanca is annular per DE421 at magnitude 0.9324, and a
     * borderline band expressed in magnitude rather than separation would silently never fire for
     * any annular eclipse ever.
     */
    @Test
    fun anAnnularEclipseIsRecognisedInsideItsOwnPath() {
        val e = found("2027-02-06T15:59:38Z", Eclipses.upcoming(from, through))!!
        val inside = Eclipses.local(e, -38.7183, -62.2661)   // Bahía Blanca
        assertTrue("DE421 puts Bahía Blanca inside the annular path", inside.annularHere)
        assertFalse("an annular eclipse is never total", inside.totalHere)
        assertEquals(0.9324, inside.magnitude, 0.01)
        // The ring leaves real light in the sky: about 86% of the disc covered, not 100%.
        assertEquals(0.8600, inside.obscuration, 0.02)

        val outside = Eclipses.local(e, -34.6037, -58.3816)  // Buenos Aires, off the path
        assertFalse("Buenos Aires is outside the annular path", outside.annularHere)
        assertEquals(0.8809, outside.magnitude, 0.01)

        // ⚠️ **The guard for the rule this test's KDoc is about.** A point on the ring path's
        // northern limit, found by bisecting for where [Eclipses.Local.annularHere] flips and then
        // confirmed against DE421: it really is inside, by 4.1 arcseconds, at magnitude 0.9295.
        // So it MUST be flagged borderline — and a band expressed as |magnitude − 1| cannot
        // possibly flag it, because that distance is 0.07, seventeen times the real uncertainty.
        // Without this assertion the separation-versus-magnitude choice is unguarded, which
        // negative-testing is exactly how I found out.
        val edge = Eclipses.local(e, -38.60, -62.2661)
        assertTrue("a point on the ring's own limit is annular", edge.annularHere)
        assertEquals(0.9295, edge.magnitude, 0.01)
        assertTrue(
            "four arcseconds from the boundary must be flagged, at a magnitude nowhere near 1",
            edge.borderline,
        )
    }

    /**
     * A lunar eclipse is the same event for everybody, so the only local question is whether the
     * Moon has risen. On 2025-03-14 it had for New York and had not for London.
     */
    @Test
    fun aLunarEclipseIsTheSameEverywhereAndOnlyTheHorizonDiffers() {
        val e = found("2025-03-14T06:58:46Z", Eclipses.upcoming(from, through))!!
        val newYork = Eclipses.local(e, 40.7128, -74.0060)
        val london = Eclipses.local(e, 51.5074, -0.1278)

        assertTrue("the Moon is well up over New York", newYork.visible)
        assertFalse("it has set over London before greatest", london.visible)
        assertEquals(
            "the magnitude is a property of the eclipse, not of the observer",
            newYork.magnitude, london.magnitude, 1e-12,
        )
        assertTrue(
            Eclipses.advice(e, london).contains("Below the horizon"),
        )
    }

    // ---- obscuration -----------------------------------------------------------------------------

    /**
     * Obscuration is an AREA and magnitude is a DIAMETER, and the gap between them is the whole
     * reason both exist.
     *
     * ⚠️ Half the Sun's diameter covered is only about 39% of its light gone, because the covered
     * part of a disc near the edge is a thin lens rather than a band. Reporting the magnitude as
     * the obscuration would overstate every partial eclipse anybody ever looks at.
     */
    @Test
    fun halfTheDiameterIsNowhereNearHalfTheLight() {
        // Equal discs, centres one radius apart: magnitude is exactly 0.5 by definition.
        val r = 0.26
        val hidden = Eclipses.overlapFraction(r, r, r)
        assertEquals(
            "half the diameter covered should be about 39% of the area",
            0.391, hidden, 0.002,
        )
    }

    @Test
    fun theTwoLimitsOfOverlapAreExact() {
        val rs = 0.2631
        val rm = 0.2721
        // Discs just touching: nothing hidden.
        assertEquals(0.0, Eclipses.overlapFraction(rs + rm, rs, rm), 1e-12)
        // Wholly apart: still nothing.
        assertEquals(0.0, Eclipses.overlapFraction(1.0, rs, rm), 1e-12)
        // The larger Moon centred on the Sun: everything.
        assertEquals(1.0, Eclipses.overlapFraction(0.0, rs, rm), 1e-12)
        // ⚠️ A SMALLER Moon centred on the Sun leaves a ring, so this must be the ratio of the
        // areas and NOT 1. Getting this branch wrong would report every annular eclipse as total
        // darkness — the most dangerous single number this file can produce, because it is the one
        // that would tell somebody it is safe to take a filter off.
        val small = 0.2
        assertEquals((small * small) / (rs * rs), Eclipses.overlapFraction(0.0, rs, small), 1e-12)
    }

    // ---- saying it -------------------------------------------------------------------------------

    /**
     * Every kind has a name and every visible solar eclipse carries a filter warning.
     *
     * ⚠️ The warning is the one piece of copy here with a physical consequence: a 90% partial
     * eclipse is as damaging to look at as an ordinary Sun and far more tempting to stare at, and
     * the annular case is the one people most often assume is safe.
     */
    @Test
    fun everySolarEclipseSomebodyCanSeeCarriesTheFilterWarning() {
        val all = Eclipses.upcoming(from, through)
        var checked = 0
        for (e in all.filter { it.isSolar }) {
            for ((lat, lon) in listOf(
                51.5074 to -0.1278, 25.6872 to 32.6396, -38.7183 to -62.2661,
                39.4699 to -0.3763, 40.7128 to -74.0060,
            )) {
                val l = Eclipses.local(e, lat, lon)
                if (!l.visible) continue
                checked++
                assertTrue(
                    "a visible solar eclipse must warn about filters: ${Eclipses.advice(e, l)}",
                    Eclipses.advice(e, l).contains("filter", ignoreCase = true) ||
                        Eclipses.advice(e, l).contains("Filters"),
                )
            }
        }
        assertTrue("the sweep has to have found some visible eclipses to mean anything", checked >= 5)
    }

    @Test
    fun everyKindHasItsOwnName() {
        val names = Eclipses.Kind.entries.map { k ->
            Eclipses.describe(Eclipses.Eclipse(k, 0L, 0.0))
        }
        assertEquals("no two kinds may read the same", names.size, names.toSet().size)
        assertTrue(names.none { it.isBlank() })
    }

    private data class Quint(
        val name: String,
        val lat: Double,
        val lon: Double,
        val magnitude: Double,
        val altitude: Double,
    )
}
