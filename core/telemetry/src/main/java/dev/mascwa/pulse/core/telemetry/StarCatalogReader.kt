package dev.mascwa.pulse.core.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos

/**
 * Reading the packed catalogue: which stars are in these tiles, down to this magnitude.
 *
 * ⚠️ **Pure JVM on purpose — it takes a [ByteBuffer] and knows nothing about where the bytes came
 * from.** On a phone that buffer is an `AssetManager` mapping; in a test it is a file read straight
 * off disk; on the desktop it would be a classpath resource. Keeping the platform out means the
 * decoder can be run against the REAL catalogue here rather than merely compiled, which is the
 * difference between a format that is checked and one that is hoped for.
 *
 * ## ⚠️ Nothing here allocates per star
 *
 * A wide view decodes a few thousand stars sixty times a second while somebody drags. Producing an
 * object apiece would make the garbage collector the frame budget, so results land in a [Sink] of
 * primitive arrays that the caller keeps and reuses. That is also why the sink grows rather than
 * being sized per call.
 *
 * ## The two things that make a huge catalogue cheap to read
 *
 * 1. **Tiles.** [SkyGrid.tilesInCone] says which few thousandths of the file the view can see.
 * 2. **Magnitude order within a tile.** [SkyProjection.magnitudeLimit] deepens as the field narrows,
 *    and because a tile's records are sorted brightest first the cut is a binary search on one byte —
 *    so a wide view touches the first handful of records in each tile and never reads the rest.
 *
 * Together the work per frame depends on the FIELD, not on how many stars are on disk.
 */
