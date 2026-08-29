package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.ReferenceCircles

/**
 * The celestial equator and the ecliptic, filled into the same container the constellations use.
 *
 * They are fixed in the equatorial frame — the equator by definition, the ecliptic to well within a
 * pixel over any span this map is asked about — so they are built **once** and cost one dot product
 * per arc per frame after that, exactly like a constellation border. Nothing here runs in the draw
 * pass.
 *
 * ⚠️ **Both are cut into [ReferenceCircles.ARCS] runs rather than drawn as one loop**, and that is
 * the difference between the cap test working and not working at all. [SkyLines] rejects a run with
 * a single dot product against its bounding cap, and the smallest cap containing a great circle is
 * the whole sphere — so a 360-degree run can never be rejected, and at a quarter-degree field the
 * map would project several hundred points a frame to keep two of them. At twelve runs each cap is
 * thirty degrees across, which [SkyLines.endLine] documents as the span its centre-of-mass cap is
 * near-optimal for.
 */
object ReferenceLines {

    /**
     * Fill [into] with one circle.
     *
     * @param obliquityDeg pass null for the celestial equator; pass the true obliquity of the date
     *   for the ecliptic. ⚠️ A parameter rather than a constant because it drifts about 0.013 degrees
     *   a century — invisible now and a quarter of a degree by the year 4000, which is inside what a
     *   chart with a time control can be asked. `Ephemeris.trueObliquityDeg` is what to pass.
     */
    fun fill(into: SkyLines, obliquityDeg: Double?) {
        into.clear()
        val v = DoubleArray(3)
        // ⚠️ Two integer loops, so the vertex count is exactly ARCS * PER_ARC — which is what the
        // caller preallocated. The traversal itself lives in ReferenceCircles.longitudeOf, in the
        // module CI actually runs tests for; `:core:sky` has no test source set, so a rule left here
        // would have no gate at all.
        for (arc in 0 until ReferenceCircles.ARCS) {
            into.beginLine()
            for (index in 0 until ReferenceCircles.PER_ARC) {
                val angle = ReferenceCircles.longitudeOf(arc, index)
                if (obliquityDeg == null) {
                    ReferenceCircles.equatorPoint(angle, v)
                } else {
                    ReferenceCircles.eclipticPoint(angle, obliquityDeg, v)
                }
                into.add(v[0], v[1], v[2])
            }
            into.endLine()
        }
    }
}
