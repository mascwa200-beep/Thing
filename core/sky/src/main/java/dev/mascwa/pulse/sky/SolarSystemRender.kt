package dev.mascwa.pulse.sky

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import dev.mascwa.pulse.core.telemetry.PlanetDisc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The Sun, the Moon and the planets, at the size they really are.
 *
 * ## Why this exists
 *
 * The map drew the Sun as a nine-pixel circle, the Moon as eight and every planet as five. That was
 * defensible while the field of view had a four-degree floor: at four degrees the Sun's half a
 * degree is under an eighth of the narrow axis, and a marker is all it could ever be. S1 dropped the
 * floor to a quarter of a degree, where the solar disc spans **twice the screen**, and a fixed nine
 * pixels became the one thing standing between the map and actually looking at the Sun.
 *
 * ## ⚠️ A marker is still right most of the time, and the switch is measured
 *
 * At a sixty-degree field on a 1080-pixel screen there are eighteen pixels to the degree, so
 * Jupiter — the largest planetary disc there is, at about fifty arcseconds — is **a quarter of a
 * pixel**. Drawing that as a disc is drawing nothing. So the real disc takes over only once it is
 * larger than the marker it replaces, and below that the marker stands: which is what every chart
 * has always done, and what keeps a planet findable at a wide field.
 *
 * ## ⚠️ Nothing here assumes which way round the projection puts the sky
 *
 * Every direction arrives as a second sky point that this function projects for itself, and every
 * orientation is then a screen-space difference. There is no parallactic angle, no east-is-left
 * convention and no correction that would silently be wrong for a projection this module does not
 * yet have. [PlanetDisc.Appearance] explains the reasoning at length.
 *
 * ## What one frame costs
 *
 * Eight bodies, three extra projections each. Even with every one drawn at full detail that is a few
 * dozen draw calls against the thousands the star pass makes, so nothing here is batched and nothing
 * needs to be.
 */
fun DrawScope.drawSolarSystemBody(
    look: PlanetDisc.Appearance?,
    /** Where the body's centre landed, already projected. */
    centre: Offset,
    /** The marker radius to fall back to, in pixels — the old fixed size. */
    markerRadiusPx: Float,
    /** How many pixels a degree of sky covers here — see [measurePixelsPerDegree]. */
    pixelsPerDegree: Float,
    colour: Color,
    /** The map's background, for the unlit part of a phased disc. */
    shadow: Color,
    /** Faded when the body is below the horizon. */
    alpha: Float,
    /** Projects a sky point the same way the caller projected the body. Null when it did not land. */
    project: (PlanetDisc.SkyPoint) -> Offset?,
) {
    val radius = if (look == null) 0f else (look.diameterDeg / 2.0).toFloat() * pixelsPerDegree
    if (look == null || !radius.isFinite() || radius <= markerRadiusPx) {
        drawMarker(centre, markerRadiusPx, colour, shadow, alpha)
        return
    }

    // ⚠️ Screen directions taken by DIFFERENCE rather than by rotating a sky angle. The projection is
    // not conformal and the field turns as you move across it, so an angle measured on the sky is
    // the angle on the screen only at the exact centre.
    val limbDir = look.limb?.let { direction(centre, project(it)) }
    val equatorDir = look.equator?.let { direction(centre, project(it)) }
    val poleDir = look.pole?.let { direction(centre, project(it)) }

    val spin = equatorDir?.let { degreesOf(it) }
    // Which side of the equator the planet's north ends up on, once the equator is laid along +x.
    // A cross product, so it needs no assumption about the projection's handedness — it measures it.
    val northSign = if (equatorDir == null || poleDir == null) {
        0f
    } else {
        val cross = equatorDir.x * poleDir.y - equatorDir.y * poleDir.x
        if (cross >= 0f) 1f else -1f
    }
    val oriented = spin != null && northSign != 0f

    if (oriented && look.rings != null) {
        drawRings(centre, radius, look.rings!!, spin!!, northSign, colour, alpha, behind = true)
    }

    drawDisc(look, centre, radius, spin, limbDir, colour, shadow, alpha)

    if (oriented && look.rings != null) {
        drawRings(centre, radius, look.rings!!, spin!!, northSign, colour, alpha, behind = false)
    }
    if (oriented && look.moons.isNotEmpty()) {
        drawMoons(look.moons, centre, radius, spin!!, northSign, colour, alpha)
    }
}

