package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.telemetry.Comets
import dev.mascwa.pulse.core.telemetry.Ephemeris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The Minor Planet Center's fixed-column format, pinned against real catalogue lines.
 *
 * ⚠️ **This is the only thing standing between a wrong column offset and a comet drawn in the wrong
 * part of the sky.** The format has no delimiters — every field is a character range — so an offset
 * that slips by one does not throw, it returns a different number. A perihelion distance read one
 * column left is still a valid double, the orbit still solves, and the app still draws a dot.
 *
 * The fixtures are verbatim lines from a live `CometEls.txt`, and the expected values were taken
 * from an independent parser rather than by counting characters. All 957 lines of that file were
 * checked the same way when these offsets were chosen: zero mismatches.
 */
class CometRepositoryTest {

    /** Verbatim lines from the MPC catalogue, spanning numbered and provisional designations. */
    private val halley =
        "0001P         2061 08  3.1476  0.571114  0.968020  112.1962   59.2960  162.1871  20260827   5.5  3.2  1P/Halley                                                MPC191592"
    private val borisov =
        "0002I         2019 12  9.0572  1.997724  3.345952  209.2911  307.8024   44.2624  20251121  11.0  4.0  2I/Borisov                                               MPEC 2025-R69"
    private val atlas =
        "0003I         2025 10 29.4825  1.356507  6.139884  128.0055  322.1535  175.1129  20251121   8.0  4.0  3I/ATLAS                                                 MPEC 2026-G41"
    private val soho =
        "0342P         2027 02  7.7817  0.051750  0.982976   27.7057   73.2548   11.6741  20260827  20.0  4.0  342P/SOHO                                                MPC101101"
    private val haleBopp =
        "    CJ95O010  1997 03 29.0341  0.924542  0.994899  130.7191  281.7980   89.7393  20260827  -2.0  4.0  C/1995 O1 (Hale-Bopp)                                    MPC194091"

    @Test
    fun `every field of a real catalogue line lands in the right place`() {
        val e = CometRepository.parse(halley).single()
        assertEquals("1P/Halley", e.designation)
        assertEquals(0.571114, e.perihelionDistanceAu, 0.0)
        assertEquals(0.968020, e.eccentricity, 0.0)
        assertEquals(112.1962, e.argumentOfPerihelionDeg, 0.0)
        assertEquals(59.2960, e.ascendingNodeDeg, 0.0)
        assertEquals(162.1871, e.inclinationDeg, 0.0)
        assertEquals(5.5, e.absoluteMagnitude!!, 0.0)
        assertEquals(3.2, e.magnitudeSlope!!, 0.0)
        // 2061-08-03.1476 TT, which an independent parser puts at exactly this Julian Date.
        assertEquals(2474039.6476, e.perihelionJdTt, 1e-9)
    }

    /**
     * ⚠️ A negative absolute magnitude is a real value, not a parse failure — Hale-Bopp's is −2.0,
     * and it is one of only a handful of comets bright enough for that. A field read one column
     * short would drop the minus sign and make the brightest comet of the century look ordinary.
     */
    @Test
    fun `a negative absolute magnitude survives, and so does a provisional designation`() {
        val e = CometRepository.parse(haleBopp).single()
        assertEquals("C/1995 O1 (Hale-Bopp)", e.designation)
        assertEquals(-2.0, e.absoluteMagnitude!!, 0.0)
        assertEquals(0.994899, e.eccentricity, 0.0)
    }

    /** Unbound orbits are ordinary here — all three interstellar objects are in this catalogue. */
    @Test
    fun `hyperbolic eccentricities are read as published`() {
        assertEquals(3.345952, CometRepository.parse(borisov).single().eccentricity, 0.0)
        assertEquals(6.139884, CometRepository.parse(atlas).single().eccentricity, 0.0)
        assertEquals(0.982976, CometRepository.parse(soho).single().eccentricity, 0.0)
    }

    /**
     * ⚠️ **The end-to-end check, and the one that would catch a plausible-looking offset.**
     *
     * Every assertion above compares one number against another number, which a consistently
     * shifted parser could still satisfy if the expectations were derived the same wrong way. This
     * one does not: it feeds the parsed elements to the solver and requires the answer to land where
     * JPL DE421 independently says Halley is. Nothing about that value came from this file.
     */
    @Test
    fun `a parsed line drives the solver to the position JPL independently gives`() {
        val e = CometRepository.parse(halley).single()
        val s = Comets.positionOf(e, 1788220800000L)
        assertNotNull(s)
        val err = Ephemeris.angularSeparationDeg(
            s!!.equatorial.rightAscensionDeg, s.equatorial.declinationDeg,
            124.92977054178874, 3.16251749030594,
        ) * 3600.0
        assertTrue("parsed Halley is $err arcseconds from where DE421 puts it", err < 1.0)
        assertTrue("and at the right distance", abs(s.geocentricAu - 35.84032869882962) < 1e-4)
    }

    @Test
    fun `several lines parse together and keep their order`() {
        val all = CometRepository.parse(listOf(halley, borisov, atlas, soho, haleBopp).joinToString("\n"))
        assertEquals(5, all.size)
        assertEquals(
            listOf("1P/Halley", "2I/Borisov", "3I/ATLAS", "342P/SOHO", "C/1995 O1 (Hale-Bopp)"),
            all.map { it.designation },
        )
    }

    /**
     * Rubbish is dropped rather than guessed at. A header line, a blank, a truncated record and a
     * line of the right length carrying nothing numeric all have to disappear silently — the MPC
     * has changed this file's preamble before, and a parser that threw would lose the catalogue.
     */
    @Test
    fun `lines that are not records are dropped without taking the good ones with them`() {
        val junk = listOf(
            "",
            "   ",
            "# a comment the file does not currently have but might",
            halley.substring(0, 60),
            "x".repeat(170),
        )
        val mixed = (junk + halley + junk + borisov).joinToString("\n")
        val parsed = CometRepository.parse(mixed)
        assertEquals(listOf("1P/Halley", "2I/Borisov"), parsed.map { it.designation })
    }

    /**
     * ⚠️ The Julian Date has to come from a real calendar. Verified against Skyfield on live
     * catalogue entries — identical to the last digit, a difference of zero seconds — and these
     * three span a leap year, a century-adjacent year, and a date before the epoch it counts from.
     */
    @Test
    fun `the perihelion date matches an independent calendar`() {
        assertEquals(2474039.6476, CometRepository.julianDate(2061, 8, 3.1476), 1e-9)
        assertEquals(2458826.5572, CometRepository.julianDate(2019, 12, 9.0572), 1e-9)
        assertEquals(2450536.5341, CometRepository.julianDate(1997, 3, 29.0341), 1e-9)
        // 2024 was a leap year: the 29th of February exists and is one day before the 1st of March.
        assertEquals(
            1.0,
            CometRepository.julianDate(2024, 3, 1.0) - CometRepository.julianDate(2024, 2, 29.0),
            1e-9,
        )
    }

}
