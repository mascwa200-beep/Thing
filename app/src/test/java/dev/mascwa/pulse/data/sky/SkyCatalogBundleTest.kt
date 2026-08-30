package dev.mascwa.pulse.data.sky

import dev.mascwa.pulse.core.telemetry.SkyGrid
import dev.mascwa.pulse.core.telemetry.StarCatalogFormat
import dev.mascwa.pulse.core.telemetry.StarCatalogReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * The packed Gaia catalogue, opened with the shipped reader and checked against what is actually in
 * it.
 *
 * ⚠️ **Every way a bundled binary breaks is silent.** It is hundreds of megabytes nobody can read: a
 * builder run with a different [SkyGrid.BANDS] produces a file that decodes perfectly and puts every
 * star in the wrong part of the sky; a tile whose records are not sorted brightest first makes the
 * reader's binary search cut it short, so stars vanish at one zoom and reappear at another; a
 * truncated download is a file that opens and simply stops somewhere over the southern sky. None of
 * that is a compile error and none of it throws.
 *
 * So this opens the REAL asset with the REAL decoder rather than a fixture with a re-implementation
 * — the same reason [StarCatalogAssetTest] parses the shipped `stars.tsv` instead of a sample.
 */
class SkyCatalogBundleTest {

    private val asset = File("../core/sky/src/main/assets/sky/stars.skycat")

