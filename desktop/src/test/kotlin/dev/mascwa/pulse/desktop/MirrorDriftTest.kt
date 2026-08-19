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
 *
 * ⚠️ **Every check here reads files outside `:desktop`, which Gradle does not know about.** They are not
 * declared inputs of `:desktop:test`, so editing a core — or the workflow file — and re-running locally
 * can report the previous result from the task cache and look like a pass. It bit while negative-testing
 * [theDesktopWorkflowRunsWhenAnyMirroredSourceChanges], where a deliberately broken workflow reported
 * green. CI is unaffected, because a fresh runner has no prior output to be up to date with; locally, use
 * `--rerun-tasks` when the thing you changed lives outside this module.
 */
class MirrorDriftTest {

    /**
     * Mirror → source. Kept here rather than read out of the Python script on purpose: two independent
     * statements of the same mapping means adding a mirror to one and forgetting the other is caught,
     * which is exactly the failure this whole test exists for.
     *
     * ⚠️ **That reasoning is right and it was only half working.** Independence catches a mirror added
     * *here* and missing from the script; it cannot catch the reverse, and the reverse is the direction
     * that actually happens, because a mirror is added by running the script and this map is what gets
     * forgotten. Measured: **22 of the script's 53 entries were absent from these two maps** — the whole
     * expansion-pack format, the entire Khan learning layer (CourseMastery, PracticeSet, Hints),
     * QuizBuilder, StudyProgress, Refresher, UpdatePolicy, Freshness, NewsSummary and ElapsedPhrase.
     * Editing any of those in the core and forgetting to regenerate would have left the desktop running
     * a stale copy with its own stale mirrored test passing against it.
     *
     * The independence is kept and [everyMirrorTheScriptWritesIsListedHere] closes the gap: the two
     * lists must name the same set, so forgetting either side fails by name.
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
        "telemetry/NewsSummary.kt" to "$CORE/NewsSummary.kt",
        "telemetry/MediaBias.kt" to "$CORE/MediaBias.kt",
        "telemetry/SocialBuzz.kt" to "$CORE/SocialBuzz.kt",
        // The Khan-model learning layer and the machinery that marks an answer. Both platforms teach
        // from the same bundled library, so they have to judge and pace it identically or the same
        // question is worth different things depending on which screen you answered it on.
        "telemetry/ContentPack.kt" to "$CORE/ContentPack.kt",
        "telemetry/CourseMastery.kt" to "$CORE/CourseMastery.kt",
        "telemetry/PracticeSet.kt" to "$CORE/PracticeSet.kt",
        "telemetry/Hints.kt" to "$CORE/Hints.kt",
        "telemetry/QuizBuilder.kt" to "$CORE/QuizBuilder.kt",
        "telemetry/StudyProgress.kt" to "$CORE/StudyProgress.kt",
        "telemetry/Refresher.kt" to "$CORE/Refresher.kt",
        // How old what is on screen is, and when a published build is safe to offer.
        "telemetry/Freshness.kt" to "$CORE/Freshness.kt",
        "telemetry/Readability.kt" to "$CORE/Readability.kt",
        "telemetry/ElapsedPhrase.kt" to "$CORE/ElapsedPhrase.kt",
        "telemetry/UpdatePolicy.kt" to "$CORE/UpdatePolicy.kt",
        // ⚠️ The live-TV four were mirrored by the script and absent from this map, which is the
        // exact hole this test exists to close — and it was a live one. Growing the curated channel
        // list from 5 to 41 without regenerating would have left the desktop showing the old five
        // **with its own mirrored copy of LiveChannelsTest still passing**, because that test is
        // itself the stale mirror and would have been asserting against the stale catalogue. Nothing
        // else in the build looks at these files: `desktop-build.yml` never runs the script's
        // `--check`, so this map is the only thing standing between a script nobody remembered to
        // run and two platforms quietly disagreeing about what is on television.
        "telemetry/LiveChannels.kt" to "$CORE/LiveChannels.kt",
        "telemetry/ChannelLineup.kt" to "$CORE/ChannelLineup.kt",
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
        "LiveChannelsTest.kt", "ChannelLineupTest.kt", "M3uCatalogTest.kt",
        "DataRateTest.kt", "StardateTest.kt",
        "ContentPackTest.kt", "CourseMasteryTest.kt", "PracticeSetTest.kt", "HintsTest.kt",
        "QuizBuilderTest.kt", "ProcedureQuestionTest.kt", "StudyProgressTest.kt", "RefresherTest.kt",
        "FreshnessTest.kt", "UpdatePolicyTest.kt", "NewsSummaryTest.kt", "ReadabilityTest.kt",
    ).associate { "telemetry/$it" to "$CORE_TEST/$it" }

    private val all: Map<String, String> get() = mirrors + testMirrors

    /**
     * A GitHub `paths:` glob as a regex: `**` crosses separators, a single `*` does not.
     *
     * ⚠️ Built character by character rather than with `Regex.escape(glob).replace("*", ...)`, which
     * is the obvious version and is silently inert: `Regex.escape` returns a `\Q…\E` literal block,
     * so the replacements find nothing and every path comes back uncovered. That looks like a
     * finding — the assertion fails loudly and names every mirrored file — which is exactly how a
     * broken check gets believed.
     */
    private fun globToRegex(glob: String): String = buildString {
        append('^')
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**", i) -> { append(".*"); i += 2 }
                glob[i] == '*' -> { append("[^/]*"); i++ }
                else -> { append(Regex.escape(glob[i].toString())); i++ }
            }
        }
        append('$')
    }

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

    /**
     * The two lists name the same set, in both directions.
     *
     * ⚠️ **The independence above only ever caught one direction, and the wrong one.** A mirror is
     * created by running `tools/mirror_desktop_cores.py`, so the script's map is the one that gets
     * updated and this file's is the one that gets forgotten — and a forgotten entry here is
     * invisible: the mirror exists, the desktop compiles against it, and nothing compares it to its
     * source ever again. Measured before this test was written: **22 of the script's 53 entries were
     * missing from this file**, among them the whole Khan learning layer and the expansion-pack
     * format. That is not a hypothetical drift window; the live-TV four had already fallen through
     * it once, and the comment above records what that would have cost.
     *
     * The map is still written out by hand — the independence is worth keeping, because a mirror
     * added *here* and missing from the script is also a real mistake and this catches it too. What
     * changes is that neither list can now be quietly shorter than the other.
     *
     * The script's map is a plain literal block, so reading it needs no Python: a line of the form
     * `f"{X}/Name.kt": f"{Y}/rel/Name.kt",` yields the destination, and the destination minus the
     * desktop root is exactly the key used here.
     */
    @Test
    fun everyMirrorTheScriptWritesIsListedHere() {
        val script = File(REPO, "tools/mirror_desktop_cores.py")
        assertTrue("the mirror script is missing: ${script.path}", script.isFile)
        val declared = Regex(""":\s*f"\{(DESKTOP|DESKTOP_TEST)}/([^"]+)"""")
            .findAll(script.readText())
            .map { it.groupValues[2] }
            .toSortedSet()
        assertTrue("parsed nothing out of the mirror script — has its MIRRORS block changed shape?",
            declared.size > 20)
        assertEquals(
            "the mirror script and this test disagree about what is mirrored. Add the missing " +
                "entries to whichever side is short; a mirror the script writes and this test does " +
                "not check is a file that can drift for ever without anything noticing.",
            declared.toList(),
            all.keys.toSortedSet().toList(),
        )
    }

    /**
     * This workflow runs whenever any mirrored source changes.
     *
     * ⚠️ **A drift guard is worth nothing if the build that runs it is not triggered.** Every check in
     * this file lives in `:desktop:test`, which only runs when `desktop-build.yml`'s `paths:` filter
     * matches the push — so a mirrored source outside that filter can be edited, left un-regenerated,
     * and never compared to its mirror by anything. That was live for one file:
     * `LiveCatalogRepository.kt` is mirrored out of the app's `data/live` package, and the filter
     * listed `data/survival` and not `data/live`.
     *
     * Found by matching every mirror source against the filter mechanically. Asserting it here is
     * what stops the next mirror from a new package reopening it, and the failure names the glob to
     * add rather than leaving somebody to work out why the desktop shipped stale logic.
     *
     * `**` is expanded to match across separators, which is what GitHub means by it and what a naive
     * glob does not.
     */
    @Test
    fun theDesktopWorkflowRunsWhenAnyMirroredSourceChanges() {
        val yaml = File(REPO, ".github/workflows/desktop-build.yml")
        assertTrue("the desktop workflow is missing: ${yaml.path}", yaml.isFile)
        val block = yaml.readText().substringAfter("paths:").substringBefore("workflow_dispatch")
        val globs = Regex("""-\s+"([^"]+)"""").findAll(block).map { it.groupValues[1] }.toList()
        assertTrue("parsed no path filters — has the workflow changed shape?", globs.size > 5)

        val patterns = globs.map { Regex(globToRegex(it)) }
        val uncovered = all.values.toSortedSet().filterNot { src -> patterns.any { it.matches(src) } }
        assertTrue(
            "mirrored sources that do not trigger this workflow, so nothing here would ever compare " +
                "them to their mirrors — add a paths: entry for each: $uncovered",
            uncovered.isEmpty(),
        )
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
