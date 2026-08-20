package dev.mascwa.pulse.core.telemetry

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator — the projection every slippy map on the internet is drawn in.
 *
 * The phone never needed this because MapLibre does it internally and never exposes it. A raster
 * map drawn onto a canvas has to do it itself, so it lives here: it is arithmetic with a published
 * definition and no dependencies, which is exactly what belongs in a tested core rather than inside
 * a screen.
 *
 * The definition is the one OpenStreetMap, Google, Bing and every tile service in [dev.mascwa.pulse.data.maps.MapLayerCatalog]
 * agree on: the world is one square tile at zoom 0, four at zoom 1, and 4^z at zoom z; x runs east
 * from the antimeridian and y runs **south** from the top.
 *
 * ⚠️ **The property that matters most is that [tiles] and [offsetX]/[offsetY] agree.** A marker is
 * positioned by the second pair and the ground under it is drawn by the first, so if the two ever
 * used different arithmetic every aircraft, earthquake and hospital on the map would sit slightly
 * off the place it is actually at — and it would look like a plausible map rather than a broken one.
 * Both go through [tileX]/[tileY], and a test relates them directly.
 */
object WebMercator {

    /**
     * The latitude Mercator stops at.
     *
     * Not a stylistic cut-off: the projection stretches towards the poles without limit, and the
     * standard square world is obtained by stopping exactly where the map becomes as tall as it is
     * wide. Past it `tan(lat)` runs away and a tile coordinate becomes meaningless, so [clampLatitude]
     * is what stands between a stray 90.0 and an infinity reaching a canvas.
     */
    const val MAX_LATITUDE = 85.05112877980659

    /** The side of one tile, in pixels, for every service here except EOX's 512-pixel option. */
    const val TILE_PX = 256

    /**
     * ⚠️ Not a taste limit — a correctness one. Tile counts are held in an `Int` and `1 shl z` on
     * the JVM shifts by `z and 31`, so a zoom of 32 silently yields **one** tile rather than
     * overflowing loudly. Every entry point clamps, and a test pins it.
     */
    const val MAX_ZOOM = 22

    fun clampZoom(zoom: Int): Int = zoom.coerceIn(0, MAX_ZOOM)