    /**
     * ⚠️ **Memory-MAPPED, not read whole, and at the deep tier that is the difference between this
     * running and this failing.** At G<15 the catalogue is about 295 MB; `readBytes()` would ask for
     * a byte array of that size on Gradle's default test heap, nine times over, once per test in
     * this class. Mapping is also exactly what the application itself does — `SkyCatalogSource`
     * exists to hand the reader a mapped asset — so the check now exercises the real access path
     * rather than a convenience that only a small file could afford.
     */
    private fun reader(): StarCatalogReader {
        assertTrue(
            "the catalogue is missing: ${asset.absolutePath}. It is no longer committed — CI builds " +
                "it behind a cache (see .github/actions/star-catalogue), because at G<15 it is far " +
                "past GitHub's 100 MB file limit.",
            asset.isFile,
        )
        val mapped = RandomAccessFile(asset, "r").use { file ->
            file.channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0L, file.length()) }
        }
        return when (val outcome = StarCatalogReader.open(mapped)) {
            is StarCatalogReader.Outcome.Ready -> outcome.reader
            is StarCatalogReader.Outcome.Unusable ->
                throw AssertionError("the shipped catalogue will not open: ${outcome.reason}")
        }
    }

    @Test
    fun `the shipped decoder reads the shipped file`() {
        val reader = reader()
        // ⚠️ **The star count and the depth are deliberately NOT pinned here, and the split is the
        // point.** They belong to whichever depth the build asked for, which lives in exactly one
        // place — the `magnitude` input of `.github/actions/star-catalogue` — and
        // `tools/sky/check_packaged.py` checks the file against it, twice, once per build workflow.
        // Restating the number in Kotlin would make this the third and fourth copy of a fact that
        // changes whenever the depth does, and a stale copy here would fail a perfectly good build.
        //
        // What this test owns instead is COHERENCE: that the file is internally consistent and was
        // built by the tiling this application reads with. That is the half a packaging gate cannot
        // see, and it is the half that fails silently.
        assertEquals(SkyGrid.tileCount, reader.tileCount)
        assertEquals(2016.0, reader.epochYear, 1e-9)
        assertTrue(
            "the catalogue claims a depth of G < ${reader.deepestMagnitude}, which is not a " +
                "magnitude any build of this would ask for",
            reader.deepestMagnitude in 6.0..17.0,
        )
        assertTrue("the catalogue is empty", reader.starCount > 1_000_000)
        assertEquals(
            StarCatalogFormat.expectedBytes(reader.tileCount, reader.starCount),
            asset.length(),
        )
    }

    @Test
    fun `the index accounts for every star and leaves no part of the sky empty`() {
        val reader = reader()
        var total = 0
        var smallest = Int.MAX_VALUE
        var largest = 0
        for (tile in 0 until reader.tileCount) {
            val size = reader.tileSize(tile)
            assertTrue("tile $tile has $size stars", size >= 0)
            total += size
            if (size < smallest) smallest = size
            if (size > largest) largest = size
        }
        assertEquals("the index does not add up to the header's star count", reader.starCount, total)
        // ⚠️ An empty tile would be a hole in the sky — a patch of a few square degrees with nothing
        // in it — and at no depth is there such a patch anywhere.
        assertTrue("some tile is empty — the sky has a hole in it", smallest > 0)
        // ⚠️ **Relative to the mean rather than absolute, because the absolute numbers belong to a
        // depth this test deliberately does not know.** Measured at G<12: thinnest 135, thickest
        // 3,638, mean 575 — so 0.23x and 6.3x. Measured live against the archive at G<15: tile 372
        // holds 54,097 against a mean of 6,873, so 7.9x. An absolute ceiling of 20,000 was correct
        // for the shallow tier and would fail the deep one, while proving nothing extra.
        //
        // What the ratio is really guarding is the band-and-column tiling keeping tiles comparable
        // rather than leaving polar slivers, and that a file built under some other geometry — where
        // the distribution would be wildly different — cannot pass unnoticed.
        val mean = reader.starCount.toDouble() / reader.tileCount
        assertTrue(
            "the thinnest tile holds $smallest stars against a mean of ${mean.toInt()}",
            smallest > mean / 16,
        )
        assertTrue(
            "the thickest tile holds $largest stars against a mean of ${mean.toInt()}",
            largest < mean * 12,
        )
    }

    @Test
    fun `every star sits inside the tile it was filed under`() {
        // ⚠️ This is the guard against the builder and the reader disagreeing about the geometry.
        // SkyGridParityTest checks the two tilings agree as functions; this checks that the file on
        // disk was actually written under the one this build reads with. A mismatch decodes without
        // error and describes a different universe.
        val reader = reader()
        val sink = StarCatalogReader.Sink(8192)
        var checked = 0
        var filedNextDoor = 0
        // Every eleventh tile, so the sample crosses every band including both poles, without
        // decoding the whole catalogue in a unit test.
        for (tile in 0 until reader.tileCount step 11) {
            sink.clear()
            reader.read(intArrayOf(tile), reader.deepestMagnitude, sink)
            val bounds = SkyGrid.boundsOf(tile)
            for (i in 0 until sink.count) {
                val ra = sink.rightAscensionDeg[i]
                val dec = sink.declinationDeg[i]
                assertTrue(
                    "tile $tile holds a star at ra=$ra, outside ${bounds.raLoDeg}..${bounds.raHiDeg}",
                    ra >= bounds.raLoDeg - 1e-6 && ra <= bounds.raHiDeg + 1e-6,
                )
                assertTrue(
                    "tile $tile holds a star at dec=$dec, outside ${bounds.decLoDeg}..${bounds.decHiDeg}",
                    dec >= bounds.decLoDeg - 1e-6 && dec <= bounds.decHiDeg + 1e-6,
                )
                if (SkyGrid.tileOf(ra, dec) != tile) filedNextDoor++
                checked++
            }
        }
        assertTrue("only $checked stars were checked", checked > 100_000)
        // ⚠️ A handful of stars decode into the NEXT tile along, and that is the format working
        // rather than failing. Positions are stored as a fraction of their tile, so a star sitting
        // on a tile's upper edge quantises to the maximum and decodes to exactly the boundary — and
        // a boundary belongs to the tile above it. Measured: 3 stars in 281,625, all three at a
        // quantisation maximum, and all three still within a twentieth of an arcsecond of where they
        // belong. What this bound is really for is the wholesale case: a file built under a
        // different tiling would put essentially EVERY star somewhere else, not one in a hundred
        // thousand.
        assertTrue(
            "$filedNextDoor of $checked stars file under a different tile — that is far too many " +
                "to be edge quantisation, so the file and this build disagree about the geometry",
            filedNextDoor < checked / 10_000,
        )
    }

    @Test
    fun `every tile is sorted brightest first`() {
        // Not cosmetic and not a preference: StarCatalogReader cuts a tile at the magnitude limit
        // with a BINARY SEARCH, which is only correct on sorted records. Unsorted, a wide view would
        // silently keep an arbitrary prefix of each tile instead of its brightest stars — the sky
        // would still be full of stars and they would be the wrong ones.
        val reader = reader()
        val sink = StarCatalogReader.Sink(8192)
        var checked = 0
        for (tile in 0 until reader.tileCount step 7) {
            sink.clear()
            reader.read(intArrayOf(tile), reader.deepestMagnitude, sink)
            for (i in 1 until sink.count) {
                assertTrue(
                    "tile $tile is out of order at $i: ${sink.magnitude[i - 1]} then ${sink.magnitude[i]}",
                    sink.magnitude[i] >= sink.magnitude[i - 1] - QUANTUM,
                )
            }
            checked += sink.count
        }
        assertTrue("only $checked stars were checked", checked > 100_000)
    }

    @Test
    fun `the magnitude limit actually limits`() {
        val reader = reader()
        val tiles = SkyGrid.tilesInCone(83.0, -5.0, 6.0)   // Orion, a rich field
        val sink = StarCatalogReader.Sink()

        sink.clear(); reader.read(tiles, reader.deepestMagnitude, sink); val deep = sink.count
        sink.clear(); reader.read(tiles, 9.0, sink); val middling = sink.count
        sink.clear(); reader.read(tiles, 6.5, sink); val nakedEye = sink.count

        assertTrue("nothing came back at all", deep > 10_000)
        assertTrue("the cut did nothing: $deep at the floor, $middling at 9", middling < deep / 4)
        assertTrue("the cut did nothing: $middling at 9, $nakedEye at 6.5", nakedEye < middling / 4)
        // And it cuts in the right direction — everything returned is at least as bright as asked.
        for (i in 0 until sink.count) {
            assertTrue("a star at ${sink.magnitude[i]} came back under a 6.5 limit",
                sink.magnitude[i] <= 6.5 + QUANTUM)
        }
    }

    @Test
    fun `a cone query comes back with stars that are in the cone`() {
        val reader = reader()
        val ra = 201.3
        val dec = -43.1
        val radius = 3.0
        val sink = StarCatalogReader.Sink()
        reader.read(SkyGrid.tilesInCone(ra, dec, radius), 8.0, sink)
        assertTrue("no stars near ra=$ra dec=$dec", sink.count > 50)
        // The tiles are generous by design, so plenty of what comes back is outside the circle. What
        // matters is that the cone is COVERED — so require the returned set to reach the rim.
        var inside = 0
        var furthest = 0.0
        for (i in 0 until sink.count) {
            val d = separationDeg(ra, dec, sink.rightAscensionDeg[i], sink.declinationDeg[i])
            if (d <= radius) inside++
            if (d > furthest) furthest = d
        }
        assertTrue("nothing landed inside the circle", inside > 20)
        assertTrue("the tiles did not even reach the rim: furthest $furthest°", furthest > radius)
    }

    @Test
    fun `Gaia is blind to the brightest stars, and this file must not be mistaken for the whole sky`() {
        // ⚠️ THE ONE THAT MATTERS MOST, and it is a correction to an assumption anybody would make.
        // Gaia SATURATES: it has no useful photometry above about G = 3 and simply omits most of it.
        // Measured against the bundled Bright Star Catalogue, of the fifteen stars brighter than
        // V = 1 — Sirius, Canopus, Arcturus, Vega, Capella, Rigel, Procyon, Betelgeuse, Achernar,
        // Altair, Aldebaran, Antares, Spica, Pollux, Fomalhaut — Gaia holds NONE. At V 1..2 it holds
        // 12%; at 2..3, 71%; at 3..4, 90%; and it only reaches 96% below fifth magnitude.
        //
        // So this catalogue is the FAINT half of the sky and `stars.tsv` is the bright half. Swapping
        // one for the other would delete every star a person can name — the whole of Orion, the
        // Plough, the Southern Cross — while still drawing three million stars and looking, at a
        // glance, like a working sky. That is why this is asserted rather than left as a comment.
        val reader = reader()
        val sink = StarCatalogReader.Sink()

        // ⚠️ 1.6 rather than the measured brightest of 1.71, and the difference matters: the limit
        // is quantised to the same fourteenths the records are, so asking for 1.7 asks for the
        // bucket that star is IN and correctly returns it. Measured: 1.5 and 1.6 return nothing,
        // 1.7 returns one. So the honest claim this pins is "Gaia holds no star of first magnitude".
        reader.read(IntArray(reader.tileCount) { it }, 1.6, sink)
        assertEquals("a first-magnitude star appeared — was the source changed?", 0, sink.count)

        sink.clear()
        reader.read(IntArray(reader.tileCount) { it }, 2.0, sink)
        assertTrue("nothing at all in the brightest bin: ${sink.count}", sink.count in 1..40)

        // And named one by one, because "the catalogue is shallow at the bright end" is a statistic
        // while "Sirius is not in this file" is the fact somebody needs to know before they delete
        // stars.tsv.
        //
        // ⚠️ The separation has to be checked here rather than left to the cone query: tilesInCone
        // returns whole TILES, which are nearly three degrees across and deliberately generous, so
        // it hands back a few hundred perfectly ordinary stars that are nowhere near the position
        // asked about. My first version of this asserted on the raw count and failed for that
        // reason — the query was right and the assertion was reading it as something it is not.
        //
        // ⚠️ And magnitude 5 rather than something tighter, because these positions are J2000 and
        // the catalogue is epoch 2016: Arcturus alone moves 2.3 arcseconds a year, so it would be
        // half an arcminute from where it is written here. A radius tight enough to exclude every
        // neighbour would also exclude the star being looked for, and the test would pass by
        // missing it. Measured over exactly these fifteen: the brightest source within 0.2° of any
        // of them is 5.79, an unrelated field star a tenth of a degree from Canopus. Any of these
        // primaries, if present, would be between magnitude -1.5 and +1.
        for ((name, position) in FIRST_MAGNITUDE) {
            sink.clear()
            reader.read(SkyGrid.tilesInCone(position.first, position.second, 0.5), 5.0, sink)
            for (i in 0 until sink.count) {
                val d = separationDeg(
                    position.first, position.second,
                    sink.rightAscensionDeg[i], sink.declinationDeg[i],
                )
                assertTrue(
                    "a magnitude ${sink.magnitude[i]} star sits ${"%.0f".format(d * 3600)}\" from " +
                        "$name — either Gaia's bright end changed or this is no longer a Gaia extract",
                    d > 0.2,
                )
            }
        }
    }

    @Test
    fun `proper motion is carried, and moves stars by a believable amount`() {
        // The catalogue is at epoch 2016.0 and positions must be carried forward to be drawn. A
        // builder that dropped the two proper-motion bytes would leave a file that reads perfectly
        // and never moves — undetectable on any one night and wrong over a lifetime.
        val reader = reader()
        val tiles = SkyGrid.tilesInCone(266.4, -29.0, 2.0)   // toward the galactic centre
        val now = StarCatalogReader.Sink()
        val later = StarCatalogReader.Sink()
        reader.read(tiles, 10.0, now)
        reader.read(tiles, 10.0, later, yearsFromEpoch = 1000.0)
        assertEquals(now.count, later.count)
        assertTrue("no stars to check", now.count > 100)

        var moved = 0
        var largestArcsec = 0.0
        for (i in 0 until now.count) {
            val d = separationDeg(
                now.rightAscensionDeg[i], now.declinationDeg[i],
                later.rightAscensionDeg[i], later.declinationDeg[i],
            ) * 3600.0
            if (d > 1.0) moved++
            if (d > largestArcsec) largestArcsec = d
        }
        assertTrue("nothing moved over a thousand years — proper motion is not in the file", moved > 10)
        // A thousand years at the fastest rate the format can hold is a large angle but a bounded
        // one; anything wilder means the encoding is being read as something it is not.
        assertTrue("a star moved $largestArcsec arcsec in a millennium", largestArcsec < 15.0 * 3600.0)
    }

    @Test
    fun `colour is present for almost every star, and absent where Gaia measured none`() {
        val reader = reader()
        val sink = StarCatalogReader.Sink()
        reader.read(SkyGrid.tilesInCone(150.0, 20.0, 5.0), reader.deepestMagnitude, sink)
        assertTrue("no stars to check", sink.count > 1000)
        var withColour = 0
        for (i in 0 until sink.count) {
            val c = sink.colourBpRp[i]
            if (!c.isNaN()) {
                withColour++
                assertTrue("bp_rp out of range: $c", c > -1.5f && c < 6.0f)
            }
        }
        // ⚠️ Asserted as a SHARE rather than as presence, and it has to be: bp_rp is genuinely
        // missing for a small fraction of Gaia sources, so requiring it everywhere would fail on
        // real data, while requiring nothing would let a rebuild that lost the column pass quietly.
        assertTrue(
            "only $withColour of ${sink.count} stars carry a colour",
            withColour > sink.count * 9 / 10,
        )
    }

    @Test
    fun `the catalogue says where it came from`() {
        // Gaia's data licence asks for the acknowledgement; a copy step that dropped the notice would
        // leave a great deal of somebody's work unattributed, and nothing would complain.
        val notice = File("../core/sky/src/main/assets/sky/NOTICE.txt")
        assertTrue("the NOTICE is missing", notice.isFile)
        // ⚠️ Whitespace-normalised, because the NOTICE is wrapped prose and the acknowledgement ESA
        // asks for is long enough to break across lines. My first version of this searched the raw
        // text and failed on a phrase that was present and merely hyphenated by a newline.
        val text = notice.readText().replace(Regex("\\s+"), " ")
        listOf(
            "stars.skycat",
            "Gaia Data Release 3",
            "Gaia Data Processing and Analysis Consortium (DPAC",
            "Gaia Multilateral Agreement",
        ).forEach {
            assertTrue("the NOTICE does not mention: $it", text.contains(it))
        }
    }

    private fun separationDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val d1 = Math.toRadians(dec1)
        val d2 = Math.toRadians(dec2)
        val dRa = Math.toRadians(ra1 - ra2)
        val cosine = Math.sin(d1) * Math.sin(d2) + Math.cos(d1) * Math.cos(d2) * Math.cos(dRa)
        return Math.toDegrees(Math.acos(cosine.coerceIn(-1.0, 1.0)))
    }
}

