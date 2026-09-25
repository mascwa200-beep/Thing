package dev.mascwa.pulse.sky

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.StarGlyph
import dev.mascwa.pulse.core.telemetry.StarNames

/**
 * Drawing a sky.
 *
 * ⚠️ **A DrawScope extension rather than a composable, deliberately.** There is nothing to lay out
 * and nothing to remember: this is one pass over some arrays. Making it a composable would put a
 * layout node between the caller's Canvas and the arithmetic for no gain, and would drag the Compose
 * compiler plugin into a module that otherwise needs only the graphics types.
 *
 * ## What one frame costs
 *
 * Per star: a dot product to find whether it is above the horizon ([SkyFrame.sinAltitude]), the
 * air's extinction when the atmosphere is on — one table lookup on that same sine, see
 * [SkyFrame.extinctionMag] — the refraction bend, likewise — an `asin`, a table lookup and about
 * fifteen multiplications, see [SkyFrame.project] — nine multiplications and a divide to project
 * ([SkyProjection.projectUnit]), two comparisons, and two writes into a bucket. Then a few dozen
 * `drawPoints` calls for the whole sky, whatever its size.
 *
 * ⚠️ **Every catalogue-frame projection here goes through [SkyFrame.project], never
 * [SkyProjection.projectUnit] on the frame's basis.** That is where refraction lives, and a layer
 * that skipped it would sit half a degree from the stars at the horizon.
 *
 * ⚠️ **The drawn count is bounded and that was measured, not hoped for.** Sweeping the real
 * catalogue across the whole zoom range at four latitudes, the number of stars that land on screen
 * runs from 12 at the narrowest field to 8,980 at the busiest — because
 * [SkyProjection.magnitudeLimit] deepens exactly as fast as the field narrows. A renderer sized for
 * nine thousand points is sized for a catalogue of any depth.
 */
object SkyRenderer {

    /**
     * How far past the edge a star is still worth drawing, as a fraction of the half-field.
     *
     * A star is a disc, so it is partly on screen while its centre is not; clipping hard at the edge
     * makes them blink in and out during a drag.
     */
    const val EDGE_MARGIN = 0.06

    /** Below the horizon is drawn, not dropped — but dimmed, so it reads as "not up right now". */
    const val BELOW_HORIZON_ALPHA = 0.28f
}

/**
 * Project a layer of stars into the batches, dropping whatever the view cannot see.
 *
 * ⚠️ **Above and below the horizon go into SEPARATE batches**, because a bucket is drawn in one call
 * with one paint and the two need different alpha. Splitting them here rather than drawing twice is
 * what keeps it one pass over the arrays.
 *
 * @param limit the magnitude cut for this zoom — [SkyProjection.magnitudeLimit].
 * @return how many stars of this layer landed on screen.
 */
fun collectStars(
    layer: StarLayer,
    frame: SkyFrame,
    viewport: SkyProjection.Viewport,
    limit: Double,
    halfPx: Float,
    centreX: Float,
    centreY: Float,
    above: StarBatches,
    below: StarBatches,
): Int {
    var drawn = 0
    for (i in 0 until layer.count) {
        val m0 = layer.magnitude[i]
        // ⚠️ The catalogue magnitude first, before any dot product: the air only ever DIMS, so a
        // star already past the cut cannot come back through it, and this is the test that throws
        // most of a deep layer away.
        if (m0 > limit) continue
        val x = layer.vx[i]
        val y = layer.vy[i]
        val z = layer.vz[i]
        val s = frame.sinAltitude(x, y, z)
        // ⚠️ What the air takes at this altitude, before the cut AND the size band — a first-
        // magnitude star a degree up is drawn as the fifth-magnitude speck it looks like, and a
        // hair lower it is not drawn at all. Zero with the atmosphere off, so `m` is the catalogue
        // magnitude to the bit. See SkyFrame.extinctionMag.
        val m = m0 + frame.extinctionMag(s)
        if (m > limit) continue
        val p = frame.project(x, y, z)
        if (!p.onScreen(viewport, SkyRenderer.EDGE_MARGIN)) continue
        val band = StarGlyph.sizeBand(m, limit)
        val target = if (frame.aboveHorizon(s)) above else below
        target.add(
            layer.colourBand[i], band,
            centreX + (p.x * halfPx).toFloat(),
            centreY + (p.y * halfPx).toFloat(),
        )
        drawn++
    }
    return drawn
}

