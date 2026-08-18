package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected value here was generated from Skyfield and the JPL DE421 ephemeris, not derived by
 * hand and not read back out of this implementation.
 *
 * Skyfield propagates with the same SGP4 implementation [Sgp4] was already matched against, so
 * agreement on the orbit itself is guaranteed — which is exactly the point. What this actually
 * exercises is the new work: the TEME to Earth-fixed rotation, the topocentric look angles, the
 * Earth-fixed velocity behind the range rate, the conical shadow test, and the root finding that
 * pins down rise, culmination and set.
 *
 * Measured agreement across these fixtures: altitude within 0.00013 degrees, azimuth within
 * 0.00035, range within 17 metres, range rate within 2.4 cm/s, and pass times within 123 ms of
 * Skyfield's own root finder. The residual is about an arcsecond, which is the size of the
 * equation of the equinoxes — precisely the term the frame note in [SatellitePasses] says is
 * being neglected. The tolerances below sit a few times above what was measured, so they catch a
 * real regression without being noise.
 */
class SatellitePassesTest {

    // The live ISS element set, the same one TleTest parses.
    private val issL1 = "1 25544U 98067A   26226.82569810  .00005167  00000+0  10032-3 0  9994"
    private val issL2 = "2 25544  51.6328   9.8801 0007568  46.9314 313.2307 15.49444701580849"
    private val iss = Tle.parse(issL1, issL2, "ISS (ZARYA)")!!

    /** 2026-08-15 00:00:00 UTC — about five hours after the element set's own epoch. */
    private val t0 = 1_786_752_000_000L

    private val nyc = SatellitePasses.Site(40.7128, -74.0060, 10.0)
    private val helsinki = SatellitePasses.Site(60.1699, 24.9384, 20.0)

    /** Near the equator, where a summer night is long enough for the ISS to enter Earth's shadow
     *  while still well up the sky — see [aSatelliteNearlyOverheadInEarthsShadowIsNotOffered]. */
    private val nairobi = SatellitePasses.Site(-1.2921, 36.8219, 0.0)

    private fun at(minutes: Long) = t0 + minutes * 60_000L

    // ---- sub-point -------------------------------------------------------------------------

    @Test fun subPointMatchesTheReferenceGeodeticTrack() {
        val propagator = Sgp4.propagator(iss)
        // minutes, latitude, longitude, altitude km
        val expected = listOf(
            listOf(0.0, -48.752009, -70.534979, 439.5646),
            listOf(17.0, -31.250173, 12.742505, 427.6890),
            listOf(55.0, 48.954150, 146.911496, 421.0685),
            listOf(300.0, -18.898003, -46.272778, 421.8283),
        )
        for ((minutes, lat, lon, altKm) in expected) {
            val sub = SatellitePasses.subPoint(propagator, at(minutes.toLong()))
            assertNotNull("no sub-point at +$minutes min", sub)
            sub!!
            assertEquals("latitude at +$minutes min", lat, sub.latitudeDeg, 1e-3)
            assertEquals("longitude at +$minutes min", lon, sub.longitudeDeg, 1e-3)
            assertEquals("altitude at +$minutes min", altKm, sub.altitudeKm, 0.05)
        }
    }

    @Test fun theSubPointStaysOnTheGlobe() {
        val track = SatellitePasses.groundTrack(iss, t0, 60_000L, 95)
        assertEquals(95, track.size)
        for (p in track) {
            assertTrue("latitude off the globe: ${p.latitudeDeg}", p.latitudeDeg in -90.0..90.0)
            assertTrue("longitude not normalised: ${p.longitudeDeg}", p.longitudeDeg in -180.0..180.0)
            // The ISS orbit is near-circular; anything outside this band means a frame error.
            assertTrue("implausible altitude: ${p.altitudeKm}", p.altitudeKm in 380.0..460.0)
        }
        // Inclination 51.63 deg bounds how far north and south the track can reach.
        assertTrue(track.maxOf { it.latitudeDeg } <= 52.0)
        assertTrue(track.minOf { it.latitudeDeg } >= -52.0)
    }

