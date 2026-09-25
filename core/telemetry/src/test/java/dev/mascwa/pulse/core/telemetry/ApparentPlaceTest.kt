package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The apparent-place chain for a star — annual aberration, the true equator and equinox of date,
 * apparent sidereal time — held against Skyfield with JPL DE421, which is what Stellarium's own
 * positions are checked against.
 *
 * ⚠️ **Every figure here was written by `scratchpad/sky/apparent_ref.py`, not typed.** Ten stars
 * read out of the bundled bright catalogue (the brightest in each of ten declination bands, so the
 * fixtures are directions the chart actually draws), at five instants from 2000 to 2045:
 * `Skyfield apparent().radec(epoch='date')` for the RA/Dec, and `apparent().altaz()` at the
 * owner's site with refraction off for the horizon pair.
 *
 * ⚠️ **The horizon reference is computed with UT1 == UTC, on purpose.** Skyfield's UT1 carries
 * DUT1 — real from the IERS table up to about 2026, then EXTRAPOLATED (−1.35 s by 2045, which is
 * 20″ of hour angle that nobody can know today). The app ignores DUT1 by design, as Stellarium
 * does, so the reference timescale sets ΔT to TT−UTC at each instant (32.184 s plus the leap
 * seconds then standing — NOT a single 69.184, which is 5 s wrong on a year-2000 row). Measured
 * against the REAL-DUT1 reference instead, the same fifty rows sit at a median of 3.2″ and a worst
 * of 20.8″: that is the whole cost of ignoring DUT1, and it is a fact about the Earth's rotation
 * rather than about this code.
 *
 * Measured (S4 probe, `scratchpad/sky/S4Probe.kt`): RA/Dec of date median 0.043″, worst 0.129″;
 * alt/az median 0.198″, worst 0.306″. What remains is Meeus ch. 21 precession against IAU 2006,
 * the mean obliquity's 0.04″ constant offset, and gravitational light deflection (≤4 mas away
 * from the Sun), none of which is carried.
 */
class ApparentPlaceTest {

    private class Star(
        val ms: Long, val name: String,
        val raJ2000: Double, val decJ2000: Double,
        val raDate: Double, val decDate: Double,
        val altDeg: Double, val azDeg: Double,
    )

    private val lat = 42.7875
    private val lon = -86.1089

