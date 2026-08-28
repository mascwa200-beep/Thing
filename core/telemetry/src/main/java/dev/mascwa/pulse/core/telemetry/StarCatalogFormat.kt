package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The packed star-catalogue file: one definition, read by the app and written by the builder.
 *
 * ⚠️ **This exists so the two halves cannot disagree.** A builder in Python and a reader in Kotlin
 * that each carry their own idea of where the magnitude byte sits do not fail — they produce a sky
 * of plausible stars in slightly wrong places, or a whole catalogue shifted by one field, and
 * nothing in either language notices. So every constant and every codec is here, `tools/sky/` reads
 * them out of this file rather than restating them, and the round trip is tested.
 *
 * ## Layout
 *
 * ```
 * header        32 bytes
 * tile index    (tileCount + 1) x uint32   record index where each tile starts; last == starCount
 * records       starCount x 8 bytes        grouped by tile, sorted BRIGHTEST FIRST within a tile
 * ```
 *
 * Little-endian throughout, because both ends are little-endian and saying so once is cheaper than
 * a byte-order field nobody would ever exercise.
 *
 * ⚠️ **Sorted by magnitude within each tile, and the renderer depends on it.** The view's magnitude
 * cut ([SkyProjection.magnitudeLimit]) is what keeps the drawn count in the low thousands however
 * deep the file goes — and it only helps if the reader can stop early. Sorted, the cut is a binary
 * search on one byte and the rest of the tile is never touched; unsorted, every query reads every
 * record it has an index for, and the whole scheme collapses.
 *
 * ## Eight bytes a star, each one measured rather than guessed
 *
 * | bytes | field | why that size |
 * |---|---|---|
 * | 0-1 | right ascension within the tile | 0.16" at the equator, and the divisions scale with the cosine so it stays about that everywhere |
 * | 2-3 | declination within the tile | 0.15" |
 * | 4 | magnitude | 1/14 of a magnitude; differences that small are invisible |
 * | 5 | colour, or 255 for "not measured" | 0.024 in bp_rp against band edges 0.26 apart |
 * | 6-7 | proper motion, one byte each | see [PM_SCALE] — this is the one that took a measurement |
 *
 * At sixteen and a half million stars that is 135 MB. Ten bytes — full 16-bit proper motion — would
 * be 168 MB for a precision nobody alive would see, which is the trade [PM_SCALE] documents.
 */
object StarCatalogFormat {

    /** `SKYC`. */
    val MAGIC = byteArrayOf(0x53, 0x4B, 0x59, 0x43)

    const val VERSION = 1
    const val HEADER_BYTES = 32
    const val RECORD_BYTES = 8

    // ---- header fields, by byte offset -----------------------------------------------------

    const val OFF_MAGIC = 0
    const val OFF_VERSION = 4          // uint16
    const val OFF_BANDS = 6            // uint16 — must equal SkyGrid.BANDS
    const val OFF_TILE_COUNT = 8       // uint32 — must equal SkyGrid.tileCount
    const val OFF_STAR_COUNT = 12      // uint32
    const val OFF_RECORD_BYTES = 16    // uint16
    const val OFF_RESERVED = 18        // uint16
    const val OFF_EPOCH_MILLIYEAR = 20 // uint32 — 2016000 for Gaia's J2016.0
    const val OFF_DEEPEST_MILLIMAG = 24 // int32 — the faintest magnitude the file actually holds
    const val OFF_RESERVED_2 = 28      // uint32

    /** Where the tile index begins. */
    fun tileIndexOffset(tile: Int): Long = HEADER_BYTES + tile.toLong() * 4L

    /** Where the records begin, given how many tiles the index describes. */
    fun recordsOffset(tileCount: Int): Long = HEADER_BYTES + (tileCount.toLong() + 1L) * 4L

    /** Where one record begins. */
    fun recordOffset(tileCount: Int, recordIndex: Int): Long =
        recordsOffset(tileCount) + recordIndex.toLong() * RECORD_BYTES

    /** Total size of a catalogue, so a builder can check what it wrote and a reader what it opened. */
    fun expectedBytes(tileCount: Int, starCount: Int): Long =
        recordsOffset(tileCount) + starCount.toLong() * RECORD_BYTES

    // ---- magnitude --------------------------------------------------------------------------

    /** Sirius is −1.46 and nothing is brighter, so −2 is the floor with room to spare. */
    const val MAG_OFFSET = -2.0

    /** 1/14 of a magnitude a step, which reaches 16.2 in a byte — past any catalogue this ships. */
    const val MAG_SCALE = 14.0

    fun encodeMagnitude(magnitude: Double): Int =
        halfUp((magnitude - MAG_OFFSET) * MAG_SCALE).coerceIn(0, 255)

    fun decodeMagnitude(raw: Int): Double = (raw and 0xFF) / MAG_SCALE + MAG_OFFSET

    /** The faintest magnitude this encoding can express, which is what a full byte means. */
    val FAINTEST_ENCODABLE: Double get() = decodeMagnitude(255)

    // ---- colour ------------------------------------------------------------------------------

    /**
     * Colour is stored as Gaia's own `bp_rp`, not as B−V.
     *
     * ⚠️ **Converting at build time would be a claim, and an approximate one.** `bp_rp` is what the
     * spacecraft measured; the relation to B−V is empirical and depends on the star. So the
     * measurement is stored and [StarNames.colourArgbFromBpRp] carries band edges calibrated for it
     * — the same six colours, honestly reached from a different scale.
     */
    const val COLOUR_OFFSET = -1.0

