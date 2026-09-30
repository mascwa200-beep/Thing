package dev.mascwa.pulse.feature.wallpaper

import dev.mascwa.pulse.feature.lcarsboard.LcarsContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperFrameTest {

    /** A small phone, the owner's Pixel 10 Pro XL, a landscape tablet. */
    private val screens = listOf(
        Triple(720, 1280, 2.0f),
        Triple(1344, 2992, 3.5f),
        Triple(2560, 1600, 2.0f),
    )

    @Test
    fun `white text clears the contrast bar on every block`() {
        for ((w, h, d) in screens) {
            val frame = WallpaperFrame.layout(w, h, d)
            val colours = frame.blocks.map { it.argb } + frame.fillets.map { it.argb }
            for (c in colours) {
                val ratio = LcarsContrast.ratio(LcarsContrast.WHITE, c)
                assertTrue("white on %08x is %.2f:1".format(c, ratio), ratio >= LcarsContrast.MIN_TEXT)
            }
        }
    }

    @Test
    fun `dimming keeps the colour's hue`() {
        // Every channel scaled by one factor: gold stays gold, it does not drift toward brown or grey.
        val gold = WallpaperFrame.dimmed(0xFFFFB000.toInt())
        val r = (gold ushr 16) and 0xFF
        val g = (gold ushr 8) and 0xFF
        val b = gold and 0xFF
        assertEquals(0, b)
        assertEquals(0xB0 / 255.0, g.toDouble() / r, 0.02)
        assertTrue(LcarsContrast.luminance(gold) <= WallpaperFrame.MAX_BLOCK_LUMINANCE)
    }

    @Test
    fun `dimming goes no further than it has to`() {
        // The palette's brightest colours land just under the ceiling, not far below it — a frame
        // dimmed to near black would read as no frame at all.
        for (c in WallpaperFrame.PALETTE) {
            val lum = LcarsContrast.luminance(WallpaperFrame.dimmed(c))
            assertTrue("%08x dimmed to %.3f".format(c, lum), lum > WallpaperFrame.MAX_BLOCK_LUMINANCE * 0.9)
        }
    }

    @Test
    fun `a colour already dark enough is left alone`() {
        val navy = 0xFF1B1B21.toInt()
        assertEquals(navy, WallpaperFrame.dimmed(navy))
    }

    @Test
    fun `nothing is drawn in the middle`() {
        for ((w, h, d) in screens) {
            val frame = WallpaperFrame.layout(w, h, d)
            val inner = frame.inner
            val middle = WallpaperFrame.Block(inner.left, inner.top, inner.right, inner.bottom, 0)
            for (b in frame.blocks) assertFalse("$b is inside the middle of ${w}x$h", b.overlaps(middle))
            for (f in frame.fillets) {
                val box = WallpaperFrame.Block(f.x, f.y, f.x + f.radius, f.y + f.radius, 0)
                assertFalse("$f is inside the middle of ${w}x$h", box.overlaps(middle))
            }
            // And the middle is most of the screen, not a letterbox.
            assertTrue((inner.right - inner.left) > w * 0.75f)
            assertTrue((inner.bottom - inner.top) > h * 0.6f)
        }
    }

    @Test
    fun `every block is on screen and clear of the status and gesture bars`() {
        for ((w, h, d) in screens) {
            val frame = WallpaperFrame.layout(w, h, d)
            val top = WallpaperFrame.TOP_INSET_DP * d
            val bottom = h - WallpaperFrame.BOTTOM_INSET_DP * d
            for (b in frame.blocks) {
                assertTrue("$b", b.left >= 0f && b.right <= w && b.right > b.left)
                assertTrue("$b", b.top >= top && b.bottom <= bottom && b.bottom > b.top)
            }
        }
    }

    @Test
    fun `blocks never overlap one another`() {
        for ((w, h, d) in screens) {
            val blocks = WallpaperFrame.layout(w, h, d).blocks
            for (i in blocks.indices) for (j in i + 1 until blocks.size) {
                assertFalse("${blocks[i]} overlaps ${blocks[j]}", blocks[i].overlaps(blocks[j]))
            }
        }
    }

    @Test
    fun `the frame has both elbows, a rail and two bars`() {
        val frame = WallpaperFrame.layout(1344, 2992, 3.5f)
        assertEquals(1, frame.blocks.count { it.outer == WallpaperFrame.Corner.TOP_START })
        assertEquals(1, frame.blocks.count { it.outer == WallpaperFrame.Corner.BOTTOM_START })
        assertEquals(2, frame.fillets.size)
        // Six rail segments, three in each bar, and the two corner pieces.
        assertEquals(6 + 3 + 3 + 2, frame.blocks.size)
    }
}
