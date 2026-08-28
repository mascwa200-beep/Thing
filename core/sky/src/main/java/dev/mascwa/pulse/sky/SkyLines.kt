package dev.mascwa.pulse.sky

import kotlin.math.sqrt

/**
 * Polylines on the sphere, in the one form the draw pass wants.
 *
 * The line counterpart of [StarLayer]: unit vectors in primitive arrays, plus where each run starts
 * and stops. Constellation figures, asterisms and the IAU borders are all this shape, so they are
 * all this class.
 *
 * ## ⚠️ Every run carries a bounding cap, and it is what makes the whole thing affordable
 *
 * The border and figure set is about **92,000 vertices** at the finest subdivision — see
 * [dev.mascwa.pulse.core.telemetry.Constellations.MIN_STEP_DEG], where the count is measured. At a
 * quarter-degree field essentially every line in the sky is off screen, and projecting ninety
 * thousand points only to discard them all would be the entire frame budget spent on nothing.
 *
 * So each run records the smallest circle on the sphere that contains it, and [visible] answers
 * "could any of this land on screen" with **one dot product** — which throws away the whole run
 * before a single vertex is touched.
 *
 * Not thread-safe: one of these belongs to one screen, and whoever fills it is the only writer.
 */
class SkyLines(initialVertices: Int = 4096, initialLines: Int = 256) {

    /** How many vertices are held. [vx]/[vy]/[vz] are valid over `0 until count`. */
    var count: Int = 0
        private set

    /** How many polylines are held. [start]/[length] and the cap arrays are valid over `0 until lines`. */
    var lines: Int = 0
        private set

    var vx: DoubleArray = DoubleArray(initialVertices)
        private set
    var vy: DoubleArray = DoubleArray(initialVertices)
        private set
    var vz: DoubleArray = DoubleArray(initialVertices)
        private set

    /** Where each run begins in the vertex arrays, and how many vertices it has. */
    var start: IntArray = IntArray(initialLines)
        private set
    var length: IntArray = IntArray(initialLines)
        private set

    /** The centre of each run's bounding cap, as a unit vector. */
    var capX: DoubleArray = DoubleArray(initialLines)
        private set
    var capY: DoubleArray = DoubleArray(initialLines)
        private set
    var capZ: DoubleArray = DoubleArray(initialLines)
        private set

    /**
     * The cosine and sine of each cap's angular radius.
     *
     * Both, because [visible] needs `cos(capRadius + coneRadius)` and the angle-addition form is
     * what avoids an inverse cosine per run per frame.
     */
    var capCos: DoubleArray = DoubleArray(initialLines)
        private set
    var capSin: DoubleArray = DoubleArray(initialLines)
        private set

    private var open = -1

    /** Start filling from empty. */
    fun clear() {
        count = 0
        lines = 0
        open = -1
    }

    /** Begin a run. Vertices added after this belong to it until [endLine]. */
    fun beginLine() {
        ensureLines(lines + 1)
        open = count
    }

    /** Add a vertex to the open run. */
    fun add(x: Double, y: Double, z: Double) {
        ensureVertices(count + 1)
        vx[count] = x
        vy[count] = y
        vz[count] = z
        count++
    }

