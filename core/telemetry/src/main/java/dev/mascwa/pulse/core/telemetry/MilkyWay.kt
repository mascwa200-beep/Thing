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
 * The plan said to sum the *flux* of the stars in each direction. Measured both ways over the
 * bundled catalogue **as it stood at the time — 3,087,821 stars to G = 12** — binned by galactic
 * latitude:
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
 * ⚠️ Those four numbers are **left at the depth they were measured at, deliberately**. They are an
 * argument about which of two instruments to use, and that argument does not turn on how deep the
 * catalogue goes; re-measuring flux needs the packed catalogue in hand, and quoting a new density
 * beside an old flux would compare two different sums — the exact mistake
 * `scratchpad/sky/measure_milkyway.py` was written to stop. The figures below, which describe the
 * raster this file actually reads, are the ones kept current.
 *
 * ## ⚠️ Dust shows up as ABSENCE, which is why this works at all
 *
 * The natural worry is that a magnitude-limited catalogue is far too shallow to see a glow made of
 * much fainter stars. Measured on the shipped raster — mean density over |b| ≤ 5°, per whole degree
 * of longitude — it varies **9.63×** along the plane and the structure lands exactly where the real
 * sky puts it: a trough at l = 20–50° bottoming at 1657 /deg² against ~4240 either side — **the
 * Great Rift** — a second trough at l = 142° for the Taurus–Perseus dust at 875, and Carina–Crux at
 * 6088 near l = 289.
 *
 * That is the mechanism. Dust does not dim a star a little; it pushes it below the magnitude cut and
 * takes it out of the count altogether. A rift is therefore the *most* visible thing in a
 * density map, not the least.
 *
 * ## ⚠️ What deepening the catalogue to G<15 did, measured rather than assumed
 *
 * The same mechanism raises a real question the other way round: a deeper cut recovers stars hidden
 * behind dust that a shallower one could not see, so it might have *filled the rifts in*. Measured
 * by the one method above, against the raster this replaced:
 *
 * | | G<12 | G<15 |
 * |---|---|---|
 * | plane / poles | 8.03× | **17.46×** |
 * | variation along the plane | 5.03× | **9.63×** |
 * | Great Rift below its flanks | 2.58× | **2.56×** |
 *
 * **The band roughly doubles in contrast and the rift is untouched** — it is the same trough, twelve
 * times brighter, in a sky twelve times brighter. One thing genuinely changed: the maximum moved
 * from Carina–Crux to **Sagittarius at l = 1°**, which at G<12 was high but not highest. That is the
 * bulge emerging from its own extinction, and it is the one place where a deeper cut visibly
 * *un-hides* something rather than merely scaling everything up.
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
     *
     * ⚠️ That table was measured on the G<12 catalogue, and **deepening to G<15 only strengthens the
     * conclusion**: the same median measured on the shipped raster is **2,130 stars a cell, so 2.2%
     * noise**. Half a degree would now be about 4.3%, which is genuinely usable — but it quadruples
     * a committed asset to sharpen structure whose narrowest real feature is several degrees wide,
     * so the choice stands. The row is not rewritten because the *comparison between step sizes* is
     * what the table is for, and re-measuring one row of it would make the three incomparable.
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

    /**
     * A decoded raster: the cells, and the density that a stored 255 stands for.
     *
     * The two travel together because neither means anything alone — a byte is a fraction of the
     * peak, so handing the cells to a caller that has to remember to fetch the peak separately is
     * one forgotten argument away from a sky scaled by 255 instead of 21,947.
     */
    class Raster(val cells: ByteArray, val peak: Double)

    /**
     * Decode a whole raster file, or refuse it.
     *
     * ⚠️ **Every check here guards a failure that is silent rather than loud.** A file read with the
     * wrong byte order, truncated by a bad copy, or built to a future layout does not throw — it
     * produces a perfectly plausible sky with the Milky Way in the wrong place, or a uniform haze,
     * and there is nothing on screen to say which. The magic and the version catch a rebuild; the
     * dimensions catch a raster built at a different resolution; the length catches truncation; and
     * a peak that is not a positive finite number catches a header read the wrong way round, which
     * is the specific mistake made once already while writing the builder for this file.
     *
     * ⚠️ **Little-endian**, matching `SkyCatalogFormat` and `tools/sky/build_milkyway.py`, which
     * packs with `"<"`. Both ends are stated so neither can drift alone.
     */
    fun readRaster(bytes: ByteArray): Raster? {
        if (bytes.size < FILE_BYTES) return null
        if (u32(bytes, OFF_MAGIC) != MAGIC) return null
        if (u16(bytes, OFF_VERSION) != FILE_VERSION) return null
        if (u16(bytes, OFF_COLUMNS) != COLUMNS) return null
        if (u16(bytes, OFF_ROWS) != ROWS) return null
        val peak = Float.fromBits(u32(bytes, OFF_PEAK)).toDouble()
        if (!peak.isFinite() || peak <= 0.0) return null
        return Raster(bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + CELLS), peak)
    }

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

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
     * The same transform for a direction already held as a unit vector, without allocating.
     *
     * ⚠️ **This is not a convenience wrapper — it exists because the glow pass runs it per screen
     * pixel.** [galacticOf] takes angles, so a caller holding a vector would pay `atan2` and `asin`
     * to make them and then five more trigonometric calls to undo that inside; and it allocates a
     * [Galactic] each time. Written out as three dot products the whole transform is nine
     * multiplications plus the one `atan2` and one `asin` that an angular answer genuinely requires.
     *
     * The three constant vectors are the same rotation [galacticOf] performs, rearranged. Expanding
     * `cos δ cos(α − α_p)` into `v_x cos α_p + v_y sin α_p` turns each of its three expressions into
     * a dot product with a fixed direction:
     *
     * - `sinB` is the projection onto the **pole** itself, which is the definition of latitude;
     * - `y` is the projection onto the **node**, the point where the galactic equator crosses the
     *   celestial one;
     * - `x` is the projection onto the third axis that completes the frame.
     *
     * ⚠️ Computed from [POLE_RA_DEG], [POLE_DEC_DEG] and [NODE_L_DEG] rather than written out as
     * nine literals. Hand-typed they would be a second definition of the frame, free to drift from
     * the first without any test noticing — the two would simply disagree about where the sky is.
     *
     * @param out two doubles: longitude 0..360 then latitude −90..+90.
     */
    fun galacticOfVector(vx: Double, vy: Double, vz: Double, out: DoubleArray) {
        val a = axes
        val sinB = vx * a[0] + vy * a[1] + vz * a[2]
        val y = vx * a[3] + vy * a[4] + vz * a[5]
        val x = vx * a[6] + vy * a[7] + vz * a[8]
        out[0] = wrapLongitude((NODE_L_DEG * DEG - atan2(y, x)) / DEG)
        out[1] = asin(sinB.coerceIn(-1.0, 1.0)) / DEG
    }

    /**
     * Pole, node and third axis, flattened — nine doubles, read in threes by [galacticOfVector].
     *
     * ⚠️ A plain `val`, deliberately, and not `by lazy`. Kotlin's default lazy is
     * `SYNCHRONIZED`: every read goes through a `Lazy` object with a volatile load and a null
     * check, which measured at **28 ns per call** in a loop that only wants a static array — a
     * third of the whole per-pixel budget of the glow pass, spent on re-asking whether a constant
     * has been computed yet. The constants it derives from are `const val`, so they are inlined at
     * compile time and there is no initialisation-order hazard to protect against.
     */
    private val axes: DoubleArray = run {
        val ra = POLE_RA_DEG * DEG
        val dec = POLE_DEC_DEG * DEG
        doubleArrayOf(
            cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec),
            -sin(ra), cos(ra), 0.0,
            -sin(dec) * cos(ra), -sin(dec) * sin(ra), cos(dec),
        )
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

    /**
     * Longitude folded into 0..360.
     *
     * ⚠️ **The two branches above the modulo are not premature — `%` on a `Double` is one of the
     * most expensive arithmetic operations the JVM has**, and this runs twice per screen pixel of
     * the glow pass. Every real caller is already inside one turn of the circle:
     * [galacticOfVector] produces `NODE_L_DEG − atan2(…)`, which spans −57..303, and [sample]'s
     * input is a longitude somebody already had. The modulo survives as the total fallback so that
     * a caller outside that range still gets a right answer rather than a wrong one.
     *
     * The only behavioural difference from the plain modulo is that a negative zero comes back
     * unchanged instead of as positive zero. They compare equal, and every consumer here does
     * arithmetic on it rather than inspecting its sign.
     */
    private fun wrapLongitude(deg: Double): Double {
        if (deg >= 0.0 && deg < 360.0) return deg
        if (deg >= -360.0 && deg < 720.0) return if (deg < 0.0) deg + 360.0 else deg - 360.0
        return ((deg % 360.0) + 360.0) % 360.0
    }

    // ---- the encoding --------------------------------------------------------------------------

    /**
     * Turn a density into the byte the raster stores, given the peak the whole raster reaches.
     *
     * ⚠️ **Square-root scaled, and the difference is an order of magnitude.** Linear spends almost
     * all of its 256 steps on the bright end, where the eye cannot tell them apart, and leaves the
     * faint high-latitude sky — which is most of the sky, and the half where a step is visible as
     * banding — with a handful of levels. Measured on the G<12 raster that settled the choice, the
     * worst relative error a single byte forces was **55.2% stored linearly against 3.6% stored
     * square-root scaled**. `tools/sky/build_milkyway.py` now prints both at whatever depth it is
     * building, so that comparison stays a live measurement rather than a remembered one.
     *
     * On the shipped G<15 raster, whose densities run from 0 to 21,947 stars per square degree, the
     * square-root cost is **5.4%**.
     *
     * ⚠️ **That 5.4% is no longer under the counting noise, and it used to be.** The 3.6% figure sat
     * comfortably beneath the 7.9% Poisson noise of the counting itself, which is where an encoding
     * wants to be — not the thing limiting the answer. Thirteen times as many stars cut that noise
     * to **2.2%**, measured the same way [STEP_DEG]'s table measures it. So the encoding is now the
     * coarser of the two. It is still finer than the eye can see in a glow drawn at a third of full
     * opacity, so this is recorded rather than acted on — but a further depth would want the byte
     * reconsidered before the count.
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
    /**
     * The density at a direction, interpolated between the four cells around it.
     *
     * ⚠️ **Longitude resolution near the poles is deliberately coarse, and it is the builder that
     * makes it so.** An equirectangular cell's solid angle shrinks with the cosine of its latitude,
     * so at |b| = 89.5 a cell covers 0.0087 deg² and holds — measured on the G<12 catalogue that
     * prompted the fix — about 0.16 stars. A count of nought or one is not an estimate of a density:
     * 303 of that row's 360 cells came out empty and the other 57 read about 115 /deg², a speckled
     * ring at each pole that reads as a rendering fault. `tools/sky/build_milkyway.py` therefore
     * averages each row over `1/cos(b)` columns, which covers a constant one degree of great-circle
     * arc at every latitude and preserves the row's total exactly.
     *
     * On the shipped G<15 raster the polar band reads **163 /deg²**, below [FAINTEST_DENSITY]'s 280,
     * so the empty sky is still drawn as empty. ⚠️ That is a property of the PAIR rather than of
     * either number, which is why the builder refuses to write a raster whose poles reach the floor:
     * the twelvefold deeper catalogue raised the poles from 22.9 to 163, and against the old floor
     * of 40 every high-latitude cell would have been painted.
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
        // ⚠️ Two integer divisions replaced by two compares, for the same reason the longitude wrap
        // has a fast path: [sample] calls this four times per screen pixel. Its `column` can only
        // ever be −1..COLUMNS, because it comes from `floor(fx)` and `floor(fx) + 1` where fx spans
        // −0.5..COLUMNS−0.5 — so the wrap is always by exactly one cell. The modulo stays for
        // anything else, so the function is still total.
        val x = when {
            column in 0 until COLUMNS -> column
            column == -1 -> COLUMNS - 1
            column == COLUMNS -> 0
            else -> ((column % COLUMNS) + COLUMNS) % COLUMNS
        }
        val y = row.coerceIn(0, ROWS - 1)
        return (cells[y * COLUMNS + x].toInt() and 0xFF).toDouble()
    }

    // ---- drawing -------------------------------------------------------------------------------

    /**
     * The density below which nothing is drawn at all.
     *
     * Measured on the shipped G<15 raster: the galactic poles sit at 163 stars per square degree and
     * the median cell over the whole raster is 276. A floor of 280 therefore leaves roughly half the
     * sky genuinely black, which is what the real sky looks like — the naked-eye Milky Way covers
     * well under half of it, and a glow smeared faintly over everything would read as a dirty lens
     * rather than as our galaxy.
     *
     * ⚠️ **THIS IS AN ABSOLUTE DENSITY, SO IT BELONGS TO A PARTICULAR CATALOGUE DEPTH, AND THAT IS
     * NOT OBVIOUS FROM ANYTHING ELSE ON THIS PAGE.** It was 40 while the raster came from a G<12
     * catalogue whose poles were 22.9. Deepening to G<15 multiplied the poles by 7.1 and the plane
     * by 15.5. Measured over the shipped raster, cell by cell, that is what the two pairs draw:
     *
     * | | sky drawn at all | at full opacity |
     * |---|---|---|
     * | G<12 raster, the old 40 / 400 | 43.6% | 0.11% |
     * | **G<15 raster, 280 / 10,000** | **50.3%** | **0.10%** |
     * | G<15 raster, the old 40 / 400 | **100%** | **37.5%** |
     *
     * The third row is what swapping the asset alone would have shipped: every direction glowing and
     * more than a third of the sky pinned flat at the cap — a uniform wash with a slab through it,
     * not a galaxy. Nothing in the build could have noticed, because the builder's own contrast
     * check reads 17x on that raster and passes happily.
     *
     * So `tools/sky/build_milkyway.py` now REFUSES to write a raster whose poles reach this floor,
     * and refuses if this number is more than 30% from the raster's own median. Change the depth and
     * the builder tells you what both constants should become.
     */
    const val FAINTEST_DENSITY = 280.0

    /**
     * The density at which the glow reaches [MAX_OPACITY] and stops brightening.
     *
     * 10,000 is the 99.9th percentile of the real raster, so the Carina–Crux peak saturates and
     * everything else has the full range to move in. Chosen over the true maximum of 21,947 because
     * a ceiling set by the single brightest cell wastes most of the scale on cells that do not exist.
     *
     * ⚠️ Absolute, and depth-bound, exactly as [FAINTEST_DENSITY] is — it was 400 against a G<12
     * raster whose 99.9th percentile was 403. Same guard applies.
     */
    const val BRIGHTEST_DENSITY = 10000.0

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