    // ---- look angles -----------------------------------------------------------------------

    @Test fun lookAnglesMatchTheReferenceForAMidLatitudeSite() {
        val propagator = Sgp4.propagator(iss)
        // minutes, altitude, azimuth, range km, range rate km/s
        val expected = listOf(
            listOf(0.0, -42.83826, 177.69685, 9251.7388, 2.03165),
            listOf(17.0, -52.39314, 116.16177, 10614.7075, 0.49479),
            listOf(55.0, -39.63420, 334.32917, 8781.0396, -0.88259),
            listOf(300.0, -29.56104, 150.77819, 7052.5262, -2.89736),
        )
        for ((minutes, alt, az, range, rate) in expected) {
            val look = SatellitePasses.look(propagator, nyc, at(minutes.toLong()))
            assertNotNull("no look angle at +$minutes min", look)
            look!!
            assertEquals("altitude at +$minutes min", alt, look.altitudeDeg, 1e-3)
            assertEquals("azimuth at +$minutes min", az, look.azimuthDeg, 1e-3)
            assertEquals("range at +$minutes min", range, look.rangeKm, 0.05)
            assertEquals("range rate at +$minutes min", rate, look.rangeRateKmS, 1e-3)
        }
    }

    @Test fun lookAnglesMatchTheReferenceForAHighLatitudeSite() {
        val propagator = Sgp4.propagator(iss)
        val expected = listOf(
            listOf(0.0, -65.68949, 244.40401, 12073.0676, -1.04939),
            listOf(17.0, -44.13554, 190.45622, 9444.8822, -3.90210),
            listOf(55.0, -27.52054, 39.44158, 6734.4939, 4.63574),
            listOf(300.0, -47.03445, 244.78677, 9880.5636, -4.64166),
        )
        for ((minutes, alt, az, range, rate) in expected) {
            val look = SatellitePasses.look(propagator, helsinki, at(minutes.toLong()))!!
            assertEquals("altitude at +$minutes min", alt, look.altitudeDeg, 1e-3)
            assertEquals("azimuth at +$minutes min", az, look.azimuthDeg, 1e-3)
            assertEquals("range at +$minutes min", range, look.rangeKm, 0.05)
            assertEquals("range rate at +$minutes min", rate, look.rangeRateKmS, 1e-3)
        }
    }

    @Test fun anElementSetThatCannotBePropagatedYieldsNothingRatherThanAGuess() {
        // A geostationary satellite: period well past 225 minutes, so SGP4 does not apply.
        val geoL1 = "1 41866U 16071A   26226.50000000  .00000000  00000+0  00000+0 0  9993"
        val geoL2 = "2 41866   0.0200  95.0000 0001000   0.0000   0.0000  1.00270000 12345"
        val geo = Tle.parse(geoL1, geoL2, "GOES-16")!!
        assertTrue("this fixture must be deep-space to test the guard", geo.isDeepSpace)
        val propagator = Sgp4.propagator(geo)
        assertNull(SatellitePasses.look(propagator, nyc, t0))
        assertNull(SatellitePasses.subPoint(propagator, t0))
        assertTrue(SatellitePasses.passes(geo, nyc, t0, t0 + 86_400_000L).isEmpty())
    }

    // ---- a sighting: where it is now, and whether that is worth anything -------------------

    /**
     * A look-angle tolerance stated as metres of cross-range rather than as degrees.
     *
     * ⚠️ A fixed angular tolerance is the wrong instrument for a satellite overhead. The fixtures
     * above are all several thousand kilometres away and agree to about an arcsecond; a satellite
     * 425 km up is nine times closer, so the *same* few tens of metres of positional disagreement
     * subtend nine times the angle. Measured across the four sightings below, the disagreement with
     * Skyfield is 4 to 36 m of cross-range and 2.6 to 26 m of range — flat, as it should be, while
     * the angular figure ranges over two orders of magnitude. So the budget is stated in metres and
     * converted here, which keeps every case equally strict.
     */
    private fun crossRangeDeg(metres: Double, rangeKm: Double): Double =
        Math.toDegrees(metres / (rangeKm * 1000.0))

