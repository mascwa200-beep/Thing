package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expectations here are not recollections.
 *
 * The tile coordinates were computed from the published Web Mercator definition in an independent
 * Python twin before a line of this was written, and the Berlin case is the canonical worked example
 * the OpenStreetMap wiki itself publishes — so if this arithmetic and the tile servers ever disagree,
 * these fail rather than the map quietly drawing the wrong part of the world.
 */
class WebMercatorTest {

    /**
     * The Brandenburg Gate at zoom 17 is tile 70406/42987. Published, not derived here.
     *
     * That makes it the one fixture in this file that would survive a mistake in my own twin, which
     * is exactly why it is first.
     */
    @Test
    fun theCanonicalWorkedExampleLandsOnItsPublishedTile() {
        assertEquals(70406, WebMercator.tileX(13.377778, 17).toInt())
        assertEquals(42987, WebMercator.tileY(52.516667, 17).toInt())
    }

    @Test
    fun realPlacesLandOnTheirTiles() {
        // London, 51.5074 N 0.1278 W, zoom 12 -> 2046/1362 (twin: 2046.545920, 1362.024541)
        assertEquals(2046, WebMercator.tileX(-0.1278, 12).toInt())
        assertEquals(1362, WebMercator.tileY(51.5074, 12).toInt())
        // Sydney, southern hemisphere and far east — both signs exercised.
        assertEquals(942, WebMercator.tileX(151.2093, 10).toInt())
        assertEquals(614, WebMercator.tileY(-33.8688, 10).toInt())
        // Rio, southern and western.
        assertEquals(97, WebMercator.tileX(-43.1729, 8).toInt())
        assertEquals(144, WebMercator.tileY(-22.9068, 8).toInt())
    }

    /** The whole world is one tile at zoom 0, and the origin sits dead centre at zoom 1. */
    @Test
    fun theWorldSquareIsWhereTheStandardPutsIt() {
        assertEquals(1, WebMercator.worldTiles(0))
        assertEquals(4, WebMercator.worldTiles(2))
        assertEquals(1.0, WebMercator.tileX(0.0, 1), 1e-12)
        assertEquals(1.0, WebMercator.tileY(0.0, 1), 1e-12)
        assertEquals(0.0, WebMercator.tileX(-180.0, 0), 1e-12)
    }

    /** Projecting and unprojecting has to return the same place, or a click lands somewhere else. */
    @Test
    fun theProjectionInverts() {
        for ((lat, lon) in listOf(51.5074 to -0.1278, -33.8688 to 151.2093, 0.0 to 0.0, 60.0 to -120.0)) {
            for (z in listOf(2, 8, 14)) {
                assertEquals(lon, WebMercator.longitudeAt(WebMercator.tileX(lon, z), z), 1e-9)
                assertEquals(lat, WebMercator.latitudeAt(WebMercator.tileY(lat, z), z), 1e-9)
            }
        }
    }

    /**
     * ⚠️ The load-bearing property of the whole file.
     *
     * A marker is placed by [WebMercator.offsetX]/[WebMercator.offsetY]; the ground under it is drawn
     * by [WebMercator.tiles]. If those two ever used different arithmetic, every aircraft, earthquake
     * and hospital would sit slightly off where it actually is — and the map would still look
     * perfectly plausible, which is what makes it worth pinning rather than eyeballing.
     */
    @Test
    fun aMarkerLandsOnTheTileThatContainsIt() {
        val vp = WebMercator.Viewport(51.5074, -0.1278, 12, 800.0, 600.0)
        val lat = 51.53
        val lon = -0.09
        val px = WebMercator.offsetX(lon, vp)
        val py = WebMercator.offsetY(lat, vp)

        val holding = WebMercator.tiles(vp).single {
            px >= it.left && px < it.left + it.size && py >= it.top && py < it.top + it.size
        }
        assertEquals(WebMercator.tileX(lon, 12).toInt(), holding.x)
        assertEquals(WebMercator.tileY(lat, 12).toInt(), holding.y)
    }

