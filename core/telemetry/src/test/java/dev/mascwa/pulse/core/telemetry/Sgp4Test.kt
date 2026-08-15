package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * SGP4 is validated against the authoritative implementation, not against itself.
 *
 * Every expected vector below was produced by the reference SGP4 that ships as the `sgp4` Python
 * package — Brandon Rhodes' port of Vallado's official C++ from *Revisiting Spacetrack Report #3*,
 * the same code the catalogues themselves are checked against. The first case is the canonical
 * published verification object (catalogue 00005); the other two are live element sets for the ISS
 * and the Chinese space station. Each is checked at five epochs including a negative step
 * (propagating backwards) and a twelve-hour run.
 *
 * The tolerance is one metre. The port actually agrees to about seven millimetres, which is the
 * rounding in the stored expectations rather than any real disagreement.
 */
class Sgp4Test {

    private data class Ref(
        val name: String, val l1: String, val l2: String,
        val tsince: Double, val r: DoubleArray, val v: DoubleArray,
    )

    private val references = listOf(
        Ref("vallado00005", "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753", "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667", 0.0, doubleArrayOf(7022.4652927, -1400.0829676, 0.0399516), doubleArrayOf(1.8938410, 6.4058938, 4.5348073)),
        Ref("vallado00005", "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753", "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667", 60.0, doubleArrayOf(-8198.2700368, 5546.9047334, 2599.0678597), doubleArrayOf(-3.2940764, -3.5829219, -2.8380987)),
        Ref("vallado00005", "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753", "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667", 180.0, doubleArrayOf(-4822.1845529, 7616.4571534, 4416.2567707), doubleArrayOf(-5.2442562, -1.5972467, -1.7889725)),
        Ref("vallado00005", "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753", "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667", 720.0, doubleArrayOf(-7134.5934012, 6531.6864133, 3260.2718648), doubleArrayOf(-4.1137930, -2.9119220, -2.5573279)),
        Ref("vallado00005", "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753", "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667", -120.0, doubleArrayOf(6339.8642991, 3536.7859945, 3189.5208480), doubleArrayOf(-3.3091810, 5.6681722, 3.3608713)),
        Ref("iss25544", "1 25544U 98067A   26226.82569810  .00005167  00000+0  10032-3 0  9994", "2 25544  51.6328   9.8801 0007568  46.9314 313.2307 15.49444701580849", 0.0, doubleArrayOf(6692.9484443, 1165.7113675, 0.0001628), doubleArrayOf(-0.8266706, 4.6831648, 6.0113746)),
        Ref("iss25544", "1 25544U 98067A   26226.82569810  .00005167  00000+0  10032-3 0  9994", "2 25544  51.6328   9.8801 0007568  46.9314 313.2307 15.49444701580849", 60.0, doubleArrayOf(-3491.8101202, -4009.5029170, -4248.4608724), doubleArrayOf(6.4851084, -1.8058801, -3.6278190)),
        Ref("iss25544", "1 25544U 98067A   26226.82569810  .00005167  00000+0  10032-3 0  9994", "2 25544  51.6328   9.8801 0007568  46.9314 313.2307 15.49444701580849", 180.0, doubleArrayOf(6464.7007785, -568.8640321, -2021.9318442), doubleArrayOf(2.1518906, 4.8082202, 5.5609626)),
        Ref("iss25544", "1 25544U 98067A   26226.82569810  .00005167  00000+0  10032-3 0  9994", "2 25544  51.6328   9.8801 0007568  46.9314 313.2307 15.49444701580849", 720.0, doubleArrayOf(656.4863009, -4175.2811268, -5333.1653992), doubleArrayOf(7.5703312, 1.0655867, 0.1026435)),
        Ref("iss25544", "1 25544U 98067A   26226.82569810  .00005167  00000+0  10032-3 0  9994", "2 25544  51.6328   9.8801 0007568  46.9314 313.2307 15.49444701580849", -120.0, doubleArrayOf(-1002.7526273, -4331.0866514, -5152.8520734), doubleArrayOf(7.4858403, 0.1136575, -1.5494244)),
        Ref("css48274", "1 48274U 21035A   26224.98627525  .00000101  00000+0  54127-5 0  9991", "2 48274  41.4709 337.2096 0001079 250.4973 109.5748 15.58975796302033", 0.0, doubleArrayOf(6240.1617028, -2621.8987057, -0.0052746), doubleArrayOf(2.2232862, 5.3039207, 5.0865559)),
        Ref("css48274", "1 48274U 21035A   26224.98627525  .00000101  00000+0  54127-5 0  9991", "2 48274  41.4709 337.2096 0001079 250.4973 109.5748 15.58975796302033", 60.0, doubleArrayOf(-5250.8425944, -2243.4223363, -3638.4889005), doubleArrayOf(4.4164858, -5.5264514, -2.9689046)),
        Ref("css48274", "1 48274U 21035A   26224.98627525  .00000101  00000+0  54127-5 0  9991", "2 48274  41.4709 337.2096 0001079 250.4973 109.5748 15.58975796302033", 180.0, doubleArrayOf(5306.1744321, -3981.0857460, -1350.8387299), doubleArrayOf(4.3086859, 4.1033916, 4.8495932)),
        Ref("css48274", "1 48274U 21035A   26224.98627525  .00000101  00000+0  54127-5 0  9991", "2 48274  41.4709 337.2096 0001079 250.4973 109.5748 15.58975796302033", 720.0, doubleArrayOf(-8.5501305, -5298.7539886, -4215.6548054), doubleArrayOf(7.3441268, -1.3852543, 1.7268906)),
        Ref("css48274", "1 48274U 21035A   26224.98627525  .00000101  00000+0  54127-5 0  9991", "2 48274  41.4709 337.2096 0001079 250.4973 109.5748 15.58975796302033", -120.0, doubleArrayOf(-3785.2720919, -3659.0374901, -4257.9601210), doubleArrayOf(6.0615025, -4.4243329, -1.5878961)),
    )

