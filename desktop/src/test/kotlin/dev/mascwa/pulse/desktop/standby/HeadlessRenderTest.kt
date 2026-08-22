package dev.mascwa.pulse.desktop.standby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves Compose can render **with no window and no GL context**.
 *
 * ⚠️ This is the load-bearing check for the whole standby display, and it is worth its own file
 * because of what it unblocks: `ImageComposeScene` draws onto a *raster* surface, which is exactly
 * why the lock-screen wallpaper can be produced by the same composable that draws the live HUD —
 * and why that half is verifiable here at all. Every previous attempt to check desktop rendering in
 * this container failed on Skiko being unable to get a GL context; this path never asks for one.
 *
 * If this test ever starts failing on a native-library or headless error rather than on an
 * assertion, the standby display has not regressed — the *renderer* has become unavailable, and the
 * lock-screen rung is what stops working. Say that rather than deleting the test.
 */
class HeadlessRenderTest {

    @Test
    fun `compose renders to a raster image with no window`() {
        val image = renderComposeScene(width = 320, height = 200, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF101014)))
            Box(Modifier.size(80.dp).background(Color(0xFFFF9900)))
        }

        assertEquals("the render must honour the size it was asked for", 320, image.width)
        assertEquals(200, image.height)

        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue("PNG encoding produced nothing", png != null && png.isNotEmpty())

        // The 8-byte PNG signature. A file that merely exists is not a file that decodes — the same
        // rule the bundled-image gate uses, for the same reason.
        val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertEquals("not a PNG", sig.toList(), png!!.take(8))
    }

    @Test
    fun `the render carries the pixels it was told to draw, not an empty surface`() {
        // ⚠️ A blank image encodes to a perfectly valid PNG, so "it produced a PNG" proves nothing
        // about whether anything was drawn. This asserts the accent square is actually there.
        val image = renderComposeScene(width = 64, height = 64, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color(0xFFFF9900)))
        }
        val bitmap = org.jetbrains.skia.Bitmap().apply {
            allocPixels(image.imageInfo)
        }
        assertTrue("could not read the rendered pixels back", image.readPixels(bitmap, 0, 0))

        val argb = bitmap.getColor(32, 32)
        assertEquals("the centre pixel is not the colour that was drawn", 0xFFFF9900.toInt(), argb)
    }
}
