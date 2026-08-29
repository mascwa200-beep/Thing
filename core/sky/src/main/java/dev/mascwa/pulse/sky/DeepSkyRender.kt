package dev.mascwa.pulse.sky

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.core.telemetry.DeepSky
import dev.mascwa.pulse.core.telemetry.SkyProjection
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * What colour each kind of deep-sky object is drawn in.
 *
 * ⚠️ **The mapping lives here and the colours come from the caller**, which is the same split the
 * star renderer uses for its unmeasured-colour parameter. Which kinds are worth telling apart is a
 * property of the renderer; what a galaxy looks like on this app's palette is a property of the app,
 * and the standalone sky app will answer that differently.
 */
class DeepSkyColors(
    val galaxy: Color,
    val cluster: Color,
    val nebula: Color,
    val planetary: Color,
    val remnant: Color,
    val dark: Color,
    val other: Color,
) {
    fun of(kind: DeepSky.Kind): Color = when (kind) {
        DeepSky.Kind.GALAXY -> galaxy
        DeepSky.Kind.GLOBULAR, DeepSky.Kind.OPEN_CLUSTER -> cluster
        DeepSky.Kind.CLUSTER_NEBULA, DeepSky.Kind.NEBULA -> nebula
        DeepSky.Kind.PLANETARY -> planetary
        DeepSky.Kind.SUPERNOVA_REMNANT -> remnant
        DeepSky.Kind.DARK_NEBULA -> dark
        DeepSky.Kind.OTHER -> other
    }
}

/**
 * Draw the deep sky.
 *
 * Runs UNDER the stars and OVER the constellation lines, so a cluster's own member stars sit on top
 * of its glow and a border does not cut across a galaxy.
 *
 * ## ⚠️ Most of this catalogue can only ever be a marker
 *
 * The median object is **1.20 arcminutes** across, which at a sixty-degree field on a 1080-pixel
 * screen is well under one pixel. Only the few hundred that are genuinely large get a shape, and only
 * once the field is narrow enough for them to subtend real pixels — [DeepSky.drawsShape]. Everything
 * else is a small ring, which is the traditional chart marker and, being hollow, cannot be mistaken
 * for a star.
 *
 * ## What one frame costs
 *
 * The cull is one comparison per object over twelve and a half thousand primitives, then a projection
 * for whatever passes. Measured over the real catalogue, between three and eighty-five objects reach
 * the screen at any field, so the per-object drawing below is paid a few dozen times — which is why
 * this is not batched the way the stars are, where the count runs into thousands.
 *
 * @param onLabel called for each drawn object worth naming, with its screen position. ⚠️ A callback
 *   rather than a returned list: this runs every frame, and the text is drawn by the caller because
 *   the typeface and the paint belong to the app, exactly as the star labels do.
 * @return how many objects were drawn.
 */
