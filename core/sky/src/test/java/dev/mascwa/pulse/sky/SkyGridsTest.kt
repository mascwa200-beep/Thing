package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.MilkyWay
import dev.mascwa.pulse.core.telemetry.ReferenceCircles
import dev.mascwa.pulse.core.telemetry.SkyProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The grids hold exactly the counts they declare, every line lies where its name says, and the
 * galactic equator is derived from the galaxy's own frame rather than typed in.
 *
 * ⚠️ Nothing here trusts a hand-typed coordinate except one anchor: the IAU galactic centre
 * (17h45m37s, −28°56′10″ J2000 = 266.405°, −28.936°), which is the one number a reader can check
 * against any reference and which pins the `NODE_L_DEG − l` convention that
 * `MilkyWay.equatorialOf` and `galacticOfVector` share. Everything else is a geometric property —
 * a vertex is on a plane, at a declination, a unit vector — read back through the shipped functions.
 */
class SkyGridsTest {

    /** 2026-09-21T00:00Z — an arbitrary epoch; nothing below depends on which. */
    private val epoch = 1_789_948_800_000L

    private fun norm(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)

    /** The north celestial pole OF DATE, as a J2000 vector — what the equatorial grid turns about. */
    private fun poleOfDate(): DoubleArray {
        val p = doubleArrayOf(0.0, 0.0, 1.0)
        Ephemeris.ofDateVectorToJ2000(p, epoch)
        return p
    }

    private fun dot(a: DoubleArray, x: Double, y: Double, z: Double) = a[0] * x + a[1] * y + a[2] * z

    @Test
    fun `the equatorial grid holds exactly the counts it declares, all unit vectors`() {
        val lines = SkyLines(SkyGrids.GRID_VERTICES, SkyGrids.GRID_RUNS)
        SkyGrids.fillEquatorial(lines, epoch)
        assertEquals(SkyGrids.GRID_VERTICES, lines.count)
        assertEquals(SkyGrids.GRID_RUNS, lines.lines)
        // 24 hour circles × 6 runs + 16 parallels × 12 runs, 16 vertices each: pinned as numbers so
        // a change to a constant shows up as a number and not as a silently different picture.
        assertEquals(5376, lines.count)
        assertEquals(336, lines.lines)
        for (i in 0 until lines.count) {
            assertEquals("vertex $i", 1.0, norm(lines.vx[i], lines.vy[i], lines.vz[i]), 1e-12)
        }
        for (run in 0 until lines.lines) assertEquals("run $run", SkyGrids.PER_RUN, lines.length[run])
    }

    @Test
    fun `hour circles pass through the pole of date and parallels sit at their declination of date`() {
        val lines = SkyLines(SkyGrids.GRID_VERTICES, SkyGrids.GRID_RUNS)
        SkyGrids.fillEquatorial(lines, epoch)
        val pole = poleOfDate()
        // An hour circle at RA r is the plane through the pole and (r, 0) of date: every vertex of
        // its six runs is orthogonal to that plane's normal.
        for (line in 0 until SkyGrids.LINES) {
            val ra = line * SkyGrids.LINE_EVERY_DEG
            val onEquator = SkyProjection.equatorialVector(ra, 0.0)
            Ephemeris.ofDateVectorToJ2000(onEquator, epoch)
            val nx = pole[1] * onEquator[2] - pole[2] * onEquator[1]
            val ny = pole[2] * onEquator[0] - pole[0] * onEquator[2]
            val nz = pole[0] * onEquator[1] - pole[1] * onEquator[0]
            for (run in 0 until SkyGrids.RUNS_PER_LINE) {
                val r = line * SkyGrids.RUNS_PER_LINE + run
                for (i in lines.start[r] until lines.start[r] + lines.length[r]) {
                    val off = lines.vx[i] * nx + lines.vy[i] * ny + lines.vz[i] * nz
                    // 1e-9, not 1e-12: the vertex and the normal each went through the
                    // precession-nutation rotation separately, and the products differ at 1.5e-12.
                    assertEquals("hour circle $ra, vertex $i off its plane", 0.0, off, 1e-9)
                }
            }
        }
        // The parallels follow, ±10 then ±20 … ±80: every vertex's projection on the pole of date
        // is the sine of that declination.
        val firstParallelRun = SkyGrids.LINES * SkyGrids.RUNS_PER_LINE
        var circle = 0
        for (k in 1..8) {
            for (sign in listOf(1.0, -1.0)) {
                val dec = sign * k * SkyGrids.CIRCLE_EVERY_DEG
                for (run in 0 until SkyGrids.RUNS_PER_CIRCLE) {
                    val r = firstParallelRun + circle * SkyGrids.RUNS_PER_CIRCLE + run
                    for (i in lines.start[r] until lines.start[r] + lines.length[r]) {
                        val s = dot(pole, lines.vx[i], lines.vy[i], lines.vz[i])
                        assertEquals("parallel $dec, vertex $i", sin(Math.toRadians(dec)), s, 1e-12)
                    }
                }
                circle++
            }
        }
        assertEquals(SkyGrids.CIRCLES, circle)
        // And the grid really is OF DATE: the pole it turns about is not the J2000 pole.
        assertTrue("the pole of date has moved off J2000's", abs(pole[2] - 1.0) > 1e-6)
    }