    /** As [crossRangeDeg], for azimuth — which is compressed by the cosine of the elevation. */
    private fun azimuthDeg(metres: Double, rangeKm: Double, altitudeDeg: Double): Double =
        crossRangeDeg(metres, rangeKm) / Math.cos(Math.toRadians(altitudeDeg))

    /** A few times the worst measured, in the spirit of the tolerances above. */
    private val crossRangeBudgetM = 100.0

    /**
     * Every figure in these four is Skyfield's, generated by sweeping the fixture window for the
     * highest instant of each kind rather than picked to suit the code. Between them they cover the
     * whole judgement: lit but drowned out, lit and worth going outside for, lit but too low to
     * see, and nearly overhead yet in Earth's shadow.
     */
    @Test fun aSightingReportsWhereItIsAndSkyfieldAgrees() {
        // +987 min: 51.4 degrees up, sunlit — and the Sun is 62 degrees up too.
        val sighting = SatellitePasses.sighting(iss, nyc, at(987))
        assertNotNull(sighting)
        sighting!!
        assertEquals(25544, sighting.noradId)
        assertEquals(51.40461, sighting.look.altitudeDeg, crossRangeDeg(crossRangeBudgetM, 527.9427))
        assertEquals(
            7.81425,
            sighting.look.azimuthDeg,
            azimuthDeg(crossRangeBudgetM, 527.9427, 51.40461),
        )
        assertEquals(527.9427, sighting.look.rangeKm, 0.05)
        assertEquals(43.46811, sighting.subPoint.latitudeDeg, 1e-3)
        assertEquals(-73.48682, sighting.subPoint.longitudeDeg, 1e-3)
        assertEquals(420.6325, sighting.subPoint.altitudeKm, 0.05)
    }

    @Test fun aSatelliteInFullSunlightInABroadDaylightSkyIsNotCalledVisible() {
        // The distinction the whole classification exists for: Skyfield says is_sunlit=True at
        // +987 min, and it is 51 degrees up — but the Sun is 62.2 degrees up, so nobody can see it.
        val sighting = SatellitePasses.sighting(iss, nyc, at(987))!!
        assertEquals(SatellitePasses.Illumination.SUNLIT, sighting.look.illumination)
        assertEquals(SatellitePasses.PassKind.DAYLIGHT, sighting.kind)
    }

    @Test fun aLitSatelliteHighInADarkSkyIsWorthGoingOutsideFor() {
        // +1990 min: 19.9 degrees up, sunlit, Sun 10.8 degrees BELOW the horizon.
        val sighting = SatellitePasses.sighting(iss, nyc, at(1990))!!
        assertEquals(19.88336, sighting.look.altitudeDeg, 1e-3)
        assertEquals(SatellitePasses.PassKind.VISIBLE, sighting.kind)
        assertTrue(sighting.worthLookingUp)
    }

    @Test fun aVisibleSatelliteScrapingTheHorizonIsNotOffered() {
        // +502 min: genuinely VISIBLE — sunlit, Sun 18 degrees down — but 1.4 degrees up, which is
        // behind the first building or tree. Kind and worth-looking-up are separate judgements.
        val sighting = SatellitePasses.sighting(iss, nyc, at(502))!!
        assertEquals(1.41168, sighting.look.altitudeDeg, 1e-3)
        assertTrue(sighting.look.aboveHorizon)
        assertEquals(SatellitePasses.PassKind.VISIBLE, sighting.kind)
        assertTrue("1.4 degrees is not worth a trip outside", !sighting.worthLookingUp)
    }

