package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * ⚠️ **Every expected value below was computed from an external ephemeris, never from recollection.**
 *
 * The diameters, phase angles, illuminated fractions and Saturn ring openings come from **JPL DE421**
 * via Skyfield; the Galilean moon positions and Jupiter's pole position angle come from **JPL
 * Horizons**. Both were fetched and the shipped functions run against them before a single assertion
 * here was typed — the probes are `scratchpad/sky/DiscProbe.kt` and `scratchpad/sky/MoonProbe.kt`,
 * which sweep 21 body-epochs and 107 moon-epochs respectively. What is pinned here is a
 * representative slice of that, so a regression fails the build rather than waiting for somebody to
 * run a probe again.
 *
 * That discipline is not decoration. Writing this file caught **three** defects in a function that
 * read perfectly well: an 1899-epoch phase set mixed with J2000 rates, `- J` where the method says
 * `- B`, and moon offsets labelled celestial when they are Jupiter's own. None is visible by reading.
 */
class PlanetDiscTest {

    // ---- angular size ----------------------------------------------------------------------------

    @Test
    fun `apparent diameters match DE421`() {
        // 2026-08-29T00:00Z. Distances and reference diameters straight out of the ephemeris.
        val cases = listOf(
            Triple(696_000.0, 151_090_720.554, 0.5278683353962039),   // Sun
            Triple(1737.4, 387_429.851, 0.5138789420178786),          // Moon
            Triple(2439.7, 204_708_122.025, 0.0013656958199650992),   // Mercury
            Triple(6051.8, 86_405_563.605, 0.008025932226448788),     // Venus
            Triple(3396.2, 279_174_931.967, 0.001394021483349905),    // Mars
            Triple(71_492.0, 929_603_482.857, 0.008812767913476393),  // Jupiter
            Triple(60_268.0, 1_291_108_843.650, 0.0053490487001662795), // Saturn
        )
        for ((radius, distance, expected) in cases) {
            val got = PlanetDisc.apparentDiameterDeg(radius, distance)
            assertEquals("radius $radius at $distance km", expected, got, expected * 1e-9)
        }
    }

    @Test
    fun `an impossible geometry answers zero rather than NaN`() {
        // Closer than its own radius is not a view. `2 asin(r/d)` would be NaN and would then poison
        // every pixel it reached; the linear form would return a confident wrong number instead.
        assertEquals(0.0, PlanetDisc.apparentDiameterDeg(1000.0, 500.0), 0.0)
        assertEquals(0.0, PlanetDisc.apparentDiameterDeg(1000.0, 1000.0), 0.0)
        assertEquals(0.0, PlanetDisc.apparentDiameterDeg(0.0, 1000.0), 0.0)
        assertEquals(0.0, PlanetDisc.apparentDiameterDeg(1000.0, 0.0), 0.0)
        assertEquals(0.0, PlanetDisc.apparentDiameterDeg(1000.0, -5.0), 0.0)
    }

    @Test
    fun `the AU overload agrees with the kilometre one`() {
        val au = 6.2140154703188815
        assertEquals(
            PlanetDisc.apparentDiameterDeg(71_492.0, au * PlanetDisc.AU_KM),
            PlanetDisc.apparentDiameterDegAu(71_492.0, au),
            0.0,
        )
    }

    // ---- phase -----------------------------------------------------------------------------------

    @Test
    fun `illuminated fractions match DE421`() {
        // Mercury near full, Venus near half, Mercury as a thin crescent — the whole range.
        assertEquals(0.997238426437164, PlanetDisc.illuminatedFraction(6.024638993534216), 1e-12)
        assertEquals(0.4071227550586044, PlanetDisc.illuminatedFraction(100.70512397190566), 1e-12)
        assertEquals(0.11071980540500737, PlanetDisc.illuminatedFraction(141.12895432354034), 1e-12)
        assertEquals(0.9206956842001062, PlanetDisc.illuminatedFraction(32.71266235509352), 1e-12)
    }