/**
 * Draw everything that has been collected.
 *
 * ⚠️ **One `android.graphics.Paint`, reused across every bucket and every frame.** A Compose
 * `drawCircle` per star would be tens of thousands of calls; a Paint per bucket would be fifty
 * allocations a frame. The caller owns the paint so it outlives the frame — see the parameter.
 *
 * @param paint remembered by the caller. Its style, cap and antialiasing are set here; only its
 *   colour and width change per bucket.
 * @param unmeasuredColour what to draw a star with no measured colour in. ⚠️ The palette's own ink
 *   rather than a guess: "no colour" is a fact, and painting it some plausible white would be a
 *   claim about a measurement nobody made.
 */
fun DrawScope.drawStarBatches(
    batches: StarBatches,
    paint: Paint,
    unmeasuredColour: Color,
    alpha: Float = 1f,
) {
    paint.isAntiAlias = true
    paint.style = Paint.Style.STROKE
    // ⚠️ ROUND is what makes a point a disc. The default cap is BUTT, which draws a SQUARE — a sky
    // of tiny squares, which reads as a rendering fault rather than as stars.
    paint.strokeCap = Paint.Cap.ROUND
    val canvas = drawContext.canvas.nativeCanvas
    batches.forEachBucket { slot, size, points, values ->
        val argb = StarNames.bandArgb(slot) ?: unmeasuredColour.toArgb()
        paint.color = argb
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        // ⚠️ **strokeWidth is the DIAMETER of the point, not its radius.** Passing the radius draws
        // every star in the sky at half the intended size, uniformly, which looks like a deliberate
        // style rather than a bug — so it is the one number here worth checking against a screenshot.
        paint.strokeWidth = StarGlyph.bandRadiusDp(size).dp.toPx() * 2f
        canvas.drawPoints(points, 0, values, paint)
    }
}

/**
 * The handful of stars bright enough to be drawn as more than a disc.
 *
 * ⚠️ **Drawn one at a time, and [StarGlyph.GLOW_HEADROOM] is what keeps that affordable.** The count
 * was swept over the real catalogue: about eight at the widest field and sixty at the busiest, out
 * of thousands drawn. The same law that makes the sky drawable — counts rising 2.8-fold per
 * magnitude — is what makes the bright end rare, so this needs no separate cap.
 */
fun DrawScope.drawStarGlow(
    layer: StarLayer,
    frame: SkyFrame,
    viewport: SkyProjection.Viewport,
    limit: Double,
    halfPx: Float,
    centreX: Float,
    centreY: Float,
    unmeasuredColour: Color,
) {
    for (i in 0 until layer.count) {
        val m0 = layer.magnitude[i].toDouble()
        if (!StarGlyph.glows(m0, limit)) continue
        val x = layer.vx[i]
        val y = layer.vy[i]
        val z = layer.vz[i]
        val s = frame.sinAltitude(x, y, z)
        // Extincted like the disc it sits on, or a setting star would keep its halo after losing
        // its size. Zero with the atmosphere off, as in collectStars.
        val m = m0 + frame.extinctionMag(s)
        if (!StarGlyph.glows(m, limit)) continue
        val p = frame.project(x, y, z)
        if (!p.onScreen(viewport, SkyRenderer.EDGE_MARGIN)) continue
        val core = StarGlyph.bandRadiusDp(StarGlyph.sizeBand(m, limit)).dp.toPx()
        val at = Offset(centreX + (p.x * halfPx).toFloat(), centreY + (p.y * halfPx).toFloat())
        val colour = StarNames.bandArgb(layer.colourBand[i])?.let { Color(it) } ?: unmeasuredColour
        val dim = if (frame.aboveHorizon(s)) 1f else SkyRenderer.BELOW_HORIZON_ALPHA
        // A radial falloff rather than a ring: what makes a bright star look bright to the eye is
        // that its light spills, and a hard-edged bigger circle just looks like a bigger dot.
        drawCircle(
            brush = Brush.radialGradient(
                0f to colour.copy(alpha = 0.55f * dim),
                0.35f to colour.copy(alpha = 0.22f * dim),
                1f to Color.Transparent,
                center = at,
                radius = core * GLOW_RADIUS,
            ),
            radius = core * GLOW_RADIUS,
            center = at,
        )
    }
}

