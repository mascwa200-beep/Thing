package dev.mascwa.pulse.desktop.library

import dev.mascwa.pulse.core.telemetry.ContentPack
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * Expansion packs, against the **real bundled library**.
 *
 * This is the claim the whole feature rests on and the one CI cannot make on Android: install a pack,
 * and from that moment its guides are in the library — listed, openable, searchable — with no network
 * anywhere in the reading path. Here the store is a folder and the library is on the classpath, so the
 * whole thing genuinely runs.
 */
class PackStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun store() = PackStore(json, tmp.root.toPath().resolve("packs"))
    private fun library(packs: PackStore) = LibraryRepository(json, packs)

    private fun pack(id: String = "cooking", version: Int = 1) = ContentPack.Pack(
        id = id, title = "Cooking", summary = "s", version = version,
        sizeBytes = 0L, guideCount = 1, url = "https://example.invalid/$id.zip",
    )

    /** A minimal but real shard — the same shape the bundle ships. */
    private fun shard(vararg guides: Pair<String, String>): String {
        val body = guides.joinToString(",") { (id, title) ->
            """{"id":"$id","title":"$title","category":"Cooking","summary":"About $title.",
               "sections":[{"heading":"Method","body":"Sear the ${title.lowercase()} hard, then braise it slowly."}]}"""
        }
        return """{"guides":[$body]}"""
    }

    // ---- the whole claim ----------------------------------------------------------------------------

    @Test
    fun aninstalledPackIsPartOfTheLibrary() = runBlocking {
        val packs = store()
        val lib = library(packs)
        val before = lib.index().size
        assertTrue("the bundled library did not load", before > 100)
        assertNull("the pack's guide cannot be there yet", lib.guide("braising"))

        val installed = packs.install(pack(), mapOf("guides_cooking.json" to shard("braising" to "Braising")))
            .getOrThrow()
        assertEquals("cooking", installed.id)

        // Listed…
        val after = lib.index()
        assertEquals(before + 1, after.size)
        assertEquals("Braising", after.first { it.id == "braising" }.title)
        // …openable, with its real content…
        val guide = lib.guide("braising")
        assertNotNull(guide)
        assertEquals("Method", guide!!.sections.first().heading)
        assertTrue(guide.sections.first().body.contains("braise"))
        // …and searchable by body text, alongside the bundle.
        val hits = lib.searchBodies("Sear the braising hard").toList().flatten()
        assertTrue("body search did not reach into the pack: $hits", "braising" in hits)
        // The category rail sees it too — nothing had to be told about packs.
        assertTrue(lib.categories().any { it.first == "Cooking" })
    }

    /**
     * ⚠️ A pack can add to the library and can never shadow it.
     *
     * The bundled corpus is the one CI validates and the one the emergency protocols live in. This
     * uses a real bundled id, so a change to the merge rule fails here rather than in somebody's hands.
     */
    @Test
    fun aPackCannotReplaceABundledGuide() = runBlocking {
        val packs = store()
        val lib = library(packs)
        val victim = "soil-testing-and-interpreting-the-results"
        val bundled = lib.guide(victim)
        assertNotNull("the bundled fixture guide is gone — pick another id", bundled)

        packs.install(
            pack(id = "hostile"),
            mapOf("guides_x.json" to shard(victim to "Not The Real One", "extra" to "Extra")),
        ).getOrThrow()

        val after = lib.guide(victim)
        assertEquals("the bundled guide was shadowed", bundled!!.title, after!!.title)
        assertEquals(bundled.category, after.category)
        assertEquals(1, lib.index().count { it.id == victim })
        // The pack's non-colliding guide still arrived, so this is the merge rule and not a refusal.
        assertNotNull(lib.guide("extra"))
    }

    // ---- installing badly ---------------------------------------------------------------------------

    /**
     * ⚠️ All of a pack or none of it.
     *
     * A half-readable pack is worse than one that never arrived, because the half that failed is
     * invisible — it looks exactly like content the pack never claimed to have.
     */
    @Test
    fun oneBadShardRefusesTheWholePack() = runBlocking {
        val packs = store()
        val lib = library(packs)
        val before = lib.index().size

        val result = packs.install(
            pack(),
            mapOf(
                "good.json" to shard("braising" to "Braising"),
                "bad.json" to "{ this is not a guide shard",
            ),
        )
        assertTrue("a corrupt shard was accepted", result.isFailure)
        assertTrue(packs.installed().isEmpty())
        assertEquals("the good half leaked in", before, lib.index().size)
        assertNull(lib.guide("braising"))
        // And nothing was left lying in the packs folder.
        val dir = tmp.root.toPath().resolve("packs")
        val stray = if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.count() } else 0L
        assertEquals("staging or shards left behind", 0L, stray)
    }

    @Test
    fun anEmptyOrUnusablePackIsRefused() = runBlocking {
        val packs = store()
        assertTrue(packs.install(pack(), emptyMap()).isFailure)
        assertTrue(packs.install(pack(id = ""), mapOf("a.json" to shard("a" to "A"))).isFailure)
        assertTrue(packs.install(pack(), mapOf("a.json" to """{"guides":[]}""")).isFailure)
        assertTrue(packs.installed().isEmpty())
    }

    /** ⚠️ A shard that names its way upward lands inside the packs folder like every other. */
    @Test
    fun aShardCannotWriteOutsideThePacksFolder() = runBlocking {
        val packs = store()
        packs.install(pack(), mapOf("../guide_index.json" to shard("braising" to "Braising"))).getOrThrow()

        val dir = tmp.root.toPath().resolve("packs")
        assertTrue(Files.isRegularFile(dir.resolve("cooking__guide_index.json")))
        assertFalse("it escaped", Files.exists(tmp.root.toPath().resolve("guide_index.json")))
        assertNotNull(library(packs).guide("braising"))
    }

    // ---- lifecycle ----------------------------------------------------------------------------------

    @Test
    fun anUpdateReplacesTheOldFilesRatherThanAccumulating() = runBlocking {
        val packs = store()
        val lib = library(packs)
        packs.install(pack(version = 1), mapOf("v1.json" to shard("braising" to "Braising"))).getOrThrow()
        val baseline = lib.index().size

        packs.install(
            pack(version = 2),
            mapOf("v2.json" to shard("braising" to "Braising, Revised", "stock" to "Stock")),
        ).getOrThrow()

        assertEquals(2, packs.installed().single().version)
        assertEquals("the old shard is still contributing", baseline + 1, lib.index().size)
        assertEquals("Braising, Revised", lib.guide("braising")!!.title)
        val dir = tmp.root.toPath().resolve("packs")
        assertFalse(Files.exists(dir.resolve("cooking__v1.json")))
    }

    @Test
    fun removingAPackTakesItsGuidesOutAndLeavesTheBundleAlone() = runBlocking {
        val packs = store()
        val lib = library(packs)
        val before = lib.index().size
        packs.install(pack(), mapOf("a.json" to shard("braising" to "Braising"))).getOrThrow()
        assertEquals(before + 1, lib.index().size)

        assertTrue(packs.remove("cooking"))
        assertEquals(before, lib.index().size)
        assertNull(lib.guide("braising"))
        assertNotNull("the bundle was harmed", lib.guide("soil-testing-and-interpreting-the-results"))
        assertFalse("removing something absent claimed success", packs.remove("cooking"))
    }

    /**
     * A pack recorded but no longer on disk — a swept temp folder, an interrupted uninstall — costs its
     * own content and nothing else. The library still opens.
     */
    @Test
    fun aPackWhoseFilesHaveVanishedIsIgnoredRatherThanFatal() = runBlocking {
        val dir = tmp.root.toPath().resolve("packs")
        run {
            val packs = store()
            packs.install(pack(), mapOf("a.json" to shard("braising" to "Braising"))).getOrThrow()
        }
        Files.delete(dir.resolve("cooking__a.json"))

        // A fresh store, so the manifest is re-read from disk rather than served from memory.
        val reopened = PackStore(json, dir)
        assertTrue(reopened.installed().isEmpty())
        val lib = library(reopened)
        assertTrue(lib.index().size > 100)
        assertNull(lib.guide("braising"))
    }

    @Test
    fun anUnreadableManifestIsToleratedRatherThanFatal() = runBlocking {
        val dir = tmp.root.toPath().resolve("packs")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("packs.json"), "{ not json")
        val packs = PackStore(json, dir)
        assertTrue(packs.installed().isEmpty())
        // And it can still install over the top.
        packs.install(pack(), mapOf("a.json" to shard("braising" to "Braising"))).getOrThrow()
        assertNotNull(library(packs).guide("braising"))
    }
}