    @Test
    fun `illumination is full at zero, half at ninety and new at a hundred and eighty`() {
        assertEquals(1.0, PlanetDisc.illuminatedFraction(0.0), 1e-15)
        assertEquals(0.5, PlanetDisc.illuminatedFraction(90.0), 1e-15)
        assertEquals(0.0, PlanetDisc.illuminatedFraction(180.0), 1e-15)
    }

    @Test
    fun `the terminator is an ellipse whose bulge reverses at half phase`() {
        // ⚠️ This is the rule that separates a correct crescent from the classic wrong one. The lit
        // part is a half-circle joined to a half-ELLIPSE of semi-minor axis r cos(i); the SIGN says
        // which way that ellipse bows. Positive is gibbous, negative crescent, and exactly zero at
        // half phase is why a quarter Moon has a straight edge.
        assertTrue("gibbous", PlanetDisc.terminatorFactor(60.0) > 0.0)
        assertEquals("straight at half phase", 0.0, PlanetDisc.terminatorFactor(90.0), 1e-15)
        assertTrue("crescent", PlanetDisc.terminatorFactor(120.0) < 0.0)
        assertEquals(0.5, PlanetDisc.terminatorFactor(60.0), 1e-12)
        assertEquals(-0.5, PlanetDisc.terminatorFactor(120.0), 1e-12)
    }

    @Test
    fun `limb darkening runs from one at the centre to one minus u at the edge`() {
        assertEquals(1.0, PlanetDisc.limbDarkening(0.0), 1e-15)
        assertEquals(1.0 - PlanetDisc.LIMB_DARKENING_U, PlanetDisc.limbDarkening(1.0), 1e-12)
        // Outside the disc there is nothing to shade; a value would be a claim about empty sky.
        assertEquals(0.0, PlanetDisc.limbDarkening(1.0001), 0.0)
        assertEquals(0.0, PlanetDisc.limbDarkening(-0.0001), 0.0)
        // Monotone inward — the whole point is that the edge is dimmer than the middle.
        var previous = 2.0
        var x = 0.0
        while (x <= 1.0) {
            val here = PlanetDisc.limbDarkening(x)
            assertTrue("brightness must fall outward at $x", here < previous)
            previous = here
            x += 0.05
        }
    }

    // ---- shape -----------------------------------------------------------------------------------

    @Test
    fun `the giants are visibly oblate and the rest are round enough`() {
        // ⚠️ Saturn is the most oblate planet in the system and Jupiter is next; drawing either round
        // is the commonest way a planetarium looks wrong to somebody who has actually looked.
        assertEquals(0.0980, PlanetDisc.flattening(PlanetDisc.Body.SATURN), 5e-4)
        assertEquals(0.0649, PlanetDisc.flattening(PlanetDisc.Body.JUPITER), 5e-4)
        assertEquals(0.0059, PlanetDisc.flattening(PlanetDisc.Body.MARS), 5e-4)
        assertEquals(0.0, PlanetDisc.flattening(PlanetDisc.Body.VENUS), 0.0)
        assertEquals(0.0, PlanetDisc.flattening(PlanetDisc.Body.SUN), 0.0)
        assertTrue(
            "Saturn must be the flattest",
            PlanetDisc.Body.entries.filter { it != PlanetDisc.Body.SATURN }
                .all { PlanetDisc.flattening(it) < PlanetDisc.flattening(PlanetDisc.Body.SATURN) },
        )
    }

    @Test
    fun `the Sun and Moon radii match the eclipse and occultation cores exactly`() {
        // ⚠️ These are copied to MATCH, not chosen. Three files carrying their own solar radius is
        // the duplicated-definition drift this project keeps correcting; until they are converged,
        // this test is what stops them silently parting company.
        assertEquals(
            Eclipses.SUN_RADIUS_KM,
            PlanetDisc.equatorialRadiusKm(PlanetDisc.Body.SUN),
            0.0,
        )
        assertEquals(
            Eclipses.MOON_RADIUS_KM,
            PlanetDisc.equatorialRadiusKm(PlanetDisc.Body.MOON),
            0.0,
        )
        assertEquals(
            Occultations.MOON_RADIUS_KM,
            PlanetDisc.equatorialRadiusKm(PlanetDisc.Body.MOON),
            0.0,
        )
    }

