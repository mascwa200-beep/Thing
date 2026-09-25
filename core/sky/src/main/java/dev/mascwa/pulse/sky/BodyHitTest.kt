package dev.mascwa.pulse.sky

/**
 * Whether the chart draws one of the eight solar-system bodies at all — the ONE rule the draw pass
 * and the tap both ask, so they cannot disagree about whether a planet is there.
 *
 * ## The two ways a body is not drawn
 *
 * A planet the daylight has swallowed is not drawn: with the sky bright enough to be dimming the
 * stars, a planet fainter than the stars' own cut is cut with them (Neptune at 7.8 vanishes at
 * midday exactly as a seventh-magnitude star does), and ONLY while the sky is actually bright — on
 * a dark sky the dimming is exactly zero and this never fires. The Sun and the Moon are never cut,
 * because they are what a daytime sky shows. And with the GROUND on, an opaque fill goes over
 * everything under the drawn horizon, bodies included: a planet under your feet is hidden by it,
 * exactly as it is by the real one.
 *
 * ## Why it is a file of its own
 *
 * Both halves lived inline in the draw pass and neither reached `identify`, so a tap could name a
 * planet under the ground or a Neptune the noon sky had taken — in confident detail, for a dot the
 * picture was not showing. Two derivations of "is it drawn" is how that happens; a review caught
 * it. Pure arithmetic over primitives rather than the view model's `Body`, so it runs under plain
 * JUnit and CI holds the rule.
 *
 * ⚠️ Whether the body is on the SCREEN is not decided here: that is a projection through the
 * horizon basis, which both callers already do, and it needs the basis, which this does not take.
 * Both callers apply `onScreen` with the same margin after this returns true.
 */
object BodyHitTest {

    /**
     * @param isPlanet a planet, as opposed to the Sun or the Moon — the only kind the daylight cut
     *   applies to.
     * @param magnitude the body's visual magnitude.
     * @param drawnAltDeg the APPARENT altitude the chart draws it at — refracted when the
     *   atmosphere is on — because the ground's edge is the drawn horizon, not the geometric one.
     * @param ground the GROUND switch.
     * @param dimmingMag what the bright sky is taking, `SkyBrightness.dimmingMag` — exactly zero
     *   at night and with the atmosphere off.
     * @param limit the stars' drawn cut for this frame, `SkyMapViewModel.drawnStarLimit`.
     */
    fun drawn(
        isPlanet: Boolean,
        magnitude: Double,
        drawnAltDeg: Double,
        ground: Boolean,
        dimmingMag: Double,
        limit: Double,
    ): Boolean {
        if (isPlanet && dimmingMag > 0.0 && magnitude > limit) return false
        if (ground && drawnAltDeg < 0.0) return false
        return true
    }
}