    /**
     * Close the open run and compute its bounding cap.
     *
     * A run of fewer than two vertices draws nothing, so it is discarded rather than recorded — a
     * one-point "line" would otherwise occupy a cap slot and be culled or projected forever after
     * for no drawn pixels.
     */
    fun endLine() {
        val from = open
        open = -1
        if (from < 0) return
        val n = count - from
        if (n < 2) {
            count = from
            return
        }
        // The centre is the normalised sum of the run's directions. Not the smallest possible cap —
        // that is a harder problem — but for a run that spans at most thirty degrees it is within a
        // whisker of it, and a slightly generous cap costs a run that is drawn and then clipped
        // rather than one that is wrongly thrown away.
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (i in from until count) {
            sx += vx[i]
            sy += vy[i]
            sz += vz[i]
        }
        val norm = sqrt(sx * sx + sy * sy + sz * sz)
        val slot = lines
        if (norm < 1e-9) {
            // Directions that cancel out have no meaningful centre. ⚠️ Answer a cap that covers the
            // whole sky rather than an arbitrary direction: this run will then never be culled,
            // which is the safe direction — the alternative silently hides it.
            capX[slot] = 0.0
            capY[slot] = 0.0
            capZ[slot] = 1.0
            capCos[slot] = -1.0
            capSin[slot] = 0.0
        } else {
            val cx = sx / norm
            val cy = sy / norm
            val cz = sz / norm
            var worst = 1.0
            for (i in from until count) {
                val d = vx[i] * cx + vy[i] * cy + vz[i] * cz
                if (d < worst) worst = d
            }
            worst = worst.coerceIn(-1.0, 1.0)
            capX[slot] = cx
            capY[slot] = cy
            capZ[slot] = cz
            capCos[slot] = worst
            capSin[slot] = sqrt((1.0 - worst * worst).coerceAtLeast(0.0))
        }
        start[slot] = from
        length[slot] = n
        lines = slot + 1
    }

    /**
     * Could any of run [i] land on a screen whose centre looks along `(fx, fy, fz)`?
     *
     * @param coneCos cosine of the angle from the view centre to the screen CORNER —
     *   [dev.mascwa.pulse.core.telemetry.SkyProjection.coneRadiusDeg].
     * @param coneSin its sine, passed in because it is the same for every run in a frame.
     */
    fun visible(
        i: Int,
        fx: Double,
        fy: Double,
        fz: Double,
        coneCos: Double,
        coneSin: Double,
    ): Boolean {
        // cos(capRadius + coneRadius), by the angle-addition formula. When the two radii sum past a
        // half-turn this goes below -1 and every run passes, which is correct: a cap that big covers
        // the sky.
        val reach = capCos[i] * coneCos - capSin[i] * coneSin
        val dot = capX[i] * fx + capY[i] * fy + capZ[i] * fz
        return dot >= reach
    }

    private fun ensureVertices(need: Int) {
        if (need <= vx.size) return
        var size = vx.size
        while (size < need) size *= 2
        vx = vx.copyOf(size)
        vy = vy.copyOf(size)
        vz = vz.copyOf(size)
    }

    private fun ensureLines(need: Int) {
        if (need <= start.size) return
        var size = start.size
        while (size < need) size *= 2
        start = start.copyOf(size)
        length = length.copyOf(size)
        capX = capX.copyOf(size)
        capY = capY.copyOf(size)
        capZ = capZ.copyOf(size)
        capCos = capCos.copyOf(size)
        capSin = capSin.copyOf(size)
    }
}

/**
 * A frame's worth of screen-space line segments, ready for one `drawLines` call.
 *
 * ⚠️ **Four floats per segment, not two per point**, because that is what
 * `android.graphics.Canvas.drawLines` wants: `x0, y0, x1, y1` repeated. Handing it a polyline's
 * points would draw every OTHER segment, which looks like a dashed style rather than a bug.
 *
 * Owned by the caller and reused across frames, for the same reason [StarBatches] is: the buffer for
 * a busy sky is tens of thousands of floats and allocating it sixty times a second is the frame.
 */
class LineBatch(initialSegments: Int = 4096) {

    var points: FloatArray = FloatArray(initialSegments * 4)
        private set

    /** How many FLOATS are in use — what `drawLines` takes as its count. */
    var values: Int = 0
        private set

    /** How many segments are in use. */
    val segments: Int get() = values / 4

    fun reset() {
        values = 0
    }

    fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (values + 4 > points.size) {
            var size = points.size
            while (values + 4 > size) size *= 2
            points = points.copyOf(size)
        }
        points[values] = x0
        points[values + 1] = y0
        points[values + 2] = x1
        points[values + 3] = y1
        values += 4
    }
}
