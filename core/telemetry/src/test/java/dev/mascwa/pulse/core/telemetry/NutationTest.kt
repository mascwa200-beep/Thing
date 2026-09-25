package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IAU 2000B evaluator is held to Skyfield's evaluation of the same model, and to the full
 * IAU 2000A model it stands in for.
 *
 * ⚠️ **Every figure here was written by `tools/sky/build_nutation.py` into `pins.json`, not typed
 * from memory** — `b` is `nutationlib.iau2000b` and `a` is `nutationlib.iau2000a`, both at the
 * same TT instants, in arcseconds. The builder had already asserted its own scalar loop against
 * `iau2000b` to 3e-14″ at 400 instants before it wrote the table; this test is the Kotlin loop
 * against the same numbers, so a misread column, a swapped sin/cos or a lost power of `t` fails
 * here by milliarcseconds against a bar of a microarcsecond.
 */
class NutationTest {

    private class Pin(val jdTT: Double, val bPsi: Double, val bEps: Double, val aPsi: Double, val aEps: Double)

    // J2000, 1900, 1950, 2026, 2045, 2075, 2100.
    private val pins = listOf(
        Pin(2451545.0, -13.931663888969783, -5.769417077292847, -13.93199633096007, -5.769398076465288),
        Pin(2415020.5, 17.433233836030652, -2.2901898233181917, 17.43363528223019, -2.2901500289892063),
        Pin(2433282.5, -3.3032615828292236, 8.323457514858648, -3.303175484015963, 8.323119711683464),
        Pin(2461041.5, 5.421077937104924, 8.065415723662106, 5.4205514008915605, 8.065596935535327),
        Pin(2467980.5, 7.284211540874366, 7.688888257533103, 7.284011448196086, 7.688732913864711),
        Pin(2478938.75, -15.375704559161854, -4.369308124352597, -15.37550739287775, -4.369173878844434),
        Pin(2488069.5, 3.2898362908155367, 8.563816430681195, 3.2884077167359154, 8.564340841229999),
    )

    private fun centuries(jdTT: Double) = (jdTT - 2451545.0) / 36525.0

    @Test
    fun `the evaluator reproduces skyfield's iau2000b to a microarcsecond`() {
        // Same table, same model, a different language: agreement to a microarcsecond is floating
        // point and nothing else. The smallest coefficient in the table is 0.05 mas, so a single
        // misread term fails this by fifty times the bar.
        for (p in pins) {
            val got = Nutation.arcsec(centuries(p.jdTT))
            assertEquals("dpsi at ${p.jdTT}", p.bPsi, got[0], 1e-6)
            assertEquals("deps at ${p.jdTT}", p.bEps, got[1], 1e-6)
        }
    }

    @Test
    fun `the B model sits within two milliarcseconds of the full A model at every pin`() {
        // Measured by the builder: worst 1.43 mas across these seven instants (it is the year-2100
        // pin; inside 1995-2050 the model's own claim of a milliarcsecond holds). Stated here so
        // the truncation is a number rather than a docstring.
        var worst = 0.0
        for (p in pins) {
            val got = Nutation.arcsec(centuries(p.jdTT))
            worst = maxOf(worst, abs(got[0] - p.aPsi), abs(got[1] - p.aEps))
        }
        assertTrue("worst B-vs-A gap $worst arcsec", worst < 0.002)
        // And it is a real gap, not two copies of one table: the planetary series is what B collapses.
        assertTrue("B and A are not identical ($worst)", worst > 0.0002)
    }

    @Test
    fun `the table is the one the builder wrote`() {
        // A regenerated table that silently changed shape or reordered its first row fails here;
        // these are the first and last rows of the NOVAS/SOFA 2000A table as printed by the builder.
        assertEquals(77, NutationTables.TERMS)
        assertEquals(77 * 5, NutationTables.MULTIPLIERS.size)
        assertEquals(77 * 3, NutationTables.LONGITUDE.size)
        assertEquals(77 * 3, NutationTables.OBLIQUITY.size)
        // Row 0 is the Moon's node term: multipliers (0,0,0,0,1), the largest amplitude by far.
        assertEquals(listOf(0, 0, 0, 0, 1), NutationTables.MULTIPLIERS.slice(0 until 5))
        assertEquals(-172064161.0, NutationTables.LONGITUDE[0], 0.0)
        assertEquals(92052331.0, NutationTables.OBLIQUITY[0], 0.0)
        // Row 76, the last one B keeps.
        assertEquals(listOf(1, 1, 2, -2, 2), NutationTables.MULTIPLIERS.slice(76 * 5 until 77 * 5))
        assertEquals(1290.0, NutationTables.LONGITUDE[76 * 3], 0.0)
        assertEquals(-556.0, NutationTables.OBLIQUITY[76 * 3], 0.0)
        // The planetary constants iau2000b adds, in tenths of a microarcsecond.
        assertEquals(-1350.0, NutationTables.PLANETARY_LONGITUDE, 0.0)
        assertEquals(3880.0, NutationTables.PLANETARY_OBLIQUITY, 0.0)
    }

    @Test
    fun `the one-term shortcut it replaced was tenths of an arcsecond wrong`() {
        // Meeus ch. 22's quick form, which was the whole nutation in this project until S4:
        // dpsi = -17.20" sin(Omega). Over 1900-2100 the full series departs from it by up to about
        // an arcsecond and typically by half of one, which is what "good to 0.5 arcsec" means and is
        // the ~1.5" residual every VSOP87 planet carried against DE421 before this file existed.
        var worst = 0.0
        var t = -1.0
        while (t <= 1.0) {
            val omega = (125.04 - 1934.136 * t) * Math.PI / 180.0
            val shortcut = -0.00478 * 3600.0 * sin(omega)
            worst = maxOf(worst, abs(Nutation.arcsec(t)[0] - shortcut))
            t += 0.002
        }
        assertTrue("worst departure from the one-term form $worst arcsec", worst > 0.3 && worst < 2.0)
    }
}
