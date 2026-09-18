package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The tiling, checked against the one property that actually matters.
 *
 * ⚠️ **A cone query that misses a tile is stars vanishing from part of a view**, and it fails in the
 * least visible way there is: the map still draws, still looks like a sky, and is simply missing
 * things near an edge or at a declination nobody happened to test. So [tilesInCone] is not checked
 * against a hand-worked example — it is brute-forced. Points are scattered through real cones and
 * every one of their tiles is required to be in the answer.
 */
class SkyGridTest {

    private val rng = Random(20260828)

    // ---- the geometry itself --------------------------------------------------------------------

    @Test
    fun `the tile count is the sum of the bands and every id is reachable`() {
        var sum = 0
        for (b in 0 until SkyGrid.BANDS) sum += SkyGrid.raDivisions(b)
        assertEquals(sum, SkyGrid.tileCount)

        // Every id resolves to a band, and the bands come out in order.
        var previous = -1
        for (tile in 0 until SkyGrid.tileCount) {
            val band = SkyGrid.bandOfTile(tile)
            assertTrue("band $band out of range at tile $tile", band in 0 until SkyGrid.BANDS)
            assertTrue("bands went backwards at $tile", band >= previous)
            previous = band
        }
    }

    @Test
    fun `a position lands in a tile whose bounds contain it`() {
        repeat(4000) {
            val ra = rng.nextDouble(0.0, 360.0)
            val dec = rng.nextDouble(-90.0, 90.0)
            val tile = SkyGrid.tileOf(ra, dec)
            val b = SkyGrid.boundsOf(tile)
            assertTrue("ra $ra outside ${b.raLoDeg}..${b.raHiDeg}", ra >= b.raLoDeg - 1e-9 && ra <= b.raHiDeg + 1e-9)
            assertTrue("dec $dec outside ${b.decLoDeg}..${b.decHiDeg}", dec >= b.decLoDeg - 1e-9 && dec <= b.decHiDeg + 1e-9)
        }
    }

    /**
     * ⚠️ The reason for varying the divisions per band rather than using a constant. A fixed count
     * would leave polar tiles fifty degrees of right ascension wide, which is a tiny angle on the
     * sky but makes every polar query read a sliver of the whole sky.
     */
    @Test
    fun `no tile is much wider than it is tall, at any declination`() {
        for (band in 0 until SkyGrid.BANDS) {
            val b = SkyGrid.boundsOf(SkyGrid.firstTileOfBand(band))
            // Width in TRUE angle, at whichever edge of the band is nearer the equator.
            val widest = maxOf(
                if (b.decLoDeg * b.decHiDeg <= 0.0) 1.0 else 0.0,
                cos(Math.toRadians(b.decLoDeg)),
                cos(Math.toRadians(b.decHiDeg)),
            )
            val widthDeg = b.raSpanDeg * widest
            assertTrue(
                "band $band tiles are ${widthDeg}deg wide against ${b.decSpanDeg}deg tall",
                widthDeg <= b.decSpanDeg * 1.001,
            )
            // And not absurdly narrow either, or the tile count would explode.
            assertTrue("band $band tiles are only ${widthDeg}deg wide", widthDeg > b.decSpanDeg / 3.0)
        }
    }

    @Test
    fun `the format key changes with the geometry`() {
        assertEquals("band${SkyGrid.BANDS}/${SkyGrid.tileCount}", SkyGrid.FORMAT_KEY)
    }

    // ---- the cone query -------------------------------------------------------------------------

    /**
     * The property, brute-forced: nothing inside the circle may be in a tile the query did not
     * return. Cones are placed at every latitude including both poles, at radii from a fraction of a
     * tile to most of the sky.
     */
    @Test
    fun `a cone query never misses a tile that holds part of the cone`() {
        val radii = listOf(0.1, 0.5, 2.0, 7.0, 30.0, 75.0, 120.0)
        val decs = listOf(-89.9, -88.0, -80.0, -75.0, -44.0, -2.0, 0.0, 12.0, 45.0, 60.0, 70.0, 80.0, 88.5, 90.0)
        var checked = 0
        for (r in radii) {
            for (dec in decs) {
                for (ra in listOf(0.0, 0.05, 137.4, 359.97)) {
                    val tiles = SkyGrid.tilesInCone(ra, dec, r).toHashSet()
                    // ⚠️ Half the samples are ON THE RIM, not scattered through the interior, and
                    // that is what makes this test able to see anything. A circle on a sphere reaches
                    // furthest in right ascension at a point on its boundary, in a sliver where the
                    // half-width barely varies — a uniform-by-area sampler lands there so rarely that
                    // a query understating the width by most of a degree passed cleanly for hundreds
                    // of thousands of points.
                    repeat(400) { i ->
                        val (pRa, pDec) = pointInCone(ra, dec, r, onRim = i % 2 == 0)
                        val separation = SkyProjection.separationDeg(ra, dec, pRa, pDec)
                        assertTrue("sampler escaped the cone: $separation > $r", separation <= r + 1e-9)
                        val tile = SkyGrid.tileOf(pRa, pDec)
                        assertTrue(
                            "MISSED tile $tile at ($pRa, $pDec), ${separation}deg from ($ra, $dec) r=$r",
                            tile in tiles,
                        )
                        checked++
                    }
                }
            }
        }
        assertTrue(checked > 100_000)
    }