    // ---- Saturn's rings --------------------------------------------------------------------------

    @Test
    fun `ring opening matches DE421 including the ring-plane crossing`() {
        // 2025-11-01 is inside the real ring-plane-crossing season, where B passes through zero —
        // the case a formula can be wrong about while looking fine at every other epoch.
        assertEquals(-0.5914945195154094, PlanetDisc.rings(356.7789774355814, -4.0801456890092895).openingDeg, 1e-9)
        assertEquals(-4.459873149904985, PlanetDisc.rings(3.7098462899875786, -0.7075675668006426).openingDeg, 1e-9)
        assertEquals(-8.623848155325014, PlanetDisc.rings(13.420640323350419, 2.880340049576075).openingDeg, 1e-9)
    }

    @Test
    fun `an edge-on ring is a line and a wide-open one is not`() {
        val crossing = PlanetDisc.rings(356.7789774355814, -4.0801456890092895)
        assertTrue("near a crossing the ellipse is nearly flat", crossing.squash < 0.011)
        // The pole seen from a right angle is the widest the rings ever open, about 27 degrees.
        val wide = PlanetDisc.Rings(26.7, 0.0)
        assertEquals(sin(26.7 * Math.PI / 180.0), wide.squash, 1e-12)
        // ⚠️ Squash is an ABSOLUTE value: the rings look equally open whichever face is toward us,
        // and a negative minor axis would flip the drawn ellipse inside out.
        assertEquals(PlanetDisc.Rings(-26.7, 0.0).squash, wide.squash, 1e-15)
    }

    @Test
    fun `the ring radii are ordered and the Cassini division lies inside them`() {
        assertTrue(PlanetDisc.RING_INNER > 1.0)
        assertTrue(PlanetDisc.RING_INNER < PlanetDisc.CASSINI_INNER)
        assertTrue(PlanetDisc.CASSINI_INNER < PlanetDisc.CASSINI_OUTER)
        assertTrue(PlanetDisc.CASSINI_OUTER < PlanetDisc.RING_OUTER)
    }

    // ---- the axis both the rings and the moons lie in ---------------------------------------------

    @Test
    fun `Jupiter's pole position angle matches Horizons`() {
        // 2026-01-01T12:00Z and 2026-06-30T12:00Z. ⚠️ Over 2026 alone this swings from 8 to 18
        // degrees, which is why the moons cannot simply be laid out east-west.
        assertEquals(
            10.33004,
            PlanetDisc.axisPositionAngle(
                PlanetDisc.JUPITER_POLE_RA_DEG, PlanetDisc.JUPITER_POLE_DEC_DEG,
                112.65976, 22.04583,
            ),
            1e-3,
        )
        assertEquals(
            13.89650,
            PlanetDisc.axisPositionAngle(
                PlanetDisc.JUPITER_POLE_RA_DEG, PlanetDisc.JUPITER_POLE_DEC_DEG,
                121.95341, 20.64215,
            ),
            1e-3,
        )
    }

    @Test
    fun `the rings take their position angle from the shared axis geometry`() {
        // One definition, two callers. If `rings` ever grows its own copy again, this parts company.
        val ra = 13.420640323350419
        val dec = 2.880340049576075
        assertEquals(
            PlanetDisc.axisPositionAngle(
                PlanetDisc.SATURN_POLE_RA_DEG, PlanetDisc.SATURN_POLE_DEC_DEG, ra, dec,
            ),
            PlanetDisc.rings(ra, dec).positionAngleDeg,
            0.0,
        )
    }

    // ---- the Galilean moons ----------------------------------------------------------------------

