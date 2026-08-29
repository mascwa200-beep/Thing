package dev.mascwa.pulse.sky

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.mascwa.pulse.core.telemetry.MilkyWay

/**
 * The small screen-space image the glow is painted into, kept between frames.
 *
 * ## ⚠️ Why a bitmap at all, rather than drawing the raster's cells
 *
 * The obvious approach is to forward-project each galactic cell and draw it. That fails three ways:
 * the cell count swings from a handful at a quarter-degree field to about twenty thousand at a
 * hundred and fifty, so the cost is worst exactly where the picture matters least; drawing them as
 * quads gives a mosaic rather than a glow, and the only primitive that would not is
 * `Canvas.drawVertices` with a colour array, whose hardware support this project cannot verify; and
 * an equirectangular grid degenerates at the poles, which is where a cell is 1° tall and a fraction
 * of a degree wide.
 *
 * Going the other way — one sample per bitmap pixel, then a single bilinear upscale — costs the same
 * on every frame whatever the field, produces a continuous field by construction, and is one draw
 * call. Measured on a build machine, the whole per-pixel chain (inverse projection, galactic
 * transform, bilinear raster sample, pack) is **91 ns**, so the size below is a real budget rather
 * than a guess.
 *
 * ## ⚠️ How big, and why the answer is "as many samples as there are degrees"
 *
 * [dev.mascwa.pulse.core.telemetry.SkyProjection.View.fovDeg] is the field across the *narrow*
 * screen axis, and the raster's cells are one degree — so `fovDeg` samples across that axis is
 * exactly one sample per cell, and anything finer is resolving detail the raster does not contain.
 * The cap is what stops the widest field being the most expensive: at 150° it under-samples by
 * about half, which softens the picture by a degree or two, and the Great Rift is ten degrees wide.
 * **Softness is the failure mode of a bitmap that is too small; blockiness is not**, because the
 * upscale is bilinear.
 *
 * The floor is not about the raster — at a narrow field one cell covers the whole screen — but about
 * the upscale itself: interpolating from a handful of knots across a phone screen can crease
 * visibly on a strong gradient.
 */
class MilkyWayGlow {

    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var pixels = IntArray(0)
    private var width = 0
    private var height = 0

    /**
     * Make sure the buffers are the right shape, allocating only when it changes.
     *
     * @return false if the size is degenerate, in which case nothing may be drawn.
     */
    fun resize(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (w == width && h == height && bitmap != null) return true
        // ⚠️ Bitmap.createBitmap can throw when there is no room for it, and a sky map that crashes
        // rather than losing its background glow is the wrong trade by a wide margin.
        val fresh = runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return false
        bitmap?.recycle()
        bitmap = fresh
        image = fresh.asImageBitmap()
        if (pixels.size < w * h) pixels = IntArray(w * h)
        width = w
        height = h
        return true
    }

    /**
     * Paint one frame.
     *
     * @param paint called once per pixel with its column and row; returns a **non-premultiplied**
     *   packed ARGB, which is what [Bitmap.setPixels] documents itself as taking.
     * @return the image to draw, or null if [resize] has not succeeded.
     */
    inline fun paint(paint: (x: Int, y: Int) -> Int): ImageBitmap? {
        val w = widthOf()
        val h = heightOf()
        if (w <= 0 || h <= 0) return null
        val buf = buffer()
        var at = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                buf[at++] = paint(x, y)
            }
        }
        return commit()
    }

    /**
     * Push [buffer] into the bitmap and hand back the image.
     *
     * ⚠️ The same [ImageBitmap] every frame, deliberately. It wraps the bitmap rather than copying
     * it, and `setPixels` bumps the bitmap's generation id, which is what tells the renderer its
     * cached texture is stale. Making a fresh wrapper each frame would allocate for nothing.
     */
    @PublishedApi
    internal fun commit(): ImageBitmap? {
        val b = bitmap ?: return null
        b.setPixels(pixels, 0, width, 0, 0, width, height)
        return image
    }

    // ⚠️ Published only so `paint` can be inline, which is what keeps a per-frame lambda allocation
    // off the draw path. Not part of the intended surface.
    @PublishedApi internal fun widthOf(): Int = width

    @PublishedApi internal fun heightOf(): Int = height

    @PublishedApi internal fun buffer(): IntArray = pixels

    companion object {
        /**
         * Samples across the narrow screen axis, from the field of view.
         *
         * ⚠️ The cap is the frame budget and the floor is the upscale — see the class note. Both are
         * measured rather than chosen: at the cap the whole pass is 5,600 samples, which is 0.5 ms
         * on a build machine and a few milliseconds on a weak phone, for a layer drawn under
         * everything else.
         */
        fun samplesAcross(fovDeg: Double): Int {
            if (!fovDeg.isFinite()) return MIN_SAMPLES
            return Math.round(fovDeg).toInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        }

        /** Below this the bilinear upscale itself can crease on a strong gradient. */
        const val MIN_SAMPLES = 16

        /** Above this the widest field would cost more than the layer is worth. */
        const val MAX_SAMPLES = 56

        /**
         * Opacity 0..1 as the alpha byte of a packed colour.
         *
         * Kept here beside the packing rather than in [MilkyWay], which has no business knowing what
         * a pixel is.
         */
        fun argb(rgb: Int, opacity: Double): Int {
            val a = Math.round(opacity.coerceIn(0.0, 1.0) * 255.0).toInt()
            return (a shl 24) or (rgb and 0x00FFFFFF)
        }
    }
}
