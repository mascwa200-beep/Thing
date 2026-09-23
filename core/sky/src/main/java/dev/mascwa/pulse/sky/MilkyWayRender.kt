package dev.mascwa.pulse.sky

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.mascwa.pulse.core.telemetry.MilkyWay
import dev.mascwa.pulse.core.telemetry.SkyProjection

/**
 * The Milky Way, painted under everything else.
 *
 * ## Drawn first, and that is the whole ordering rule
 *
 * It is unresolved starlight: every star bright enough to be a dot on this map is *in front of* the
 * glow, and so is every galaxy and every constellation line. Drawing it last would put a haze over
 * the sky rather than behind it.
 *
 * ## ⚠️ The direction of the arithmetic is the opposite of every other pass here
 *
 * Stars, deep-sky objects and constellation lines are all *forward* projected: a known place on the
 * sky becomes a place on the screen. This pass runs backwards — a screen pixel becomes a direction,
 * which becomes galactic coordinates, which index the raster. That is not a stylistic choice; it is
 * what makes the cost independent of the field of view, and [MilkyWayGlow] explains at length why
 * the forward alternatives do not work here.
 *
 * ## ⚠️ Not refracted, deliberately — but extincted, and faded by twilight
 *
 * Every other catalogue-frame layer goes through `SkyFrame.project`, which bends it toward the
 * zenith the way the air does. This one reads `frame.basis` directly and paints the geometric sky,
 * because the raster is a ONE-DEGREE grid of star density, and the bend it would carry is half a
 * degree for something truly on the horizon and a sixth of a degree five degrees up — smaller than
 * the blur the bilinear upscale already spreads a cell over. A haze drawn half a cell higher is
 * indistinguishable from the haze, and the arithmetic to move it would run per pixel here rather
 * than per star.
 *
 * What the air TAKES is a different matter from where it moves things: the glow is made of the
 * same starlight the stars are, so it fades toward the horizon by the same
 * [SkyFrame.transmission] — one table lookup per sample, on the sine of altitude the sample's
 * direction already gives — and it fades out altogether through astronomical twilight
 * ([opacityScale], from `SkyBrightness.milkyWayFactor`). Both are exactly 1 with the atmosphere
 * off or the Sun well down, so the dark-sky glow is the picture it always was.
 *
 * ## What the glow means
 *
 * A byte in the raster is a star count per square degree, measured from the bundled catalogue.
 * [MilkyWay.opacity] turns that into ink. The Great Rift is visible in it because dust does not dim
 * a star a little — it pushes it below the catalogue's magnitude cut and out of the count entirely,
 * so a dust lane is a *hole* in the density map and the most legible thing in it.
 */
fun DrawScope.drawMilkyWay(
    raster: MilkyWay.Raster,
    frame: SkyFrame,
    view: SkyProjection.View,
    glow: MilkyWayGlow,
    tint: Color,
    /**
     * The most samples this device may take across the narrow axis, from
     * [dev.mascwa.pulse.core.telemetry.SkyBudget]. Defaulted to the full-strength cap so nothing
     * changes for a caller that does not care; the glow's own floor still wins, because below it the
     * bilinear upscale creases and the cheaper setting would be the worse picture.
     */
    maxSamples: Int = MilkyWayGlow.MAX_SAMPLES,
    /**
     * What to multiply every sample's opacity by — the twilight fade, 1 on a dark sky. Defaulted
     * to 1 so a caller that has no Sun to ask about draws the night glow unchanged.
     */
    opacityScale: Double = 1.0,
) {
    if (opacityScale <= 0.0) return
    if (size.width <= 0f || size.height <= 0f) return
    val minor = minOf(size.width, size.height)
    if (minor <= 0f) return

    val across = MilkyWayGlow.samplesAcross(view.fovDeg, maxSamples)
    // The bitmap is sampled in screen units, where the NARROW axis spans -1..+1 — the same
    // normalisation `SkyProjection.viewportOf` uses, so a pixel here lands where a star would.
    val halfW = size.width / minor
    val halfH = size.height / minor
    val w = Math.round(across * halfW).toInt().coerceAtLeast(1)
    val h = Math.round(across * halfH).toInt().coerceAtLeast(1)
    if (!glow.resize(w, h)) return

    val rgb = tint.toArgb() and 0x00FFFFFF
    val cells = raster.cells
    val peak = raster.peak
    val basis = frame.basis
    // ⚠️ Two scratch arrays held across the whole sweep rather than one pair per pixel. At the cap
    // that is 5,600 allocations a frame saved, and they are the reason the two core functions take
    // an `out` parameter at all.
    val dir = DoubleArray(3)
    val gal = DoubleArray(2)
    // Half a pixel in, so a sample sits at the centre of the screen area it stands for. Without it
    // the whole picture is offset by half a bitmap pixel, which at the cap is a degree of sky.
    val stepX = 2.0 * halfW / w
    val stepY = 2.0 * halfH / h
    val originX = -halfW + stepX / 2.0
    val originY = -halfH + stepY / 2.0

    val image = glow.paint { x, y ->
        val sx = originX + x * stepX
        val sy = originY + y * stepY
        if (!SkyProjection.unprojectUnit(sx, sy, basis, dir)) {
            0
        } else {
            MilkyWay.galacticOfVector(dir[0], dir[1], dir[2], gal)
            // The air's share at this direction's altitude — exactly 1 with the atmosphere off.
            val air = frame.transmission(frame.sinAltitude(dir[0], dir[1], dir[2]))
            MilkyWayGlow.argb(
                rgb,
                MilkyWay.opacity(MilkyWay.sample(cells, peak, gal[0], gal[1])) * opacityScale * air,
            )
        }
    } ?: return

    // ⚠️ `FilterQuality.Low` is bilinear, and it is the half of this design that makes a 56-sample
    // image acceptable at full screen size. With `None` — nearest neighbour — the same buffer would
    // be a grid of visible squares, which is the one artefact a diffuse glow cannot survive.
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(w, h),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        filterQuality = FilterQuality.Low,
    )
}