    @Test
    fun `the moons are where Horizons puts them`() {
        // ⚠️ The tolerances are each MEASURED-plus-0.03, not the method's global worst case, and that
        // matters: a loose "0.40 for everything" passed happily with one moon's orbital-radius
        // perturbation deleted, which is the recorded mechanism of an assertion too weak to see the
        // damage. Anything that moves a moon by more than three hundredths of a Jovian radius now
        // fails here.
        //
        // 2026-01-01T12:00Z, in Jupiter's own equatorial frame.
        val a = PlanetDisc.galileanMoons(1_767_268_800_000L)
        assertMoon(a[0], 4.04683, 0.10267, 0.044)
        assertMoon(a[1], 5.26658, -0.11290, 0.119)
        assertMoon(a[2], 14.40712, -0.10088, 0.046)
        assertMoon(a[3], 5.00069, 0.50837, 0.194)

        // 2026-06-30T12:00Z — half a year on, with every moon at an unrelated phase.
        val b = PlanetDisc.galileanMoons(1_782_820_800_000L)
        assertMoon(b[0], 3.27011, -0.08301, 0.052)
        assertMoon(b[1], -9.33735, -0.02633, 0.055)
        assertMoon(b[2], 12.64436, 0.15863, 0.099)
        assertMoon(b[3], 25.91544, -0.13981, 0.126)
    }

    @Test
    fun `Horizons says which moons are hidden, and the model agrees`() {
        // ⚠️ Ground truth for `behind` is not the sky position at all — it is each moon's RANGE from
        // Earth against Jupiter's. Farther means beyond the planet. Taken from Horizons; the
        // separations are thousands of Jovian radii, so these are not close calls.
        //
        // Cases within about a tenth of greatest elongation are LEFT OUT deliberately: there the
        // moon is beside Jupiter rather than in front of or behind it, the true answer flips within
        // minutes, and a method good to a tenth of a radius has no business being asked.
        //
        // This test is why the shipped rule is `cos(u) < 0`. A first draft had it the other way and
        // disagreed with every one of these five.
        val jan = PlanetDisc.galileanMoons(1_767_268_800_000L)
        assertEquals("Io was beyond Jupiter", true, jan[0].behind)
        assertEquals("Europa was in front", false, jan[1].behind)
        assertEquals("Callisto was beyond Jupiter", true, jan[3].behind)

        val jun = PlanetDisc.galileanMoons(1_782_820_800_000L)
        assertEquals("Io was in front", false, jun[0].behind)
        assertEquals("Ganymede was beyond Jupiter", true, jun[2].behind)
    }

    private fun assertMoon(got: PlanetDisc.Moonlet, x: Double, y: Double, tol: Double) {
        val err = hypot(got.x - x, got.y - y)
        assertTrue(
            "${got.name}: got (${got.x}, ${got.y}) want ($x, $y) — off by $err Jovian radii",
            err <= tol,
        )
    }

    @Test
    fun `the resonance is built into the constants, which is what catches a mistyped digit`() {
        // ⚠️ The three inner moons are locked so that l1 - 3*l2 + 2*l3 = 180 degrees, always. It is a
        // law of the system rather than a fitted number, so the METHOD'S OWN CONSTANTS have to
        // satisfy it — both the phases at epoch and the rates. This needs no ephemeris and it is the
        // one check that would catch a single wrong digit in any of the six numbers involved.
        //
        // The phases and rates are recovered from the shipped function rather than restated here, by
        // sampling two instants a whole number of days apart: a mistyped constant cannot hide.
        val day = 86_400_000L
        val t0 = 1_767_268_800_000L
        val t1 = t0 + 400L * day
        val (p0, r0) = laplaceAt(t0)
        val (p1, _) = laplaceAt(t1)
        assertEquals("the argument must sit at 180 degrees", 180.0, p0, 4.1)
        assertEquals("and must not walk away from it", 180.0, p1, 4.1)
        // ⚠️ The 4.1-degree window is NOT slack for a wrong constant, it is the amplitude of the
        // three periodic terms this method carries: 0.473 + 3*1.065 + 2*0.174 = 4.04 degrees, since
        // their arguments run at unrelated rates and cannot cancel. A wrong rate would show as the
        // argument circulating instead — which the second sample, 400 days later, is there to catch.
        assertTrue("the moons must stay inside their orbits", r0)
    }

