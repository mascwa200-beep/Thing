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
) {
    if (size.width <= 0f || size.height <= 0f) return
    val minor = minOf(size.width, size.height)
    if (minor <= 0f) return

    val across = MilkyWayGlow.samplesAcross(view.fovDeg)
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
            MilkyWayGlow.argb(rgb, MilkyWay.opacity(MilkyWay.sample(cells, peak, gal[0], gal[1])))
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
