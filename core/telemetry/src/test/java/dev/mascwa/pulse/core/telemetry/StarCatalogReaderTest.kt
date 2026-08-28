package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/**
 * The decoder, against catalogues this test writes itself.
 *
 * ⚠️ **Writing the file here is the point, not a convenience.** The builder is Python and the reader
 * is Kotlin, so the only way to test the decoder against a known truth without a network is to pack
 * bytes to the same specification and read them back. What that leaves unproven is whether the
 * Python writer agrees — which is why `StarCatalogBundleTest` in the app module opens the REAL
 * bundled catalogue, and why `SkyGridParityTest` pins the tiling the two share.
 */
class StarCatalogReaderTest {

    private data class Star(
        val ra: Double,
        val dec: Double,
        val magnitude: Double,
        val bpRp: Double?,
        val pmRa: Double = 0.0,
        val pmDec: Double = 0.0,
    )

    /** Pack a catalogue exactly as `tools/sky/build_catalogue.py` does. */
    private fun write(stars: List<Star>, epochYear: Double = 2016.0, deepest: Double = 12.0): ByteBuffer {
        val byTile = stars.groupBy { SkyGrid.tileOf(it.ra, it.dec) }
            .mapValues { (_, v) -> v.sortedBy { it.magnitude } }
        val total = stars.size
        val tiles = SkyGrid.tileCount
        val size = StarCatalogFormat.expectedBytes(tiles, total).toInt()
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        StarCatalogFormat.MAGIC.forEachIndexed { i, b -> buffer.put(i, b) }
        buffer.putShort(StarCatalogFormat.OFF_VERSION, StarCatalogFormat.VERSION.toShort())
        buffer.putShort(StarCatalogFormat.OFF_BANDS, SkyGrid.BANDS.toShort())
        buffer.putInt(StarCatalogFormat.OFF_TILE_COUNT, tiles)
        buffer.putInt(StarCatalogFormat.OFF_STAR_COUNT, total)
        buffer.putShort(StarCatalogFormat.OFF_RECORD_BYTES, StarCatalogFormat.RECORD_BYTES.toShort())
        buffer.putInt(StarCatalogFormat.OFF_EPOCH_MILLIYEAR, (epochYear * 1000).toInt())
        buffer.putInt(StarCatalogFormat.OFF_DEEPEST_MILLIMAG, (deepest * 1000).toInt())

        var running = 0
        val recordsAt = StarCatalogFormat.recordsOffset(tiles).toInt()
        for (tile in 0 until tiles) {
            buffer.putInt(StarCatalogFormat.tileIndexOffset(tile).toInt(), running)
            val bounds = SkyGrid.boundsOf(tile)
            for (s in byTile[tile].orEmpty()) {
                val at = recordsAt + running * StarCatalogFormat.RECORD_BYTES
                buffer.putShort(at, StarCatalogFormat.encodeRa(s.ra, bounds).toShort())
                buffer.putShort(at + 2, StarCatalogFormat.encodeDec(s.dec, bounds).toShort())
                buffer.put(at + 4, StarCatalogFormat.encodeMagnitude(s.magnitude).toByte())
                buffer.put(at + 5, StarCatalogFormat.encodeColour(s.bpRp).toByte())
                buffer.put(at + 6, StarCatalogFormat.encodeProperMotion(s.pmRa).toByte())
                buffer.put(at + 7, StarCatalogFormat.encodeProperMotion(s.pmDec).toByte())
                running++
            }
        }
        buffer.putInt(StarCatalogFormat.tileIndexOffset(tiles).toInt(), running)
        return buffer
    }

    private fun ready(stars: List<Star>): StarCatalogReader {
        val outcome = StarCatalogReader.open(write(stars))
        assertTrue("open said: $outcome", outcome is StarCatalogReader.Outcome.Ready)
        return (outcome as StarCatalogReader.Outcome.Ready).reader
    }

