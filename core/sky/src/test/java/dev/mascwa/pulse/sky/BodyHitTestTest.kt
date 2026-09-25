package dev.mascwa.pulse.sky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rule for whether a solar-system body is drawn, asked by the draw pass and by the tap.
 *
 * Each case pins one clause and its exact boundary, because both clauses are comparisons whose
 * direction is easy to get backwards and whose failure is quiet: a planet drawn at noon, or a tap
 * naming one under the ground, with nothing thrown either way.
 */
class BodyHitTestTest {

    @Test
    fun `a planet fainter than the cut is not drawn while the sky is bright, and is at night`() {
        // Neptune at 7.8 against a daytime cut of −4: gone.
        assertFalse(BodyHitTest.drawn(isPlanet = true, magnitude = 7.8, drawnAltDeg = 40.0, ground = false, dimmingMag = 12.5, limit = -4.0))
        // The same planet against the same cut with NO dimming — a dark sky — is drawn: the cut
        // only applies while the sky is actually taking something.
        assertTrue(BodyHitTest.drawn(isPlanet = true, magnitude = 7.8, drawnAltDeg = 40.0, ground = false, dimmingMag = 0.0, limit = -4.0))
        // And a planet brighter than the daytime cut survives it: Venus at −4.4 against −4.0.
        assertTrue(BodyHitTest.drawn(isPlanet = true, magnitude = -4.4, drawnAltDeg = 40.0, ground = false, dimmingMag = 12.5, limit = -4.0))
    }

    @Test
    fun `the Sun and the Moon are never cut by daylight`() {
        // A Moon at −10 is brighter than any cut anyway; the point is the kind, so give it a
        // magnitude the planet clause would reject and check it is still drawn.
        assertTrue(BodyHitTest.drawn(isPlanet = false, magnitude = 7.8, drawnAltDeg = 40.0, ground = false, dimmingMag = 12.5, limit = -4.0))
    }

    @Test
    fun `with the ground on, a body under the drawn horizon is hidden — any body`() {
        assertFalse(BodyHitTest.drawn(isPlanet = true, magnitude = -2.0, drawnAltDeg = -3.0, ground = true, dimmingMag = 0.0, limit = 8.5))
        assertFalse(BodyHitTest.drawn(isPlanet = false, magnitude = -12.0, drawnAltDeg = -0.1, ground = true, dimmingMag = 0.0, limit = 8.5))
        // With the ground off the same body is drawn, dimmed — the map shows where things are.
        assertTrue(BodyHitTest.drawn(isPlanet = true, magnitude = -2.0, drawnAltDeg = -3.0, ground = false, dimmingMag = 0.0, limit = 8.5))
        // Exactly on the drawn horizon is up: the ground begins below zero, not at it.
        assertTrue(BodyHitTest.drawn(isPlanet = true, magnitude = -2.0, drawnAltDeg = 0.0, ground = true, dimmingMag = 0.0, limit = 8.5))
    }
}