    @Test fun aSatelliteNearlyOverheadInEarthsShadowIsNotOffered() {
        // +27 min over Nairobi: 78 degrees up in a sky 46 degrees past sunset, and Skyfield says
        // is_sunlit=False. As close to overhead as it gets, and completely invisible.
        val sighting = SatellitePasses.sighting(iss, nairobi, at(27))!!
        assertEquals(77.96033, sighting.look.altitudeDeg, crossRangeDeg(crossRangeBudgetM, 424.8092))
        assertEquals(SatellitePasses.Illumination.UMBRA, sighting.look.illumination)
        assertEquals(SatellitePasses.PassKind.ECLIPSED, sighting.kind)
    }

    @Test fun theSkyIsCalledBrightUntilCivilTwilightAndNotMerelyUntilSunset() {
        // +598 min over New York: the ISS 33.6 degrees up and fully sunlit, with the Sun 2.3
        // degrees BELOW the horizon — Skyfield's figure. The Sun has set, so a rule written against
        // the horizon would offer this as a sighting; it is still far too bright to see anything.
        // Together with the visible case above, at a Sun 10.8 degrees down, this brackets the
        // threshold — which is what stops the judgement drifting if it is ever copied.
        val sighting = SatellitePasses.sighting(iss, nyc, at(598))!!
        assertEquals(SatellitePasses.Illumination.SUNLIT, sighting.look.illumination)
        assertTrue(sighting.worthLookingUp)
        assertEquals(SatellitePasses.PassKind.DAYLIGHT, sighting.kind)
    }

    @Test fun aPassAndASightingInsideItAgreeAboutTheSky() {
        // The reason the classification was factored out of buildPass rather than copied: a pass
        // and an instant inside it must never disagree about whether the sky is too bright. They
        // may legitimately differ on VISIBLE versus ECLIPSED — a pass asks whether the satellite
        // was lit at ANY point across it — but daylight is daylight.
        val window = SatellitePasses.passes(iss, nyc, t0, t0 + 3 * 86_400_000L)
        val daylight = window.filter { it.kind == SatellitePasses.PassKind.DAYLIGHT }
        assertTrue("no daylight pass in the window to check against", daylight.isNotEmpty())
        for (pass in daylight) {
            val at = SatellitePasses.sighting(iss, nyc, pass.culminationEpochMs)!!
            assertEquals(
                "pass and sighting disagree at culmination of the pass rising at ${pass.riseEpochMs}",
                SatellitePasses.PassKind.DAYLIGHT,
                at.kind,
            )
        }
    }

    @Test fun anElementSetThatCannotBePropagatedProducesNoSighting() {
        val geoL1 = "1 41866U 16071A   26226.50000000  .00000000  00000+0  00000+0 0  9993"
        val geoL2 = "2 41866   0.0200  95.0000 0001000   0.0000   0.0000  1.00270000 12345"
        val geo = Tle.parse(geoL1, geoL2, "GOES-16")!!
        assertNull(SatellitePasses.sighting(geo, nyc, t0))
    }

    // ---- illumination ----------------------------------------------------------------------

    @Test fun theShadowTestAgreesWithTheReferenceAcrossAWholeOrbit() {
        val propagator = Sgp4.propagator(iss)
        // Skyfield is_sunlit, sampled every six minutes: dark until somewhere in +30..+36.
        val expected = mapOf(
            0L to false, 6L to false, 12L to false, 18L to false, 24L to false, 30L to false,
            36L to true, 42L to true, 48L to true, 54L to true, 60L to true, 66L to true,
            72L to true, 78L to true, 84L to true, 90L to true,
        )
        for ((minutes, sunlit) in expected) {
            val look = SatellitePasses.look(propagator, nyc, at(minutes))!!
            assertEquals(
                "illumination at +$minutes min was ${look.illumination}",
                sunlit, look.illumination.isLit,
            )
        }
    }

    @Test fun theShadowHasAPenumbraBetweenFullSunAndFullDark() {
        // Walk the terminator crossing found above one second at a time: a conical model must pass
        // through partial shadow. A cylindrical shortcut would snap straight from UMBRA to SUNLIT.
        val propagator = Sgp4.propagator(iss)
        var sawPenumbra = false
        var t = at(30)
        while (t <= at(36)) {
            if (SatellitePasses.look(propagator, nyc, t)!!.illumination ==
                SatellitePasses.Illumination.PENUMBRA
            ) {
                sawPenumbra = true
                break
            }
            t += 1_000L
        }
        assertTrue("never entered the penumbra — the shadow cone is not being modelled", sawPenumbra)
    }