/** The old fixed marker: a filled dot with a hollow centre, so it is not mistaken for a star. */
private fun DrawScope.drawMarker(
    centre: Offset,
    radius: Float,
    colour: Color,
    shadow: Color,
    alpha: Float,
) {
    drawCircle(colour.copy(alpha = colour.alpha * alpha), radius, centre)
    drawCircle(shadow.copy(alpha = alpha), radius * 0.35f, centre, style = Stroke(width = 1f))
}

/** The body itself: a disc, an oval if it is flattened, and a phase if it shows one. */
private fun DrawScope.drawDisc(
    look: PlanetDisc.Appearance,
    centre: Offset,
    radius: Float,
    spinDeg: Float?,
    limbDir: Offset?,
    colour: Color,
    shadow: Color,
    alpha: Float,
) {
    val lit = colour.copy(alpha = colour.alpha * alpha)

    if (!look.phased || limbDir == null) {
        // ⚠️ Flattening is along the pole, so it means nothing without an orientation. Drawn round
        // when there is none — which is what everything but the two giants looks like anyway.
        val polar = if (spinDeg == null) radius else radius * (1.0 - look.flattening).toFloat()
        rotate(spinDeg ?: 0f, centre) {
            val topLeft = Offset(centre.x - radius, centre.y - polar)
            val size = Size(radius * 2f, polar * 2f)
            if (look.limbDarkened) {
                // ⚠️ A radial gradient, not a flat fill, and it is the difference between a drawn Sun
                // and a disc of paint. The one-parameter law with u about 0.6 puts the very edge at
                // roughly forty per cent of the centre's brightness — a plainly visible effect, and
                // the thing that makes the Sun read as a sphere rather than a circle.
                drawOval(
                    brush = Brush.radialGradient(
                        0f to lit,
                        0.7f to lit.copy(alpha = lit.alpha * MID_DISC_FALLOFF),
                        1f to lit.copy(alpha = lit.alpha * PlanetDisc.limbDarkening(1.0).toFloat()),
                        center = centre,
                        radius = max(radius, 1f),
                    ),
                    topLeft = topLeft,
                    size = size,
                )
            } else {
                drawOval(color = lit, topLeft = topLeft, size = size)
            }
        }
        return
    }

    // ⚠️ THE LIT PART IS A HALF-CIRCLE JOINED TO A HALF-ELLIPSE, never two overlapping circles. A
    // circular bite is the classic wrong crescent: wrong at every phase except exactly half, and
    // near full it produces a shape no telescope has ever shown. The correct boundary is the
    // projection of the great circle dividing day from night, whose semi-minor axis toward the Sun
    // is r·cos(i) — and the SIGN of that is what makes a gibbous disc bulge away from the lit limb
    // rather than toward it.
    //
    // Built as a polygon rather than two arcs, because the arithmetic is then plainly readable: walk
    // the bright limb, then walk the terminator back. Sixty-four steps is smooth past any zoom this
    // map allows, and the whole thing is one path.
    //
    // ⚠️ Drawn ROUND rather than flattened. Only Mercury, Venus and Mars ever show a phase worth
    // drawing, and the flattest of those is Mars at 0.006 — under a pixel on any disc smaller than a
    // hundred and seventy across. Applying the flattening here would mean rotating the phase to the
    // limb and the oblateness to the pole at the same time, for an effect nobody can see.
    val k = look.terminator.toFloat()
    val path = Path()
    for (i in 0..TERMINATOR_STEPS) {
        val t = (-90.0 + 180.0 * i / TERMINATOR_STEPS) * DEG
        val x = radius * cos(t).toFloat()
        val y = radius * sin(t).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (i in TERMINATOR_STEPS downTo 0) {
        val t = (-90.0 + 180.0 * i / TERMINATOR_STEPS) * DEG
        // ⚠️ Negated. At full phase (k = 1) this traces the FAR half of the limb, closing the whole
        // disc; without the sign it retraces the near half and the lit area collapses to nothing,
        // which looks like a new moon at every phase.
        path.lineTo(-k * radius * cos(t).toFloat(), radius * sin(t).toFloat())
    }
    path.close()

    // The unlit part first, so the terminator comes out as a clean edge rather than a seam.
    drawCircle(shadow.copy(alpha = alpha * UNLIT_ALPHA), radius, centre)
    drawCircle(
        colour.copy(alpha = colour.alpha * alpha * UNLIT_EDGE_ALPHA),
        radius,
        centre,
        style = Stroke(width = 1f),
    )
    // The path is built with the bright limb toward +x, so it turns to wherever the Sun actually is.
    rotate(degreesOf(limbDir), centre) {
        translate(centre.x, centre.y) {
            drawPath(path, lit)
        }
    }
}

/**
 * Saturn's rings, as the inclination-projected ellipse they are.
 *
 * Drawn in two passes with the planet between them, because the far half of the ring passes behind
 * the globe and the near half in front — the single detail that makes a drawn Saturn look like
 * Saturn rather than like a planet with a line through it.
 *
 * ⚠️ **Which half is which is derived, not assumed.** Seeing the northern face means we are above
 * the ring plane, and from above the FAR half of the ring projects toward the north pole — the same
 * geometry that decides whether a Galilean moon is in front of Jupiter or behind it. So the far half
 * lies on the pole side when the opening angle is positive and on the other side when it is
 * negative, and [northSign] says which of those is which on this particular screen.
 */
private fun DrawScope.drawRings(
    centre: Offset,
    radius: Float,
    rings: PlanetDisc.Rings,
    spinDeg: Float,
    northSign: Float,
    colour: Color,
    alpha: Float,
    behind: Boolean,
) {
    val squash = rings.squash.toFloat()
    val ink = colour.copy(alpha = colour.alpha * alpha * RING_ALPHA)
    // ⚠️ Exactly edge-on the rings vanish, and that is real rather than a limitation: the system is
    // a few tens of metres thick against two hundred thousand kilometres across, so at a crossing it
    // genuinely disappears from any Earth-bound telescope. A hairline is drawn instead of nothing so
    // the planet does not silently lose a feature it has the rest of the time.
    val thin = squash < EDGE_ON

    // Compose measures an arc clockwise from +x, and +y is DOWN, so angles 0..180 sweep the lower
    // half of the screen. "North is at positive y" is exactly `northSign > 0` after the rotation.
    val northAtPositiveY = northSign > 0f
    val farHalfAtPositiveY = northAtPositiveY == (rings.openingDeg > 0.0)
    val wantPositiveY = if (behind) farHalfAtPositiveY else !farHalfAtPositiveY

    rotate(spinDeg, centre) {
        for (edge in RING_EDGES) {
            val rx = radius * edge
            val ry = if (thin) 0f else rx * squash
            drawArc(
                color = ink,
                startAngle = if (wantPositiveY) 0f else 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(centre.x - rx, centre.y - ry),
                size = Size(rx * 2f, max(ry * 2f, 1f)),
                style = Stroke(width = if (thin) 1f else max(1f, rx * RING_STROKE)),
            )
        }
    }
}

/** The four moons Galileo saw, strung along Jupiter's equator. */
private fun DrawScope.drawMoons(
    moons: List<PlanetDisc.Moonlet>,
    centre: Offset,
    radius: Float,
    spinDeg: Float,
    northSign: Float,
    colour: Color,
    alpha: Float,
) {
    val ink = colour.copy(alpha = colour.alpha * alpha)
    val dot = max(MIN_MOON_PX, radius * MOON_RADIUS_SHARE)
    rotate(spinDeg, centre) {
        for (m in moons) {
            // ⚠️ A moon behind the planet is not drawn, and only when it is actually covered. The
            // flag says which half of the orbit it is on; whether that hides it depends on how far
            // out it is at the time, and a moon at greatest elongation is beside Jupiter rather than
            // behind it. Drawing a hidden one would put a dot on the globe nobody at an eyepiece
            // could see; hiding a visible one would lose a moon for half its orbit.
            if (m.behind && hypot(m.x, m.y) < 1.0) continue
            drawCircle(
                ink,
                dot,
                Offset(
                    centre.x + (m.x * radius).toFloat(),
                    centre.y + (m.y * radius).toFloat() * northSign,
                ),
            )
        }
    }
}

/** The screen-space direction from [centre] to a projected sky point, or null if it did not land. */
private fun direction(centre: Offset, to: Offset?): Offset? {
    if (to == null) return null
    val d = to - centre
    // A degenerate step says nothing about a direction, and normalising it would produce noise.
    return if (hypot(d.x, d.y) < MIN_DIRECTION_PX) null else d
}

/** A screen vector as a rotation in degrees, measured the way `rotate` wants it. */
private fun degreesOf(v: Offset): Float = (atan2(v.y.toDouble(), v.x.toDouble()) / DEG).toFloat()

/**
 * How many pixels one degree of sky covers here, measured rather than derived.
 *
 * ⚠️ The projection is not linear — a degree near the edge of a wide field covers fewer pixels than
 * one at the centre — so `halfPx / (fovDeg / 2)` is right only at the middle of the screen and
 * increasingly wrong toward the corners, which is exactly where a low body sits. Projecting a point
 * one degree from the body and measuring costs one projection and is right everywhere.
 *
 * @param oneDegreeAway the projection of [stepAlong] with the default step. Null gives zero, which
 *   the caller must read as "fall back to the marker" rather than as a body of no size.
 */
fun measurePixelsPerDegree(centre: Offset, oneDegreeAway: Offset?): Float {
    if (oneDegreeAway == null) return 0f
    val d = oneDegreeAway - centre
    val px = hypot(d.x, d.y)
    return if (px.isFinite()) px else 0f
}

/**
 * A point one degree from a horizon position, along a position angle east of north.
 *
 * ⚠️ Lives here rather than in the pure core because it is what a caller needs to BUILD the sky
 * points [PlanetDisc.Appearance] asks for, and because doing the step in horizon coordinates is what
 * lets the renderer stay ignorant of equatorial ones. The step is small enough that treating the sky
 * as flat across it costs nothing, and large enough that the projected difference is not noise.
 */
fun stepAlong(
    azimuthDeg: Double,
    altitudeDeg: Double,
    positionAngleDeg: Double,
    stepDeg: Double = 1.0,
): PlanetDisc.SkyPoint {
    val pa = positionAngleDeg * DEG
    val dAlt = stepDeg * cos(pa)
    // ⚠️ Divided by the cosine of the altitude: a degree of azimuth is a degree of sky only at the
    // horizon and shrinks to nothing at the zenith. Without this the direction is wrong for anything
    // high up, which is where the Moon spends half of its time.
    val cosAlt = cos(altitudeDeg * DEG)
    val dAz = if (abs(cosAlt) < MIN_COS_ALT) 0.0 else stepDeg * sin(pa) / cosAlt
    return PlanetDisc.SkyPoint(azimuthDeg + dAz, (altitudeDeg + dAlt).coerceIn(-90.0, 90.0))
}

private const val DEG = Math.PI / 180.0

/** Smooth past any zoom this map allows; the whole terminator is one path either way. */
private const val TERMINATOR_STEPS = 64

/** How dark the night side is. Not black: earthshine is real, and a black bite reads as a hole. */
private const val UNLIT_ALPHA = 0.55f

/** The unlit limb still gets a hairline, so the body's true size stays readable. */
private const val UNLIT_EDGE_ALPHA = 0.35f

/** The gradient's middle stop, so the falloff is not a straight ramp from centre to edge. */
private const val MID_DISC_FALLOFF = 0.92f

/** Ring edges in planetary radii: the C ring's inner edge, the Cassini gap, the A ring's outer. */
private val RING_EDGES = floatArrayOf(
    PlanetDisc.RING_INNER.toFloat(),
    PlanetDisc.CASSINI_INNER.toFloat(),
    PlanetDisc.CASSINI_OUTER.toFloat(),
    PlanetDisc.RING_OUTER.toFloat(),
)

private const val RING_ALPHA = 0.75f
private const val RING_STROKE = 0.03f
private const val EDGE_ON = 0.02f
private const val MOON_RADIUS_SHARE = 0.09f
private const val MIN_MOON_PX = 1.5f
private const val MIN_DIRECTION_PX = 0.05f
private const val MIN_COS_ALT = 1e-4