/** How far the halo reaches, as a multiple of the star's own drawn radius. */
private const val GLOW_RADIUS = 4.5f

/**
 * Project a set of polylines into a batch of screen-space segments.
 *
 * ⚠️ **The per-run cap test comes first and is the reason this is affordable.** The constellation
 * set is about ninety thousand vertices at the finest subdivision; at a narrow field almost every
 * run is off screen, and one dot product throws each of those away before any of its vertices is
 * touched. Without it a quarter-degree field would spend its whole frame projecting points to
 * discard them.
 *
 * ⚠️ **A segment with both ends off screen is KEPT when it straddles.** The rejection is four
 * half-plane tests, which fire only when both endpoints are outside the SAME side — so a line that
 * crosses the view with neither end on it survives. That case is the common one when zoomed in, and
 * an axis-aligned box test would have quietly erased every long line on the screen.
 *
 * @param coneCos cosine of [SkyProjection.coneRadiusDeg] for this view — the angle to the screen
 *   CORNER, not the half-field.
 * @return how many segments were emitted.
 */
fun collectLines(
    lines: SkyLines,
    frame: SkyFrame,
    viewport: SkyProjection.Viewport,
    coneCos: Double,
    coneSin: Double,
    halfPx: Float,
    centreX: Float,
    centreY: Float,
    into: LineBatch,
): Int {
    val marginW = viewport.halfWidth + SkyRenderer.EDGE_MARGIN
    val marginH = viewport.halfHeight + SkyRenderer.EDGE_MARGIN
    var drawn = 0
    for (run in 0 until lines.lines) {
        if (!lines.visible(run, frame.forwardX, frame.forwardY, frame.forwardZ, coneCos, coneSin)) {
            continue
        }
        val from = lines.start[run]
        val to = from + lines.length[run]
        var haveLast = false
        var lastX = 0.0
        var lastY = 0.0
        for (i in from until to) {
            // ⚠️ Refracted like the stars it joins, or a figure would float half a degree off its
            // own stars at the horizon and the ecliptic would miss the planets that follow it.
            val p = frame.project(lines.vx[i], lines.vy[i], lines.vz[i])
            if (!p.visible) {
                // ⚠️ A vertex behind the viewer has no finite screen position, so the run is BROKEN
                // here rather than joined across the gap. Joining would draw a line from one edge of
                // the sky to the other — the classic wraparound streak.
                haveLast = false
                continue
            }
            if (haveLast) {
                val outside =
                    (lastX > marginW && p.x > marginW) || (lastX < -marginW && p.x < -marginW) ||
                        (lastY > marginH && p.y > marginH) || (lastY < -marginH && p.y < -marginH)
                if (!outside) {
                    into.add(
                        centreX + (lastX * halfPx).toFloat(),
                        centreY + (lastY * halfPx).toFloat(),
                        centreX + (p.x * halfPx).toFloat(),
                        centreY + (p.y * halfPx).toFloat(),
                    )
                    drawn++
                }
            }
            lastX = p.x
            lastY = p.y
            haveLast = true
        }
    }
    return drawn
}

/**
 * Draw a collected batch of segments in one call.
 *
 * @param paint remembered by the caller, as [drawStarBatches]'s is and for the same reason.
 */
fun DrawScope.drawLineBatch(
    batch: LineBatch,
    paint: Paint,
    colour: Color,
    widthPx: Float,
    alpha: Float,
) {
    if (batch.values == 0) return
    paint.isAntiAlias = true
    paint.style = Paint.Style.STROKE
    // ⚠️ BUTT rather than ROUND, unlike the star paint: a polyline is drawn as many short segments
    // and a round cap puts a blob at every joint, which at these widths reads as a beaded line.
    paint.strokeCap = Paint.Cap.BUTT
    paint.color = colour.toArgb()
    paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
    paint.strokeWidth = widthPx
    drawContext.canvas.nativeCanvas.drawLines(batch.points, 0, batch.values, paint)
}