    private val stars = listOf(
        Star(946684800000L, "α Car", 95.987900, -52.695800, 95.9960945, -52.6977213, -28.6539941, 136.8343164),
        Star(1340668800000L, "α Car", 95.987900, -52.695800, 96.0498089, -52.7045491, -33.9682665, 226.8848718),
        Star(1734652800000L, "α Car", 95.987900, -52.695800, 96.1354427, -52.7074893, -34.4031617, 132.8440370),
        Star(1997308800000L, "α Car", 95.987900, -52.695800, 96.1701107, -52.7229245, -7.5767726, 193.9058889),
        Star(2391292800000L, "α Car", 95.987900, -52.695800, 96.2447006, -52.7156169, -74.6051636, 136.2490986),
        Star(946684800000L, "α Sco", 247.351700, -26.431900, 247.3422443, -26.4288262, -43.8970566, 272.8076678),
        Star(1340668800000L, "α Sco", 247.351700, -26.431900, 247.5542026, -26.4590183, 1.9687488, 129.7398624),
        Star(1734652800000L, "α Sco", 247.351700, -26.431900, 247.7289290, -26.4862020, -35.6599078, 265.1917421),
        Star(1997308800000L, "α Sco", 247.351700, -26.431900, 247.8701597, -26.5008701, -45.4215643, 85.8100940),
        Star(2391292800000L, "α Sco", 247.351700, -26.431900, 248.0543134, -26.5307357, 8.8635098, 220.5046985),
        Star(946684800000L, "α CMa", 101.287100, -16.716100, 101.2901117, -16.7176839, -9.4357447, 104.0941823),
        Star(1340668800000L, "α CMa", 101.287100, -16.716100, 101.4243497, -16.7313866, -9.1289666, 255.6068381),
        Star(1734652800000L, "α CMa", 101.287100, -16.716100, 101.5717132, -16.7408854, -17.5092890, 96.8665116),
        Star(1997308800000L, "α CMa", 101.287100, -16.716100, 101.6587594, -16.7587473, 28.2732186, 199.2733042),
        Star(2391292800000L, "α CMa", 101.287100, -16.716100, 101.8014479, -16.7617099, -62.0095657, 25.2840850),
        Star(946684800000L, "α CMi", 114.825400, 5.225000, 114.8272274, 5.2236604, -4.4306511, 78.6881023),
        Star(1340668800000L, "α CMi", 114.825400, 5.225000, 114.9899454, 5.1942178, 15.6637546, 262.4480658),
        Star(1734652800000L, "α CMi", 114.825400, 5.225000, 115.1619521, 5.1682698, -12.4132594, 70.7666248),
        Star(1997308800000L, "α CMi", 114.825400, 5.225000, 115.2681928, 5.1426637, 52.1830890, 186.6105497),
        Star(2391292800000L, "α CMi", 114.825400, 5.225000, 115.4350485, 5.1198743, -42.0719899, 357.9206143),
        Star(946684800000L, "α Boo", 213.915400, 19.182500, 213.9091098, 19.1825848, -25.3349365, 339.0026651),
        Star(1340668800000L, "α Boo", 213.915400, 19.182500, 214.0681098, 19.1259046, 57.9501457, 129.5747905),
        Star(1734652800000L, "α Boo", 213.915400, 19.182500, 214.2053621, 19.0645605, -21.7871593, 328.1347237),
        Star(1997308800000L, "α Boo", 213.915400, 19.182500, 214.3120860, 19.0278059, 9.2788676, 72.6073337),
        Star(2391292800000L, "α Boo", 213.915400, 19.182500, 214.4498938, 18.9720751, 20.3699905, 277.4044767),
        Star(946684800000L, "α Lyr", 279.234600, 38.783600, 279.2250655, 38.7846317, 22.2914665, 302.8897814),
        Star(1340668800000L, "α Lyr", 279.234600, 38.783600, 279.3493770, 38.7961659, 24.5997099, 58.9886743),
        Star(1734652800000L, "α Lyr", 279.234600, 38.783600, 279.4365821, 38.8045014, 29.3513369, 297.3324688),
        Star(1997308800000L, "α Lyr", 279.234600, 38.783600, 279.5175268, 38.8114501, -6.4447042, 15.4155483),
        Star(2391292800000L, "α Lyr", 279.234600, 38.783600, 279.6190721, 38.8283819, 78.5166459, 254.6425681),
        Star(946684800000L, "α Aur", 79.172500, 45.998100, 79.1754831, 45.9973764, 44.5463141, 62.3397761),
        Star(1340668800000L, "α Aur", 79.172500, 45.998100, 79.4013929, 46.0091999, 18.8233288, 316.0721107),
        Star(1734652800000L, "α Aur", 79.172500, 45.998100, 79.6415574, 46.0266957, 37.3194598, 58.1658187),
        Star(1997308800000L, "α Aur", 79.172500, 45.998100, 79.7854185, 46.0313334, 61.8571997, 290.4157386),
        Star(2391292800000L, "α Aur", 79.172500, 45.998100, 80.0258773, 46.0445741, 3.7845982, 22.8033378),
        Star(946684800000L, "ε UMa", 193.507100, 55.959700, 193.5009770, 55.9565901, 8.7445457, 0.2007831),
        Star(1340668800000L, "ε UMa", 193.507100, 55.959700, 193.6461633, 55.8954292, 76.4658485, 12.6183094),
        Star(1734652800000L, "ε UMa", 193.507100, 55.959700, 193.7792746, 55.8195134, 9.0317353, 353.8718596),
        Star(1997308800000L, "ε UMa", 193.507100, 55.959700, 193.8758953, 55.7796004, 42.1964316, 47.0248205),
        Star(2391292800000L, "ε UMa", 193.507100, 55.959700, 194.0001390, 55.7113435, 29.3571158, 320.4485210),
        Star(946684800000L, "β UMi", 222.676300, 74.155600, 222.6606020, 74.1530891, 28.5459403, 351.3857938),
        Star(1340668800000L, "β UMi", 222.676300, 74.155600, 222.6798902, 74.1083472, 55.0241019, 15.6014175),
        Star(1734652800000L, "β UMi", 222.676300, 74.155600, 222.6569747, 74.0478247, 29.8628689, 348.3221274),
        Star(1997308800000L, "β UMi", 222.676300, 74.155600, 222.6749455, 74.0186704, 37.3295539, 19.6883989),
        Star(2391292800000L, "β UMi", 222.676300, 74.155600, 222.6456605, 73.9685186, 45.8933298, 337.9318270),
        Star(946684800000L, "α UMi", 37.952900, 89.264200, 38.1911990, 89.2670376, 43.4545871, 0.4161648),
        Star(1340668800000L, "α UMi", 37.952900, 89.264200, 41.6044793, 89.3136125, 42.2119617, 359.4927977),
        Star(1734652800000L, "α UMi", 37.952900, 89.264200, 46.3036404, 89.3733187, 43.2417648, 0.5905245),
        Star(1997308800000L, "α UMi", 37.952900, 89.264200, 49.0767048, 89.3993796, 42.9878128, 359.2272707),
        Star(2391292800000L, "α UMi", 37.952900, 89.264200, 55.3645449, 89.4409364, 42.4937390, 0.6467127),
    )