    /** 254 steps over −1.0 to +5.0: 0.024 apart, against band edges a quarter apart. */
    const val COLOUR_SCALE = 254.0 / 6.0

    /** No colour was measured. About one star in three hundred, so it earns a value of its own. */
    const val COLOUR_ABSENT = 255

    fun encodeColour(bpRp: Double?): Int {
        if (bpRp == null || !bpRp.isFinite()) return COLOUR_ABSENT
        return halfUp((bpRp - COLOUR_OFFSET) * COLOUR_SCALE).coerceIn(0, 254)
    }

    fun decodeColour(raw: Int): Double? {
        val v = raw and 0xFF
        if (v == COLOUR_ABSENT) return null
        return v / COLOUR_SCALE + COLOUR_OFFSET
    }

    // ---- proper motion -------------------------------------------------------------------------

    /**
     * Proper motion in one byte, on a square law: `mas/yr = sign(v) * v² * PM_SCALE`.
     *
     * ⚠️ **A linear byte cannot do this job and the measurement says why.** Of the 16.8 million stars
     * brighter than magnitude 14, **356 move faster than 1000 mas/yr** and 2,000 faster than 500 —
     * and those few are precisely the ones proper motion exists for. Barnard's Star crosses 10,328
     * mas/yr in declination alone. A linear byte covering ±10,000 would quantise the other sixteen
     * million to 79 mas/yr, which is eight arcseconds a century of error on ordinary stars; a linear
     * byte covering ±500 would clamp Barnard's Star to a twentieth of its real motion.
     *
     * A square law spends its resolution where the stars are. At 0.75 the ceiling is ±12,097 mas/yr,
     * comfortably past the fastest star known, and the absolute error is `sqrt(0.75 x pm)`:
     *
     * | proper motion | error | after a century | after a millennium |
     * |---|---|---|---|
     * | 10 mas/yr | 2.7 | 0.3" | 3" |
     * | 100 | 8.7 | 0.9" | 9" |
     * | 1,000 | 27 | 2.7" | 27" |
     * | 10,000 | 87 | 8.7" | 87" |
     *
     * ⚠️ **The honest limit, stated rather than implied:** at the narrowest field this map allows,
     * a pixel is about nine tenths of an arcsecond, so a century of drift is invisible and a
     * millennium is not. Nobody who uses this will see it. Widening to sixteen bits costs 33 MB on
     * the deep catalogue and is a version bump, if that ever stops being the right trade.
     *
     * ⚠️ Note also that 128,016 stars — one in 130 — have **no** measured proper motion at all, and
     * are stored as zero. That is the right default for drawing and it is still a choice, not a
     * measurement.
     */
    const val PM_SCALE = 0.75

    /** Past the fastest proper motion known, which is Barnard's Star. */
    val PM_MAX: Double get() = 127.0 * 127.0 * PM_SCALE

    fun encodeProperMotion(masPerYear: Double): Int {
        if (!masPerYear.isFinite() || masPerYear == 0.0) return 0
        val v = halfUp(sqrt(abs(masPerYear) / PM_SCALE)).coerceIn(0, 127)
        return (sign(masPerYear).toInt() * v)
    }

    fun decodeProperMotion(raw: Int): Double {
        val v = raw.toByte().toInt()          // sign-extend: the field is a signed byte
        return sign(v.toDouble()) * v.toDouble() * v.toDouble() * PM_SCALE
    }

    // ---- position within a tile ------------------------------------------------------------------

    /**
     * Position is stored as a fraction of the tile it is in, which is what buys sub-arcsecond
     * precision from two bytes: the whole range only has to cover a few degrees, not 360.
     */
    fun encodeRa(raDeg: Double, bounds: SkyGrid.Bounds): Int =
        encodeFraction((norm360(raDeg) - bounds.raLoDeg) / bounds.raSpanDeg)

    fun decodeRa(raw: Int, bounds: SkyGrid.Bounds): Double =
        norm360(bounds.raLoDeg + decodeFraction(raw) * bounds.raSpanDeg)

    fun encodeDec(decDeg: Double, bounds: SkyGrid.Bounds): Int =
        encodeFraction((decDeg - bounds.decLoDeg) / bounds.decSpanDeg)

    fun decodeDec(raw: Int, bounds: SkyGrid.Bounds): Double =
        (bounds.decLoDeg + decodeFraction(raw) * bounds.decSpanDeg).coerceIn(-90.0, 90.0)

    private fun encodeFraction(fraction: Double): Int =
        if (!fraction.isFinite()) 0 else halfUp(fraction * 65535.0).coerceIn(0, 65535)

    private fun decodeFraction(raw: Int): Double = (raw and 0xFFFF) / 65535.0

    // ---- small helpers ---------------------------------------------------------------------------

    /**
     * ⚠️ **Half-up, explicitly, because `kotlin.math.round` is `Math.rint` and rounds ties to
     * EVEN.** This repository has already shipped a defect from that once — a displayed distance
     * whose last digit moved in whichever direction the digit before it happened to be. Here it
     * would be worse than cosmetic: the builder is Python, whose own `round` is also banker's, and a
     * mismatch would put the two halves of the format a single unit apart on exactly the values that
     * land on a tie.
     */
    private fun halfUp(value: Double): Int =
        if (value.isFinite()) floor(value + 0.5).toInt() else 0

    private fun norm360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }
}
