package dev.mascwa.pulse.desktop.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fonts are actually there, and are actually fonts.
 *
 * ⚠️ **This is the gate the compiler cannot be.** Desktop `Font(resource = "font/x.ttf")` resolves
 * its argument from the classpath at *render* time, so a wrong path, a resource-copy step that
 * quietly stopped working, or a truncated file all compile perfectly and then throw the first time
 * a window opens. That failure would reach a Windows machine before anything here noticed, because
 * this container cannot get a GL context and so cannot render a single frame to find out.
 *
 * What it proves: every resource the theme names is on the classpath and begins with a real sfnt
 * signature. What it does not prove: that the letterforms are legible at the sizes chosen, or that
 * Skia is happy with them — that stays owner-verify on Windows, as all layout here does.
 *
 * The list is written out rather than derived from `Fonts.kt`, on purpose. Deriving it would make
 * the test agree with the code by construction and pass whatever the code said; naming the files
 * independently is what lets it disagree.
 */
class BundledFontsTest {

    private val expected = listOf(
        "font/orbitron_var.ttf",
        "font/chakra_petch_regular.ttf",
        "font/chakra_petch_medium.ttf",
        "font/chakra_petch_semibold.ttf",
        "font/chakra_petch_bold.ttf",
        "font/jetbrains_mono_var.ttf",
    )

    private fun read(path: String): ByteArray? =
        javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes() }

    @Test
    fun everyFontTheThemeNamesIsOnTheClasspath() {
        for (path in expected) {
            assertNotNull("missing bundled font resource: $path", read(path))
        }
    }

    @Test
    fun eachOneIsARealTrueTypeFile() {
        for (path in expected) {
            val bytes = read(path) ?: error("missing $path")
            // A truncated or wrong-content file is the realistic failure — a saved error page named
            // .ttf, say — and it is indistinguishable from a good one until something parses it.
            assertTrue("$path is far too small to be a font (${bytes.size} bytes)", bytes.size > 10_000)
            // sfnt version: 0x00010000 for TrueType outlines. Checked as four bytes rather than as
            // a string, because 'true'/'OTTO' are different tags and would be a real difference.
            val tag = bytes.take(4).map { it.toInt() and 0xFF }
            assertEquals("$path does not start with the TrueType sfnt signature", listOf(0, 1, 0, 0), tag)
        }
    }

    /**
     * The condensed face is still shipped, because the phone's navigation bar still needs it.
     *
     * It is bundled from the same shared directory, so deleting it there — which would look like
     * harmless cleanup now that the console voice is Orbitron — would silently take it off both
     * platforms at once.
     */
    @Test
    fun theCondensedFaceIsStillBundledForTheNavigationBar() {
        assertNotNull("antonio_var.ttf is gone; the phone's nav bar depends on it", read("font/antonio_var.ttf"))
    }
}
