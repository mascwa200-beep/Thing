package dev.mascwa.pulse.feature.lcarsboard

import dev.mascwa.pulse.feature.lcarsboard.LcarsContrast.BLACK
import dev.mascwa.pulse.feature.lcarsboard.LcarsContrast.MIN_TEXT
import dev.mascwa.pulse.feature.lcarsboard.LcarsContrast.WHITE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LcarsContrastTest {

    @Test
    fun `the ratio runs from one to twenty-one`() {
        assertEquals(21.0, LcarsContrast.ratio(BLACK, WHITE), 0.01)
        assertEquals(1.0, LcarsContrast.ratio(GOLD, GOLD), 1e-9)
        // Symmetric: which one is text and which is ground does not change how far apart they are.
        assertEquals(LcarsContrast.ratio(GOLD, BLACK), LcarsContrast.ratio(BLACK, GOLD), 1e-12)
    }

    @Test
    fun `a colour that already reads is left exactly as it is`() {
        val ink = 0xFFF4F4F6.toInt()
        assertEquals(ink, LcarsContrast.legible(ink, BLACK))
        // ⚠️ Gold as well as ink. Ink sits so near white that one step toward white rounds back to
        // itself, so ink alone could not tell "left alone" from "moved and happened to land here".
        assertEquals(GOLD, LcarsContrast.legible(GOLD, BLACK))
    }

    @Test
    fun `a dark calendar colour on black is lifted until it reads, and stays its own colour`() {
        val navy = 0xFF0B3D91.toInt()
        assertTrue("fixture must start illegible", LcarsContrast.ratio(navy, BLACK) < MIN_TEXT)
        val lifted = LcarsContrast.legible(navy, BLACK)
        val r = LcarsContrast.ratio(lifted, BLACK)
        assertTrue("reads: $r", r >= MIN_TEXT)
        // Moved only as far as it takes, not snapped to white: two calendars must stay tellable apart.
        assertNotEquals(WHITE, lifted)
        assertTrue("barely past the bar, not far beyond it: $r", r < MIN_TEXT + 1.0)
        // Still blue: the blue channel leads the red.
        assertTrue(((lifted ushr 0) and 0xFF) > ((lifted ushr 16) and 0xFF))
    }

    @Test
    fun `on a light block a pale colour is darkened rather than lightened`() {
        val paleYellow = 0xFFFFF08A.toInt()
        val onGold = LcarsContrast.legible(paleYellow, GOLD)
        assertTrue(LcarsContrast.ratio(onGold, GOLD) >= MIN_TEXT)
        assertTrue("darker than it started", LcarsContrast.luminance(onGold) < LcarsContrast.luminance(paleYellow))
    }

    @Test
    fun `a transparent colour is drawn opaque, not invisible`() {
        val clearWhite = 0x00FFFFFF
        assertEquals(WHITE, LcarsContrast.legible(clearWhite, BLACK))
    }

    @Test
    fun `block text is black on light blocks and white on dark ones`() {
        assertEquals(BLACK, LcarsContrast.onBlock(GOLD))
        assertEquals(WHITE, LcarsContrast.onBlock(0xFF660A0A.toInt()))
    }

    /**
     * Every colour the app's palettes declare — ordinary, red alert, and both banks of blocks — is
     * read out of `TosPalette.kt` itself rather than restated here, so a palette change is checked the
     * moment it is made rather than when somebody remembers to update a copy.
     */
    @Test
    fun `every palette colour reads on every ground the console draws on`() {
        val colours = paletteColours()
        // Self-check: a parser that found nothing would pass every assertion below vacuously.
        assertTrue("found only ${colours.size} palette colours", colours.size >= 20)
        assertTrue("the gold block must be among them", GOLD in colours)

        val grounds = listOf(BLACK, PANEL, CARBON) + colours
        for (fg in colours) for (bg in grounds) {
            val drawn = LcarsContrast.legible(fg, bg)
            val r = LcarsContrast.ratio(drawn, bg)
            assertTrue("${hex(fg)} on ${hex(bg)} reads at $r", r >= MIN_TEXT)
        }
        for (block in colours) {
            val r = LcarsContrast.ratio(LcarsContrast.onBlock(block), block)
            assertTrue("text on block ${hex(block)} reads at $r", r >= MIN_TEXT)
        }
    }

    private fun paletteColours(): List<Int> {
        val src = File("src/main/java/dev/mascwa/pulse/ui/theme/TosPalette.kt")
        assertTrue("palette source not found from ${File(".").absolutePath}", src.isFile)
        return Regex("""Color\(0x([0-9A-Fa-f]{8})\)""").findAll(src.readText())
            .map { it.groupValues[1].toLong(16).toInt() }
            .distinct()
            .toList()
    }

    private fun hex(c: Int) = "#%08X".format(c)

    private companion object {
        val GOLD = 0xFFFFB000.toInt()
        val PANEL = 0xFF121216.toInt()
        val CARBON = 0xFF08080A.toInt()
    }
}