    // ---- magnitude -------------------------------------------------------------------------

    @Test fun magnitudeFollowsTheStandardModelAndIsAbsentWhenUnknown() {
        // Reference arithmetic at the visible culmination below: range 476.7105 km, phase 86.2845.
        val mag = SatellitePasses.visualMagnitude(-1.8, 476.7105, 86.2845)
        assertNotNull(mag)
        assertEquals(-3.023, mag!!, 1e-3)

        assertEquals(-1.8, SatellitePasses.standardMagnitude(25544)!!, 1e-9)
        // An object with no published standard magnitude reports none rather than a plausible guess.
        assertNull(SatellitePasses.standardMagnitude(99999))
        // Fully back-lit: nothing is reflecting towards the observer.
        assertNull(SatellitePasses.visualMagnitude(-1.8, 500.0, 180.0))
        // Closer is brighter, which in magnitudes means a smaller number.
        val near = SatellitePasses.visualMagnitude(-1.8, 400.0, 60.0)!!
        val far = SatellitePasses.visualMagnitude(-1.8, 1400.0, 60.0)!!
        assertTrue("$near should be brighter than $far", near < far)
    }

    // ---- passes ----------------------------------------------------------------------------

    @Test fun findsEveryPassTheReferenceFindsOverTwentyFourHours() {
        val passes = SatellitePasses.passes(iss, nyc, t0, t0 + 24 * 3600_000L)
        // Skyfield's find_events reports six culminations above ten degrees in this window.
        val expectedRises = listOf(
            1786787683037L, 1786793492083L, 1786799422375L,
            1786805267351L, 1786811040914L, 1786816892105L,
        )
        val expectedCulminations = listOf(
            1786787865644L, 1786793672891L, 1786799527728L,
            1786805397801L, 1786811238861L, 1786817028368L,
        )
        val expectedSets = listOf(
            1786788049241L, 1786793854485L, 1786799633180L,
            1786805528127L, 1786811436513L, 1786817164610L,
        )
        val expectedMaxAltitudes = listOf(34.058, 30.850, 13.446, 15.954, 54.172, 17.076)

        assertEquals("pass count", expectedRises.size, passes.size)
        passes.forEachIndexed { i, pass ->
            assertEquals("rise #$i", expectedRises[i].toDouble(), pass.riseEpochMs.toDouble(), 400.0)
            assertEquals(
                "culmination #$i",
                expectedCulminations[i].toDouble(), pass.culminationEpochMs.toDouble(), 400.0,
            )
            assertEquals("set #$i", expectedSets[i].toDouble(), pass.setEpochMs.toDouble(), 400.0)
            assertEquals("max altitude #$i", expectedMaxAltitudes[i], pass.maxAltitudeDeg, 0.02)
            assertTrue("pass #$i runs backwards", pass.durationMs > 0)
            // Every reported pass is a complete one, so both ends sit on the horizon cut.
            assertEquals("rise altitude #$i", 10.0, altitudeAt(pass.riseEpochMs, nyc), 0.02)
            assertEquals("set altitude #$i", 10.0, altitudeAt(pass.setEpochMs, nyc), 0.02)
        }
        // The Sun is up for all six, so none of them can be seen.
        assertTrue(passes.all { it.kind == SatellitePasses.PassKind.DAYLIGHT })
        assertTrue("a daylight pass must not advertise a brightness",
            passes.all { it.brightestMagnitude == null })
    }