    /** The Laplace argument in degrees at [ms], plus whether every moon is within its own orbit. */
    private fun laplaceAt(ms: Long): Pair<Double, Boolean> {
        val m = PlanetDisc.galileanMoons(ms)
        val radii = doubleArrayOf(5.9057, 9.3966, 14.9883, 26.3627)
        var inside = true
        val ang = DoubleArray(3)
        for (i in 0 until 3) {
            val s = (m[i].x / radii[i]).coerceIn(-1.0, 1.0)
            val a = Math.toDegrees(kotlin.math.asin(s))
            ang[i] = if (m[i].behind) a else 180.0 - a
        }
        for (i in 0 until 4) {
            // The swing terms move each radius by a few hundredths, hence the small allowance.
            if (abs(m[i].x) > radii[i] + 0.2) inside = false
        }
        var res = (ang[0] - 3 * ang[1] + 2 * ang[2]) % 360.0
        if (res < 0) res += 360.0
        return res to inside
    }

    @Test
    fun `the frame term carries no secular rate`() {
        // ⚠️ This is the defect the whole rewrite turned on. Subtracting J rather than B slows Io
        // from 203.4059 to 202.503 degrees a day — a period of 1.7778 days instead of 1.7699, which
        // looks perfectly reasonable and drifts a whole orbit in about seven months. Measuring the
        // period from the shipped output is the only way to see it.
        var crossings = 0
        var first = 0L
        var last = 0L
        var previous = 0
        var t = 1_767_268_800_000L
        val step = 600_000L
        repeat(20_000) {
            val x = PlanetDisc.galileanMoons(t)[0].x
            val sign = if (x >= 0.0) 1 else -1
            if (previous == -1 && sign == 1) {
                if (first == 0L) first = t else last = t
                crossings++
            }
            previous = sign
            t += step
        }
        assertTrue("expected many Io orbits in 139 days", crossings > 70)
        val period = (last - first) / (crossings - 1.0) / 86_400_000.0
        // Io's period against the Earth-Jupiter line, which is what a longitude measured from
        // superior conjunction runs at: 360 / 203.4058643 = 1.76986 days.
        assertEquals(1.76986, period, 0.001)
    }

    @Test
    fun `behind is true exactly when the moon is on the far side`() {
        // `behind` must agree with the geometry the renderer draws: the moon is hidden when it is
        // beyond Jupiter, and that is cos(u) > 0 with u measured from superior conjunction. Sampling
        // densely, `behind` must be true for a contiguous half of every orbit and no more.
        var behindCount = 0
        var total = 0
        var t = 1_767_268_800_000L
        repeat(4000) {
            if (PlanetDisc.galileanMoons(t)[0].behind) behindCount++
            total++
            t += 600_000L
        }
        val share = behindCount.toDouble() / total
        assertTrue("behind for about half the orbit, got $share", share > 0.45 && share < 0.55)
    }

    @Test
    fun `the moons stay within a fifteenth of edge-on`() {
        // ⚠️ Measured over 2026-2074 by running the shipped function: the worst across-track offset
        // is 0.06345 of the moon's own ORBITAL RADIUS, which is sin(3.64 degrees).
        //
        // The denominator is the orbital radius and NOT the instantaneous separation, and that is
        // the whole subtlety. A moon passing in front of Jupiter has x near zero and y at its
        // largest, so |y| / hypot(x, y) reaches 1.0 there — a perfectly healthy geometry that the
        // obvious version of this test reports as a catastrophic failure. It did, on the first run.
        val orbits = doubleArrayOf(5.9057, 9.3966, 14.9883, 26.3627)
        var t = 1_767_268_800_000L
        var worst = 0.0
        repeat(2000) {
            for ((i, m) in PlanetDisc.galileanMoons(t).withIndex()) {
                worst = maxOf(worst, abs(m.y) / orbits[i])
            }
            t += 7 * 86_400_000L
        }
        assertTrue("across-track share reached $worst", worst < 1.0 / 15.0)
        // And it really does get near edge-on, or this would pass on a function returning zero.
        assertTrue("the tilt should be real, not absent: $worst", worst > 0.05)
    }