fun DrawScope.drawDeepSky(
    layer: DeepSkyLayer,
    frame: SkyFrame,
    viewport: SkyProjection.Viewport,
    limit: Double,
    fovDeg: Double,
    halfPx: Float,
    centreX: Float,
    centreY: Float,
    colours: DeepSkyColors,
    onLabel: (Float, Float, String) -> Unit,
): Int {
    val basis = frame.basis
    val markerPx = MARKER_RADIUS_DP.dp.toPx()
    val markerStroke = MARKER_STROKE_DP.dp.toPx()
    var drawn = 0
    for (i in 0 until layer.count) {
        if (!layer.visible(i, limit, fovDeg)) continue
        val p = SkyProjection.projectUnit(layer.vx[i], layer.vy[i], layer.vz[i], basis)
        if (!p.onScreen(viewport, SkyRenderer.EDGE_MARGIN)) continue

        val e = layer.entries[i]
        val x = centreX + (p.x * halfPx).toFloat()
        val y = centreY + (p.y * halfPx).toFloat()
        val colour = colours.of(e.kind)
        val dim = if (frame.sinAltitude(layer.vx[i], layer.vy[i], layer.vz[i]) >= 0.0) {
            1f
        } else {
            SkyRenderer.BELOW_HORIZON_ALPHA
        }

        val major = e.majorAxisArcmin
        val shape = if (major == null) {
            null
        } else {
            DeepSky.shapeOf(
                e.rightAscensionDeg, e.declinationDeg,
                major, e.minorAxisArcmin, e.positionAngleDeg, basis,
            )
        }
        if (shape != null && DeepSky.drawsShape(shape.semiMajorUnits, halfPx.toDouble())) {
            val alpha = DeepSky.opacity(DeepSky.surfaceBrightness(e)).toFloat() * dim
            drawShape(e, shape, colour, alpha, halfPx, x, y)
        } else {
            drawCircle(
                color = colour.copy(alpha = MARKER_ALPHA * dim),
                radius = markerPx,
                center = Offset(x, y),
                style = Stroke(width = markerStroke),
            )
        }
        if (DeepSky.labels(e, limit)) onLabel(x, y, e.label)
        drawn++
    }
    return drawn
}

/**
 * One object at its real size and orientation.
 *
 * ⚠️ **Every random-looking element is seeded from the object's own identifier**, never from a frame
 * counter or a random source. A nebula that reshuffles its speckle each frame reads as noise rather
 * than as a nebula, and `Math.random()` in a draw pass makes a render untestable. `String.hashCode`
 * is specified by the language, so the same object looks the same on every device and every launch.
 */
