package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.SkyProjection

/**
 * Which star a fingertip landed on — restricted to the stars the chart is actually DRAWING.
 *
 * ## The defect this exists to remove
 *
 * `identify` used to search the whole layer within the tap tolerance and hand back the brightest
 * hit, and the layer is far deeper than the screen: a 15° field draws stars to about magnitude 8.5
 * from a file that goes to 15, and at midday with the atmosphere on the chart draws nothing fainter
 * than Venus. So a tap on empty sky could name an eleventh-magnitude star nobody could see, and the
 * card would describe, in confident detail, a dot that was not there.
 *
 * ## The rule, and where each half of it comes from
 *
 * A star is drawn when it survives everything `collectStars` asks of it, in this order: the
 * catalogue magnitude against the limit; the magnitude the air leaves against the same limit; and
 * its projection landing on the drawing surface. Two more things stand between a survivor and the
 * eye, both decided in the draw pass rather than the collector: with the GROUND on, everything
 * under the drawn horizon is painted over (the below-horizon batches are not drawn at all), and
 * that is what the `ground` flag applies here. This applies exactly those cuts, to the candidates
 * within reach of the finger, and nothing else — so a star this names is a star the chart drew.
 *
 * ⚠️ **The extincted magnitude is compared as a DOUBLE, bit-identical to `collectStars`.** A first
 * version rounded it to a float before the cut, and a float has half an ulp of slack around 8.5
 * (about 5e-7 magnitudes) — so a star the draw pass rejected by that hair was named by the tap.
 * Rare, but "the same two cuts" has to mean the same arithmetic, and a review caught it.
 *
 * ## Why it is a file of its own
 *
 * `identify` lives in the view model, which is Android-bound; this touches nothing but the layer,
 * the frame and arithmetic, so it runs under plain JUnit and CI holds the rule. The alternative — the
 * rule as a private function beside the tap — is what shipped, and it was wrong for two slices with
 * every test green, because no test could reach it.
 *
 * ⚠️ **Brighter wins, and "brighter" is the DRAWN magnitude, not the catalogue one.** Two stars
 * within a fingertip are both what was meant; the answer is the one that can actually be seen, and
 * at the horizon the air can reorder them. The card still reports the catalogue magnitude, which is
 * the star's, not the air's.
 */
object StarHitTest {

    /**
     * The brightest DRAWN star of [layer] within [cosTolerance] of [target], or null.
     *
     * @param target the touched direction in the layer's own frame — J2000 equatorial, aberration
     *   already taken back off, as `identify` prepares it.
     * @param cosTolerance the cosine of the tap tolerance, compared against a dot product so no
     *   angle is ever recovered from a cosine near one.
     * @param frame the frame the chart drew with; supplies the altitude sine, the projection and,
     *   with the atmosphere on, what the air takes at it. With the atmosphere off `extinctionMag`
     *   is 0.0 and the second cut is the first one repeated, which is how a chart with the switch
     *   off answers exactly as it always did.
     * @param limit the magnitude the chart is cutting at — the SAME number `collectStars` was given
     *   for this frame, derived once in the view model so the tap and the draw cannot disagree.
     * @param viewport the drawing surface, in the units [SkyProjection.Screen] uses — the one the
     *   chart was drawn on. A star inside the tap tolerance can still lie past the edge of the
     *   screen: the tolerance is an angle and the edge is a line, and at the top of a portrait
     *   screen the projection stretches a degree over more of the surface than it does in the
     *   middle. `collectStars` does not draw such a star, so this does not name it.
     * @param edgeMarginUnits how far past the edge `collectStars` still draws — pass
     *   `SkyRenderer.EDGE_MARGIN`, which is a parameter here only because that object is
     *   Compose-bound and this file is not. Anything else is a second definition of the edge.
     * @param ground whether the GROUND is on. When it is, the chart paints an opaque fill over
     *   everything under the drawn horizon and does not draw the below-horizon batches at all, so
     *   a star under it — however bright, however exactly the finger lands — is not there to name.
     */
    fun nearestDrawnStar(
        layer: StarLayer,
        target: DoubleArray,
        cosTolerance: Double,
        frame: SkyFrame,
        limit: Double,
        viewport: SkyProjection.Viewport,
        edgeMarginUnits: Double,
        ground: Boolean,
    ): Int? {
        var best = -1
        var bestDrawn = Double.MAX_VALUE
        for (i in 0 until layer.count) {
            val m0 = layer.magnitude[i]
            // The catalogue magnitude first, as the draw pass does: the air only ever dims, so a
            // star past the cut here cannot come back through it — and a star no brighter than the
            // best so far cannot win, since its drawn magnitude is at least its catalogue one.
            // ⚠️ An EARLY-OUT, not the rule: the cut below subsumes it (m ≥ m0), which is why
            // deleting this line alone leaves every test green — measured, and the negative test
            // removes both cuts for that reason. What this line buys is the dot product it skips.
            if (m0 > limit || m0 >= bestDrawn) continue
            val x = layer.vx[i]
            val y = layer.vy[i]
            val z = layer.vz[i]
            val dot = target[0] * x + target[1] * y + target[2] * z
            if (dot < cosTolerance) continue
            val s = frame.sinAltitude(x, y, z)
            // Under the ground: not drawn, so not named. The same test that sorts a star into the
            // below-horizon batch, which is the batch the draw pass skips when the ground is on.
            if (ground && !frame.aboveHorizon(s)) continue
            // A Double, exactly as collectStars computes and compares it — see the class note.
            val m = m0 + frame.extinctionMag(s)
            if (m > limit || m >= bestDrawn) continue
            // Last, because it is the one projection this loop pays and only a candidate that has
            // survived every cheaper cut reaches it: the same `project` and the same `onScreen`
            // the draw pass uses, so a star just past the margin is as invisible here as there.
            if (!frame.project(x, y, z).onScreen(viewport, edgeMarginUnits)) continue
            bestDrawn = m
            best = i
        }
        return if (best >= 0) best else null
    }
}