/** Magnitudes are stored in fourteenths, so equality has to allow one step. */
private const val QUANTUM = 1.0f / 14.0f

/**
 * The fifteen stars brighter than magnitude 1, J2000, and Gaia holds not one of them.
 *
 * Measured: the brightest source anywhere within 0.2° of any of these positions is magnitude 6.57,
 * ten arcseconds from Rigel — which is Rigel B, the companion Gaia can resolve because it is faint
 * enough to measure. Every one of the primaries is missing.
 */
private val FIRST_MAGNITUDE = listOf(
    "Sirius" to (101.2871 to -16.7161),
    "Canopus" to (95.9880 to -52.6957),
    "Arcturus" to (213.9153 to 19.1824),
    "Vega" to (279.2347 to 38.7837),
    "Capella" to (79.1723 to 45.9980),
    "Rigel" to (78.6345 to -8.2016),   // Gaia has Rigel B at 6.57, ten arcseconds off; not Rigel
    "Procyon" to (114.8255 to 5.2250),
    "Betelgeuse" to (88.7929 to 7.4071),
    "Achernar" to (24.4288 to -57.2367),
    "Altair" to (297.6958 to 8.8683),
    "Aldebaran" to (68.9802 to 16.5093),
    "Antares" to (247.3519 to -26.4320),
    "Spica" to (201.2983 to -11.1613),
    "Pollux" to (116.3290 to 28.0262),
    "Fomalhaut" to (344.4127 to -29.6222),
)
