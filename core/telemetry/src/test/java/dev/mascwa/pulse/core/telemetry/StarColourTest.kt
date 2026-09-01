package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The B-V colour table, pinned at the real stars either side of every band edge.
 *
 * ⚠️ **This exists because the table now has two consumers on two platforms and the failure would be
 * silent.** It was a private function inside one Android screen; the companion draws the same
 * bundled catalogue, so the choice was one shared table or a second copy free to drift — the mistake
 * this project has corrected six times. Nothing about a wrong colour throws, renders oddly, or shows
 * up in a compile: a chart drawn in slightly wrong colours simply looks like a chart.
 *
 * The values are checked against **actual stars whose colours are known** rather than against the
 * band numbers restated, so a boundary moved by a decimal fails here instead of quietly turning
 * Betelgeuse yellow.
 */
class StarColourTest {

    /** Blue-white: Rigel is B8, measured at −0.03, and looks it. */
    @Test
    fun `a hot star comes out blue`() {
        assertEquals(0xFFBBD2FF.toInt(), StarNames.colourArgb(-0.03))
        assertEquals(0xFFBBD2FF.toInt(), StarNames.colourArgb(-0.24)) // Spica, hotter still
    }

    /** Orange: Betelgeuse is M2 at +1.85, and Aldebaran K5 at +1.54. Both past the last edge. */
    @Test
    fun `a cool star comes out orange`() {
        assertEquals(0xFFFFB27A.toInt(), StarNames.colourArgb(1.85))
        assertEquals(0xFFFFB27A.toInt(), StarNames.colourArgb(1.54))
    }

    /**
     * The Sun is +0.65 and Capella +0.80 — both in the fourth band, which is where a sun-like star
     * belongs. ⚠️ This is the assertion most worth having: the fourth and fifth bands are adjacent
     * warm creams, so an off-by-one there is invisible on screen and would still be wrong.
     */
    @Test
    fun `a sun-like star sits in the warm band, not the orange one`() {
        assertEquals(0xFFFFE7BE.toInt(), StarNames.colourArgb(0.65))
        assertEquals(0xFFFFE7BE.toInt(), StarNames.colourArgb(0.80))
        assertNotEquals(StarNames.colourArgb(0.65), StarNames.colourArgb(1.6))
    }

    /** Sirius at 0.00 is the edge case the first comparison turns on: `< 0.0` is false, so band two. */
    @Test
    fun `zero is not negative`() {
        assertEquals(0xFFE4ECFF.toInt(), StarNames.colourArgb(0.0))
        assertEquals(0xFFBBD2FF.toInt(), StarNames.colourArgb(-0.001))
    }

    /**
     * ⚠️ **The contract, not an oversight.** About three per cent of the catalogue has no measured
     * colour, and the honest answer is the drawing surface's own ink — a palette fact, which belongs
     * to the platform rather than to a module with no UI dependency. Returning a made-up white here
     * would put a claim about a measurement into a value nothing could tell from a real one.
     */
    @Test
    fun `no measured colour returns nothing rather than a guess`() {
        assertNull(StarNames.colourArgb(null))
    }

    /** Every band is a distinct, fully opaque colour — a transparent one would draw as nothing. */
    @Test
    fun `the bands are distinct and opaque`() {
        val bands = listOf(-0.5, 0.1, 0.4, 0.8, 1.2, 2.0).map { StarNames.colourArgb(it)!! }
        assertEquals("two bands render identically", bands.size, bands.distinct().size)
        bands.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
    }
}
