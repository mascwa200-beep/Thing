package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.SkyProjection
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Where the ground is on the screen — the region below the horizon, as one exact shape.
 *
 * ## ⚠️ Why this is a circle and not a mesh
 *
 * The projection is stereographic ([SkyProjection.projectUnit]: `k = 2/(1+z)` from the antipode
 * of the view centre), under which **every circle on the sphere is a circle on the plane** — and
 * the horizon is a circle on the sphere. So the whole of the ground is one side of one circle,
 * found from three projected horizon points, and a fill needs no sampling, no triangle mesh with
 * seams between its pieces, and no polygon that has to be closed through screen corners it cannot
 * see. The obvious mesh approach was tried in thought and rejected on exactly those seams: two
 * anti-aliased polygons that share an edge leave a hairline of sky between them, and a ground that
 * shows a grid of cracks is worse than no ground.
 *
 * ⚠️ **Which side is the ground is decided by projecting the nadir, never by assuming "below the
 * curve on screen".** Looking down, the horizon surrounds the nadir and the ground is the INSIDE of
 * the circle; looking up, it surrounds the zenith and the ground is the OUTSIDE. A rule of thumb
 * about screen-y would be right in one of those cases and wrong in the other. When the nadir is too
 * near the antipode to project — the view within about two and a half degrees of straight up — the
 * zenith is asked instead, and the answer inverted.
 *
 * ⚠️ **The circle degenerates when the view centre lies ON the horizon**, where a great circle
 * through the centre projects to a straight line and the circumcircle's radius runs away. That case
 * is real (a phone held level) and it is answered as a [Shape.HalfPlane] rather than as a circle of
 * radius ten million, which a float-precision canvas would draw as a wobble. The switch is at
 * [MAX_DISC_UNITS]: a circle that big deviates from its chord across the whole screen by under a
 * fifth of a pixel, so nothing is lost by drawing the line.
 *
 * Pure: takes the horizon basis and the forward vector as [collectHorizonLines] does, answers in
 * screen units (the same units [SkyProjection.Screen] uses, one unit = the half-screen), and is held
 * by a test that classifies hundreds of random directions against their true altitude.
 */
object GroundShape {

    sealed class Shape {
        /**
         * The projected horizon is this circle; the ground is its inside when [groundInside], its
         * outside otherwise. Screen units, centred on the view.
         */
        class Disc(val cx: Double, val cy: Double, val radius: Double, val groundInside: Boolean) : Shape()

        /**
         * The projected horizon is (to within a pixel) the straight line through ([px], [py]) along
         * ([tx], [ty]); the ground is on the side ([dx], [dy]) points to. Unit directions, screen units.
         */
        class HalfPlane(
            val px: Double, val py: Double,
            val tx: Double, val ty: Double,
            val dx: Double, val dy: Double,
        ) : Shape()

        /** No orientation to draw in — an unusable basis. Draw nothing. */
        object None : Shape()
    }

    /**
     * Above this radius the horizon is drawn as a line. In screen units; at 2,000 the sagitta over
     * a two-unit chord (the whole width of the screen) is 4/(8·2000) = 0.00025 units, a sixth of a
     * pixel on a phone. Below it a circle draws exactly and float precision is not in question.
     */
    const val MAX_DISC_UNITS = 2000.0

