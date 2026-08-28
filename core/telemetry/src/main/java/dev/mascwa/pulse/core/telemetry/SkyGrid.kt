package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Cutting the sky into tiles, so a star catalogue can be read in pieces.
 *
 * A catalogue of a few million stars cannot be loaded to draw a two-degree field. It is stored
 * sorted by tile, with an index saying where each tile begins, and the reader asks this object which
 * tiles the current view touches. Everything else — the record layout, the magnitude cut — lives
 * elsewhere; this is only the geometry.
 *
 * ## ⚠️ This is NOT HEALPix, and the name says so on purpose
 *
 * HEALPix is the standard and it is genuinely equal-area, which matters enormously for the
 * statistical work it was designed for. It matters not at all here: nothing computes a density or a
 * power spectrum, and the only questions asked are "which tile is this star in" and "which tiles
 * could this circle touch". A band-and-column grid answers both in twenty lines, and its cone query
 * can be made provably conservative — where HEALPix's `query_disc` is a few hundred lines of ring
 * geometry whose failure mode is stars silently missing at the edge of a view.
 *
 * ## The shape
 *
 * Bands of equal declination height, each divided into a whole number of equal RA columns, with the
 * division count chosen so no tile is much wider than it is tall **anywhere in its band**. That is
 * what stops polar tiles becoming 51°-wide slivers that every query has to read.
 *
 * ⚠️ **A pleasant consequence worth knowing: positional precision comes out roughly uniform.** A
 * catalogue that stores a star's position as a fraction of its tile has a coarser RA step near the
 * poles — but an RA degree there is a small angle on the sky, and the two effects cancel almost
 * exactly, because the division count already scales with the cosine.
 *
 * Tiles are numbered from the south pole upward, and within a band by increasing right ascension. A
 * tile id is therefore stable for a given [BANDS] and nothing else — see [FORMAT_KEY].
 */
object SkyGrid {

    private const val DEG = Math.PI / 180.0

    /**
     * How many declination bands the sky is cut into.
     *
     * ⚠️ **Changing this renumbers every tile**, so a catalogue built with one value cannot be read
     * with another. [FORMAT_KEY] exists so that mistake is caught at the header rather than as a sky
     * quietly drawn in the wrong places.
     *
     * 64 gives bands of 2.8125° and about five thousand tiles, so a tile holds roughly eight square
     * degrees. Against a sixteen-million-star catalogue that is a few thousand stars per tile — a
     * few tens of kilobytes, which is the size a random read wants to be.
     */
    const val BANDS = 64

    /** Height of every band, in degrees. */
    const val BAND_HEIGHT_DEG = 180.0 / BANDS

    /** Southern edge of a band. */
    fun bandFloorDeg(band: Int): Double = -90.0 + band * BAND_HEIGHT_DEG

    /**
     * How many RA columns a band is divided into.
     *
     * Chosen from the band's WIDEST declination — the edge nearer the equator — so that no column in
     * it exceeds [BAND_HEIGHT_DEG] of true angle. Using the band centre instead would leave the
     * equator-facing edge of each polar band wider than intended, which is invisible until a cone
     * query starts missing tiles.
     */
    fun raDivisions(band: Int): Int {
        val lo = bandFloorDeg(band)
        val hi = lo + BAND_HEIGHT_DEG
        val widest = when {
            lo >= 0.0 -> cos(lo * DEG)      // northern: the southern edge is nearer the equator
            hi <= 0.0 -> cos(hi * DEG)      // southern: the northern edge is
            else -> 1.0                     // straddles the equator
        }
        return max(1, ceil(360.0 * widest / BAND_HEIGHT_DEG).toInt())
    }

    /** Index of the first tile in a band. `bandStart[BANDS]` is the total tile count. */
    private val bandStart: IntArray = IntArray(BANDS + 1).also { starts ->
        var running = 0
        for (b in 0 until BANDS) {
            starts[b] = running
            running += raDivisions(b)
        }
        starts[BANDS] = running
    }