    fun clampLatitude(deg: Double): Double =
        if (deg.isNaN()) 0.0 else deg.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)

    /** Longitude folded into [-180, 180). A map repeats east–west; the arithmetic should not care. */
    fun normaliseLongitude(deg: Double): Double {
        if (!deg.isFinite()) return 0.0
        var d = (deg + 180.0) % 360.0
        if (d < 0) d += 360.0
        return d - 180.0
    }

    /** How many tiles span the world at this zoom. */
    fun worldTiles(zoom: Int): Int = 1 shl clampZoom(zoom)

    /** Fractional tile column. 0 at the antimeridian, [worldTiles] at the antimeridian again. */
    fun tileX(lonDeg: Double, zoom: Int): Double =
        (normaliseLongitude(lonDeg) + 180.0) / 360.0 * worldTiles(zoom)

    /** Fractional tile row, counted **south** from the top edge. */
    fun tileY(latDeg: Double, zoom: Int): Double {
        val r = clampLatitude(latDeg) * PI / 180.0
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldTiles(zoom)
    }

    fun longitudeAt(tileX: Double, zoom: Int): Double =
        tileX / worldTiles(zoom) * 360.0 - 180.0

    fun latitudeAt(tileY: Double, zoom: Int): Double {
        val n = PI * (1.0 - 2.0 * tileY / worldTiles(zoom))
        return atan(sinh(n)) * 180.0 / PI
    }

    /**
     * How much ground one pixel covers, for a scale bar.
     *
     * Mercator's whole bargain is that it preserves angles by stretching distance with latitude, so
     * this is only true at the latitude asked for — which is why a scale bar on a Mercator map is
     * drawn for the middle of the view and is wrong at its top and bottom.
     */
    fun metresPerPixel(latDeg: Double, zoom: Int, tilePx: Int = TILE_PX): Double {
        val circumference = 40_075_016.686
        return circumference * cos(clampLatitude(latDeg) * PI / 180.0) /
            (tilePx.toDouble() * worldTiles(zoom))
    }

    /** What the map is currently showing: a centre, a zoom, and the size of the hole it is seen through. */
    data class Viewport(
        val centreLat: Double,
        val centreLon: Double,
        val zoom: Int,
        val widthPx: Double,
        val heightPx: Double,
        val tilePx: Int = TILE_PX,
    )

    /**
     * One tile, and where to put it.
     *
     * ⚠️ [x] is **wrapped** and [left] is **not**, and the difference is the whole reason this is a
     * data class rather than a pair of ints. Fetching needs a real tile that exists on a server;
     * placing needs to know that this copy of the world is the one to the left of the seam. Collapse
     * them and a map panned across the antimeridian either tears or fetches a 404.
     */
    data class Placed(
        val z: Int,
        val x: Int,
        val y: Int,
        val left: Double,
        val top: Double,
        val size: Double,
    ) {
        /** A stable identity for a cache, independent of where on screen this copy landed. */
        val key: String get() = "$z/$x/$y"
    }

    /**
     * Horizontal pixel offset of a longitude from the viewport's left edge.
     *
     * ⚠️ The delta is taken the short way round the world. Without that, a marker one degree west of
     * the antimeridian on a map centred one degree east of it would be placed most of a world-width
     * away — off screen, on a map where it is plainly visible.
     */
    fun offsetX(lonDeg: Double, vp: Viewport): Double {
        val n = worldTiles(vp.zoom)
        var d = tileX(lonDeg, vp.zoom) - tileX(vp.centreLon, vp.zoom)
        val half = n / 2.0
        while (d > half) d -= n
        while (d < -half) d += n
        return vp.widthPx / 2.0 + d * vp.tilePx
    }

    /** Vertical pixel offset of a latitude from the viewport's top edge. No wrapping — there is no seam. */
    fun offsetY(latDeg: Double, vp: Viewport): Double =
        vp.heightPx / 2.0 + (tileY(latDeg, vp.zoom) - tileY(vp.centreLat, vp.zoom)) * vp.tilePx

    /** The inverse of [offsetX]/[offsetY] — what is under the pointer. */
    fun longitudeAtOffset(px: Double, vp: Viewport): Double {
        val t = tileX(vp.centreLon, vp.zoom) + (px - vp.widthPx / 2.0) / vp.tilePx
        return normaliseLongitude(longitudeAt(t, vp.zoom))
    }

    fun latitudeAtOffset(py: Double, vp: Viewport): Double {
        val t = tileY(vp.centreLat, vp.zoom) + (py - vp.heightPx / 2.0) / vp.tilePx
        return latitudeAt(t.coerceIn(0.0, worldTiles(vp.zoom).toDouble()), vp.zoom)
    }

    /**
     * Every tile needed to cover [vp], each with where it goes.
     *
     * Rows outside the world are dropped rather than clamped — there is genuinely nothing above the
     * north pole, and repeating the top row up the screen would draw Greenland into the sky.
     * Columns are kept and wrapped, because east–west the world really does repeat.
     */
    fun tiles(vp: Viewport): List<Placed> {
        val z = clampZoom(vp.zoom)
        val n = worldTiles(z)
        val size = vp.tilePx.toDouble()
        if (size <= 0 || !vp.widthPx.isFinite() || !vp.heightPx.isFinite()) return emptyList()
        if (vp.widthPx <= 0 || vp.heightPx <= 0) return emptyList()

        val leftTile = tileX(vp.centreLon, z) - (vp.widthPx / 2.0) / size
        val topTile = tileY(vp.centreLat, z) - (vp.heightPx / 2.0) / size
        val x0 = floor(leftTile).toInt()
        val x1 = floor(leftTile + vp.widthPx / size).toInt()
        val y0 = floor(topTile).toInt()
        val y1 = floor(topTile + vp.heightPx / size).toInt()

        val out = ArrayList<Placed>()
        for (ty in y0..y1) {
            if (ty < 0 || ty >= n) continue
            for (tx in x0..x1) {
                out += Placed(
                    z = z,
                    x = Math.floorMod(tx, n),
                    y = ty,
                    left = (tx - leftTile) * size,
                    top = (ty - topTile) * size,
                    size = size,
                )
                if (out.size >= MAX_TILES) return out
            }
        }
        return out
    }

    /**
     * ⚠️ Insurance, not a fix for anything observed. A real window at 4K asks for about 256 tiles;
     * this only bites on a viewport nothing sane produces, and returning what fits beats letting a
     * transient canvas size allocate without bound.
     */
    const val MAX_TILES = 1024

    /**
     * Fill a `{z}/{x}/{y}` template.
     *
     * The template carries the ordering, which is why EOX's WMTS address — `{z}/{y}/{x}`, row before
     * column — needs no special case here.
     */
    fun url(template: String, t: Placed): String =
        template.replace("{z}", t.z.toString())
            .replace("{x}", t.x.toString())
            .replace("{y}", t.y.toString())
}