private fun DrawScope.drawShape(
    entry: DeepSky.Entry,
    shape: DeepSky.Shape,
    colour: Color,
    alpha: Float,
    halfPx: Float,
    x: Float,
    y: Float,
) {
    val a = (shape.semiMajorUnits * halfPx).toFloat()
    val b = (shape.semiMinorUnits * halfPx).toFloat()
    val grain = GRAIN_RADIUS_DP.dp.toPx()
    // xorshift over the identifier: the object's own sequence, and it costs three shifts a value.
    var seed = (entry.id.hashCode().toLong() and 0xFFFFFFFFL) or 1L
    fun next(): Float {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 7)
        seed = seed xor (seed shl 17)
        return (seed and 0xFFFFFFL).toFloat() / 0xFFFFFF.toFloat()
    }

    rotateRad(shape.angleRad.toFloat(), pivot = Offset(x, y)) {
        // The long axis now lies along +x, so squashing y by the axis ratio makes every circle drawn
        // below an ellipse of the right shape and orientation — and, unlike drawOval, it stretches
        // the radial gradients with it, which is what makes an edge-on galaxy read as one.
        val k = if (a > 0f) (b / a).coerceIn(MIN_AXIS_RATIO, 1f) else 1f
        scale(1f, k, pivot = Offset(x, y)) {
            when (entry.kind) {
                DeepSky.Kind.GALAXY -> {
                    glow(x, y, a, colour, alpha, mid = 0.45f, midAlpha = 0.45f)
                    drawCircle(colour.copy(alpha = alpha * 0.8f), min(a, b) * 0.18f, Offset(x, y))
                }

                DeepSky.Kind.GLOBULAR -> {
                    glow(x, y, a, colour, alpha * 0.9f, mid = 0.6f, midAlpha = 0.33f)
                    // Grains concentrated toward the middle: a globular resolves into stars at its
                    // edge long before it does at its core, and that is how one is told apart from
                    // a galaxy of the same size.
                    repeat(GRAINS) {
                        val r = next() * next() // squared, so density rises toward the centre
                        val t = next() * TAU
                        drawCircle(
                            colour.copy(alpha = alpha),
                            grain,
                            Offset(x + a * r * cos(t), y + a * r * sin(t)),
                        )
                    }
                }

                DeepSky.Kind.OPEN_CLUSTER, DeepSky.Kind.CLUSTER_NEBULA -> {
                    if (entry.kind == DeepSky.Kind.CLUSTER_NEBULA) {
                        glow(x, y, a, colour, alpha * 0.5f, mid = 0.5f, midAlpha = 0.25f)
                    }
                    // Loose members, spread evenly rather than piled at the middle.
                    repeat(MEMBERS) {
                        val r = next()
                        val t = next() * TAU
                        drawCircle(
                            colour.copy(alpha = alpha),
                            grain,
                            Offset(x + a * r * cos(t), y + a * r * sin(t)),
                        )
                    }
                }

                DeepSky.Kind.PLANETARY -> {
                    // A ring: a shell seen from outside is brightest at its rim, which is what
                    // distinguishes a planetary nebula at the eyepiece from anything else this size.
                    drawCircle(
                        color = colour.copy(alpha = alpha),
                        radius = a * 0.78f,
                        center = Offset(x, y),
                        style = Stroke(width = a * 0.32f),
                    )
                }

                DeepSky.Kind.NEBULA, DeepSky.Kind.SUPERNOVA_REMNANT -> {
                    // Overlapping lobes rather than one disc: real nebulae are not round, and a
                    // perfectly circular glow is the tell that a renderer has given up.
                    repeat(LOBES) {
                        val r = next() * 0.45f
                        val t = next() * TAU
                        val lobe = a * (0.5f + next() * 0.45f)
                        glow(
                            x + a * r * cos(t), y + a * r * sin(t),
                            lobe, colour, alpha * 0.55f, mid = 0.5f, midAlpha = 0.25f,
                        )
                    }
                }

                DeepSky.Kind.DARK_NEBULA -> {
                    // ⚠️ An outline and nothing inside it. A dark nebula is dust seen in ABSORPTION
                    // — the place where there is LESS light — so filling one with a glow would be a
                    // straightforward lie about what is there.
                    drawCircle(
                        color = colour.copy(alpha = alpha * 0.7f),
                        radius = a,
                        center = Offset(x, y),
                        style = Stroke(
                            width = DARK_STROKE_DP.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(a * 0.18f, a * 0.12f),
                            ),
                        ),
                    )
                }

                DeepSky.Kind.OTHER -> {
                    drawCircle(
                        color = colour.copy(alpha = alpha),
                        radius = a,
                        center = Offset(x, y),
                        style = Stroke(width = MARKER_STROKE_DP.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** A soft round falloff — the one shape every extended object here is built from. */
private fun DrawScope.glow(
    x: Float,
    y: Float,
    radius: Float,
    colour: Color,
    alpha: Float,
    mid: Float,
    midAlpha: Float,
) {
    if (radius <= 0f) return
    val at = Offset(x, y)
    drawCircle(
        brush = Brush.radialGradient(
            0f to colour.copy(alpha = alpha),
            mid to colour.copy(alpha = alpha * midAlpha),
            1f to Color.Transparent,
            center = at,
            radius = radius,
        ),
        radius = radius,
        center = at,
    )
}

private const val TAU = (2.0 * Math.PI).toFloat()

/**
 * How flat an object may be drawn.
 *
 * The catalogue holds axis ratios down to about a fiftieth, and at that the squash is so severe the
 * gradient collapses to a line with no visible falloff. A fiftieth is still visibly edge-on.
 */
private const val MIN_AXIS_RATIO = 0.02f

/** The hollow chart marker for an object too small on this screen to draw as a shape. */
private const val MARKER_RADIUS_DP = 2.6f
private const val MARKER_STROKE_DP = 1.1f
private const val MARKER_ALPHA = 0.55f

/** Grains in a globular, members in an open cluster, lobes in a nebula. */
private const val GRAINS = 26
private const val MEMBERS = 14
private const val LOBES = 4
private const val GRAIN_RADIUS_DP = 0.7f
private const val DARK_STROKE_DP = 1.2f
