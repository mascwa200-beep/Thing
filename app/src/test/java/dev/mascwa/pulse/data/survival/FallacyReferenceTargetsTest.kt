package dev.mascwa.pulse.data.survival

import dev.mascwa.pulse.core.telemetry.Fallacies
import dev.mascwa.pulse.core.telemetry.FallacyReference
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import java.io.File

/**
 * Every fallacy reference must land on a section that actually exists in the shipped library.
 *
 * Exactly the guard [EmergencyTriageTargetsTest] provides for the emergency table, and for the same
 * reason: [FallacyReference] names guides and headings as strings — which is what keeps the core
 * pure and Android-free, and also what lets a content edit break the routing without anything
 * noticing. There are only two guides in 651 that discuss fallacies at all, so a re-edit of either
 * would take the citation and the adjudicator's reference with it.
 */
class FallacyReferenceTargetsTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Same resolution the sibling asset tests use: Gradle runs this with the module as cwd. */
    private fun allGuides(): List<Guide> {
        val dir = File("src/main/assets/survival")
        check(dir.isDirectory) { "Expected ${dir.absolutePath} — run via :app:testDebugUnitTest" }
        return dir.listFiles { f -> f.isFile && f.name.startsWith("guides") && f.name.endsWith(".json") }
            .orEmpty()
            .flatMap { json.decodeFromString(GuideBook.serializer(), it.readText()).guides }
    }

    @Test fun everyReferenceResolvesToARealGuideSection() {
        val guides = allGuides().associateBy { it.id }
        val problems = mutableListOf<String>()

        for (route in FallacyReference.allRoutes()) {
            val guide = guides[route.guideId]
            if (guide == null) {
                problems += "route to guide '${route.guideId}' — that guide no longer exists"
                continue
            }
            if (guide.sections.none { it.heading == route.heading }) {
                problems += "'${route.guideId}' ▸ \"${route.heading}\" is not one of its sections: " +
                    guide.sections.joinToString(", ") { "\"" + it.heading + "\"" }
            }
        }
        if (problems.isNotEmpty()) {
            fail("Fallacy references are broken (${problems.size}):\n" + problems.joinToString("\n") { "  - $it" })
        }
    }

    /**
     * ⚠️ Every fallacy in the taxonomy must reach a real page, including the ones with no special
     * route. The general section is what makes that true, so this fails if the default is ever
     * dropped or made nullable — a fallacy that reaches nothing would show a finding with no
     * citation and hand the adjudicator no reference, silently, for that one type only.
     */
    @Test fun everyFallacyInTheTaxonomyHasSomewhereToPointAt() {
        val known = allGuides().associateBy { it.id }
        for (f in Fallacies.ALL) {
            val route = FallacyReference.routeFor(f.id)
            val guide = known[route.guideId]
            assertTrue(
                "${f.id} points at '${route.guideId}', which is not bundled",
                guide != null,
            )
            assertTrue(
                "${f.id} points at \"${route.heading}\", which '${route.guideId}' does not have",
                guide!!.sections.any { it.heading == route.heading },
            )
        }
    }
}
