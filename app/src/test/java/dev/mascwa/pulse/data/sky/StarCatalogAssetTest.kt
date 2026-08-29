package dev.mascwa.pulse.data.sky

import dev.mascwa.pulse.core.telemetry.StarNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The bundled star catalogue, checked against itself and against the tables that name it.
 *
 * ⚠️ **Every one of these guards exists because its failure would be silent.** A star catalogue is
 * eight thousand rows of numbers nobody proofreads: a column offset out by one in the builder gives
 * positions that parse perfectly and describe a different sky, a constellation abbreviation missing
 * from [StarNames] renders as a bare "CMa" on a tap, and a mistyped proper-name key simply never
 * fires. None of that would fail a compile or look wrong in a diff.
 */
class StarCatalogAssetTest {

    private val asset = File("src/main/assets/sky/stars.tsv")

    private class Star(
        val ra: Double,
        val dec: Double,
        val mag: Double,
        val bv: String,
        val bayer: String,
        val flamsteed: String,
        val constellation: String,
        val pmRaMas: Double,
        val pmDecMas: Double,
    )

    private fun load(): List<Star> {
        assertTrue("the catalogue is missing: ${asset.absolutePath}", asset.isFile)
        return asset.readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { line ->
                val f = line.split('\t')
                assertEquals("wrong column count in: $line", 9, f.size)
                Star(
                    f[0].toDouble(), f[1].toDouble(), f[2].toDouble(), f[3], f[4], f[5], f[6],
                    f[7].toDouble(), f[8].toDouble(),
                )
            }
    }

    @Test
    fun `splitting keeps the trailing empty columns this parse depends on`() {
        // ⚠️ Kotlin's split is NOT Java's, and I wrote the Java idiom here first. In Java a negative
        // limit means "keep trailing empties"; in Kotlin `limit` must be non-negative and throws
        // otherwise, and the default of 0 already keeps them. Most stars in the catalogue have no
        // Bayer letter and no Flamsteed number, so their rows genuinely end in empty fields — get
        // this wrong and every row past the bright ones loses its constellation.
        assertEquals(listOf("a", "b", "", ""), "a\tb\t\t".split('\t'))
        // ⚠️ **That hazard is now LATENT rather than live, and this note used to say otherwise.**
        // 5,325 of the 8,404 rows carry three empty fields in a row, because the Bright Star
        // Catalogue leaves the whole designation blank for a star with neither a Bayer letter nor a
        // Flamsteed number and the constellation lives in the same field. Until the proper-motion
        // columns were appended those empties were TRAILING, which is the case a Java-idiom split
        // silently drops; now two numeric columns follow them and interior empties are kept by any
        // split at all. Kept because the ordering could be changed back, and because the assertion
        // above is about Kotlin rather than about this file.
        val stars = load()
        val anonymous = stars.count { it.bayer.isBlank() && it.flamsteed.isBlank() }
        assertTrue("expected most of the catalogue to be unnamed, got $anonymous", anonymous > stars.size / 2)
    }

    @Test
    fun `everything a person could point at by name is named`() {
        // ⚠️ The useful form of the coverage claim. Overall the catalogue is 37% named, which sounds
        // poor and is irrelevant — what matters is that the stars bright enough to notice all have a
        // designation. Measured: 100% down to second magnitude, 94% down to fourth, and it falls off
        // only among stars that need a dark sky to see at all.
        val stars = load()
        val bright = stars.filter { it.mag <= 3.0 }
        val named = bright.count { it.bayer.isNotBlank() || it.flamsteed.isNotBlank() }
        assertTrue(
            "only $named of ${bright.size} stars brighter than third magnitude are named",
            named >= bright.size - 1,
        )
    }

    @Test
    fun `every row is a physically possible position`() {
        val stars = load()
        assertTrue("suspiciously few stars: ${stars.size}", stars.size > 8000)
        stars.forEach { s ->
            assertTrue("right ascension out of range: ${s.ra}", s.ra in 0.0..360.0)
            assertTrue("declination out of range: ${s.dec}", s.dec in -90.0..90.0)
            assertTrue("magnitude out of range: ${s.mag}", s.mag in -2.0..6.5)
        }
    }

    @Test
    fun `Sirius anchors the whole chain`() {
        // ⚠️ The Bright Star Catalogue is fixed-width with no delimiters, so an offset wrong by one
        // produces a perfectly well-formed file describing the wrong sky. Sirius is the brightest
        // star there is, at 06h45m09s -16d42m58s in J2000, V = -1.46, alpha Canis Majoris. If any
        // one of those four is off, the builder read the wrong columns.
        val first = load().first()
        assertEquals(101.2871, first.ra, 0.001)
        assertEquals(-16.7161, first.dec, 0.001)
        assertEquals(-1.46, first.mag, 0.001)
        assertEquals("α", first.bayer)
        assertEquals("CMa", first.constellation)
        assertEquals("Sirius", StarNames.label("α", "9", "CMa"))
    }

    @Test
    fun `the catalogue is ordered brightest first`() {
        // Not cosmetic: a reader that stops early — because the device is weak, or the zoom is wide
        // — must be left holding the stars that matter rather than an arbitrary slice.
        val mags = load().map { it.mag }
        assertEquals(mags.sorted(), mags)
    }

    @Test
    fun `every constellation in the data has a name`() {
        val unknown = load()
            .map { it.constellation }
            .filter { it.isNotBlank() }
            .toSortedSet()
            .filterNot { StarNames.CONSTELLATIONS.containsKey(it) }
        assertTrue("abbreviations with no name: $unknown", unknown.isEmpty())
    }

