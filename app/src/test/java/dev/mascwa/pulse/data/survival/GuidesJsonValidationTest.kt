package dev.mascwa.pulse.data.survival

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Validates every bundled `assets/survival/guides*.json` file the same way
 * [SurvivalContentRepository.index]/[SurvivalContentRepository.guide] load them at runtime — decodes each
 * file independently, then builds
 * the full merged catalog and checks the invariants the Knowledge Base authoring pipeline promises. Before
 * this test existed, CI never decoded the guide catalog at all: a malformed JSON asset would have shipped
 * green. Plain JVM test (no Android/Robolectric needed) — [GuideModels] is pure kotlinx.serialization.
 */
class GuidesJsonValidationTest {

    /** Every category string in use today. A new category is a deliberate content decision — add it here
     *  when you introduce one, so a stray typo/near-duplicate ("Cooking-Breads" vs "Cooking — Breads") fails
     *  loudly instead of silently splitting the browse rail. */
    private val knownCategories = setOf(
        "Astronomy", "Biology", "Chemistry", "Climate", "Computing", "Earth Science", "Engineering",
        "Essentials", "Foundations", "Geography", "Hazards", "Health", "Making", "Mathematics", "Medical",
        "Movement", "Navigation", "Nutrition", "Physics", "Preparedness", "Psychology", "Reference", "Rescue",
        "Skills", "Sustenance", "Weather",
        "Cooking — Food Safety",
        // The 10,000-topic ontology's added categories (tools/kb/topic_manifest.json is the topic ledger).
        "Energy & Environment", "Sports & Fitness", "Electronics", "Home & Repair", "Agriculture & Gardening",
        "Vehicles & Transport", "Cooking — Techniques", "History", "Philosophy", "Religion & Mythology",
        "Literature & Writing", "Language & Linguistics", "Society & Culture", "Archaeology & Anthropology",
        "Law & Government", "Education & Learning", "Music", "Visual Arts & Design", "Games & Recreation",
        "Media & Communication", "Business & Finance", "Economics",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun survivalAssetsDir(): File {
        // Gradle runs :app:testDebugUnitTest with the module directory as the working directory.
        val dir = File("src/main/assets/survival")
        check(dir.isDirectory) { "Expected ${dir.absolutePath} to exist — run this test via :app:testDebugUnitTest" }
        return dir
    }

    private fun guideFiles(): List<File> =
        survivalAssetsDir().listFiles { f -> f.isFile && f.name.startsWith("guides") && f.name.endsWith(".json") }
            ?.sortedBy { it.name }.orEmpty()

    private fun everyBundledImagePath(): Set<String> {
        val images = File(survivalAssetsDir(), "images")
        if (!images.isDirectory) return emptySet()
        return images.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(images).path.replace(File.separatorChar, '/') }
            .toSet()
    }

    @Test fun everyGuideFileDecodesIndependently() {
        val files = guideFiles()
        assertTrue("Expected at least one guides*.json under assets/survival/", files.isNotEmpty())
        for (f in files) {
            runCatching { json.decodeFromString(GuideBook.serializer(), f.readText()) }
                .onFailure { fail("${f.name} failed to decode: ${it.message}") }
        }
    }

    @Test fun mergedCatalogInvariants() {
        val guides = guideFiles().flatMap { f ->
            json.decodeFromString(GuideBook.serializer(), f.readText()).guides
        }
        assertTrue("Expected at least one guide across all guides*.json files", guides.isNotEmpty())

        val bundledImages = everyBundledImagePath()
        val seenIds = HashMap<String, String>() // id -> first guide title that claimed it
        val errors = mutableListOf<String>()

        for (g in guides) {
            val where = "guide '${g.id}' (\"${g.title}\")"

            if (g.id.isBlank()) errors += "blank id on a guide titled \"${g.title}\""
            if (g.title.isBlank()) errors += "blank title on $where"
            if (g.category.isBlank()) errors += "blank category on $where"
            if (g.summary.isBlank()) errors += "blank summary on $where"
            if (g.sections.isEmpty()) errors += "$where has zero sections"

            if (g.id.isNotBlank()) {
                val prior = seenIds.putIfAbsent(g.id, g.title)
                if (prior != null) errors += "duplicate id '${g.id}' (\"$prior\" vs \"${g.title}\")"
            }

            if (g.category.isNotBlank() && g.category !in knownCategories) {
                errors += "$where has unrecognized category \"${g.category}\" — add it to knownCategories if intentional"
            }

            g.safetyNote?.let { if (it.isBlank()) errors += "$where has a blank safetyNote (omit the field instead of \"\")" }
            if (g.category.startsWith("Cooking — ") && g.category != "Cooking — Food Safety" && g.safetyNote.isNullOrBlank()) {
                errors += "$where is a recipe (category \"${g.category}\") but has no safetyNote"
            }

            val headings = g.sections.map { it.heading }
            if (headings.size != headings.toSet().size) {
                val dupes = headings.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                errors += "$where has duplicate section heading(s): $dupes"
            }

            for (s in g.sections) {
                if (s.heading.isBlank()) errors += "$where has a blank section heading"
                val hasBody = s.body.isNotBlank()
                val hasIngredients = !s.ingredients.isNullOrEmpty()
                val hasSteps = !s.steps.isNullOrEmpty()
                if (!hasBody && !hasIngredients && !hasSteps) {
                    errors += "$where section \"${s.heading}\" has no body, ingredients, or steps — completely empty"
                }
                s.ingredients?.let { list ->
                    if (list.isEmpty()) errors += "$where section \"${s.heading}\" has an empty (non-null) ingredients list"
                    list.forEachIndexed { i, line -> if (line.isBlank()) errors += "$where section \"${s.heading}\" ingredients[$i] is blank" }
                }
                s.steps?.let { list ->
                    if (list.isEmpty()) errors += "$where section \"${s.heading}\" has an empty (non-null) steps list"
                    list.forEachIndexed { i, line -> if (line.isBlank()) errors += "$where section \"${s.heading}\" steps[$i] is blank" }
                }
                s.image?.let { img ->
                    if (img !in bundledImages) errors += "$where section \"${s.heading}\" references missing image \"$img\""
                }
            }
        }

        if (errors.isNotEmpty()) {
            fail("guides*.json validation failed (${errors.size} issue(s)):\n" + errors.joinToString("\n") { "  - $it" })
        }
    }

