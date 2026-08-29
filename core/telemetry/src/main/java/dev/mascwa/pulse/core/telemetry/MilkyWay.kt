package dev.mascwa.pulse.core.telemetry

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Milky Way, measured off the star catalogue rather than painted.
 *
 * ## Why this is derived data and not a picture
 *
 * The obvious way to draw the Milky Way is to bundle a photograph of it. That costs tens of
 * megabytes, is somebody else's copyright, and — the part that actually matters — it does not
 * respond to anything. **Star density on the sky IS the Milky Way**: the glow you see with your own
 * eyes is unresolved starlight, so counting the stars a catalogue holds in each direction measures
 * the same thing the eye integrates. The bulge, the thinning toward the anticentre and the dark dust
 * rifts all come out of the measurement rather than out of an artist's judgement.
 *
 * ## ⚠️ Density, not flux — and this corrects the design it was written from
 *
 * The plan said to sum the *flux* of the stars in each direction. Measured both ways over the real
 * bundled catalogue (3,087,821 stars to G = 12), binned by galactic latitude:
 *
 * | signal | plane (|b| < 5°) | poles (|b| > 75°) | contrast |
 * |---|---|---|---|
 * | **star density** | **183.8 /deg²** | 23.2 /deg² | **7.93×** |
 * | integrated flux | 0.01645 | 0.00364 | 4.52× |
 *
 * **Density carries nearly twice the signal.** Flux is dominated by the handful of brightest stars
 * in each direction, and those are nearby — so they are close to isotropic and dilute exactly the
 * structure this exists to show. Counting is the better instrument, and it is also the cheaper one.
 *
 * ## ⚠️ Dust shows up as ABSENCE, which is why this works at all
 *
 * The natural worry is that a catalogue cut at twelfth magnitude is far too shallow to see a glow
 * made of much fainter stars. Measured along the plane (|b| ≤ 2°), density varies **4.63×** with
 * longitude and the structure lands exactly where the real sky puts it: a 2.6× trough at
 * l = 20–50° bottoming at 82 /deg² against ~210 either side — **the Great Rift** — a second trough
 * at l = 140–150° for the Taurus–Perseus dust, and a peak of 372 /deg² at l = 280–300° for
 * Carina–Crux. Sagittarius at l = 0° is high at 212 but is *not* the maximum, which is correct
 * rather than a fault: the bulge is heavily obscured at this depth.
 *
 * That is the mechanism. Dust does not dim a star a little; it pushes it below the magnitude cut and
 * takes it out of the count altogether. A rift is therefore the *most* visible thing in a
 * density map, not the least.
 */
object MilkyWay {

    private const val DEG = Math.PI / 180.0

    // ---- the raster ----------------------------------------------------------------------------

    /**
     * How wide a cell is, in degrees, on both axes.
     *
     * ⚠️ **One degree is a measured choice, not a round number.** Against the real catalogue, with
     * the median taken over cells near the plane (a median over the whole sky is dominated by empty
     * polar cells and would flatter every resolution equally):
     *
     * | step | cells | bytes | median stars/cell | Poisson noise |
     * |---|---|---|---|---|
     * | 0.5° | 259,200 | 253 kB | 40 | 15.8% |
     * | **1.0°** | **64,800** | **63 kB** | **162** | **7.9%** |
     * | 2.0° | 16,200 | 15 kB | 647 | 3.9% |
     *
     * 7.9% noise sits far under the ~160% signal the Great Rift presents, so one degree is limited
     * by neither. Half a degree triples the asset to buy noise that would swamp the structure; two
     * degrees is coarse enough to read as blocks once the field is narrow.
     */
    const val STEP_DEG = 1.0

    /** Cells around the galactic equator. */
    const val COLUMNS = 360

    /** Cells from pole to pole. */
    const val ROWS = 180

    /** How many cells a complete raster holds. */
    const val CELLS = COLUMNS * ROWS

    // ---- the file -------------------------------------------------------------------------------

    /**
     * The bundled raster's layout, so the builder and the reader are one definition.
     *
     * A sixteen-byte header and then [CELLS] bytes, row 0 at the south galactic pole and column 0 at
     * galactic longitude zero. **64,816 bytes, which deflates to 46 kB** — what the APK actually
     * pays, and an eighth of what was budgeted for it.
     *
     * ⚠️ **LITTLE-endian**, matching the star catalogue beside it: `StarCatalogReader` opens its
     * buffer with `ByteOrder.LITTLE_ENDIAN`, so a reader here that took the JVM's big-endian default
     * would read the version as 256 and refuse a perfectly good file. Two conventions in one
     * directory would be worse than either.
     *
     * ⚠️ **The peak lives in the file rather than in this object.** It is a property of the
     * catalogue the raster was built from, not of the format — rebuild against a deeper star tier
     * and every density changes. A constant here would be a number that silently stopped being true
     * the first time the builder was re-run, and nothing would report it.
     */
    const val MAGIC = 0x4D574159 // "MWAY"
    const val FILE_VERSION = 1
    const val HEADER_BYTES = 16
    const val OFF_MAGIC = 0
    const val OFF_VERSION = 4    // uint16
    const val OFF_COLUMNS = 6    // uint16
    const val OFF_ROWS = 8       // uint16
    const val OFF_RESERVED = 10  // uint16
    const val OFF_PEAK = 12      // float32, stars per square degree that byte 255 stands for