    @Test
    fun `the galactic equator is derived from the galaxy's own frame and starts at the galactic centre`() {
        val lines = SkyLines(SkyGrids.CIRCLE_VERTICES, SkyGrids.CIRCLE_RUNS)
        SkyGrids.fillGalacticEquator(lines)
        assertEquals(SkyGrids.CIRCLE_VERTICES, lines.count)
        assertEquals(SkyGrids.CIRCLE_RUNS, lines.lines)
        val g = DoubleArray(2)
        for (run in 0 until lines.lines) {
            for (i in 0 until lines.length[run]) {
                val at = lines.start[run] + i
                MilkyWay.galacticOfVector(lines.vx[at], lines.vy[at], lines.vz[at], g)
                assertEquals("latitude at vertex $at", 0.0, g[1], 1e-9)
                val wantL = ReferenceCircles.longitudeOf(run, i) % 360.0
                val gotL = ((g[0] % 360.0) + 360.0) % 360.0
                val diff = abs(gotL - wantL).let { if (it > 180.0) 360.0 - it else it }
                assertEquals("longitude at vertex $at", 0.0, diff, 1e-9)
            }
        }
        // The one typed anchor: longitude zero is the galactic centre, in Sagittarius. This is what
        // pins the sign convention — a `NODE_L_DEG + l` frame would put it on the far side of the sky.
        val centre = SkyProjection.equatorialVector(266.405, -28.936)
        val d = centre[0] * lines.vx[0] + centre[1] * lines.vy[0] + centre[2] * lines.vz[0]
        assertTrue("first vertex is ${Math.toDegrees(kotlin.math.acos(d))}° from the galactic centre", d > cos(Math.toRadians(0.02)))
    }

    @Test
    fun `the horizon grid is in the observer's frame, azimuth lines vertical and altitude circles level`() {
        val lines = SkyLines(SkyGrids.GRID_VERTICES, SkyGrids.GRID_RUNS)
        SkyGrids.fillHorizon(lines)
        assertEquals(SkyGrids.GRID_VERTICES, lines.count)
        assertEquals(SkyGrids.GRID_RUNS, lines.lines)
        val zenith = SkyProjection.unitVector(0.0, 90.0)
        // An azimuth line at A lies in the vertical plane through A, so it is orthogonal to the
        // horizontal direction ninety degrees round from A.
        for (line in 0 until SkyGrids.LINES) {
            val az = line * SkyGrids.LINE_EVERY_DEG
            val across = SkyProjection.unitVector(az + 90.0, 0.0)
            for (run in 0 until SkyGrids.RUNS_PER_LINE) {
                val r = line * SkyGrids.RUNS_PER_LINE + run
                for (i in lines.start[r] until lines.start[r] + lines.length[r]) {
                    assertEquals("azimuth line $az, vertex $i", 0.0, dot(across, lines.vx[i], lines.vy[i], lines.vz[i]), 1e-12)
                }
            }
        }
        val firstCircleRun = SkyGrids.LINES * SkyGrids.RUNS_PER_LINE
        var circle = 0
        for (k in 1..8) {
            for (sign in listOf(1.0, -1.0)) {
                val alt = sign * k * SkyGrids.CIRCLE_EVERY_DEG
                for (run in 0 until SkyGrids.RUNS_PER_CIRCLE) {
                    val r = firstCircleRun + circle * SkyGrids.RUNS_PER_CIRCLE + run
                    for (i in lines.start[r] until lines.start[r] + lines.length[r]) {
                        val s = dot(zenith, lines.vx[i], lines.vy[i], lines.vz[i])
                        assertEquals("altitude circle $alt, vertex $i", sin(Math.toRadians(alt)), s, 1e-12)
                    }
                }
                circle++
            }
        }
    }