    @Test fun classifiesARealVisiblePassAndMeasuresIt() {
        // 2026-08-16 pre-dawn over New York: the ISS rises inside Earth's shadow and emerges into
        // sunlight partway across the sky. Exactly the case a cylindrical shadow model gets wrong.
        val from = 1787044052293L - 600_000L
        val passes = SatellitePasses.passes(iss, nyc, from, from + 3600_000L)
        assertEquals(1, passes.size)
        val pass = passes.first()

        assertEquals("rise", 1787044052293.0, pass.riseEpochMs.toDouble(), 400.0)
        assertEquals("culmination", 1787044248716.0, pass.culminationEpochMs.toDouble(), 400.0)
        assertEquals("set", 1787044446200.0, pass.setEpochMs.toDouble(), 400.0)
        assertEquals("max altitude", 59.8284, pass.maxAltitudeDeg, 0.02)
        assertEquals("rise azimuth", 220.3679, pass.riseAzimuthDeg, 0.2)
        assertEquals("set azimuth", 59.8567, pass.setAzimuthDeg, 0.2)
        assertEquals("closest approach", 476.71, pass.minRangeKm, 1.0)

        assertEquals(SatellitePasses.PassKind.VISIBLE, pass.kind)
        assertTrue(pass.isVisible)
        // 220.4 deg and 59.9 deg on the sixteen-point compass Geodesy.cardinal uses.
        assertEquals("SW to ENE", pass.trackDescription)

        // Sunlit at culmination, in shadow at the rise — the emergence is the whole point.
        val propagator = Sgp4.propagator(iss)
        assertTrue(SatellitePasses.look(propagator, nyc, pass.culminationEpochMs)!!.illumination.isLit)
        assertTrue(!SatellitePasses.look(propagator, nyc, pass.riseEpochMs)!!.illumination.isLit)

        // Brighter than Sirius, and the ISS is the one object with a published standard magnitude.
        val brightest = pass.brightestMagnitude
        assertNotNull("a visible ISS pass must carry a magnitude", brightest)
        assertTrue("implausible magnitude $brightest", brightest!! in -4.5..-2.0)

        // The brightest moment is genuinely NOT the closest one. Across this pass the phase angle
        // sweeps from 21 to 159 degrees, so the satellite is best lit well before culmination and
        // is already half back-lit by the time it is overhead. Reading the magnitude off the
        // culmination alone would report about -2.6 instead of about -3.3, so this pins down that
        // the whole pass is searched.
        val atCulmination = SatellitePasses
            .look(propagator, nyc, pass.culminationEpochMs)!!.magnitude
        assertNotNull(atCulmination)
        assertTrue(
            "brightest ($brightest) should beat the culmination value ($atCulmination)",
            brightest < atCulmination!! - 0.25,
        )
    }

    @Test fun raisingTheHorizonCutDropsTheShallowPasses() {
        val low = SatellitePasses.passes(iss, nyc, t0, t0 + 24 * 3600_000L, minElevationDeg = 10.0)
        val high = SatellitePasses.passes(iss, nyc, t0, t0 + 24 * 3600_000L, minElevationDeg = 30.0)
        assertTrue("a higher cut cannot find more passes", high.size < low.size)
        assertTrue(high.all { it.maxAltitudeDeg >= 30.0 })
        // A shorter window over the same sky is a strict subset in time.
        val half = SatellitePasses.passes(iss, nyc, t0, t0 + 12 * 3600_000L)
        assertTrue(half.size <= low.size)
        assertTrue(half.all { it.setEpochMs <= t0 + 12 * 3600_000L })
    }

    @Test fun onlyWholePassesAreReported() {
        // Start the search in the middle of a known pass: its rise already happened, so reporting
        // it would mean inventing a rise time. It must be skipped, and the next one still found.
        val insideAPass = 1786787865644L // the first culmination
        val passes = SatellitePasses.passes(iss, nyc, insideAPass, insideAPass + 3 * 3600_000L)
        assertTrue(passes.none { it.riseEpochMs <= insideAPass })
        assertTrue("the following passes must still be found", passes.isNotEmpty())
        assertEquals(1786793492083.0, passes.first().riseEpochMs.toDouble(), 400.0)
    }