    /** Every category in [knownCategories] must have a supergroup mapping in [CATEGORY_SUPERGROUP] — an
     *  omission would silently drop that category's guides into the [OTHER] fallback bucket in the browse
     *  rail instead of a real supergroup. */
    @Test fun everyKnownCategoryHasASupergroup() {
        val unmapped = knownCategories.filter { it !in CATEGORY_SUPERGROUP }
        assertTrue(
            "These categories have no GuideTaxonomy.CATEGORY_SUPERGROUP mapping: $unmapped",
            unmapped.isEmpty(),
        )
    }

    /** `guide_index.json` (the small always-loaded catalog index the lazy loader relies on) must stay in
     *  lockstep with the shards — editing content without re-running `tools/kb/kb_pipeline.py` fails here
     *  instead of silently serving a stale index on-device. */
    @Test fun indexStaysInLockstepWithShards() {
        val indexFile = File(survivalAssetsDir(), "guide_index.json")
        assertTrue("guide_index.json missing — run tools/kb/kb_pipeline.py", indexFile.isFile)
        val index = json.decodeFromString(GuideIndex.serializer(), indexFile.readText()).entries
        val guidesByFile = guideFiles().associate { f ->
            f.name to json.decodeFromString(GuideBook.serializer(), f.readText()).guides
        }
        val allGuides = guidesByFile.values.flatten()
        assertEquals(
            "index entry count != guide count — re-run tools/kb/kb_pipeline.py",
            allGuides.size, index.size,
        )
        val byId = allGuides.associateBy { it.id }
        val errors = mutableListOf<String>()
        for (e in index) {
            val g = byId[e.id]
            if (g == null) {
                errors += "index entry '${e.id}' has no matching guide"
                continue
            }
            if (e.title != g.title || e.category != g.category || e.summary != g.summary) {
                errors += "index entry '${e.id}' drifted from its guide (title/category/summary)"
            }
            if (e.headings != g.sections.map { it.heading }) {
                errors += "index entry '${e.id}' headings drifted from its guide's sections"
            }
            val shard = guidesByFile[e.file]
            if (shard == null || shard.none { it.id == e.id }) {
                errors += "index entry '${e.id}' points at '${e.file}' which doesn't contain it"
            }
        }
        if (errors.isNotEmpty()) {
            fail("guide_index.json is out of lockstep (${errors.size} issue(s)) — re-run tools/kb/kb_pipeline.py:\n" +
                errors.joinToString("\n") { "  - $it" })
        }
    }

    /** The Knowledge Base's official growth metric: a FULL PAGE = one section whose body+ingredients+steps
     *  reach [FULL_PAGE_WORDS] words (about one printed page). The standing target is 10,000+; this only
     *  ever ratchets UP — bump [FULL_PAGE_BASELINE] as content waves land, never down. */
    @Test fun fullPageCountNeverRegresses() {
        val guides = guideFiles().flatMap { f ->
            json.decodeFromString(GuideBook.serializer(), f.readText()).guides
        }
        val fullPages = guides.sumOf { g -> g.sections.count { sectionWords(it) >= FULL_PAGE_WORDS } }
        println("KB FULL PAGES (>=$FULL_PAGE_WORDS words): $fullPages / 10,000 target")
        assertTrue(
            "Full-page count regressed: $fullPages < $FULL_PAGE_BASELINE — Knowledge Base content must only grow",
            fullPages >= FULL_PAGE_BASELINE,
        )
    }

    /** Whitespace-token count over body + ingredients + steps — kept algorithm-identical to
     *  tools/kb/kb_pipeline.py's `section_words` so the two report the same number. */
    private fun sectionWords(s: GuideSection): Int {
        var text = s.body
        s.ingredients?.let { text += " " + it.joinToString(" ") }
        s.steps?.let { text += " " + it.joinToString(" ") }
        return text.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    private companion object {
        const val FULL_PAGE_WORDS = 400

        /** Measured after expansion wave A1 completed (238 guides · 2,902 sections). Ratchet up as waves land. */
        const val FULL_PAGE_BASELINE = 1906
    }
}