    // ---- the round trip ------------------------------------------------------------------------

    @Test
    fun `every star comes back where it was put, at the magnitude it was given`() {
        val rng = Random(4242)
        val stars = (0 until 3000).map {
            Star(
                ra = rng.nextDouble(0.0, 360.0),
                dec = rng.nextDouble(-89.9, 89.9),
                magnitude = rng.nextDouble(-1.0, 12.0),
                bpRp = if (it % 300 == 0) null else rng.nextDouble(-0.5, 3.5),
            )
        }
        val reader = ready(stars)
        assertEquals(stars.size, reader.starCount)
        assertEquals(2016.0, reader.epochYear, 1e-9)
        assertEquals(12.0, reader.deepestMagnitude, 1e-9)

        val sink = StarCatalogReader.Sink()
        reader.read(IntArray(SkyGrid.tileCount) { it }, magnitudeLimit = 99.0, sink = sink)
        assertEquals("every star should have been read back", stars.size, sink.count)

        // ⚠️ Paired by POSITION, not by sorted magnitude. My first version of this sorted both sides
        // by magnitude and paired them off, which is meaningless: the format stores magnitude in one
        // byte at 1/14 steps, so three thousand stars across thirteen magnitudes share about 182
        // distinct values — sixteen apiece, in arbitrary order. It reported a star "moving" fifty
        // degrees when nothing was wrong with the reader at all.
        val byTile = stars.groupBy { SkyGrid.tileOf(it.ra, it.dec) }
        for (i in 0 until sink.count) {
            val ra = sink.rightAscensionDeg[i]
            val dec = sink.declinationDeg[i]
            val candidates = byTile[SkyGrid.tileOf(ra, dec)].orEmpty()
            assertTrue("nothing was written into the tile this star decoded into", candidates.isNotEmpty())
            val original = candidates.minByOrNull { SkyProjection.separationDeg(it.ra, it.dec, ra, dec) }!!
            val separation = SkyProjection.separationDeg(original.ra, original.dec, ra, dec) * 3600.0
            assertTrue("a star moved $separation\"", separation < 0.5)
            assertTrue(
                "magnitude ${original.magnitude} -> ${sink.magnitude[i]}",
                abs(original.magnitude - sink.magnitude[i]) < 0.05,
            )
            val colour = if (sink.colourBpRp[i].isNaN()) null else sink.colourBpRp[i].toDouble()
            if (original.bpRp == null) {
                assertTrue("an unmeasured colour came back as $colour", colour == null)
            } else {
                assertTrue("colour ${original.bpRp} -> $colour", abs(original.bpRp - (colour ?: 99.0)) < 0.03)
            }
        }
    }

    // ---- the magnitude cut, which is what makes a big file cheap ---------------------------------

    @Test
    fun `the cut stops a tile early instead of reading all of it`() {
        // One tile, a hundred stars evenly spread in magnitude.
        val bounds = SkyGrid.boundsOf(SkyGrid.tileOf(45.0, 20.0))
        val stars = (0 until 100).map {
            Star(
                ra = bounds.raLoDeg + bounds.raSpanDeg * 0.5,
                dec = bounds.decLoDeg + bounds.decSpanDeg * 0.5,
                magnitude = it / 10.0,          // 0.0 to 9.9
                bpRp = 0.5,
            )
        }
        val reader = ready(stars)
        val tiles = intArrayOf(SkyGrid.tileOf(45.0, 20.0))
        val sink = StarCatalogReader.Sink()

        for ((limit, expected) in listOf(9.9 to 100, 5.0 to 51, 2.0 to 21, 0.0 to 1, -1.0 to 0)) {
            sink.clear()
            reader.read(tiles, magnitudeLimit = limit, sink = sink)
            assertEquals("a cut at $limit", expected, sink.count)
            for (i in 0 until sink.count) {
                assertTrue("a star fainter than the cut came through", sink.magnitude[i] <= limit + 0.05)
            }
        }
    }

