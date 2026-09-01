package dev.mascwa.pulse.desktop.sky

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * That the star catalogue is actually IN the build, and whole.
 *
 * ⚠️ **Every way this can go wrong produces an app that compiles perfectly and draws an empty sky.**
 * The `processResources` copy not running, the file landing under the wrong prefix, the resource
 * path being read relative to a package rather than the jar root, a rebuilt catalogue changing its
 * column order — none of those is a compile error, none throws at runtime (the loader deliberately
 * answers an empty list rather than crashing a page), and all of them look identical from here.
 * They would be discovered on Windows, by the owner, with nothing to say which it was.
 *
 * So it is checked at build time, against the real bundled file rather than a fixture. This is the
 * same reasoning `LibraryBundleTest` was written with, for the same kind of borrowed asset.
 */
class StarCatalogBundleTest {

    private val catalogue = StarCatalogSource()

    @Test
    fun `the catalogue is on the classpath and substantial`() = runBlocking {
        val stars = catalogue.all()
        // A floor rather than the exact 8,404: the catalogue could legitimately be rebuilt with a
        // different magnitude cut, and a test that had to be edited for that would be edited without
        // being read. Anything remotely near zero is the copy having failed.
        assertTrue("only ${stars.size} stars loaded — did processResources run?", stars.size >= 5_000)
    }

    /**
     * ⚠️ Named separately from the count, because the two fail for different reasons. An empty list
     * means the copy did not happen; a list of the right length whose numbers are nonsense means the
     * columns moved. The second is the one that would otherwise ship: the file has no header a
     * parser can key on, so a reordered column yields a valid double in the wrong field and a star
     * lands somewhere it is not.
     */
    @Test
    fun `the columns are where the parser thinks they are`() = runBlocking {
        val stars = catalogue.all()
        assertTrue(
            "right ascension must be an angle around the sky",
            stars.all { it.rightAscensionDeg >= 0.0 && it.rightAscensionDeg < 360.0 },
        )
        assertTrue(
            "declination cannot leave the poles",
            stars.all { it.declinationDeg >= -90.0 && it.declinationDeg <= 90.0 },
        )
        // Sirius is the brightest star in the sky at −1.46 and nothing else comes close, so the
        // first row is a fact about the catalogue rather than a guess. It also pins the sort order
        // that `brighterThan` depends on being a prefix rather than a filter.
        val sirius = stars.first()
        assertEquals(-1.46, sirius.magnitude, 1e-9)
        assertEquals("α", sirius.bayer)
        assertEquals("CMa", sirius.constellation)
        assertTrue(
            "and its position has to be Sirius's, not some other row's",
            abs(sirius.rightAscensionDeg - 101.2871) < 1e-4 &&
                abs(sirius.declinationDeg - (-16.7161)) < 1e-4,
        )
    }

    /** The prefix `brighterThan` relies on. A file that stopped being sorted would silently truncate. */
    @Test
    fun `the file is sorted brightest first`() = runBlocking {
        val stars = catalogue.all()
        val outOfOrder = stars.zipWithNext().count { (a, b) -> b.magnitude < a.magnitude }
        assertEquals("$outOfOrder rows are brighter than the row before them", 0, outOfOrder)
    }

    @Test
    fun `the naming core resolves the stars a chart labels`() = runBlocking {
        val stars = catalogue.all()
        // The five the occultation list names on the phone, chosen there because they are the only
        // first-magnitude stars the Moon can reach. If any of them stops resolving, the two consoles
        // have stopped agreeing about the same catalogue.
        listOf("Aldebaran", "Regulus", "Spica", "Antares", "Alcyone").forEach { name ->
            assertNotNull("$name no longer resolves out of the bundled catalogue", stars.firstOrNull { it.name == name })
        }
    }

    @Test
    fun `brighterThan stops at the limit`() = runBlocking {
        val bright = catalogue.brighterThan(2.0)
        assertTrue("a magnitude-2 sky should hold a few dozen stars, not ${bright.size}", bright.size in 10..200)
        assertTrue(bright.all { it.magnitude <= 2.0 })
    }

    /**
     * ⚠️ The licence, not tidiness. The Bright Star Catalogue is CDS-licensed and its attribution is
     * a condition of shipping it, so the notice has to travel with the data into the jar — and the
     * copy that puts the stars there is the same one that would silently leave the notice behind.
     */
    @Test
    fun `the attribution ships with it`() {
        val notice = javaClass.getResourceAsStream("/sky/NOTICE.txt")?.bufferedReader()?.use { it.readText() }
        assertNotNull("the star catalogue's NOTICE.txt is not in the build", notice)
        assertTrue("the notice must name the source", notice!!.contains("Bright Star Catalogue"))
    }
}