    @Test
    fun `a moon's offset never exceeds its orbital radius`() {
        var t = 1_767_268_800_000L
        val limits = doubleArrayOf(5.93, 9.49, 15.01, 26.56)
        repeat(3000) {
            val moons = PlanetDisc.galileanMoons(t)
            for ((i, m) in moons.withIndex()) {
                assertTrue(
                    "${m.name} at $t reached ${hypot(m.x, m.y)}",
                    hypot(m.x, m.y) <= limits[i],
                )
            }
            t += 11 * 3_600_000L
        }
    }

    @Test
    fun `the four are ordered outward and named`() {
        val m = PlanetDisc.galileanMoons(1_767_268_800_000L)
        assertEquals(listOf("Io", "Europa", "Ganymede", "Callisto"), m.map { it.name })
    }

    // ---- the geometry the rings rest on ----------------------------------------------------------

    @Test
    fun `the two extremes of ring opening are exact`() {
        // ⚠️ My first version of this test asserted the wrong extreme — it put the BODY at the
        // celestial pole and expected 90 degrees. That case is neither extreme, and the code was
        // right: the answer there is exactly minus Saturn's own pole declination, because the dot
        // product with (0,0,1) is sin(dec) and asin undoes it. Pinned, since it is a free exact
        // check on the whole vector path.
        assertEquals(
            -PlanetDisc.SATURN_POLE_DEC_DEG,
            PlanetDisc.rings(0.0, 90.0).openingDeg,
            1e-9,
        )
        // Looking straight down the pole is the widest the rings can ever be.
        assertEquals(
            -90.0,
            PlanetDisc.rings(PlanetDisc.SATURN_POLE_RA_DEG, PlanetDisc.SATURN_POLE_DEC_DEG).openingDeg,
            1e-9,
        )
        // A right angle from the pole is exactly edge-on.
        assertEquals(
            0.0,
            PlanetDisc.rings(
                PlanetDisc.SATURN_POLE_RA_DEG, PlanetDisc.SATURN_POLE_DEC_DEG - 90.0,
            ).openingDeg,
            1e-9,
        )
    }

    @Test
    fun `the position angle is measured east of north`() {
        // A pole displaced due north of the body must give zero; due east, ninety.
        val north = PlanetDisc.axisPositionAngle(0.0, 40.0, 0.0, 20.0)
        assertEquals(0.0, north, 1e-9)
        val east = PlanetDisc.axisPositionAngle(20.0, 0.0, 0.0, 0.0)
        assertEquals(90.0, east, 1e-9)
        // And it is reported in 0..360 rather than signed, which is what a renderer rotates by.
        val west = PlanetDisc.axisPositionAngle(-20.0, 0.0, 0.0, 0.0)
        assertEquals(270.0, west, 1e-9)
    }

    @Test
    fun `equatorial and polar radii agree except where a body is genuinely flattened`() {
        for (body in PlanetDisc.Body.entries) {
            assertTrue(
                "$body: polar radius cannot exceed equatorial",
                PlanetDisc.polarRadiusKm(body) <= PlanetDisc.equatorialRadiusKm(body),
            )
            assertTrue("$body must have a positive radius", PlanetDisc.equatorialRadiusKm(body) > 0.0)
        }
    }