    @Test
    fun `the meridian runs north, zenith, south, nadir and never leaves that plane`() {
        val lines = SkyLines(SkyGrids.CIRCLE_VERTICES, SkyGrids.CIRCLE_RUNS)
        SkyGrids.fillMeridian(lines)
        assertEquals(SkyGrids.CIRCLE_VERTICES, lines.count)
        assertEquals(SkyGrids.CIRCLE_RUNS, lines.lines)
        val east = SkyProjection.unitVector(90.0, 0.0)
        for (i in 0 until lines.count) {
            assertEquals("vertex $i off the meridian plane", 0.0, dot(east, lines.vx[i], lines.vy[i], lines.vz[i]), 1e-12)
        }
        fun same(a: DoubleArray, b: DoubleArray) {
            for (k in 0..2) assertEquals(a[k], b[k], 1e-12)
        }
        same(SkyProjection.unitVector(0.0, 0.0), SkyGrids.meridianPoint(0.0))
        same(SkyProjection.unitVector(0.0, 90.0), SkyGrids.meridianPoint(90.0))
        same(SkyProjection.unitVector(180.0, 0.0), SkyGrids.meridianPoint(180.0))
        same(SkyProjection.unitVector(0.0, -90.0), SkyGrids.meridianPoint(270.0))
        // The seam at 360 is the seam at 0, and the parameter wraps.
        same(SkyGrids.meridianPoint(0.0), SkyGrids.meridianPoint(360.0))
        same(SkyGrids.meridianPoint(45.0), SkyGrids.meridianPoint(405.0))
    }

    @Test
    fun `horizon lines project through the basis, and the cap test throws away what a narrow field cannot see`() {
        val meridian = SkyLines(SkyGrids.CIRCLE_VERTICES, SkyGrids.CIRCLE_RUNS)
        SkyGrids.fillMeridian(meridian)
        val viewport = SkyProjection.Viewport(0.6, 1.0)
        val batch = LineBatch()

        fun collect(azimuth: Double, altitude: Double, fov: Double): Int {
            val view = SkyProjection.View(azimuth, altitude, fov)
            val basis = SkyProjection.basisOf(view)
            val f = SkyProjection.unitVector(azimuth, altitude)
            val cone = Math.toRadians(SkyProjection.coneRadiusDeg(fov, viewport))
            batch.reset()
            // The margin the renderer passes is SkyRenderer.EDGE_MARGIN; that file imports Compose,
            // so a plain number of the same order stands in here and the property under test —
            // which runs are projected at all — does not depend on it.
            return collectHorizonLines(
                meridian, basis, f[0], f[1], f[2], viewport, cos(cone), sin(cone),
                500f, 500f, 900f, 0.05, batch,
            )
        }
        // Aimed due north, thirty degrees up: the meridian runs straight through the screen.
        val north = collect(0.0, 30.0, 60.0)
        assertTrue("segments looking north: $north", north > 0)
        assertEquals(north * 4, batch.values)
        // Aimed due east at a narrow field: the meridian is ninety degrees away from everything on
        // screen, so the cap test rejects every run and nothing is projected.
        assertEquals(0, collect(90.0, 30.0, 5.0))
        // Aimed north-east at the widest field: the north point is 45° off axis, inside a 150°
        // field's 75° half-width, so the meridian reaches the screen edge and must not be culled.
        // ⚠️ The first version aimed due EAST here and expected segments — wrong: a 150° field is
        // ±75°, and the meridian is 90° away. The code refused it correctly.
        assertTrue(collect(45.0, 0.0, 150.0) > 0)
    }

    @Test
    fun `labels sit on the lines they name`() {
        val eq = SkyGrids.equatorialLabels(epoch)
        assertEquals(12 + 8, eq.size)
        val pole = poleOfDate()
        for (label in eq) {
            val s = dot(pole, label.x, label.y, label.z)
            when {
                label.text.endsWith("h") -> assertEquals(label.text, 0.0, s, 1e-12)
                label.text == "+30°" -> assertEquals(sin(Math.toRadians(30.0)), s, 1e-12)
                label.text == "+60°" -> assertEquals(sin(Math.toRadians(60.0)), s, 1e-12)
                label.text == "−30°" -> assertEquals(-sin(Math.toRadians(30.0)), s, 1e-12)
                label.text == "−60°" -> assertEquals(-sin(Math.toRadians(60.0)), s, 1e-12)
                else -> throw AssertionError("unexpected label ${label.text}")
            }
        }
        assertEquals(listOf("0h", "2h", "4h", "6h", "8h", "10h", "12h", "14h", "16h", "18h", "20h", "22h"), eq.map { it.text }.filter { it.endsWith("h") })

        val hz = SkyGrids.horizonLabels()
        assertEquals(8 + 4, hz.size)
        val zenith = SkyProjection.unitVector(0.0, 90.0)
        val texts = hz.map { it.text }
        // The four the chart letters itself are not labelled twice.
        for (t in listOf("0°", "90°", "180°", "270°")) assertTrue("$t should be a letter, not a number", t !in texts.take(8))
        for (label in hz.take(8)) assertEquals(label.text, 0.0, dot(zenith, label.x, label.y, label.z), 1e-12)
        for (label in hz.drop(8)) {
            val want = if (label.text == "30°") sin(Math.toRadians(30.0)) else sin(Math.toRadians(60.0))
            assertEquals(label.text, want, dot(zenith, label.x, label.y, label.z), 1e-12)
        }
    }
}
