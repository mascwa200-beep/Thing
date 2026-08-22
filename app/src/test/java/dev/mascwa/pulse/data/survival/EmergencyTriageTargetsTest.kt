package dev.mascwa.pulse.data.survival

import dev.mascwa.pulse.core.telemetry.EmergencyTriage
import dev.mascwa.pulse.core.telemetry.Guide
import dev.mascwa.pulse.core.telemetry.GuideBook
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every emergency route must land on a section that actually exists in the shipped library.
 *
 * `EmergencyTriage` names guides and section headings as strings, which is what lets the core stay
 * pure and Android-free — and also what lets a content edit silently break the routing. This closes
 * that: rename a section in `guides_medical.json` and the build fails here rather than the app
 * quietly sending someone doing CPR to a page that no longer exists.
 */
class EmergencyTriageTargetsTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Same resolution [GuidesJsonValidationTest] uses: Gradle runs this with the module as cwd. */
    private fun survivalAssetsDir(): File {
        val dir = File("src/main/assets/survival")
        check(dir.isDirectory) { "Expected ${dir.absolutePath} — run via :app:testDebugUnitTest" }
        return dir
    }

    private fun allGuides(): List<Guide> =
        survivalAssetsDir().listFiles { f -> f.isFile && f.name.startsWith("guides") && f.name.endsWith(".json") }
            .orEmpty()
            .flatMap { json.decodeFromString(GuideBook.serializer(), it.readText()).guides }

    @Test fun everyRoutedEmergencyResolvesToARealGuideSection() {
        val guides = allGuides().associateBy { it.id }
        val problems = mutableListOf<String>()

        for (e in EmergencyTriage.EMERGENCIES) {
            val gid = e.guideId ?: continue // an admitted gap is checked by the core's own test
            val guide = guides[gid]
            if (guide == null) {
                problems += "${e.id} routes to guide '$gid', which no longer exists"
                continue
            }
            val section = e.section
            if (guide.sections.none { it.heading == section }) {
                problems += "${e.id} routes to '$gid' ▸ \"$section\", which is not one of its " +
                    "sections: ${guide.sections.joinToString(", ") { "\"" + it.heading + "\"" }}"
            }
        }
        if (problems.isNotEmpty()) {
            fail("Emergency routing is broken (${problems.size}):\n" + problems.joinToString("\n") { "  - $it" })
        }
    }

    /** The routed set must stay the majority: triage exists to reach protocols, not to apologise. */
    @Test fun mostRecognisedEmergenciesHaveAProtocolBehindThem() {
        val covered = EmergencyTriage.EMERGENCIES.count { it.covered }
        assertTrue(
            "only $covered of ${EmergencyTriage.EMERGENCIES.size} emergencies route anywhere",
            covered * 2 > EmergencyTriage.EMERGENCIES.size,
        )
    }
}
