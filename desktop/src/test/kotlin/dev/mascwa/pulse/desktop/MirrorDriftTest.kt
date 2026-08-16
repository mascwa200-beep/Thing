package dev.mascwa.pulse.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guard the copy-not-share convention never had.
 *
 * `:desktop` declares no dependency on `:app` or `:core:*` — that is what keeps it buildable without AGP
 * or the Android SDK — so shared pure logic is **copied** rather than imported. A copy with nothing
 * watching it drifts, and this one already had: before this test existed, `NewsMarketLink` and
 * `NewsExplainers` differed from their originals in real code, not merely in comments, and no gate
 * anywhere would have said so. The repo has separately had to correct duplicated-definition drift four
 * times over palettes.
 *
 * A mirror is allowed to differ in exactly three mechanical ways, each of them unavoidable: the
 * `// MIRROR OF` banner, the `package` line, and imports of this project's own packages. Everything
 * else must be byte-identical. `tools/mirror_desktop_cores.py` writes the mirrors by the same rule, so
 * a failure here is fixed by re-running it rather than by hand-editing.
 */
class MirrorDriftTest {

    /**
     * Mirror → source. Kept here rather than read out of the Python script on purpose: two independent
     * statements of the same mapping means adding a mirror to one and forgetting the other is caught,
     * which is exactly the failure this whole test exists for.
     */
    private val mirrors = mapOf(
        "telemetry/GuideSearch.kt" to "$CORE/GuideSearch.kt",
        "telemetry/LibraryConsult.kt" to "$CORE/LibraryConsult.kt",
        "telemetry/StudyQuestions.kt" to "$CORE/StudyQuestions.kt",
        "telemetry/Recall.kt" to "$CORE/Recall.kt",
        "telemetry/Curriculum.kt" to "$CORE/Curriculum.kt",
        "telemetry/DailyLesson.kt" to "$CORE/DailyLesson.kt",
        "telemetry/DeviceSearch.kt" to "$CORE/DeviceSearch.kt",
        "telemetry/EmergencyTriage.kt" to "$CORE/EmergencyTriage.kt",
        "library/GuideModels.kt" to "$SURVIVAL/GuideModels.kt",
        "library/GuideTaxonomy.kt" to "$SURVIVAL/GuideTaxonomy.kt",
    )

    /** Strip the three things a mirror is allowed to differ in. Everything left must match exactly. */
    private fun comparable(text: String): List<String> = text.lines().filterNot {
        val s = it.trim()
        s.startsWith("// MIRROR OF ") || s.startsWith("package ") || s.startsWith("import dev.mascwa.pulse.")
    }

    @Test
    fun everyMirrorMatchesItsSourceLineForLine() {
        mirrors.forEach { (mirrorRel, sourceRel) ->
            val mirror = File(DESKTOP_SRC, mirrorRel)
            val source = File(REPO, sourceRel)
            assertTrue("mirror missing: $mirrorRel", mirror.isFile)
            assertTrue("source missing: $sourceRel", source.isFile)
            assertEquals(
                "MIRROR DRIFT in $mirrorRel — re-run tools/mirror_desktop_cores.py",
                comparable(source.readText()),
                comparable(mirror.readText()),
            )
        }
    }

    /** A mirror without its banner is a file nobody can tell is a mirror. */
    @Test
    fun everyMirrorDeclaresWhatItIsAMirrorOf() {
        mirrors.forEach { (mirrorRel, sourceRel) ->
            val first = File(DESKTOP_SRC, mirrorRel).readLines().firstOrNull().orEmpty()
            assertTrue("$mirrorRel has no MIRROR OF banner: '$first'", first.startsWith("// MIRROR OF "))
            assertTrue("$mirrorRel banner names the wrong source: '$first'", first.contains(sourceRel))
        }
    }

    /**
     * The mirrored logic must not have picked up a platform dependency on either side.
     *
     * If an Android core ever gains an `android.*` import it stops being mirrorable, and the honest
     * failure is here — at the moment it happens — rather than as a desktop compile error weeks later.
     */
    @Test
    fun nothingMirroredTouchesAndroid() {
        mirrors.values.forEach { sourceRel ->
            val android = File(REPO, sourceRel).readLines().filter { it.startsWith("import android") }
            assertTrue("$sourceRel is no longer platform-free: $android", android.isEmpty())
        }
    }

    private companion object {
        /**
         * Gradle runs tests with the module directory as the working directory, so the repo root is one
         * level up. Asserted rather than assumed — a silently-wrong root would make every check above
         * pass vacuously on missing files, which is worse than failing.
         */
        val REPO: File = File("..").absoluteFile.normalize().also {
            require(File(it, "settings.gradle.kts").isFile) { "repo root not found from ${File(".").absolutePath}" }
        }
        val DESKTOP_SRC = File(REPO, "desktop/src/main/kotlin/dev/mascwa/pulse/desktop")
        const val CORE = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry"
        const val SURVIVAL = "app/src/main/java/dev/mascwa/pulse/data/survival"
    }
}