    @Test
    fun `apparent right ascension and declination of date match skyfield to half an arcsecond`() {
        // Measured worst 0.129". A bar of 0.5" is four times that and a tenth of the 1.5" the
        // one-term nutation shortcut left on every planet, so it separates the two by an order of
        // magnitude while leaving floating point and the precession model out of the question.
        var worst = 0.0
        for (s in stars) {
            val got = Ephemeris.apparentStarOfDate(s.raJ2000, s.decJ2000, s.ms)
            val sep = Ephemeris.angularSeparationDeg(got[0], got[1], s.raDate, s.decDate) * 3600.0
            assertTrue("${s.name} at ${s.ms}: $sep arcsec", sep < 0.5)
            worst = maxOf(worst, sep)
        }
        assertTrue("worst $worst arcsec — the chain must be doing more than precession alone", worst > 0.0)
    }

    @Test
    fun `apparent altitude and azimuth match skyfield within an arcsecond, with UT1 as UTC`() {
        // Measured worst 0.306". This is the chart's whole star path — aberration, the true pair,
        // GAST — so a mean-equinox sidereal time here fails by ~15" and a dropped aberration by up
        // to 20", both an order of magnitude over the bar.
        for (s in stars) {
            val h = Ephemeris.apparentStarHorizontal(s.raJ2000, s.decJ2000, lat, lon, s.ms)
            val sep = Ephemeris.angularSeparationDeg(h.azimuthDeg, h.altitudeDeg, s.azDeg, s.altDeg) * 3600.0
            assertTrue("${s.name} at ${s.ms}: $sep arcsec (alt ${h.altitudeDeg})", sep < 1.0)
        }
    }

    @Test
    fun `apparent sidereal time carries the equation of the equinoxes`() {
        // Skyfield at the two instants EphemerisTest already holds GMST fixtures for. The GAST
        // reference is the UT1 == UTC one (DUT1 was 0.074 s and 0.049 s there, 1.1" and 0.7" of
        // rotation the app does not model); the equation of the equinoxes is DUT1-free, so it is
        // held to Skyfield's real value directly. Measured: 0.06" on GAST, 0.005" on the equation.
        val refs = listOf(
            // ms, GAST with UT1 == UTC (deg), Skyfield's own GAST - GMST (arcsec, real timescale)
            Triple(1767225600000L, 100.662224, 4.972),
            Triple(1782777600000L, 278.079454, 7.502),
        )
        for ((ms, gastUt1IsUtc, eqEqArcsec) in refs) {
            val jd = Ephemeris.julianDate(ms)
            val gast = Ephemeris.gastDeg(jd)
            val gmst = Ephemeris.gmstDeg(jd)
            assertEquals("GAST at $ms", gastUt1IsUtc, gast, 0.0002)
            assertEquals("equation of the equinoxes at $ms", eqEqArcsec, (gast - gmst) * 3600.0, 0.02)
        }
    }

    @Test
    fun `aberration is a displacement of twenty arcseconds along the Earth's motion, and undoes itself`() {
        // |beta| = v/c: 29.8 km/s over 299,792 km/s is 9.9e-5 radians, 20.5". It is not a rotation,
        // so a direction at right angles to the motion moves by the full amount and one along it
        // by nothing; and un-aberrating what was aberrated must return to the start to order beta².
        val beta = Ephemeris.aberrationJ2000Equatorial(1767225600000L)
        val mag = Math.toDegrees(kotlin.math.sqrt(beta[0] * beta[0] + beta[1] * beta[1] + beta[2] * beta[2])) * 3600.0
        assertTrue("|beta| $mag arcsec", mag > 19.5 && mag < 21.5)
        var worst = 0.0
        var ra = 0.0
        while (ra < 360.0) {
            var dec = -80.0
            while (dec <= 80.0) {
                val v = SkyProjection.equatorialVector(ra, dec)
                val start = v.copyOf()
                Ephemeris.aberrateEquatorial(v, beta)
                Ephemeris.unaberrateEquatorial(v, beta)
                for (i in 0..2) worst = maxOf(worst, kotlin.math.abs(v[i] - start[i]))
                dec += 20.0
            }
            ra += 30.0
        }
        // beta² is 1e-8 of a unit vector; the round trip is bounded by that, not by 1e-15.
        assertTrue("round trip residual $worst", worst < 2e-8)
    }
}