class StarCatalogReader private constructor(
    private val buffer: ByteBuffer,
    /** How many stars the file holds in total. */
    val starCount: Int,
    /** Tiles the index describes; must match [SkyGrid.tileCount]. */
    val tileCount: Int,
    /** The epoch its positions are referred to, as a year — 2016.0 for Gaia DR3. */
    val epochYear: Double,
    /** The faintest magnitude the file actually holds, so a caller need not guess. */
    val deepestMagnitude: Double,
) {

    /** Why a catalogue could not be opened, in words rather than as a null. */
    sealed interface Outcome {
        data class Ready(val reader: StarCatalogReader) : Outcome
        data class Unusable(val reason: String) : Outcome
    }

    /** Decoded stars, in primitive arrays the caller owns and reuses. */
    class Sink(initialCapacity: Int = 4096) {
        var count: Int = 0
            internal set
        var rightAscensionDeg: DoubleArray = DoubleArray(initialCapacity)
            private set
        var declinationDeg: DoubleArray = DoubleArray(initialCapacity)
            private set
        var magnitude: FloatArray = FloatArray(initialCapacity)
            private set

        /**
         * Gaia's `bp_rp`, or **NaN where none was measured** — about one star in three hundred.
         *
         * ⚠️ NaN rather than a plausible number, because "no colour" is a fact and drawing it as
         * white is the reading side's decision to make. A sentinel that looked like a measurement
         * would remove that choice silently.
         */
        var colourBpRp: FloatArray = FloatArray(initialCapacity)
            private set

        fun clear() { count = 0 }

        internal fun ensure(extra: Int) {
            val needed = count + extra
            if (needed <= rightAscensionDeg.size) return
            var size = rightAscensionDeg.size
            while (size < needed) size *= 2
            rightAscensionDeg = rightAscensionDeg.copyOf(size)
            declinationDeg = declinationDeg.copyOf(size)
            magnitude = magnitude.copyOf(size)
            colourBpRp = colourBpRp.copyOf(size)
        }
    }

    /**
     * Fill [sink] with every star in [tiles] brighter than [magnitudeLimit].
     *
     * @param yearsFromEpoch how far to carry each star by its own proper motion. Zero leaves
     *   positions exactly as catalogued; the caller works it out from [epochYear] and the date it is
     *   drawing. Over a human lifetime this moves almost nothing and it is what makes a chart of the
     *   far past or future honest rather than decorative.
     * @return how many stars were added.
     */
    fun read(
        tiles: IntArray,
        magnitudeLimit: Double,
        sink: Sink,
        yearsFromEpoch: Double = 0.0,
    ): Int {
        val cut = StarCatalogFormat.encodeMagnitude(magnitudeLimit)
        val recordsAt = StarCatalogFormat.recordsOffset(tileCount)
        var added = 0

        for (tile in tiles) {
            if (tile < 0 || tile >= tileCount) continue
            val from = tileStart(tile)
            val until = tileStart(tile + 1)
            if (until <= from) continue

            val stop = firstFainterThan(from, until, cut, recordsAt)
            if (stop <= from) continue

            val bounds = SkyGrid.boundsOf(tile)
            sink.ensure(stop - from)
            for (i in from until stop) {
                val at = (recordsAt + i.toLong() * StarCatalogFormat.RECORD_BYTES).toInt()
                var ra = StarCatalogFormat.decodeRa(buffer.getShort(at).toInt() and 0xFFFF, bounds)
                var dec = StarCatalogFormat.decodeDec(buffer.getShort(at + 2).toInt() and 0xFFFF, bounds)
                if (yearsFromEpoch != 0.0) {
                    val pmRa = StarCatalogFormat.decodeProperMotion(buffer.get(at + 6).toInt())
                    val pmDec = StarCatalogFormat.decodeProperMotion(buffer.get(at + 7).toInt())
                    // ⚠️ Gaia's `pmra` is mu-alpha-STAR — it already carries the cos(dec) factor, so
                    // recovering a change in right ascension means dividing it back out. Forgetting
                    // that is invisible at the equator and grows without limit toward the poles,
                    // which is exactly where it would be hardest to notice.
                    val shrink = cos(Math.toRadians(dec)).let { if (abs(it) < 1e-6) 1e-6 else it }
                    ra += pmRa * yearsFromEpoch / (MAS_PER_DEGREE * shrink)
                    dec += pmDec * yearsFromEpoch / MAS_PER_DEGREE
                    dec = dec.coerceIn(-90.0, 90.0)
                    ra = ((ra % 360.0) + 360.0) % 360.0
                }
                val slot = sink.count + added
                sink.rightAscensionDeg[slot] = ra
                sink.declinationDeg[slot] = dec
                sink.magnitude[slot] =
                    StarCatalogFormat.decodeMagnitude(buffer.get(at + 4).toInt()).toFloat()
                sink.colourBpRp[slot] =
                    StarCatalogFormat.decodeColour(buffer.get(at + 5).toInt())?.toFloat() ?: Float.NaN
                added++
            }
            sink.count += added
            added = 0
        }
        return sink.count
    }

    /** How many stars a tile holds, for diagnostics and for sizing a sink. */
    fun tileSize(tile: Int): Int =
        if (tile < 0 || tile >= tileCount) 0 else tileStart(tile + 1) - tileStart(tile)

    private fun tileStart(tile: Int): Int =
        buffer.getInt(StarCatalogFormat.tileIndexOffset(tile).toInt())

    /**
     * The first record in `[from, until)` too faint to draw.
     *
     * ⚠️ A binary search, and it is only valid because the builder sorts each tile brightest first —
     * which is why that ordering is verified when the file is written and asserted in the format's
     * own tests. Unsorted, this would silently cut a tile short.
     */
    private fun firstFainterThan(from: Int, until: Int, cut: Int, recordsAt: Long): Int {
        var lo = from
        var hi = until
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val at = (recordsAt + mid.toLong() * StarCatalogFormat.RECORD_BYTES).toInt()
            val magnitude = buffer.get(at + 4).toInt() and 0xFF
            if (magnitude <= cut) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val MAS_PER_DEGREE = 3_600_000.0

        /**
         * Open a catalogue, or say why not.
         *
         * ⚠️ **The header is checked against [SkyGrid], not merely parsed.** A file built under a
         * different tiling reads perfectly and puts every star in the wrong part of the sky, so a
         * mismatch has to be an error rather than something a renderer discovers by looking odd.
         */
        fun open(bytes: ByteBuffer): Outcome {
            val buffer = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.capacity() < StarCatalogFormat.HEADER_BYTES) {
                return Outcome.Unusable("the file is only ${buffer.capacity()} bytes — it holds no header")
            }
            for (i in StarCatalogFormat.MAGIC.indices) {
                if (buffer.get(i) != StarCatalogFormat.MAGIC[i]) {
                    return Outcome.Unusable("this is not a star catalogue — the first four bytes are wrong")
                }
            }
            val version = buffer.getShort(StarCatalogFormat.OFF_VERSION).toInt() and 0xFFFF
            if (version != StarCatalogFormat.VERSION) {
                return Outcome.Unusable(
                    "the catalogue is version $version and this build reads version " +
                        "${StarCatalogFormat.VERSION}",
                )
            }
            val bands = buffer.getShort(StarCatalogFormat.OFF_BANDS).toInt() and 0xFFFF
            val tiles = buffer.getInt(StarCatalogFormat.OFF_TILE_COUNT)
            if (bands != SkyGrid.BANDS || tiles != SkyGrid.tileCount) {
                return Outcome.Unusable(
                    "the catalogue was built for a different tiling (band$bands/$tiles, this build " +
                        "expects ${SkyGrid.FORMAT_KEY}) — every star would land in the wrong place",
                )
            }
            val recordBytes = buffer.getShort(StarCatalogFormat.OFF_RECORD_BYTES).toInt() and 0xFFFF
            if (recordBytes != StarCatalogFormat.RECORD_BYTES) {
                return Outcome.Unusable("records are $recordBytes bytes, this build reads ${StarCatalogFormat.RECORD_BYTES}")
            }
            val stars = buffer.getInt(StarCatalogFormat.OFF_STAR_COUNT)
            if (stars < 0) return Outcome.Unusable("the header claims $stars stars")
            val expected = StarCatalogFormat.expectedBytes(tiles, stars)
            if (buffer.capacity().toLong() != expected) {
                return Outcome.Unusable(
                    "the file is ${buffer.capacity()} bytes but its header describes $expected — it is truncated or padded",
                )
            }
            val epoch = buffer.getInt(StarCatalogFormat.OFF_EPOCH_MILLIYEAR) / 1000.0
            val deepest = buffer.getInt(StarCatalogFormat.OFF_DEEPEST_MILLIMAG) / 1000.0
            return Outcome.Ready(StarCatalogReader(buffer, stars, tiles, epoch, deepest))
        }
    }
}
