package dev.mascwa.pulse.desktop.library

import dev.mascwa.pulse.desktop.telemetry.ContentPack
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackArchiveTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun shard(id: String, body: String) =
        """{"guides":[{"id":"$id","title":"T","category":"C","summary":"S",
           "sections":[{"heading":"H","body":"$body"}]}]}"""

    // ---- what comes out of an archive ---------------------------------------------------------------

    @Test
    fun onlyJsonShardsAreTakenAndFoldersAreFlattened() {
        val bytes = zip(
            "guides_a.json" to shard("a", "one").toByteArray(),
            "nested/guides_b.json" to shard("b", "two").toByteArray(),
            "README.md" to "ignore me".toByteArray(),
            "images/diagram.png" to byteArrayOf(1, 2, 3),
        )
        val read = PackArchive.read(bytes).getOrThrow()
        assertEquals(setOf("guides_a.json", "guides_b.json"), read.keys)
        assertTrue(read.getValue("guides_b.json").contains("\"id\":\"b\""))
    }

    /**
     * ⚠️ The defect this test exists for: reading in 8 KB blocks and decoding **each block** as UTF-8
     * splits any character that straddles the seam.
     *
     * This corpus is full of em dashes, degree signs and middots, so the damage is silent and shows up
     * only as mojibake in the reader, in whichever shard was unlucky enough to sit on the boundary.
     * The padding here puts a multi-byte character across every 8 KB mark in the entry.
     */
    @Test
    fun multiByteCharactersSurviveTheReadBoundary() {
        // ⚠️ Density, not placement, is what makes this bite. A read boundary lands wherever the
        // inflater decides, so a test that tries to *aim* padding at the 8 KB mark proves nothing —
        // the first version of this test did exactly that, passed against the broken reader, and was
        // therefore no guard at all. A body that is almost entirely three-byte characters leaves a
        // chunked decoder no clean boundary to land on across a megabyte of content.
        val body = "—".repeat(300_000) + " 20°C · ±3 µm ✓"
        val text = shard("wide", body)
        val encoded = text.toByteArray(Charsets.UTF_8)
        assertTrue("the fixture is not large enough to cross many read boundaries", encoded.size > 512 * 1024)

        val got = PackArchive.read(zip("guides_wide.json" to encoded)).getOrThrow().getValue("guides_wide.json")

        assertEquals("the archive did not round-trip byte for byte", text, got)
        assertEquals(300_000, got.count { it == '—' })
        assertTrue("replacement characters — the decode was chunked", '�' !in got)
        // And it is still parseable, which is what actually matters downstream.
        assertEquals("wide", json.decodeFromString(GuideBook.serializer(), got).guides.single().id)
    }

    // ---- an archive is remote content ---------------------------------------------------------------

    @Test
    fun anArchiveWithNothingUsableInItIsRefused() {
        assertTrue(PackArchive.read(zip("README.md" to "hello".toByteArray())).isFailure)
        assertTrue(PackArchive.read(ByteArray(0)).isFailure)
        assertTrue("not a zip at all", PackArchive.read("plain text".toByteArray()).isFailure)
        assertTrue(PackArchive.read(zip("empty.json" to ByteArray(0))).isFailure)
    }

    /**
     * ⚠️ A zip that expands to far more than it weighs is a few kilobytes on the wire and an
     * out-of-memory kill on somebody's phone. The reader stops at the breach rather than finishing
     * the entry and judging afterwards.
     */
    @Test
    fun anArchiveThatExpandsWildlyIsStoppedRatherThanRead() {
        val huge = ByteArray(PackArchive.MAX_ENTRY_BYTES.toInt() + 1024) { 'x'.code.toByte() }
        val bytes = zip("guides_bomb.json" to huge)
        // Compresses to almost nothing; the guard is on what comes out, not what went in.
        assertTrue("the archive was not even suspicious on the wire", bytes.size < huge.size / 100)
        val result = PackArchive.read(bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("too large"))
    }

    @Test
    fun tooManyFilesIsRefused() {
        val entries = (0..PackArchive.MAX_ENTRIES).map { "guides_$it.json" to shard("g$it", "b").toByteArray() }
        assertTrue(PackArchive.read(zip(*entries.toTypedArray())).isFailure)
    }

    // ---- end to end ---------------------------------------------------------------------------------

    /** Archive to installed to readable, through the real store and the real bundled library. */
    @Test
    fun anArchiveBecomesPartOfTheLibrary() = runBlocking {
        val packs = PackStore(json, tmp.root.toPath().resolve("packs"))
        val lib = LibraryRepository(json, packs)
        val before = lib.index().size

        val bytes = zip(
            "guides_cooking.json" to shard("braising", "Sear it hard — then braise at 150°C.").toByteArray(),
        )
        val shards = PackArchive.read(bytes).getOrThrow()
        packs.install(
            ContentPack.Pack("cooking", "Cooking", "s", 1, 0L, 1, "https://example.invalid/c.zip"),
            shards,
        ).getOrThrow()

        assertEquals(before + 1, lib.index().size)
        val guide = lib.guide("braising")
        assertNotNull(guide)
        assertTrue("the degree sign did not survive", guide!!.sections.single().body.contains("150°C"))
    }
}