    /** And the inverse: what is under a pixel is the place that pixel was drawn for. */
    @Test
    fun theOffsetInverts() {
        val vp = WebMercator.Viewport(35.68, 139.69, 11, 1024.0, 768.0)
        assertEquals(139.72, WebMercator.longitudeAtOffset(WebMercator.offsetX(139.72, vp), vp), 1e-9)
        assertEquals(35.62, WebMercator.latitudeAtOffset(WebMercator.offsetY(35.62, vp), vp), 1e-9)
    }

    /** The centre of the view is the centre of the view. */
    @Test
    fun theCentreIsTheCentre() {
        val vp = WebMercator.Viewport(51.5074, -0.1278, 12, 800.0, 600.0)
        assertEquals(400.0, WebMercator.offsetX(-0.1278, vp), 1e-9)
        assertEquals(300.0, WebMercator.offsetY(51.5074, vp), 1e-9)
    }

    /**
     * ⚠️ The antimeridian. A point one degree west of it, on a map centred one degree east of it, is
     * two degrees away and plainly on screen — but its raw tile column is most of a world apart.
     * Without taking the delta the short way round it would be placed thousands of pixels off, and
     * the seam would look like the map had torn.
     */
    @Test
    fun theSeamIsCrossedTheShortWay() {
        val vp = WebMercator.Viewport(0.0, 179.0, 6, 800.0, 600.0)
        val px = WebMercator.offsetX(-179.0, vp)
        assertTrue("a point 2 degrees away must be near the centre, not off the map: $px", px in 380.0..520.0)
        assertTrue("and it must be to the EAST of the centre", px > 400.0)
    }

    /** East–west the world repeats, so tiles are wrapped for fetching while staying put on screen. */
    @Test
    fun columnsWrapForFetchingButNotForPlacing() {
        val vp = WebMercator.Viewport(0.0, 179.9, 2, 900.0, 300.0)
        val placed = WebMercator.tiles(vp)
        val n = WebMercator.worldTiles(2)
        assertTrue("every fetched column must be a tile that exists", placed.all { it.x in 0 until n })
        // The seam is inside this view, so the same column is drawn twice in different places.
        val lefts = placed.filter { it.y == placed.first().y }.map { it.left }
        assertEquals("the placements must all be distinct", lefts.size, lefts.toSet().size)
    }

    /** There is nothing above the north pole, so no row is drawn there. */
    @Test
    fun rowsOutsideTheWorldAreDroppedRatherThanRepeated() {
        val vp = WebMercator.Viewport(WebMercator.MAX_LATITUDE, 0.0, 1, 800.0, 800.0)
        val n = WebMercator.worldTiles(1)
        assertTrue(WebMercator.tiles(vp).all { it.y in 0 until n })
    }