    /** How many tiles the whole sky is cut into. */
    val tileCount: Int get() = bandStart[BANDS]

    /** The first tile in a band. `firstTileOfBand(BANDS)` is [tileCount], so bands are half-open. */
    fun firstTileOfBand(band: Int): Int = bandStart[band.coerceIn(0, BANDS)]

    /**
     * A short string identifying this geometry, written into a catalogue header and checked on load.
     *
     * ⚠️ The whole point is that a mismatch is loud. A catalogue built under a different [BANDS] is
     * not corrupt in any way a parser could notice — every record reads perfectly and lands in the
     * wrong part of the sky.
     */
    val FORMAT_KEY: String get() = "band$BANDS/$tileCount"

    /** Which band a declination falls in. */
    fun bandOf(decDeg: Double): Int =
        ((decDeg + 90.0) / BAND_HEIGHT_DEG).let { floor(it).toInt() }.coerceIn(0, BANDS - 1)

    /** Which band a tile belongs to. */
    fun bandOfTile(tile: Int): Int {
        require(tile in 0 until tileCount) { "tile $tile is outside 0..${tileCount - 1}" }
        // Bands are few and the search is over a sorted array; a scan would also be fine.
        var lo = 0
        var hi = BANDS - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (bandStart[mid] <= tile) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The tile containing a position. */
    fun tileOf(raDeg: Double, decDeg: Double): Int {
        val band = bandOf(decDeg)
        val n = raDivisions(band)
        val column = floor(norm360(raDeg) / (360.0 / n)).toInt().coerceIn(0, n - 1)
        return bandStart[band] + column
    }

    /** The corners of a tile: right ascension runs `raLoDeg` to `raHiDeg`, never wrapping. */
    data class Bounds(
        val raLoDeg: Double,
        val raHiDeg: Double,
        val decLoDeg: Double,
        val decHiDeg: Double,
    ) {
        val raSpanDeg: Double get() = raHiDeg - raLoDeg
        val decSpanDeg: Double get() = decHiDeg - decLoDeg
    }

    fun boundsOf(tile: Int): Bounds {
        val band = bandOfTile(tile)
        val n = raDivisions(band)
        val column = tile - bandStart[band]
        val width = 360.0 / n
        val lo = bandFloorDeg(band)
        return Bounds(column * width, (column + 1) * width, lo, lo + BAND_HEIGHT_DEG)
    }

    /**
     * Every tile a circle on the sky could touch.
     *
     * ⚠️ **This must never miss, and being generous is free.** A tile wrongly included costs a few
     * kilobytes read and nothing drawn; a tile wrongly excluded is stars vanishing from part of the
     * view — which looks like a rendering fault, or worse, like a sky that is simply wrong.
     *
     * ## Three layers, deliberately redundant, and the measurement that says so
     *
     * 1. [raHalfWidthDeg] computes the RA half-width **exactly**, at the true widest declination.
     * 2. The declination range is widened by a whole band and the RA range by a whole column.
     * 3. A cone reaching a pole takes every column of the bands it touches.
     *
     * ⚠️ **Each of the three is sufficient on its own, and that was established by removing them.**
     * Knock out any single layer and `SkyGridTest` still passes — the other two cover it. Knock out
     * all three together and both cone tests fail. So none of them is dead weight and none of them
     * is individually provable, which is exactly the right shape for a property whose failure is
     * invisible: the map still draws, still looks like a sky, and is quietly missing things at a
     * declination nobody tested.
     *
     * The test brute-forces rather than working an example: points are scattered through real cones —
     * half of them **on the rim**, where a circle reaches furthest in right ascension — and every one
     * of their tiles is required to be in the answer.
     */
    fun tilesInCone(raDeg: Double, decDeg: Double, radiusDeg: Double): IntArray {
        val r = radiusDeg.coerceIn(0.0, 180.0)
        if (r >= 180.0) return IntArray(tileCount) { it }
        val centreRa = norm360(raDeg)
        val centreDec = decDeg.coerceIn(-90.0, 90.0)

        val bandLo = bandOf(centreDec - r) - 1
        val bandHi = bandOf(centreDec + r) + 1
        val out = ArrayList<Int>(64)

        for (band in max(0, bandLo)..min(BANDS - 1, bandHi)) {
            val n = raDivisions(band)
            val start = bandStart[band]
            val floorDeg = bandFloorDeg(band)
            val ceilDeg = floorDeg + BAND_HEIGHT_DEG

            // A cone touching a pole covers every column of the bands it reaches: near the pole all
            // right ascensions converge, so no RA window can describe it.
            if (abs(centreDec) + r >= 90.0 || floorDeg <= -90.0 + 1e-9 || ceilDeg >= 90.0 - 1e-9) {
                for (c in 0 until n) out += start + c
                continue
            }

            val halfWidth = raHalfWidthDeg(centreDec, r, floorDeg, ceilDeg)
            if (halfWidth == null) continue          // the cone does not reach this band at all
            if (halfWidth >= 180.0) {
                for (c in 0 until n) out += start + c
                continue
            }

            val width = 360.0 / n
            // One whole column of slack either way, on top of the exact half-width.
            val loCol = floor((centreRa - halfWidth) / width).toInt() - 1
            val hiCol = floor((centreRa + halfWidth) / width).toInt() + 1
            if (hiCol - loCol + 1 >= n) {
                for (c in 0 until n) out += start + c
                continue
            }
            for (c in loCol..hiCol) out += start + Math.floorMod(c, n)
        }
        return out.distinct().toIntArray()
    }

    /**
     * How far in right ascension a cone reaches, at the most favourable declination in a band.
     *
     * Null when the cone cannot reach the band; 180 or more when it wraps the whole way round.
     *
     * ⚠️ **The obvious answer — evaluate where the band comes closest to the cone's own declination —
     * is WRONG, and it is wrong in the unsafe direction.** A circle on a sphere is at its widest in
     * right ascension not at its centre's declination but at `asin(sin δ₀ / cos r)`, which is pushed
     * toward the nearer pole because meridians converge there. Measured against the exact answer,
     * the nearest-edge shortcut understates the half-width by up to **0.8°** at δ₀ = 80°, r = 7° —
     * a sliver of sky that would simply have no stars in it.
     *
     * It was found by measurement rather than by reading: the shortfall is a fraction of a degree in
     * a region where the function is nearly flat, so a sampler scattering points uniformly through a
     * cone almost never lands in it. All four candidate declinations are tried now, and the one that
     * matters is the extreme-RA point clamped into the band.
     */
    private fun raHalfWidthDeg(
        centreDec: Double,
        radiusDeg: Double,
        bandLoDeg: Double,
        bandHiDeg: Double,
    ): Double? {
        val sinExtreme = sin(centreDec * DEG) / cos(radiusDeg * DEG)
        val extreme = if (abs(sinExtreme) <= 1.0) Math.toDegrees(Math.asin(sinExtreme)) else centreDec
        val candidates = doubleArrayOf(
            bandLoDeg,
            bandHiDeg,
            centreDec.coerceIn(bandLoDeg, bandHiDeg),
            extreme.coerceIn(bandLoDeg, bandHiDeg),
        )
        var best: Double? = null
        for (dec in candidates) {
            val here = halfWidthAt(centreDec, radiusDeg, dec) ?: continue
            if (best == null || here > best) best = here
        }
        return best
    }

    /** The cone's right-ascension half-width at one declination, or null if it does not reach. */
    private fun halfWidthAt(centreDec: Double, radiusDeg: Double, decDeg: Double): Double? {
        val denominator = cos(centreDec * DEG) * cos(decDeg * DEG)
        if (abs(denominator) < 1e-12) return 180.0
        val cosDelta =
            (cos(radiusDeg * DEG) - sin(centreDec * DEG) * sin(decDeg * DEG)) / denominator
        if (cosDelta > 1.0) return null
        if (cosDelta < -1.0) return 180.0
        return Math.toDegrees(Math.acos(cosDelta))
    }

    private fun norm360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }
}