    @Test
    fun `tiles that were not asked for are not read`() {
        val here = Star(10.0, 10.0, 3.0, 0.4)
        val faraway = Star(200.0, -60.0, 3.0, 0.4)
        val reader = ready(listOf(here, faraway))
        val sink = StarCatalogReader.Sink()
        reader.read(intArrayOf(SkyGrid.tileOf(here.ra, here.dec)), 9.0, sink)
        assertEquals(1, sink.count)
        assertEquals(10.0, sink.rightAscensionDeg[0], 0.01)
        // A tile id outside the file is ignored rather than throwing: a caller computing tiles from
        // a different geometry is a header problem, and the header is where it is reported.
        sink.clear()
        reader.read(intArrayOf(-5, SkyGrid.tileCount + 99), 9.0, sink)
        assertEquals(0, sink.count)
    }

    // ---- proper motion ---------------------------------------------------------------------------

    /**
     * ⚠️ The cos(dec) factor, which is the classic way to get this wrong. Gaia publishes `pmra`
     * already multiplied by the cosine of the declination, so recovering a change in right ascension
     * means dividing it back out — an error that is exactly zero at the equator and unbounded at the
     * pole, i.e. invisible wherever anybody would first check.
     */
    @Test
    fun `proper motion moves a star by the right amount, at any declination`() {
        val cases = listOf(0.0 to 0.0, 45.0 to 1000.0, 70.0 to 500.0, -80.0 to 2000.0)
        for ((dec, pmRa) in cases) {
            val star = Star(ra = 100.0, dec = dec, magnitude = 4.0, bpRp = 0.5, pmRa = pmRa, pmDec = 0.0)
            val reader = ready(listOf(star))
            val tiles = intArrayOf(SkyGrid.tileOf(star.ra, star.dec))

            // ⚠️ **The base position is READ, not assumed to be 100.0.** Two separate quantisations
            // live in this record and only one of them is under test here: a position is stored as a
            // fraction of its tile and comes back about a twentieth of an arcsecond away. Asserting
            // against the round number I typed in conflates the two and fails for the wrong reason —
            // which is exactly what my first version of this did.
            val atEpoch = StarCatalogReader.Sink()
            reader.read(tiles, 9.0, atEpoch, yearsFromEpoch = 0.0)
            val moved = StarCatalogReader.Sink()
            reader.read(tiles, 9.0, moved, yearsFromEpoch = 100.0)
            assertEquals(1, atEpoch.count)
            assertEquals(1, moved.count)

            // ⚠️ And the expectation comes from the STORED proper motion, not the one handed in: the
            // square law quantises 1000 mas/yr to 1026.75, which is 27 mas/yr — three arcseconds over
            // a century, precisely the accuracy the format documents.
            val stored = StarCatalogFormat.decodeProperMotion(StarCatalogFormat.encodeProperMotion(pmRa))
            // 100 years at `stored` mas/yr is that many mas of TRUE angle; in right ascension it is
            // divided by the cosine.
            val expectedShift = stored * 100.0 / 3_600_000.0 / Math.cos(Math.toRadians(dec))
            // ⚠️ The tolerance is DERIVED rather than picked, because a fixed one was wrong twice.
            // The reader divides by the cosine of the DECODED declination, which sits up to a
            // twentieth of an arcsecond from the round number typed in above; near the pole that
            // shifts 1/cos noticeably, and at dec -80 it moves the answer by 5e-7 degrees. One part
            // in ten thousand clears that everywhere while still being four orders of magnitude
            // tighter than the defect this test exists for — dropping the cosine entirely is a
            // factor of 5.8 at dec -80.
            assertEquals(
                "at dec $dec",
                expectedShift,
                moved.rightAscensionDeg[0] - atEpoch.rightAscensionDeg[0],
                abs(expectedShift) * 1e-4 + 1e-9,
            )
            assertEquals(
                "declination should not have moved",
                atEpoch.declinationDeg[0],
                moved.declinationDeg[0],
                1e-12,
            )
        }
    }