    /**
     * ⚠️ Mercator runs away towards the poles, so a stray 90.0 — a GPS fix at the pole, or a bad feed
     * value — would produce an infinity and take the canvas down with it rather than drawing wrongly.
     */
    @Test
    fun thePolesAndRubbishValuesStayFinite() {
        for (bad in listOf(90.0, -90.0, 1e9, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue("tileY($bad) must be finite", WebMercator.tileY(bad, 10).isFinite())
        }
        for (bad in listOf(1e12, Double.NaN, Double.NEGATIVE_INFINITY)) {
            assertTrue("tileX($bad) must be finite", WebMercator.tileX(bad, 10).isFinite())
        }
        assertEquals(0.0, WebMercator.normaliseLongitude(Double.NaN), 1e-12)
    }

    /** Longitude repeats: 190 east is 170 west, and the map should not care which was typed. */
    @Test
    fun longitudeFolds() {
        assertEquals(-170.0, WebMercator.normaliseLongitude(190.0), 1e-9)
        assertEquals(170.0, WebMercator.normaliseLongitude(-190.0), 1e-9)
        assertEquals(-180.0, WebMercator.normaliseLongitude(180.0), 1e-9)
        assertEquals(0.0, WebMercator.normaliseLongitude(720.0), 1e-9)
    }

    /**
     * ⚠️ `1 shl z` on the JVM shifts by `z and 31`, so an unclamped zoom of 32 yields **one** tile
     * rather than failing — a map that silently draws the whole world when asked for a street.
     */
    @Test
    fun anAbsurdZoomIsClampedRatherThanWrapped() {
        assertEquals(WebMercator.MAX_ZOOM, WebMercator.clampZoom(32))
        assertEquals(0, WebMercator.clampZoom(-4))
        assertTrue("a shifted zoom must not collapse the world", WebMercator.worldTiles(32) > 1)
        assertEquals(WebMercator.worldTiles(WebMercator.MAX_ZOOM), WebMercator.worldTiles(99))
    }

    /** A scale bar reads this, and at the equator zoom 0 it is the classic 156 km per pixel. */
    @Test
    fun theScaleIsTheKnownOne() {
        assertEquals(156_543.03, WebMercator.metresPerPixel(0.0, 0), 0.01)
        // Half the ground per pixel for every zoom level in.
        assertEquals(
            WebMercator.metresPerPixel(0.0, 10) / 2.0,
            WebMercator.metresPerPixel(0.0, 11),
            1e-9,
        )
        // And less ground per pixel away from the equator, which is Mercator's whole bargain.
        assertTrue(WebMercator.metresPerPixel(60.0, 10) < WebMercator.metresPerPixel(0.0, 10))
    }

    /** The template carries the ordering, which is how EOX's row-before-column address works at all. */
    @Test
    fun theTemplateCarriesTheOrdering() {
        val t = WebMercator.Placed(12, 2046, 1362, 0.0, 0.0, 256.0)
        assertEquals(
            "https://tile.opentopomap.org/12/2046/1362.png",
            WebMercator.url("https://tile.opentopomap.org/{z}/{x}/{y}.png", t),
        )
        assertEquals(
            "https://x/12/1362/2046.jpg",
            WebMercator.url("https://x/{z}/{y}/{x}.jpg", t),
        )
        assertEquals("12/2046/1362", t.key)
    }

    /** A viewport with no area, or a nonsense one, asks for nothing rather than throwing. */
    @Test
    fun anEmptyViewportAsksForNoTiles() {
        assertTrue(WebMercator.tiles(WebMercator.Viewport(0.0, 0.0, 5, 0.0, 400.0)).isEmpty())
        assertTrue(WebMercator.tiles(WebMercator.Viewport(0.0, 0.0, 5, 400.0, Double.NaN)).isEmpty())
        assertTrue(WebMercator.tiles(WebMercator.Viewport(0.0, 0.0, 5, 400.0, 400.0, 0)).isEmpty())
    }

    /** A window covers a handful of tiles; the cap is insurance and must never bite on a real one. */
    @Test
    fun aRealWindowAsksForAModestNumberOfTiles() {
        val n = WebMercator.tiles(WebMercator.Viewport(51.5, -0.1, 14, 3840.0, 2160.0)).size
        assertTrue("a 4K window asked for $n tiles", n in 100..300)
        assertTrue(n < WebMercator.MAX_TILES)
    }

    /** Tiles cover the viewport with no gap: the first starts at or before the edge. */
    @Test
    fun theTilesCoverTheWholeView() {
        val vp = WebMercator.Viewport(48.85, 2.35, 13, 700.0, 500.0)
        val placed = WebMercator.tiles(vp)
        assertTrue(placed.minOf { it.left } <= 0.0)
        assertTrue(placed.minOf { it.top } <= 0.0)
        assertTrue(placed.maxOf { it.left + it.size } >= vp.widthPx)
        assertTrue(placed.maxOf { it.top + it.size } >= vp.heightPx)
        // And no tile is placed a whole tile beyond the far edge, which would mean fetching for nothing.
        assertTrue(placed.all { it.left < vp.widthPx && it.top < vp.heightPx })
    }

    /** Neighbouring tiles abut exactly — a rounding slip here shows as hairlines across the map. */
    @Test
    fun neighbouringTilesAbut() {
        val vp = WebMercator.Viewport(48.85, 2.35, 13, 700.0, 500.0)
        val row = WebMercator.tiles(vp).filter { it.top == WebMercator.tiles(vp).first().top }
            .sortedBy { it.left }
        row.zipWithNext { a, b -> assertTrue(abs(a.left + a.size - b.left) < 1e-9) }
    }
}
