package dev.mascwa.pulse.desktop.library

import dev.mascwa.pulse.core.telemetry.CATEGORY_SUPERGROUP
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the library is actually IN the build, and whole.
 *
 * The desktop reads the Knowledge Base off the classpath, put there by a `processResources` copy from
 * the Android app's asset directory. Every way that can go wrong — the copy not running, landing under
 * the wrong prefix, the index falling out of lockstep with the shards, a guide naming a diagram that
 * was never packaged — produces an app that compiles perfectly and is empty or broken when opened. On
 * Windows. Where nobody here can see it.
 *
 * So it is checked at build time instead, against the real bundled corpus rather than a fixture.
 */
class LibraryBundleTest {

    private val repo = LibraryRepository(Json { ignoreUnknownKeys = true })

    @Test
    fun theIndexIsBundledAndSubstantial() = runBlocking {
        val index = repo.index()
        // A floor, not the exact count: the corpus grows with every content wave and a test that had to
        // be edited each time would be edited without being read.
        assertTrue("only ${index.size} guides bundled — did processResources run?", index.size >= 500)
        assertTrue(index.all { it.id.isNotBlank() && it.title.isNotBlank() })
        assertTrue("every entry must name its shard", index.all { it.file.isNotBlank() })
    }

    @Test
    fun everyCategoryIsMappedIntoTheTaxonomy() {
        runBlocking {
            val unmapped = repo.index().map { it.category }.distinct()
                .filter { CATEGORY_SUPERGROUP[it] == null }
            // The Android side asserts the same thing; a category added there and not here would put a
            // whole section of the library under "Other" on the desktop and nowhere else.
            assertTrue("categories with no supergroup: $unmapped", unmapped.isEmpty())
        }
    }

    /**
     * The index names a shard per guide; opening one has to actually produce that guide. This is the
     * lockstep check — a stale index is the failure mode that leaves the browse rail full and every
     * guide blank.
     */
    @Test
    fun aGuideFromEveryShardOpens() = runBlocking {
        val bySpread = repo.index().groupBy { it.file }
        assertTrue("expected many shards, got ${bySpread.size}", bySpread.size >= 10)
        bySpread.forEach { (file, entries) ->
            val first = entries.first()
            val guide = repo.guide(first.id)
            assertNotNull("shard $file did not yield ${first.id}", guide)
            assertEquals(first.title, guide!!.title)
            assertTrue("${first.id} has no sections", guide.sections.isNotEmpty())
        }
    }

    @Test
    fun theIndexHeadingsMatchTheGuideTheyPointAt() = runBlocking {
        val entry = repo.index().first { it.headings.size >= 3 }
        val guide = repo.guide(entry.id)!!
        assertEquals(entry.headings, guide.sections.map { it.heading })
    }

    /**
     * ⚠️ The one that justifies bundling 69 MB of diagrams: every image a guide names must be packaged.
     * A reference that resolves on Android but was left out of the desktop copy is a blank space in the
     * reader, and knots and first aid are precisely the guides that need their figures.
     */
    @Test
    fun everyDiagramAGuideNamesIsPackaged() = runBlocking {
        val named = LinkedHashSet<String>()
        repo.index().map { it.file }.distinct().forEach { file ->
            // Walk the shards rather than every guide id — same coverage, one parse per shard.
            repo.index().filter { it.file == file }.forEach { e ->
                repo.guide(e.id)?.sections?.forEach { s -> s.image?.let { named += it } }
            }
        }
        assertTrue("no guide references a diagram — the schema or the copy is wrong", named.size >= 100)
        val missing = named.filter { repo.imageBytes(it) == null }
        assertTrue("diagrams referenced but not packaged (${missing.size}): ${missing.take(5)}", missing.isEmpty())
    }

    @Test
    fun theDiagramProvenanceNoticeIsBundled() = runBlocking {
        // Several of these images are CC-BY / CC-BY-SA: shipping them without the attribution file is a
        // licensing failure, not a cosmetic one.
        val notice = repo.imageNotice()
        assertTrue("NOTICE.txt is missing from the bundle", notice.length > 200)
        assertTrue(notice.contains("PUBLIC DOMAIN", true) || notice.contains("licens", true))
    }

    @Test
    fun bodySearchFindsSomethingAndStaysBounded() = runBlocking {
        // A distinctive phrase from the corpus, streamed shard by shard.
        val hits = repo.searchBodies("hypothermia").toList().flatten()
        assertTrue("body search found nothing", hits.isNotEmpty())
        assertTrue(hits.all { id -> repo.index().any { it.id == id } })
        // A blank query must not scan the whole corpus.
        assertTrue(repo.searchBodies("   ").toList().isEmpty())
    }
}
