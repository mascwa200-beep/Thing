package dev.mascwa.pulse.desktop.standby

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import dev.mascwa.pulse.desktop.theme.PulseDesktopTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Draws the standby display to a picture, with no window and no graphics context.
 *
 * ## Why this is possible at all, and why it matters
 *
 * `androidx.compose.ui.renderComposeScene` composes and lays out onto a **raster** Skia surface. It
 * asks for no GL context, which is the thing that has made every other desktop render in this
 * project unverifiable outside a real machine. So the same composable that draws the live HUD can
 * produce the Windows lock-screen wallpaper — one layout, one palette, one set of numbers, three
 * surfaces that cannot disagree — and, unusually here, **the whole path is exercised by a test that
 * actually runs.**
 *
 * ⚠️ It renders **exactly one frame**. Nothing driven by a `LaunchedEffect`, an animation or a flow
 * will have happened by the time the frame is captured — which is why [StandbyDisplay] is a pure
 * function of its state and must stay one.
 */
object StandbyRender {

    /**
     * Render to PNG bytes.
     *
     * Returns null rather than throwing: every caller is a background pass whose failure must be a
     * recorded reason, not a crashed process.
     */
    fun renderToPng(state: StandbyState, widthPx: Int, heightPx: Int): ByteArray? = runCatching {
        require(widthPx > 0 && heightPx > 0) { "a picture needs a size" }
        val layout = StandbyLayout.forCanvas(widthPx, heightPx)
        // Density 1 on purpose: it makes the dp canvas exactly the pixel canvas, so the layout's
        // scale is the only quantity deciding how big anything is, with no second factor to reason
        // about when a picture comes out wrong.
        val image = renderComposeScene(width = widthPx, height = heightPx, density = Density(1f)) {
            PulseDesktopTheme {
                StandbyDisplay(state, layout, Modifier.fillMaxSize())
            }
        }
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
    }.getOrNull()

    /**
     * Render straight to a file, atomically.
     *
     * ⚠️ Written to a sibling temp file and moved into place. The lock-screen image is read by
     * Windows on a schedule this process does not control, and a half-written PNG would be a
     * corrupt wallpaper rather than a stale one.
     */
    fun renderToFile(state: StandbyState, widthPx: Int, heightPx: Int, target: File): File? = runCatching {
        val bytes = renderToPng(state, widthPx, heightPx) ?: return null
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            // A rename across a lock or an existing file can refuse on Windows; falling back to a
            // copy is worse but still correct, and silently doing nothing would not be.
            target.writeBytes(bytes)
            tmp.delete()
        }
        target
    }.getOrNull()
}