    /** How large a complete raster file is. */
    const val FILE_BYTES = HEADER_BYTES + CELLS

    // ---- the galactic frame --------------------------------------------------------------------

    /**
     * The north galactic pole and the ascending node, in the J2000 realisation of the IAU 1958
     * galactic frame.
     *
     * ⚠️ **The raster is stored in galactic coordinates and that is a deliberate choice.** The
     * structure being stored is aligned with this frame — the plane runs along a single row — so a
     * galactic raster spends its resolution where the detail is, and every cell in the busiest part
     * of the sky is the same shape. Stored equatorially, the plane would cut diagonally across the
     * grid and the same file would resolve it worse.
     */
    const val POLE_RA_DEG = 192.85948
    const val POLE_DEC_DEG = 27.12825

    /** Galactic longitude of the north celestial pole — what fixes the zero of longitude. */
    const val NODE_L_DEG = 122.93192

    /** A direction in galactic coordinates: longitude 0..360, latitude -90..+90. */
    class Galactic(val longitudeDeg: Double, val latitudeDeg: Double)

    /** Equatorial (J2000) to galactic. */
    fun galacticOf(rightAscensionDeg: Double, declinationDeg: Double): Galactic {
        val ra = rightAscensionDeg * DEG
        val dec = declinationDeg * DEG
        val poleRa = POLE_RA_DEG * DEG
        val poleDec = POLE_DEC_DEG * DEG
        val sinB = sin(poleDec) * sin(dec) + cos(poleDec) * cos(dec) * cos(ra - poleRa)
        val y = cos(dec) * sin(ra - poleRa)
        val x = cos(poleDec) * sin(dec) - sin(poleDec) * cos(dec) * cos(ra - poleRa)
        val l = NODE_L_DEG * DEG - atan2(y, x)
        return Galactic(wrapLongitude(l / DEG), asin(sinB.coerceIn(-1.0, 1.0)) / DEG)
    }

    /**
     * Galactic back to equatorial (J2000).
     *
     * ⚠️ Exists so [galacticOf] can be checked by round-tripping rather than against numbers typed
     * in from a reference — a transform whose only test is a handful of remembered coordinates is
     * one wrong constant away from being confidently wrong everywhere.
     */
    fun equatorialOf(longitudeDeg: Double, latitudeDeg: Double): DoubleArray {
        val l = longitudeDeg * DEG
        val b = latitudeDeg * DEG
        val poleRa = POLE_RA_DEG * DEG
        val poleDec = POLE_DEC_DEG * DEG
        val node = NODE_L_DEG * DEG
        val sinDec = sin(poleDec) * sin(b) + cos(poleDec) * cos(b) * cos(node - l)
        val dec = asin(sinDec.coerceIn(-1.0, 1.0))
        val y = cos(b) * sin(node - l)
        val x = cos(poleDec) * sin(b) - sin(poleDec) * cos(b) * cos(node - l)
        val ra = atan2(y, x) + poleRa
        return doubleArrayOf(wrapLongitude(ra / DEG), dec / DEG)
    }

