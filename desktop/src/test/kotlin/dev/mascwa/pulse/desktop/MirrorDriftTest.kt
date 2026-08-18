package dev.mascwa.pulse.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guard the copy-not-share convention never had.
 *
 * `:desktop` declares no dependency on `:app` or `:core:*` — that is what keeps it buildable without AGP
 * or the Android SDK — so shared pure logic is **copied** rather than imported, and nothing was watching
 * the copies.
 *
 * ⚠️ Diffing them turned out well, which is the point: three were identical and `NewsMarketLink` differed
 * only in trailing whitespace. But that could only be established by hand-diffing six files, and the one
 * real difference — `NewsExplainers` — is a *deliberate* adaptation (it names social tabs and a cloud
 * analysis engine the desktop does not have), so it is an adapted port rather than a mirror and is
 * excluded here. The repo has separately had to correct duplicated-definition drift four times over
 * palettes; being able to answer "has it drifted?" mechanically is worth more than the answer today.
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
        "telemetry/NewsInsights.kt" to "$CORE/NewsInsights.kt",
        "telemetry/NewsMarketLink.kt" to "$CORE/NewsMarketLink.kt",
        "telemetry/MediaBias.kt" to "$CORE/MediaBias.kt",
        "telemetry/SocialBuzz.kt" to "$CORE/SocialBuzz.kt",
        // ⚠️ The live-TV four were mirrored by the script and absent from this map, which is the
        // exact hole this test exists to close — and it was a live one. Growing the curated channel
        // list from 5 to 41 without regenerating would have left the desktop showing the old five
        // **with its own mirrored copy of LiveChannelsTest still passing**, because that test is
        // itself the stale mirror and would have been asserting against the stale catalogue. Nothing
        // else in the build looks at these files: `desktop-build.yml` never runs the script's
        // `--check`, so this map is the only thing standing between a script nobody remembered to
        // run and two platforms quietly disagreeing about what is on television.
        "telemetry/LiveChannels.kt" to "$CORE/LiveChannels.kt",
        "telemetry/M3uCatalog.kt" to "$CORE/M3uCatalog.kt",
        "telemetry/DataRate.kt" to "$CORE/DataRate.kt",
        // ⚠️ Its cross-check against `EconomyVintage` lives in a SEPARATE, unmirrored test on the
        // Android side, deliberately: that core is not mirrored (the companion has no economy
        // screen), so a cross-check inside the mirrored test would not compile here — and would not
        // mean anything either, since the property is that two implementations *coexisting in one
        // module* cannot drift.
        "telemetry/Stardate.kt" to "$CORE/Stardate.kt",
        "live/LiveCatalogRepository.kt" to "$LIVE/LiveCatalogRepository.kt",
        "library/GuideModels.kt" to "$SURVIVAL/GuideModels.kt",
        "library/GuideTaxonomy.kt" to "$SURVIVAL/GuideTaxonomy.kt",
    )

    /** The same, for the tests — mirrored logic with unmirrored tests is unexercised logic. */
    private val testMirrors = listOf(
        "GuideSearchTest.kt", "LibraryConsultTest.kt", "StudyQuestionsTest.kt", "RecallTest.kt",
        "CurriculumTest.kt", "DailyLessonTest.kt", "DeviceSearchTest.kt", "EmergencyTriageTest.kt",
        "LiveChannelsTest.kt", "M3uCatalogTest.kt", "DataRateTest.kt", "StardateTest.kt",
    ).associate { "telemetry/$it" to "$CORE_TEST/$it" }

    private val all: Map<String, String> get() = mirrors + testMirrors

    /** Mirrored tests live under src/test; everything else under src/main. */
    private fun rootFor(mirrorRel: String): File =
        if (mirrorRel.endsWith("Test.kt")) DESKTOP_TEST else DESKTOP_SRC

    /** Strip the three things a mirror is allowed to differ in. Everything left must match exactly. */
    private fun comparable(text: String): List<String> = text.lines().filterNot {
        val s = it.trim()
        s.startsWith("// MIRROR OF ") || s.startsWith("package ") || s.startsWith("import dev.mascwa.pulse.")
    }

    @Test
    fun everyMirrorMatchesItsSourceLineForLine() {
        all.forEach { (mirrorRel, sourceRel) ->
            val mirror = File(rootFor(mirrorRel), mirrorRel)
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
        all.forEach { (mirrorRel, sourceRel) ->
            val first = File(rootFor(mirrorRel), mirrorRel).readLines().firstOrNull().orEmpty()
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
        all.values.forEach { sourceRel ->
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
        val DESKTOP_TEST = File(REPO, "desktop/src/test/kotlin/dev/mascwa/pulse/desktop")
        const val CORE = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry"
        const val CORE_TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry"
        const val SURVIVAL = "app/src/main/java/dev/mascwa/pulse/data/survival"
        const val LIVE = "app/src/main/java/dev/mascwa/pulse/data/live"
    }
}