    @Test
    fun `the constellation tables agree with each other`() {
        // Both tables are hand-written, so the failure mode is one of them being edited alone.
        assertEquals(88, StarNames.CONSTELLATIONS.size)
        StarNames.CONSTELLATIONS.keys.forEach { abbreviation ->
            val genitive = StarNames.genitive(abbreviation)
            assertTrue("no genitive for $abbreviation", genitive.isNotBlank())
            assertTrue(
                "$abbreviation fell back to its own abbreviation, so a genitive is missing",
                genitive != abbreviation,
            )
        }
    }

    @Test
    fun `every proper name matches a star that is actually in the catalogue`() {
        // ⚠️ The one that catches a typo. "α CMa" resolves to Sirius; "α Cma" resolves to nothing
        // and the name never appears, with no error anywhere.
        val present = load()
            .filter { it.bayer.isNotBlank() }
            .map { "${it.bayer} ${it.constellation}" }
            .toSet()
        val orphans = StarNames.properKeys().filterNot { it in present }
        assertTrue("proper names keyed to no star in the catalogue: $orphans", orphans.isEmpty())
    }

    @Test
    fun `the brightest stars are the ones everybody knows`() {
        // A second independent anchor on the ordering and the names together.
        val top = load().take(10).mapNotNull {
            StarNames.label(it.bayer, it.flamsteed, it.constellation)
        }
        listOf("Sirius", "Canopus", "Arcturus", "Vega", "Capella", "Rigel", "Procyon").forEach {
            assertTrue("$it should be in the ten brightest, got $top", top.contains(it))
        }
    }

    @Test
    fun `a star with no designation gets no label rather than a blank one`() {
        val anonymous = load().first { it.bayer.isBlank() && it.flamsteed.isBlank() }
        assertEquals(null, StarNames.label(anonymous.bayer, anonymous.flamsteed, anonymous.constellation))
        assertEquals(null, StarNames.shortLabel(anonymous.bayer, anonymous.flamsteed, anonymous.constellation))
    }

    @Test
    fun `a Flamsteed-only star is named by its number`() {
        val flamsteedOnly = load().first { it.bayer.isBlank() && it.flamsteed.isNotBlank() }
        val label = StarNames.label(flamsteedOnly.bayer, flamsteedOnly.flamsteed, flamsteedOnly.constellation)
        assertNotNull(label)
        assertTrue("should start with the number: $label", label!!.startsWith(flamsteedOnly.flamsteed))
        assertTrue("and use the genitive: $label", label.contains(StarNames.genitive(flamsteedOnly.constellation)))
    }

    @Test
    fun `colour information is present for most of the catalogue`() {
        // B-V is what makes Betelgeuse orange and Rigel blue-white on the chart. It is optional in
        // the source, so this asserts coverage rather than presence — and states the measured share
        // so a future rebuild that loses the column cannot pass quietly.
        val stars = load()
        val withColour = stars.count { it.bv.isNotBlank() }
        assertTrue(
            "only $withColour of ${stars.size} have a B-V colour",
            withColour > stars.size * 9 / 10,
        )
    }

    @Test
    fun `proper motion is present for practically the whole catalogue`() {
        // ⚠️ **Two subsystems now depend on these two columns and neither would report their
        // absence.** The star layer carries every plotted star forward from J2000 by them, and the
        // occultation search does the same for its five targets — so a rebuild that dropped them
        // would leave both quietly running the arithmetic against zeros, which is precisely the
        // uncorrected behaviour they were added to replace. Nothing throws, nothing logs, and a
        // chart of an unmoving sky looks exactly like a chart of a moving one.
        //
        // Coverage is stated as a measured share rather than a presence check, the same way the
        // colour test above is, because the source genuinely lacks a few: 8,400 of 8,404 rows carry
        // a measurement and four do not.
        val stars = load()
        val moving = stars.count { it.pmRaMas != 0.0 || it.pmDecMas != 0.0 }
        assertTrue(
            "only $moving of ${stars.size} rows carry a proper motion",
            moving > stars.size * 99 / 100,
        )
    }

    @Test
    fun `the proper motion columns are the projected ones and are in milliarcseconds`() {
        // ⚠️ The unit is invisible to a column count and a factor of a thousand wrong is the easy
        // mistake — the source states these in arcseconds per year and the builder multiplies. So
        // the guard is the SCALE of the numbers, and the bounds below are measured off this very
        // asset rather than recalled: the largest single component is 5,813 mas/yr and the median
        // total is 38.2. Arcseconds per year would make those 5.8 and 0.038; anything reading a
        // different column entirely would miss the window in the other direction.
        val stars = load()
        val fastest = stars.maxOf { maxOf(abs(it.pmRaMas), abs(it.pmDecMas)) }
        assertTrue(
            "the fastest component is $fastest — not milliarcseconds a year for a naked-eye star",
            fastest in 1_000.0..20_000.0,
        )

        // ⚠️ And the great majority barely move at all, because a bright star is usually a distant
        // giant. Scaling the whole column would still pass the check above on its single fastest
        // row, so the middle of the distribution is checked as well as the end of it.
        val median = stars.map { hypot(it.pmRaMas, it.pmDecMas) }.sorted()[stars.size / 2]
        assertTrue("the median star moves $median mas/yr", median in 5.0..200.0)
    }
}