    private fun wrapLongitude(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    // ---- the encoding --------------------------------------------------------------------------

    /**
     * Turn a density into the byte the raster stores, given the peak the whole raster reaches.
     *
     * ⚠️ **Square-root scaled, and the difference is nearly tenfold.** Measured over the real
     * 64,800-cell raster, whose densities run from 0 to 717 stars per square degree, the worst
     * relative error a single byte forces is **55.2% stored linearly and 5.7% stored square-root
     * scaled**. Linear spends almost all of its 256 steps on the bright end, where the eye cannot
     * tell them apart, and leaves the faint high-latitude sky — which is most of the sky, and the
     * half where a step is visible as banding — with a handful of levels.
     *
     * 5.7% also sits comfortably under the 7.9% Poisson noise of the counting itself, which is where
     * an encoding wants to be: not the thing limiting the answer.
     */
    fun encodeDensity(density: Double, peak: Double): Int {
        if (peak <= 0.0 || density <= 0.0) return 0
        return (255.0 * sqrt((density / peak).coerceIn(0.0, 1.0))).roundToInt().coerceIn(0, 255)
    }

    /** The inverse of [encodeDensity]. */
    fun decodeDensity(raw: Int, peak: Double): Double {
        val v = (raw and 0xFF) / 255.0
        return v * v * peak
    }

    // ---- sampling ------------------------------------------------------------------------------

    /**
     * The density in one direction, interpolated between the four cells around it.
     *
     * ⚠️ **Bilinear rather than nearest, and that is what stops a mosaic.** A one-degree cell is
     * three times the width of the full Moon; drawn as a flat block the glow would read as a tiled
     * wall rather than as the sky. Interpolating also handles the convergence at the poles, where a
     * cell's true solid angle collapses — the answer stays continuous because it is a weighted
     * average of neighbours rather than a claim about a cell's area.
     *
     * ⚠️ **Longitude wraps and latitude clamps, and getting either wrong is silent.** Column 359 and
     * column 0 are neighbours on the sky, so a sample at l = 359.7° must blend across the seam or a
     * one-degree stripe of wrong values runs pole to pole down the middle of Sagittarius. Latitude
     * has no such neighbour: past the pole there is nothing to blend with, so the edge row is held.
     *
     * @param cells the raster, [CELLS] bytes, row 0 at the south galactic pole.
     * @param peak the density the byte 255 stands for, carried with the raster.
     */
    fun sample(cells: ByteArray, peak: Double, longitudeDeg: Double, latitudeDeg: Double): Double {
        if (cells.size < CELLS) return 0.0
        // Cell centres sit half a step in, so a sample exactly at a centre must weight it fully.
        val fx = wrapLongitude(longitudeDeg) / STEP_DEG - 0.5
        val fy = (latitudeDeg.coerceIn(-90.0, 90.0) + 90.0) / STEP_DEG - 0.5

        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0

        val c00 = at(cells, x0, y0)
        val c10 = at(cells, x0 + 1, y0)
        val c01 = at(cells, x0, y0 + 1)
        val c11 = at(cells, x0 + 1, y0 + 1)

        val top = c00 + (c10 - c00) * tx
        val bottom = c01 + (c11 - c01) * tx
        // ⚠️ Interpolated in the ENCODED domain and decoded once. The encoding is a curve, so
        // decoding the four corners first and averaging those gives a different — and systematically
        // brighter — answer than averaging first. Either is defensible; doing it in one place is
        // what matters, and this way is also three fewer squarings per sample.
        val raw = top + (bottom - top) * ty
        val v = (raw / 255.0).coerceIn(0.0, 1.0)
        return v * v * peak
    }

    private fun at(cells: ByteArray, column: Int, row: Int): Double {
        val x = ((column % COLUMNS) + COLUMNS) % COLUMNS
        val y = row.coerceIn(0, ROWS - 1)
        return (cells[y * COLUMNS + x].toInt() and 0xFF).toDouble()
    }

    // ---- drawing -------------------------------------------------------------------------------

    /**
     * The density below which nothing is drawn at all.
     *
     * Measured: the galactic poles sit at 21–24 stars per square degree and the median cell over the
     * whole raster is 38. A floor of 40 therefore leaves roughly half the sky genuinely black, which
     * is what the real sky looks like — the naked-eye Milky Way covers well under half of it, and a
     * glow smeared faintly over everything would read as a dirty lens rather than as our galaxy.
     */
    const val FAINTEST_DENSITY = 40.0

    /**
     * The density at which the glow reaches [MAX_OPACITY] and stops brightening.
     *
     * 400 is the 99.9th percentile of the real raster, so the Carina–Crux peak saturates and
     * everything else has the full range to move in. Chosen over the true maximum of 717 because a
     * ceiling set by the single brightest cell wastes most of the scale on cells that do not exist.
     */
    const val BRIGHTEST_DENSITY = 400.0

    /**
     * How opaque the brightest part of the glow may be.
     *
     * ⚠️ The Milky Way is drawn UNDER the stars, so this is bounded by legibility rather than by
     * realism: a glow that competes with the star field defeats the chart it is decorating. Tunable
     * from a screenshot — it is the one constant here that no measurement can settle, because it is
     * a judgement about looking at a phone rather than about the sky.
     */
    const val MAX_OPACITY = 0.34

    /**
     * Density to how strongly to paint it, 0..[MAX_OPACITY].
     *
     * Square-rooted for the same reason the storage is: the eye's response to a faint glow is far
     * from linear, and a linear ramp leaves the whole outer Milky Way looking like an abrupt edge
     * around a bright core.
     */
    fun opacity(density: Double): Double {
        // ⚠️ The floor and the ceiling are BOTH the coerce, and there is deliberately no separate
        // `if (density <= FAINTEST) return 0.0` in front of it. The first version had one, and a
        // negative test found it could never fire — anything under the floor gives a negative `t`,
        // which the coerce already takes to zero. Two mechanisms for one rule, one of them dead.
        val span = BRIGHTEST_DENSITY - FAINTEST_DENSITY
        val t = ((density - FAINTEST_DENSITY) / span).coerceIn(0.0, 1.0)
        return sqrt(t) * MAX_OPACITY
    }
}