    @Test
    fun `zero years leaves a star exactly where it was catalogued`() {
        val star = Star(100.0, 45.0, 4.0, 0.5, pmRa = 5000.0, pmDec = -3000.0)
        val reader = ready(listOf(star))
        val sink = StarCatalogReader.Sink()
        reader.read(intArrayOf(SkyGrid.tileOf(star.ra, star.dec)), 9.0, sink, yearsFromEpoch = 0.0)
        assertEquals(100.0, sink.rightAscensionDeg[0], 0.001)
        assertEquals(45.0, sink.declinationDeg[0], 0.001)
    }

    // ---- refusing a file it must not read ---------------------------------------------------------

    /**
     * ⚠️ Every one of these is a case where reading on would produce a **plausible wrong sky**
     * rather than an error, so each has to be a refusal with a sentence rather than a null.
     */
    @Test
    fun `a catalogue built for another tiling is refused, not read`() {
        val buffer = write(listOf(Star(1.0, 1.0, 3.0, 0.5)))
        buffer.putShort(StarCatalogFormat.OFF_BANDS, (SkyGrid.BANDS + 8).toShort())
        val outcome = StarCatalogReader.open(buffer)
        assertTrue(outcome is StarCatalogReader.Outcome.Unusable)
        assertTrue(
            "the reason should name the tiling: ${(outcome as StarCatalogReader.Outcome.Unusable).reason}",
            outcome.reason.contains("tiling"),
        )
    }

    @Test
    fun `a truncated or padded file is refused`() {
        val whole = write(listOf(Star(1.0, 1.0, 3.0, 0.5), Star(200.0, -20.0, 4.0, 1.0)))
        val short = ByteBuffer.allocate(whole.capacity() - 1).order(ByteOrder.LITTLE_ENDIAN)
        whole.rewind()
        for (i in 0 until short.capacity()) short.put(i, whole.get(i))
        assertTrue(StarCatalogReader.open(short) is StarCatalogReader.Outcome.Unusable)
    }

    @Test
    fun `something that is not a catalogue at all is refused`() {
        val rubbish = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(StarCatalogReader.open(rubbish) is StarCatalogReader.Outcome.Unusable)
        assertTrue(StarCatalogReader.open(ByteBuffer.allocate(3)) is StarCatalogReader.Outcome.Unusable)
    }

    @Test
    fun `a future version is refused rather than guessed at`() {
        val buffer = write(listOf(Star(1.0, 1.0, 3.0, 0.5)))
        buffer.putShort(StarCatalogFormat.OFF_VERSION, (StarCatalogFormat.VERSION + 1).toShort())
        val outcome = StarCatalogReader.open(buffer)
        assertTrue(outcome is StarCatalogReader.Outcome.Unusable)
        assertTrue((outcome as StarCatalogReader.Outcome.Unusable).reason.contains("version"))
    }

    // ---- the sink -----------------------------------------------------------------------------------

    @Test
    fun `the sink grows and is reused rather than reallocated per call`() {
        val rng = Random(7)
        val stars = (0 until 5000).map {
            Star(rng.nextDouble(0.0, 360.0), rng.nextDouble(-89.0, 89.0), rng.nextDouble(0.0, 11.0), 0.6)
        }
        val reader = ready(stars)
        val sink = StarCatalogReader.Sink(initialCapacity = 8)
        val all = IntArray(SkyGrid.tileCount) { it }
        reader.read(all, 99.0, sink)
        assertEquals(5000, sink.count)
        val arrayAfterFirst = sink.rightAscensionDeg
        sink.clear()
        reader.read(all, 99.0, sink)
        assertEquals(5000, sink.count)
        assertTrue("a second read should not have reallocated", sink.rightAscensionDeg === arrayAfterFirst)
    }
}