    /**
     * The same property at the exact declination where a cone is widest in right ascension —
     * `asin(sin δ₀ / cos r)` — walked densely in position angle rather than sampled.
     *
     * ⚠️ This is the case the shortcut got wrong, so it gets a test of its own rather than relying on
     * a random sampler to wander into it.
     */
    @Test
    fun `the widest point of a cone is inside the tiles it returned`() {
        for (dec in listOf(10.0, 45.0, 60.0, 70.0, 80.0, -70.0, -85.0)) {
            for (r in listOf(0.5, 2.0, 7.0, 15.0, 30.0)) {
                if (abs(dec) + r >= 90.0) continue      // the pole case takes whole bands anyway
                for (ra in listOf(0.0, 91.3, 275.5)) {
                    val tiles = SkyGrid.tilesInCone(ra, dec, r).toHashSet()
                    for (step in 0 until 720) {
                        val (pRa, pDec) = onRim(ra, dec, r, step * 0.5)
                        assertTrue(
                            "MISSED the rim at ($pRa, $pDec) of ($ra, $dec) r=$r",
                            SkyGrid.tileOf(pRa, pDec) in tiles,
                        )
                    }
                }
            }
        }
    }

    /**
     * ⚠️ The other half of the bargain: generous is fine, useless is not. A query that answered
     * "every tile" would pass the test above and defeat the whole index.
     */
    @Test
    fun `a small cone reads a small number of tiles`() {
        assertTrue(SkyGrid.tilesInCone(80.0, 10.0, 1.0).size <= 12)
        assertTrue(SkyGrid.tilesInCone(80.0, 10.0, 5.0).size <= 40)
        // Even at the pole, where every column of a band is taken, the bands are few.
        assertTrue(SkyGrid.tilesInCone(80.0, 89.0, 2.0).size < SkyGrid.tileCount / 8)
    }

    @Test
    fun `the whole sky is one query`() {
        assertEquals(SkyGrid.tileCount, SkyGrid.tilesInCone(0.0, 0.0, 180.0).size)
        // And no id repeats, or a reader would decode the same stars twice.
        val tiles = SkyGrid.tilesInCone(200.0, -30.0, 90.0)
        assertEquals(tiles.size, tiles.distinct().size)
    }

    // ---- a deterministic point somewhere inside a cone -------------------------------------------

    /**
     * Uniform by area within [radiusDeg] of the centre: `cos θ` uniform between `cos r` and 1, and
     * the position angle uniform, then rotated onto the centre through a local east/north frame.
     *
     * ⚠️ Rejection sampling would have been simpler and is unusable here — at a one-degree radius
     * only one point in thirteen thousand lands inside, so the small cones, which are the ones a
     * reader actually issues, would barely be tested at all.
     */
    private fun pointInCone(
        raDeg: Double,
        decDeg: Double,
        radiusDeg: Double,
        onRim: Boolean,
    ): Pair<Double, Double> {
        val cosR = cos(Math.toRadians(radiusDeg))
        // On the rim the separation is exactly the radius; inside, uniform by area.
        val cosT = if (onRim) cosR else cosR + rng.nextDouble() * (1.0 - cosR)
        return offset(raDeg, decDeg, cosT, rng.nextDouble() * 360.0)
    }

    /** A point exactly [radiusDeg] from the centre, at a given position angle. */
    private fun onRim(
        raDeg: Double,
        decDeg: Double,
        radiusDeg: Double,
        positionAngleDeg: Double,
    ): Pair<Double, Double> = offset(raDeg, decDeg, cos(Math.toRadians(radiusDeg)), positionAngleDeg)

    /**
     * Rotate an offset onto a centre through a local east/north frame.
     *
     * ⚠️ Rejection sampling would have been simpler and is unusable here — at a one-degree radius
     * only one point in thirteen thousand lands inside, so the small cones, which are the ones a
     * reader actually issues, would barely be tested at all.
     */
    private fun offset(
        raDeg: Double,
        decDeg: Double,
        cosTheta: Double,
        positionAngleDeg: Double,
    ): Pair<Double, Double> {
        val a0 = Math.toRadians(raDeg)
        val d0 = Math.toRadians(decDeg)
        val cosT = cosTheta.coerceIn(-1.0, 1.0)
        val sinT = kotlin.math.sqrt((1.0 - cosT * cosT).coerceAtLeast(0.0))
        val phi = Math.toRadians(positionAngleDeg)

        val n = doubleArrayOf(cos(d0) * cos(a0), cos(d0) * sin(a0), sin(d0))
        val east = doubleArrayOf(-sin(a0), cos(a0), 0.0)
        val north = doubleArrayOf(-sin(d0) * cos(a0), -sin(d0) * sin(a0), cos(d0))

        val x = cosT * n[0] + sinT * (cos(phi) * north[0] + sin(phi) * east[0])
        val y = cosT * n[1] + sinT * (cos(phi) * north[1] + sin(phi) * east[1])
        val z = cosT * n[2] + sinT * (cos(phi) * north[2] + sin(phi) * east[2])

        val dec = Math.toDegrees(Math.asin(z.coerceIn(-1.0, 1.0)))
        var ra = Math.toDegrees(Math.atan2(y, x))
        if (ra < 0) ra += 360.0
        return ra to dec
    }
}
