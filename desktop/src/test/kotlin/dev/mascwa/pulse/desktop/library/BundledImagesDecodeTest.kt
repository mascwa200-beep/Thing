package dev.mascwa.pulse.desktop.library

import androidx.compose.ui.res.loadImageBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every bundled diagram actually decodes, with the loader the reader itself uses.
 *
 * ⚠️ **This is the gate neither the compiler nor `LibraryBundleTest` can be.** That test proves each
 * named file is *present on the classpath*; presence is not decodability. A format Skia does not
 * handle compiles, packages and ships perfectly, and fails for the first time when somebody opens
 * that page on Windows — which this container can never discover by rendering, because it cannot get
 * a GL context.
 *
 * It is not hypothetical. The corpus carried a single `.gif`, and `feature/library/Diagram.kt` says
 * in as many words that Compose Desktop documents loaders for png, jpg and svg "and says nothing
 * about the fourth". That diagram had failed soft to its caption on every Windows machine since it
 * was bundled, and nothing in the build knew. Converting the corpus to WebP fixed it — and this test
 * is what will notice next time.
 *
 * SVGs are excluded deliberately: `loadSvgPainter` needs a `Density` and produces a painter rather
 * than a bitmap, so it is a different call with a different failure mode. Their integrity is covered
 * by `LibraryBundleTest`'s presence check and by the reader's own soft-fail path.
 */
class BundledImagesDecodeTest {

    @Test
    fun everyRasterDiagramDecodesWithTheReadersOwnLoader() = runBlocking {
        val repo = LibraryRepository()
        val named = sortedSetOf<String>()
        for (entry in repo.index()) {
            repo.guide(entry.id)?.sections?.forEach { s -> s.image?.let { named += it } }
        }
        assertTrue("the corpus should name a great many diagrams, found ${named.size}", named.size > 300)

        val rasters = named.filterNot { it.endsWith(".svg", ignoreCase = true) }
        val failures = mutableListOf<String>()
        for (name in rasters) {
            val bytes = repo.imageBytes(name)
            if (bytes == null || bytes.isEmpty()) {
                failures += "$name: not on the classpath"
                continue
            }
            val decoded = runCatching { bytes.inputStream().use { loadImageBitmap(it) } }
            val bmp = decoded.getOrNull()
            when {
                bmp == null -> failures += "$name: ${decoded.exceptionOrNull()}"
                bmp.width <= 0 || bmp.height <= 0 -> failures += "$name: decoded to ${bmp.width}x${bmp.height}"
            }
        }
        assertTrue(
            "diagrams that will not draw on the desktop: ${failures.take(8)} (${failures.size} total)",
            failures.isEmpty(),
        )
    }

    /**
     * Nothing is wider than the readers can show.
     *
     * The Android reader caps a diagram at 260.dp and the desktop at 620.dp, so at any plausible
     * density 1280 px is more than either will ever ask for. A file above that is bytes shipped,
     * downloaded and decoded to be discarded during layout — which is the whole reason the corpus
     * was re-encoded. Asserting it here is what stops the next image wave quietly undoing that.
     */
    @Test
    fun noDiagramIsLargerThanEitherReaderCanDisplay() = runBlocking {
        val repo = LibraryRepository()
        val named = sortedSetOf<String>()
        for (entry in repo.index()) {
            repo.guide(entry.id)?.sections?.forEach { s -> s.image?.let { named += it } }
        }
        val oversized = mutableListOf<String>()
        for (name in named.filterNot { it.endsWith(".svg", ignoreCase = true) }) {
            val bytes = repo.imageBytes(name) ?: continue
            val bmp = runCatching { bytes.inputStream().use { loadImageBitmap(it) } }.getOrNull() ?: continue
            if (bmp.width > MAX_DISPLAY_PX) oversized += "$name (${bmp.width}px)"
        }
        assertTrue(
            "wider than any reader can draw, so the extra pixels are pure cost: " +
                "${oversized.take(8)} (${oversized.size} total)",
            oversized.isEmpty(),
        )
    }

    private companion object {
        /** Android 260.dp at density 3 is 780 px; desktop 620.dp at density 2 is 1240 px. */
        const val MAX_DISPLAY_PX = 1280
    }
}