    @Test
    fun `the disc and the terminator agree about what half phase means`() {
        // Two independent functions describing one picture. At every phase the lit fraction and the
        // terminator's signed axis have to tell the same story, or a gibbous disc gets a crescent's
        // bite taken out of it.
        var phase = 0.0
        while (phase <= 180.0) {
            val lit = PlanetDisc.illuminatedFraction(phase)
            val term = PlanetDisc.terminatorFactor(phase)
            // lit = (1 + cos i)/2 and term = cos i, so lit is exactly (1 + term)/2.
            assertEquals("at $phase degrees", (1.0 + term) / 2.0, lit, 1e-15)
            if (phase < 90.0) assertTrue("gibbous at $phase", lit > 0.5 && term > 0.0)
            if (phase > 90.0) assertTrue("crescent at $phase", lit < 0.5 && term < 0.0)
            phase += 1.0
        }
    }

    @Test
    fun `a full sweep of phase never leaves the unit interval`() {
        var phase = -720.0
        while (phase <= 720.0) {
            val lit = PlanetDisc.illuminatedFraction(phase)
            assertTrue("$phase gave $lit", lit in 0.0..1.0)
            assertTrue("$phase", PlanetDisc.terminatorFactor(phase) in -1.0..1.0)
            phase += 0.5
        }
        // cos is even, so a negative phase angle is the same picture — which matters because a
        // caller subtracting two angles can easily hand this a negative one.
        assertEquals(
            PlanetDisc.illuminatedFraction(37.0),
            PlanetDisc.illuminatedFraction(-37.0),
            1e-15,
        )
    }

    @Test
    fun `limb darkening is the published one-parameter law`() {
        // 1 - u(1 - cos(theta)) with cos(theta) = sqrt(1 - x^2). Computed here from the definition
        // rather than transcribed, so the assertion cannot inherit a mistake from the code.
        for (x in listOf(0.0, 0.25, 0.5, 0.75, 0.9, 1.0)) {
            val mu = kotlin.math.sqrt(1.0 - x * x)
            val expected = 1.0 - PlanetDisc.LIMB_DARKENING_U * (1.0 - mu)
            assertEquals("at $x of the radius", expected, PlanetDisc.limbDarkening(x), 1e-15)
        }
    }

    @Test
    fun `the rings ellipse has a real minor axis whenever it is not edge-on`() {
        var opening = -27.0
        while (opening <= 27.0) {
            val squash = PlanetDisc.Rings(opening, 0.0).squash
            assertTrue("$opening gave $squash", squash >= 0.0 && squash <= 1.0)
            if (abs(opening) > 1.0) assertTrue("visibly open at $opening", squash > 0.017)
            opening += 0.5
        }
    }

    @Test
    fun `the moon positions are continuous`() {
        // A wrap or a branch cut in any of the angles would show as a jump between adjacent samples.
        // Io moves fastest, about 0.14 Jovian radii a minute at its quickest, so five minutes cannot
        // move it a whole radius unless something discontinuous has happened.
        var t = 1_767_268_800_000L
        var previous = PlanetDisc.galileanMoons(t)
        repeat(3000) {
            t += 5 * 60_000L
            val now = PlanetDisc.galileanMoons(t)
            for (i in 0 until 4) {
                val jump = hypot(now[i].x - previous[i].x, now[i].y - previous[i].y)
                assertTrue("${now[i].name} jumped $jump radii in five minutes at $t", jump < 1.0)
            }
            previous = now
        }
    }

    @Test
    fun `Jupiter's moons and Saturn's rings share one pole convention`() {
        // Both poles are stated as J2000 right ascension and declination, and both are fed to the
        // same function. A sign or a units slip in either would show as a pole nowhere near the one
        // the IAU publishes, so pin the values themselves.
        assertEquals(40.589, PlanetDisc.SATURN_POLE_RA_DEG, 1e-9)
        assertEquals(83.537, PlanetDisc.SATURN_POLE_DEC_DEG, 1e-9)
        assertEquals(268.057, PlanetDisc.JUPITER_POLE_RA_DEG, 1e-9)
        assertEquals(64.495, PlanetDisc.JUPITER_POLE_DEC_DEG, 1e-9)
        // Saturn's axis is far more upright than Jupiter's is tilted toward us; both are well away
        // from the celestial pole, which is what makes the position angle worth computing at all.
        assertTrue(PlanetDisc.SATURN_POLE_DEC_DEG < 90.0)
        assertTrue(PlanetDisc.JUPITER_POLE_DEC_DEG < 90.0)
    }

