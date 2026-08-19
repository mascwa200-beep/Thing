package dev.mascwa.pulse.desktop.library

import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.loadSvgPainter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Every bundled SVG diagram parses, with the call the reader itself makes.
 *
 * ⚠️ **This exists because [BundledImagesDecodeTest] excludes SVGs from both of its tests, by an
 * explicit `filterNot { it.endsWith(".svg") }`.** Its reasoning is sound as far as it goes —
 * `loadSvgPainter` needs a [Density] and returns a painter rather than a bitmap, so it is a
 * different call with a different failure mode — but that is an argument for a second test, not for
 * no coverage. It was a defensible gap when the corpus held 15 vectors. An image wave that is more
 * than half SVG makes it the largest untested surface in the bundle, and the whole reason
 * [BundledImagesDecodeTest] was written is that a format Skia cannot read compiles, packages and
 * ships perfectly, failing for the first time when somebody opens that page on Windows.
 *
 * The realistic failures this catches are not exotic. A saved error page written out under a `.svg`
 * name is the ordinary way image sourcing fails silently; so is a truncated download, and so is
 * SVG the renderer does not support. None of those is visible to the compiler, to
 * `LibraryBundleTest`'s presence check, or to a human reading a diff of binary assets.
 *
 * `Density(1f)` because this asks whether the file parses at all, which is independent of the
 * density the reader will eventually draw it at.
 */
class BundledSvgDiagramsParseTest {

    @Test
    fun everySvgDiagramParsesWithTheReadersOwnLoader() = runBlocking {
        val repo = LibraryRepository()
        val svgs = sortedSetOf<String>()
        for (entry in repo.index()) {
            repo.guide(entry.id)?.sections?.forEach { s ->
                s.image?.let { if (it.endsWith(".svg", ignoreCase = true)) svgs += it }
            }
        }
        assertTrue("the corpus should name some vector diagrams, found ${svgs.size}", svgs.isNotEmpty())

        val density = Density(1f)
        val failures = mutableListOf<String>()
        for (name in svgs) {
            val bytes = repo.imageBytes(name)
            if (bytes == null || bytes.isEmpty()) {
                failures += "$name: not on the classpath"
                continue
            }
            val parsed = runCatching { loadSvgPainter(ByteArrayInputStream(bytes), density) }
            val painter = parsed.getOrNull()
            when {
                painter == null -> failures += "$name: ${parsed.exceptionOrNull()}"
                // A painter with no intrinsic size draws nothing at all, which is the shape a saved
                // error page takes once Skia has been persuaded to parse it.
                painter.intrinsicSize.width <= 0f || painter.intrinsicSize.height <= 0f ->
                    failures += "$name: parsed to an empty ${painter.intrinsicSize}"
            }
        }
        assertTrue(
            "vector diagrams that will not draw on the desktop: ${failures.take(8)} " +
                "(${failures.size} of ${svgs.size})",
            failures.isEmpty(),
        )
    }

    /**
     * No vector is so large that opening its page stalls the reader.
     *
     * An SVG has no pixel dimensions to cap, so the sibling raster test's width rule has nothing to
     * check here — but a pathological vector is a real cost, paid on every draw rather than once at
     * decode. The bound is the same one the sourcing tool applies (`MAX_SVG_BYTES`), stated again
     * on this side so a file added by hand is held to it too.
     */
    @Test
    fun noVectorDiagramIsPathologicallyLarge() = runBlocking {
        val repo = LibraryRepository()
        val oversized = mutableListOf<String>()
        for (entry in repo.index()) {
            repo.guide(entry.id)?.sections?.forEach { s ->
                val name = s.image ?: return@forEach
                if (!name.endsWith(".svg", ignoreCase = true)) return@forEach
                val size = repo.imageBytes(name)?.size ?: return@forEach
                if (size > MAX_SVG_BYTES) oversized += "$name (${size / 1024} kB)"
            }
        }
        assertTrue(
            "vectors heavy enough to stall a phone renderer: ${oversized.take(8)} " +
                "(${oversized.size} total)",
            oversized.isEmpty(),
        )
    }

    private companion object {
        /** Matches `MAX_SVG_BYTES` in tools/kb/source_images.py — a diagram never needs this much. */
        const val MAX_SVG_BYTES = 400_000
    }
}
