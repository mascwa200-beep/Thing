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
 * Per star: nine multiplications and a divide to project ([SkyProjection.projectUnit]), a dot
 * product to find whether it is above the horizon ([SkyFrame.sinAltitude]), two comparisons, and two
 * writes into a bucket. Then a few dozen `drawPoints` calls for the whole sky, whatever its size.
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
    val basis = frame.basis
    for (i in 0 until layer.count) {
        val m = layer.magnitude[i]
        if (m > limit) continue
        val x = layer.vx[i]
        val y = layer.vy[i]
        val z = layer.vz[i]
        val p = SkyProjection.projectUnit(x, y, z, basis)
        if (!p.onScreen(viewport, SkyRenderer.EDGE_MARGIN)) continue
        val band = StarGlyph.sizeBand(m.toDouble(), limit)
        val target = if (frame.sinAltitude(x, y, z) >= 0.0) above else below
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
    val basis = frame.basis
    for (i in 0 until layer.count) {
        val m = layer.magnitude[i].toDouble()
        if (!StarGlyph.glows(m, limit)) continue
        val x = layer.vx[i]
        val y = layer.vy[i]
        val z = layer.vz[i]
        val p = SkyProjection.projectUnit(x, y, z, basis)
        if (!p.onScreen(viewport, SkyRenderer.EDGE_MARGIN)) continue
        val core = StarGlyph.bandRadiusDp(StarGlyph.sizeBand(m, limit)).dp.toPx()
        val at = Offset(centreX + (p.x * halfPx).toFloat(), centreY + (p.y * halfPx).toFloat())
        val colour = StarNames.bandArgb(layer.colourBand[i])?.let { Color(it) } ?: unmeasuredColour
        val dim = if (frame.sinAltitude(x, y, z) >= 0.0) 1f else SkyRenderer.BELOW_HORIZON_ALPHA
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