    @Test fun matchesTheReferenceImplementationAtEveryEpoch() {
        var worstPos = 0.0
        var worstVel = 0.0
        for (ref in references) {
            val elements = Tle.parse(ref.l1, ref.l2, ref.name)
            assertNotNull("could not parse ${ref.name}", elements)
            val result = Sgp4.propagator(elements!!).propagate(ref.tsince)
            assertTrue(
                "${ref.name} at t=${ref.tsince} did not propagate: $result",
                result is Sgp4.Propagation.Ok,
            )
            val s = (result as Sgp4.Propagation.Ok).state
            worstPos = maxOf(
                worstPos,
                abs(s.x - ref.r[0]), abs(s.y - ref.r[1]), abs(s.z - ref.r[2]),
            )
            worstVel = maxOf(
                worstVel,
                abs(s.vx - ref.v[0]), abs(s.vy - ref.v[1]), abs(s.vz - ref.v[2]),
            )
        }
        assertTrue("worst position error was $worstPos km, expected under 1 m", worstPos < 0.001)
        assertTrue("worst velocity error was $worstVel km/s", worstVel < 1e-6)
    }

    @Test fun propagatesBackwardsAsWellAsForwards() {
        val negative = references.first { it.tsince < 0 }
        val elements = Tle.parse(negative.l1, negative.l2)!!
        val result = Sgp4.propagator(elements).propagate(negative.tsince)
        assertTrue(result is Sgp4.Propagation.Ok)
        val s = (result as Sgp4.Propagation.Ok).state
        assertEquals(negative.r[0], s.x, 0.001)
    }

    @Test fun deepSpaceObjectsAreDeclinedRatherThanGuessedAt() {
        // The ISS elements with a geostationary mean motion: period ~1436 min, far past the
        // 225-minute threshold where SGP4 stops applying and SDP4 would be required.
        val geoLine2 = "2 25544  51.6328   9.8801 0007568  46.9314 313.2307  1.00270000580849"
        val elements = Tle.parse(
            "1 25544U 98067A   26226.82569810  .00005167  00000+0  10032-3 0  9994",
            geoLine2,
        )
        assertNotNull(elements)
        assertTrue("a geostationary period must read as deep space", elements!!.isDeepSpace)
        assertTrue(elements.periodMinutes > 1400)
        val propagator = Sgp4.propagator(elements)
        assertTrue(propagator.isDeepSpace)
        // The point of the whole exercise: no plausible-looking wrong number.
        assertTrue(propagator.propagate(0.0) is Sgp4.Propagation.DeepSpace)
        assertTrue(propagator.propagate(500.0) is Sgp4.Propagation.DeepSpace)
    }

    @Test fun lowEarthOrbitIsNotMistakenForDeepSpace() {
        for (ref in references) {
            val e = Tle.parse(ref.l1, ref.l2)!!
            assertTrue("${ref.name} should be near-Earth", !e.isDeepSpace)
            assertTrue("${ref.name} period looks wrong: ${e.periodMinutes}", e.periodMinutes < 225.0)
        }
    }

    @Test fun stateGeometryIsSelfConsistent() {
        val e = Tle.parse(references[5].l1, references[5].l2)!! // the ISS at epoch
        val s = (Sgp4.propagator(e).propagate(0.0) as Sgp4.Propagation.Ok).state
        // The ISS orbits a few hundred km up at roughly 7.7 km/s.
        assertTrue("altitude was ${s.altitudeKm} km", s.altitudeKm in 300.0..500.0)
        assertTrue("speed was ${s.speedKmS} km/s", s.speedKmS in 7.0..8.0)
        assertEquals(s.radiusKm, s.altitudeKm + Sgp4.EARTH_RADIUS_KM, 1e-9)
    }

    @Test fun unusableElementsReportWhyInsteadOfPropagating() {
        val base = Tle.parse(references[5].l1, references[5].l2)!!
        val zeroMotion = base.copy(noKozai = 0.0)
        assertTrue(Sgp4.propagator(zeroMotion).propagate(0.0) is Sgp4.Propagation.BadElements)
        val hyperbolic = base.copy(eccentricity = 1.5)
        assertTrue(Sgp4.propagator(hyperbolic).propagate(0.0) is Sgp4.Propagation.BadElements)
        val negativeEcc = base.copy(eccentricity = -0.1)
        assertTrue(Sgp4.propagator(negativeEcc).propagate(0.0) is Sgp4.Propagation.BadElements)
    }

    @Test fun minutesBetweenConvertsWallClockToElapsedMinutes() {
        val e = Tle.parse(references[5].l1, references[5].l2)!!
        // One hour past the epoch, expressed as an instant, must read as 60 minutes.
        val epochMillis = ((e.epochJulian - 2440587.5) * 86_400_000.0).toLong() + 3_600_000L
        assertEquals(60.0, Sgp4.minutesBetween(e.epochJulian, epochMillis), 0.001)
    }
}