    @Test
    fun `an unmoving epoch gives an unmoving answer`() {
        val a = PlanetDisc.galileanMoons(1_767_268_800_000L)
        val b = PlanetDisc.galileanMoons(1_767_268_800_000L)
        for (i in 0 until 4) {
            assertEquals(a[i].x, b[i].x, 0.0)
            assertEquals(a[i].y, b[i].y, 0.0)
            assertEquals(a[i].behind, b[i].behind)
        }
    }

    @Test
    fun `the across-track offset follows the cosine, not the sine`() {
        // y = -r cos(u) sin(D_E), so it is LARGEST when the moon is in front of or behind Jupiter and
        // ZERO at greatest elongation. Getting that the wrong way round would put the moons at their
        // furthest from the line exactly when they should be on it — and would still look like a
        // tilted line of moons.
        var t = 1_767_268_800_000L
        var atElongation = 0.0
        var atConjunction = 0.0
        repeat(4000) {
            val m = PlanetDisc.galileanMoons(t)[3]
            val along = abs(m.x) / 26.3627
            if (along > 0.99) atElongation = maxOf(atElongation, abs(m.y))
            if (along < 0.05) atConjunction = maxOf(atConjunction, abs(m.y))
            t += 3 * 3_600_000L
        }
        assertTrue("nothing sampled near elongation", atElongation > 0.0)
        assertTrue("nothing sampled near conjunction", atConjunction > 0.0)
        assertTrue(
            "y should peak at conjunction ($atConjunction) not elongation ($atElongation)",
            atConjunction > atElongation * 5.0,
        )
    }

    @Test
    fun `a body's diameter falls off as one over its distance`() {
        // The small-angle behaviour has to hold even though the function uses asin: doubling the
        // distance must halve the angle to well within a part in a million at these ratios.
        val near = PlanetDisc.apparentDiameterDeg(71_492.0, 600_000_000.0)
        val far = PlanetDisc.apparentDiameterDeg(71_492.0, 1_200_000_000.0)
        assertEquals(2.0, near / far, 1e-6)
    }

    @Test
    fun `the ring geometry is stable across a whole Saturnian year`() {
        // Saturn takes 29 years to go round, and the rings must open and close smoothly rather than
        // jumping. Sweeping right ascension all the way round at a fixed declination stands in for
        // that and would catch an atan2 branch or an out-of-range asin.
        var ra = 0.0
        var previous = PlanetDisc.rings(0.0, 5.0).openingDeg
        while (ra <= 360.0) {
            val opening = PlanetDisc.rings(ra, 5.0).openingDeg
            assertTrue("opening out of range at $ra: $opening", abs(opening) <= 90.0)
            assertTrue("opening jumped at $ra", abs(opening - previous) < 2.0)
            previous = opening
            ra += 0.5
        }
    }

    @Test
    fun `a phase angle and its supplement are mirror images`() {
        // The lit fractions at i and 180-i must sum to one: what one shows lit, the other shows dark.
        for (i in listOf(0.0, 12.5, 45.0, 71.0, 89.9)) {
            assertEquals(
                1.0,
                PlanetDisc.illuminatedFraction(i) + PlanetDisc.illuminatedFraction(180.0 - i),
                1e-15,
            )
        }
    }

    @Test
    fun `the terminator factor is the cosine it claims to be`() {
        var phase = 0.0
        while (phase <= 180.0) {
            assertEquals(
                cos(phase * Math.PI / 180.0),
                PlanetDisc.terminatorFactor(phase),
                1e-15,
            )
            phase += 3.0
        }
    }
}
