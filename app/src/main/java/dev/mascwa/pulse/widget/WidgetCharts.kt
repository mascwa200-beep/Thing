package dev.mascwa.pulse.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * The widget's sparklines — the one thing on the board that has to be drawn rather than written.
 *
 * ## The budget, and why it is arithmetic rather than a guess
 *
 * A bitmap in `RemoteViews` is parcelled to the launcher, and `MAX_SINGLE_PARCEL_SIZE` is **800000**
 * (verified against the platform). Drawing at the ImageView's real pixel size would blow it: a cell
 * is ~200px wide on a 3× phone, so a 200×66 ARGB_8888 chart is 52 KB and eight of them 422 KB,
 * before a single string of text.
 *
 * Two facts keep it comfortable instead, both read out of the platform rather than assumed:
 *
 *  1. **Draw small and let the ImageView scale.** A sparkline is a smooth low-frequency line, so
 *     upscaling costs nothing anyone can see. [W]×[H] is 23 KB a chart.
 *  2. ⚠️ **The size variants SHARE one bitmap cache.** `RemoteViews.initializeFrom` assigns the same
 *     `mBitmapCache` to each child of a `Map<SizeF, RemoteViews>`, and `BitmapCache.getBitmapId`
 *     de-duplicates on `Object.hashCode()` — identity, since `Bitmap` does not override it. So the
 *     SAME instance handed to both board variants is stored once.
 *
 * ⚠️ **That second point is a constraint on the caller, not a free gift.** Building the bitmaps
 * inside each render pass would produce distinct objects and pay for every chart twice. They are
 * built once, in the provider, and the same instances go to both variants.
 *
 * The total is checked before anything is attached: this module knows exactly what it allocated,
 * which is a better number than any estimate. Over [BUDGET_BYTES] the board simply draws without
 * charts — the labels and values are the reading, and the line is context.
 */
internal object WidgetCharts {

    /**
     * Chart size in PIXELS, deliberately independent of screen density.
     *
     * A cell is around 200px wide on a 3× phone, so this is roughly a 1.5× upscale under `fitXY`.
     * Chosen as the point where eight charts (186 KB) sit comfortably inside a parcel that also has
     * to carry six layout variants' worth of text.
     */
    const val W = 132
    const val H = 44

    /**
     * What the charts may cost in total.
     *
     * Well under `MAX_SINGLE_PARCEL_SIZE`, because the charts are not the only thing in the parcel:
     * six variants of text actions ride along with them, and the launcher's own overhead is not
     * ours to measure.
     */
    const val BUDGET_BYTES = 320_000

    /** Below this there is no line to draw, only a point. */
    private const val MIN_POINTS = 3

    private const val STROKE_PX = 2.4f
    private const val BASELINE_ALPHA = 70
    private const val FILL_ALPHA = 46

    /**
     * One sparkline, or null when the series cannot make a line.
     *
     * Null is returned rather than a blank bitmap on purpose: an empty chart area reads as a feed
     * that failed, where no chart at all reads as a cell that simply reports a number.
     */
    fun sparkline(series: List<Double>, argb: Int): Bitmap? {
        val pts = series.filter { it.isFinite() }
        if (pts.size < MIN_POINTS) return null
        val lo = pts.min()
        val hi = pts.max()
        // A flat series has no shape. Drawing it would put a straight line through the middle of
        // the cell, which looks like data rather than the absence of movement.
        if (!(hi - lo).isFinite() || hi - lo <= 0.0) return null

        val bmp = runCatching { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val canvas = Canvas(bmp)

        val pad = STROKE_PX
        val usableH = H - pad * 2
        val stepX = (W - pad * 2) / (pts.size - 1).toFloat()
        fun x(i: Int) = pad + stepX * i
        fun y(v: Double) = pad + (1.0 - (v - lo) / (hi - lo)).toFloat() * usableH

        // Where it started, so the line reads as up or down from something rather than as a shape.
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            alpha = BASELINE_ALPHA
            strokeWidth = 1f
        }
        canvas.drawLine(0f, y(pts.first()), W.toFloat(), y(pts.first()), base)

        val path = Path().apply {
            moveTo(x(0), y(pts[0]))
            for (i in 1 until pts.size) lineTo(x(i), y(pts[i]))
        }

        // A faint wash under the line, closed back along the baseline — enough to give the line a
        // direction at a glance without competing with the value printed above it.
        val fill = Path(path).apply {
            lineTo(x(pts.size - 1), y(pts.first()))
            lineTo(x(0), y(pts.first()))
            close()
        }
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            alpha = FILL_ALPHA
            style = Paint.Style.FILL
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = Paint.Style.STROKE
            strokeWidth = STROKE_PX
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        })
        return bmp
    }

    /**
     * What a set of charts actually allocated.
     *
     * ⚠️ Counts each DISTINCT instance once, matching what the shared `BitmapCache` will store —
     * counting a reused instance twice would refuse a payload that fits.
     */
    fun bytesOf(charts: Collection<Bitmap?>): Int =
        charts.filterNotNull()
            .distinctBy { System.identityHashCode(it) }
            .sumOf { it.allocationByteCount }
}
