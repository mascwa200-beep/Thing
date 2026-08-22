package dev.mascwa.pulse.data.survival

import dev.mascwa.pulse.core.telemetry.GuideBook
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled diagrams are real images, no larger than the reader draws them, and none is dead weight.
 *
 * ⚠️ **Android had a presence check and nothing else.** [GuidesJsonValidationTest] asserts that a
 * referenced image is on disk — and only ever in that direction, so nothing here decoded a file,
 * measured one, or noticed a file no guide points at. Both gaps have bitten:
 *
 *  - An aborted image wave left **59 files referenced by nothing** in the tree. They ship inside the
 *    APK and inside the desktop jar, every check green, and the only symptom is a bigger download.
 *  - A saved error page written out under a `.webp` or `.svg` name is the ordinary way image
 *    sourcing fails silently. It is not visible to the compiler, to the presence check, or to a
 *    human reading a diff of binary assets.
 *
 * The desktop has [BundledImagesDecodeTest] and its vector twin, which decode with Skia and are the
 * stronger check — but they run on a copy of these same files in a different module, and the phone
 * is where the corpus actually lives. This is the gate on the source of truth.
 *
 * ⚠️ **Rasters are checked by parsing the container header, not by decoding.** The corpus is
 * entirely WebP and `javax.imageio` has no WebP reader on a stock JVM, so a decode-based test here
 * would need a new test dependency to say less than this does. The header carries the magic bytes
 * and the true canvas size, which is exactly what distinguishes a real image from an error page and
 * what the size rule needs. Full pixel decoding is the desktop tests' job.
 *
 * Plain JVM: no Android, no Robolectric. [GuideModels] is pure kotlinx.serialization.
 */
class BundledImagesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun assets(): File {
        val dir = File("src/main/assets/survival")
        check(dir.isDirectory) { "Expected ${dir.absolutePath} — run via :app:testDebugUnitTest" }
        return dir
    }

    private fun imagesDir() = File(assets(), "images")

    /** Every path a guide points at, relative to `images/`, exactly as the readers resolve it. */
    private fun referenced(): Set<String> {
        val out = sortedSetOf<String>()
        assets().listFiles { f -> f.isFile && f.name.startsWith("guides") && f.name.endsWith(".json") }
            ?.forEach { f ->
                json.decodeFromString(GuideBook.serializer(), f.readText())
                    .guides.forEach { g -> g.sections.forEach { s -> s.image?.let { out += it } } }
            }
        return out
    }

    /** Every image file actually on disk, relative to `images/`. NOTICE.txt is documentation. */
    private fun onDisk(): Set<String> =
        imagesDir().walkTopDown().filter { it.isFile && !it.name.endsWith(".txt") }
            .map { it.relativeTo(imagesDir()).path.replace(File.separatorChar, '/') }
            .toSortedSet()

    @Test
    fun nothingShipsThatNoGuidePointsAt() {
        val orphans = onDisk() - referenced()
        assertTrue(
            "images bundled into the APK and the desktop jar that no guide references, so they are " +
                "downloaded and stored to be shown to nobody — usually the residue of an image wave " +
                "that was stopped part-way: ${orphans.take(10)} (${orphans.size} total)",
            orphans.isEmpty(),
        )
    }

    @Test
    fun everyRasterIsARealImageNoWiderThanTheReadersDrawIt() {
        val problems = mutableListOf<String>()
        for (name in referenced()) {
            if (name.endsWith(".svg", ignoreCase = true)) continue
            val f = File(imagesDir(), name)
            if (!f.isFile) continue                       // absence is the presence test's finding
            val bytes = f.readBytes()
            val size = webpSize(bytes)
            when {
                size == null -> problems += "$name: not a WebP image (${bytes.size} bytes, " +
                    "starts \"${bytes.take(8).joinToString("") { b -> "%02x".format(b) }}\")"
                size.first <= 0 || size.second <= 0 -> problems += "$name: ${size.first}x${size.second}"
                size.first > MAX_DISPLAY_PX -> problems += "$name: ${size.first}px wide"
            }
        }
        assertTrue(
            "diagrams that are not images, or are wider than either reader can draw so the extra " +
                "pixels are pure download and decode cost: ${problems.take(8)} " +
                "(${problems.size} total)",
            problems.isEmpty(),
        )
    }

    @Test
    fun everyVectorIsRealSvgAndNotPathologicallyLarge() {
        val problems = mutableListOf<String>()
        for (name in referenced()) {
            if (!name.endsWith(".svg", ignoreCase = true)) continue
            val f = File(imagesDir(), name)
            if (!f.isFile) continue
            val bytes = f.readBytes()
            // Read only the head: an SVG may declare an XML prolog, a doctype and comments before
            // the root element, and a saved error page has "<html" within the same distance.
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
            when {
                !head.contains("<svg", ignoreCase = true) ->
                    problems += "$name: no <svg> root in the first 2 kB"
                bytes.size > MAX_SVG_BYTES ->
                    problems += "$name: ${bytes.size / 1024} kB"
                // ⚠️ **This branch is the floor, and its absence shipped three defects.** The vector
                // check had a ceiling only, while rasters below are floored by width — so graphical
                // FRAGMENTS passed as diagrams. `Shogi da22.svg` is a 9x9 canvas holding one
                // diagonal line, a piece of a board-tile set, and it was the sole illustration of a
                // whole guide; a sibling and a lone 64x64 notation glyph did the same.
                //
                // Poor by BOTH measures, because either alone is wrong: element count throws out a
                // real 7 kB diagram drawn as four complex paths, and byte count throws out a lean
                // but complete one. tools/kb/source_images.py carries the measurement over all
                // bundled vectors that placed these two numbers, and tools/kb/check_images.py reads
                // them straight out of it so the local gate and this one cannot disagree.
                bytes.size < MIN_SVG_BYTES &&
                    SVG_ELEMENT.findAll(String(bytes, Charsets.UTF_8)).count() < MIN_SVG_ELEMENTS ->
                    problems += "$name: too sparse to be a diagram (${bytes.size} B)"
            }
        }
        assertTrue(
            "vectors that are not SVG, heavy enough to stall a phone renderer, or too sparse to be " +
                "a diagram at all: ${problems.take(8)} (${problems.size} total)",
            problems.isEmpty(),
        )
    }

    private companion object {
        /** Android draws a diagram at 260.dp (780 px at density 3); the desktop at 620.dp. */
        const val MAX_DISPLAY_PX = 1280

        /**
         * Matches `MAX_SVG_BYTES` in tools/kb/source_images.py.
         *
         * ⚠️ **Two vectors used to be over this and are not exempted — they were converted.** The
         * bar was written for what an image wave may *add*, and applying it to two hand-curated
         * legacy files looked at first like a case for an allowlist. Then the desktop's new
         * `BundledSvgDiagramsParseTest` reported that Skia could not parse `circadian-clock.svg` at
         * **all** ("Can't wrap nullptr"), so that diagram had been failing to a caption on every
         * Windows machine since it was bundled — the same silent failure as the `.gif`, invisible
         * for the same reason: SVGs were excluded from the desktop tests. Both were rendered to
         * WebP at 1280 px, which fixes the render and takes them from 981 kB and 456 kB to 93 kB
         * and 76 kB. There is nothing left to exempt, which is the better outcome than a list.
         */
        const val MAX_SVG_BYTES = 400_000

        /** The vector floor. Kept in step with tools/kb/source_images.py, which reads these
         *  names; see the check that uses them for the measurement that placed them. */
        const val MIN_SVG_BYTES = 1_000
        const val MIN_SVG_ELEMENTS = 6
        val SVG_ELEMENT = Regex(
            "<(?:path|circle|rect|line|polyline|polygon|ellipse|text|image|use)\\b",
            RegexOption.IGNORE_CASE,
        )

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

        /**
         * Canvas size from a WebP container, or null if this is not one.
         *
         * A WebP is `RIFF<u32 size>WEBP` followed by chunks. Three carry a size, and all three are
         * in the corpus' plausible range, so all three are read rather than assuming the encoder's
         * current choice: `VP8 ` (lossy, 14-bit dimensions after the 3-byte start code
         * `9d 01 2a`), `VP8L` (lossless, 14-bit dimensions minus one packed after a `2f` signature)
         * and `VP8X` (extended, 24-bit canvas dimensions minus one).
         */
        fun webpSize(b: ByteArray): Pair<Int, Int>? {
            if (b.size < 30) return null
            if (String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(b, 8, 4, Charsets.US_ASCII) != "WEBP") return null
            return when (String(b, 12, 4, Charsets.US_ASCII)) {
                "VP8 " -> {
                    if (u8(b, 23) != 0x9d || u8(b, 24) != 0x01 || u8(b, 25) != 0x2a) null
                    else Pair(
                        (u8(b, 26) or (u8(b, 27) shl 8)) and 0x3FFF,
                        (u8(b, 28) or (u8(b, 29) shl 8)) and 0x3FFF,
                    )
                }
                "VP8L" -> {
                    if (u8(b, 20) != 0x2f) null else {
                        val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                        Pair((bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)
                    }
                }
                "VP8X" -> Pair(
                    (u8(b, 24) or (u8(b, 25) shl 8) or (u8(b, 26) shl 16)) + 1,
                    (u8(b, 27) or (u8(b, 28) shl 8) or (u8(b, 29) shl 16)) + 1,
                )
                else -> null
            }
        }
    }
}