    @Test fun aWindowOpeningMidPassCannotProduceAnEpochZeroRise() {
        // The regression this guards is specific and was found by benchmarking, not by reading.
        // A satellite already above the cut when the window opened left the rise time at its 0
        // sentinel, and the following descent was paired with it -- so the pass was "1970 until
        // now" and its sampling walk ran fifty-nine million iterations. One real catalogue object
        // (COSMOS 1867) hit it and took 77 seconds; the ISS masked it, because propagating an ISS
        // element set back fifty-six years fails and bailed out early by luck.
        //
        // COSMOS 1867's real element set, and a window that deliberately opens mid-pass.
        val cosmos = Tle.parse(
            "1 18187U 87060A   26227.09573321 -.00000100  00000+0  28882-6 0  9990",
            "2 18187  65.0084 223.0799 0017877 281.6408  78.2670 14.31521137 42612",
            "COSMOS 1867",
        )!!
        val site = SatellitePasses.Site(40.7128, -74.0060, 10.0)

        // Find a moment the satellite is genuinely up, then start a search from exactly there.
        val propagator = Sgp4.propagator(cosmos)
        val scanFrom = 1787040000000L
        var midPass = -1L
        var t = scanFrom
        while (t < scanFrom + 24 * 3600_000L) {
            if ((SatellitePasses.look(propagator, site, t)?.altitudeDeg ?: -90.0) > 20.0) {
                midPass = t
                break
            }
            t += 30_000L
        }
        assertTrue("fixture never found the satellite above 20 degrees", midPass > 0)

        val startedAt = System.currentTimeMillis()
        val passes = SatellitePasses.passes(cosmos, site, midPass, midPass + 6 * 3600_000L)
        val elapsed = System.currentTimeMillis() - startedAt

        // Every reported pass is real and inside the window.
        for (p in passes) {
            assertTrue("rise before the window: ${p.riseEpochMs}", p.riseEpochMs >= midPass)
            assertTrue("set before rise", p.setEpochMs > p.riseEpochMs)
            assertTrue("implausible duration ${p.durationMs} ms", p.durationMs < 3600_000L)
        }
        // Six hours over one satellite is milliseconds of work. The bug made it a minute-plus, so
        // a generous ceiling still catches any regression.
        assertTrue("pass search took ${elapsed} ms — the unbounded walk is back", elapsed < 5_000L)
    }

    @Test fun degenerateWindowsReturnNothing() {
        assertTrue(SatellitePasses.passes(iss, nyc, t0, t0).isEmpty())
        assertTrue(SatellitePasses.passes(iss, nyc, t0, t0 - 1000L).isEmpty())
        assertTrue(SatellitePasses.passes(iss, nyc, t0, t0 + 86_400_000L, limit = 0).isEmpty())
        assertEquals(2, SatellitePasses.passes(iss, nyc, t0, t0 + 86_400_000L, limit = 2).size)
    }

    // ---- ground track ----------------------------------------------------------------------

    @Test fun theGroundTrackSplitsAtTheAntimeridian() {
        // Two full orbits guarantees several wraps.
        val segments = SatellitePasses.groundTrackSegments(iss, t0, 30_000L, 380)
        assertTrue("expected the track to wrap more than once", segments.size >= 2)
        assertEquals(380, segments.sumOf { it.size })
        for (segment in segments) {
            for (i in 1 until segment.size) {
                val step = abs(segment[i].longitudeDeg - segment[i - 1].longitudeDeg)
                assertTrue("a segment still jumps the antimeridian: $step deg", step <= 180.0)
            }
        }
    }

    @Test fun degenerateTrackRequestsReturnNothing() {
        assertTrue(SatellitePasses.groundTrack(iss, t0, 60_000L, 0).isEmpty())
        assertTrue(SatellitePasses.groundTrack(iss, t0, 0L, 10).isEmpty())
        assertTrue(SatellitePasses.groundTrackSegments(iss, t0, 60_000L, 0).isEmpty())
    }

    private fun altitudeAt(epochMs: Long, site: SatellitePasses.Site): Double =
        SatellitePasses.look(Sgp4.propagator(iss), site, epochMs)!!.altitudeDeg
}