    /**
     * The ground for a view looking along `(fx, fy, fz)` in the horizon frame, drawn through [basis].
     *
     * The three probe points are the horizon at the view's azimuth and ninety degrees either side of
     * it — never more than ninety degrees plus the altitude from the centre, so none can be near the
     * antipode where the projection blows up, whatever the view.
     */
    fun of(basis: SkyProjection.Basis, fx: Double, fy: Double, fz: Double): Shape {
        // Azimuth clockwise from north, with x east and y north — SkyProjection.unitVector's frame.
        val az0 = Math.toDegrees(atan2(fx, fy))
        val a = horizonPoint(az0 - 90.0, basis) ?: return Shape.None
        val b = horizonPoint(az0, basis) ?: return Shape.None
        val c = horizonPoint(az0 + 90.0, basis) ?: return Shape.None

        // Circumcircle of the three, or a line when they are (nearly) collinear.
        val d = 2.0 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
        if (abs(d) > COLLINEAR_EPS) {
            val a2 = a.x * a.x + a.y * a.y
            val b2 = b.x * b.x + b.y * b.y
            val c2 = c.x * c.x + c.y * c.y
            val ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d
            val uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
            val r = sqrt((a.x - ux) * (a.x - ux) + (a.y - uy) * (a.y - uy))
            if (r <= MAX_DISC_UNITS) {
                return Shape.Disc(ux, uy, r, groundInside = groundInside(basis, fz, ux, uy, r))
            }
        }
        // The line through the near point along the chord, with the ground on the side a point
        // just below the horizon lands. Both ends of the chord are well inside the sphere's
        // projection, so the direction is well conditioned even when the circle's centre is not.
        var tx = c.x - a.x
        var ty = c.y - a.y
        val tn = sqrt(tx * tx + ty * ty)
        if (tn < 1e-12) return Shape.None
        tx /= tn; ty /= tn
        val below = SkyProjection.unitVector(az0, -BELOW_PROBE_DEG)
        val q = SkyProjection.projectUnit(below[0], below[1], below[2], basis)
        // Its component across the line is the ground side; along the line it says nothing.
        var dx = q.x - b.x
        var dy = q.y - b.y
        val along = dx * tx + dy * ty
        dx -= along * tx
        dy -= along * ty
        val dn = sqrt(dx * dx + dy * dy)
        if (dn < 1e-12) return Shape.None
        return Shape.HalfPlane(b.x, b.y, tx, ty, dx / dn, dy / dn)
    }

    /** Is a screen point (in units) on the ground, for this shape? The test's whole question. */
    fun isGround(shape: Shape, x: Double, y: Double): Boolean = when (shape) {
        is Shape.Disc -> {
            val inside = (x - shape.cx) * (x - shape.cx) + (y - shape.cy) * (y - shape.cy) <
                shape.radius * shape.radius
            inside == shape.groundInside
        }
        is Shape.HalfPlane -> (x - shape.px) * shape.dx + (y - shape.py) * shape.dy > 0.0
        Shape.None -> false
    }

    private fun horizonPoint(azDeg: Double, basis: SkyProjection.Basis): SkyProjection.Screen? {
        val v = SkyProjection.unitVector(azDeg, 0.0)
        val p = SkyProjection.projectUnit(v[0], v[1], v[2], basis)
        return if (p.visible) p else null
    }

    /**
     * Which side of the circle the ground is. The nadir when it projects, else the zenith with the
     * answer inverted — see the class note. `fz` is the forward vector's vertical component, which
     * is the nadir's projection depth with the sign flipped.
     */
    private fun groundInside(basis: SkyProjection.Basis, fz: Double, ux: Double, uy: Double, r: Double): Boolean {
        val nadir = SkyProjection.projectUnit(0.0, 0.0, -1.0, basis)
        if (nadir.visible) {
            val dn = (nadir.x - ux) * (nadir.x - ux) + (nadir.y - uy) * (nadir.y - uy)
            return dn < r * r
        }
        val zenith = SkyProjection.projectUnit(0.0, 0.0, 1.0, basis)
        val dz = (zenith.x - ux) * (zenith.x - ux) + (zenith.y - uy) * (zenith.y - uy)
        // The zenith is inside the circle exactly when the ground is outside it.
        return dz > r * r
    }

    /** Below this the three probe points are treated as collinear. Screen units squared, roughly. */
    private const val COLLINEAR_EPS = 1e-12

    /** How far under the horizon the side-of-line probe looks. A degree: close, never ambiguous. */
    private const val BELOW_PROBE_DEG = 1.0
}
