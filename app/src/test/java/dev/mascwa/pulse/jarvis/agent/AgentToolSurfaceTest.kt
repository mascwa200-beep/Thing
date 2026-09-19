package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.DeviceSearch.RecordKind
import dev.mascwa.pulse.testing.SourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The assistant's tool surface does not misdescribe itself.
 *
 * ## Why this exists — two defects, both silent, both found by hand
 *
 * ⚠️ **Two registered tools were both called `open`.** `OpenScreenTool` opens one of the app's own
 * screens; `OpenLinkTool` opens a URL. Dispatch is `tools.firstOrNull { it.name == call.name }` and
 * the native path is `tools.distinctBy { it.name.lowercase() }`, so both resolve to whichever
 * `AppContainer` builds first. That is `OpenScreenTool`, which left `OpenLinkTool` constructed on
 * every launch and reachable by nothing: the assistant could not open a link, and answered *"No
 * screen called https://…"* when asked to.
 *
 * Nothing caught it, and the near-miss is the instructive part. `distinctBy` keeps the schema
 * well-formed — the OpenAI tools API rejects duplicate function names — so the serious failure was
 * already averted. But its own comment says it was written for *"an authored tool could collide
 * with a built-in"*: it absorbed a collision between two BUILT-INS in silence. A guard doing its
 * job for the wrong reason reads exactly like no problem at all, which is the
 * silent-truncation-reads-as-covered shape this repository keeps finding.
 *
 * ⚠️ **And `DeviceSearchTool` told the model it searched seven kinds while the index emitted ten.**
 * The three missing were screens, settings and ingested documents — settings and documents both
 * added in the same session this was found. A tool's `usage` is the model's whole basis for
 * choosing it, so *"where do I turn on sponsor skipping"* was answerable from the device index and
 * would never have been routed there. That is the owner's twice-repeated "I can't find it", one
 * layer up from the screen it was first reported on.
 *
 * ## Shape
 *
 * Reads the source as text, in the shape of
 * [dev.mascwa.pulse.data.settings.ThrottleStampCoverageTest], so it cannot fail for an environmental
 * reason and leave someone unable to tell a real break from a broken harness. Every extractor
 * self-checks against a value known to be present first, because a parse that silently matches
 * nothing passes every assertion built on it.
 *
 * ⚠️ What it cannot catch: a tool whose `usage` is accurate about its corpus and wrong about
 * everything else, or a name that is unique and meaningless. "Named" is not "well described" — that
 * is a question for whoever reviews the tool, and a textual gate pretending to answer it would be
 * worse than saying so.
 */
class AgentToolSurfaceTest {

    /** ⚠️ Relative to the MODULE directory — Gradle resolves a test's relative paths from there. */
    private val agentDir = File("src/main/java/dev/mascwa/pulse/jarvis")
    private val indexFile = File("src/main/java/dev/mascwa/pulse/data/search/DeviceSearchIndex.kt")
    private val searchTool = File("src/main/java/dev/mascwa/pulse/jarvis/agent/DeviceSearchTool.kt")

    /**
     * The word in [DeviceSearchTool]'s `usage` that counts as naming each kind the index emits.
     *
     * ⚠️ **This map is the thing a new kind forces someone to update, and that is deliberate.** A
     * gate demanding the literal `RecordKind.label` would force the sentence to say "Feature" where
     * "screens" is the honest English for a model to read — the label is UI copy for a section
     * heading, and this is prose for a prompt. They are allowed to differ; what is not allowed is a
     * kind with no word at all. So adding a kind to the index fails
     * [everyKindTheIndexEmitsIsNamedInTheToolsOwnDescription] on the missing map entry, and whoever
     * adds it has to decide what to call it rather than silently shipping a corpus nobody is told
     * about.
     */
    private val namedInUsageAs = mapOf(
        RecordKind.GUIDE to "guides",
        RecordKind.NOTE to "notes",
        RecordKind.DIARY to "diary",
        RecordKind.MEMORY to "memory",
        RecordKind.TASK to "tasks",
        RecordKind.PROFILE to "profile",
        RecordKind.FINDING to "findings",
        RecordKind.KNOWLEDGE to "documents",
        RecordKind.FEATURE to "screens",
        RecordKind.SETTING to "settings",
    )

    // ---- extraction ------------------------------------------------------------------------

    /** Every `override val name = "..."` under `jarvis/`, paired with the file that declares it. */
    private fun declaredToolNames(): List<Pair<String, String>> {
        val files = SourceGate.kotlinFilesUnder(agentDir)
        val found = files.flatMap { f ->
            Regex("""override\s+val\s+name\s*=\s*"([^"]+)"""")
                .findAll(SourceGate.stripComments(f.readText()))
                .map { it.groupValues[1] to f.name }
                .toList()
        }
        // ⚠️ Self-check before any verdict: `search` is declared by DeviceSearchTool and always has
        // been. A regex that matched nothing would otherwise report a perfectly clean registry.
        assertTrue(
            "the tool-name extractor found nothing it should have — the parse is broken, not the tree",
            found.any { it.first == "search" },
        )
        return found
    }

    /** The kinds `DeviceSearchIndex` actually produces a record for. */
    private fun kindsTheIndexEmits(): Set<RecordKind> {
        val src = SourceGate.stripComments(indexFile.readText())
        val named = Regex("""RecordKind\.([A-Z_]+)""").findAll(src).map { it.groupValues[1] }.toSet()
        val kinds = RecordKind.entries.filter { it.name in named }.toSet()
        // ⚠️ GUIDE is emitted by the library block and cannot not be — if the parse misses it, the
        // whole set is untrustworthy and every "is it named?" assertion below would pass vacuously.
        assertTrue(
            "the index parse found no GUIDE records — the parse is broken, not the index",
            RecordKind.GUIDE in kinds,
        )
        return kinds
    }

    /**
     * The text of `DeviceSearchTool.usage`, concatenation chain and all.
     *
     * ⚠️ Comments are stripped FIRST. A gate that reads prose can otherwise be satisfied by the
     * paragraph explaining why a kind is missing — `CredentialCoverageTest` records being
     * negative-tested against exactly that, and the KDoc above this class names all ten kinds.
     */
    private fun usageText(): String {
        val src = SourceGate.stripComments(searchTool.readText())
        val start = src.indexOf("override val usage")
        assertTrue("no `override val usage` in ${searchTool.name}", start >= 0)
        // To the next declaration — the assignment is a `+` chain over several lines.
        val end = src.indexOf("override suspend fun run", start).let { if (it < 0) src.length else it }
        val chunk = src.substring(start, end)
        val text = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(chunk)
            .joinToString(" ") { it.groupValues[1] }
        assertTrue(
            "the usage extractor read no literals — the parse is broken, not the tool",
            "search <query>" in text,
        )
        return text.lowercase()
    }

    // ---- the two properties ------------------------------------------------------------------

    @Test
    fun `no two of the assistant's tools share a name`() {
        val declared = declaredToolNames()
        val dupes = declared.groupBy { it.first.lowercase() }.filter { it.value.size > 1 }
        assertTrue(
            "two tools share a name, so only the first one registered can ever run and the other " +
                "is dead — dispatch is firstOrNull, and the native path's distinctBy drops it from " +
                "the schema without saying so: " +
                dupes.map { (n, fs) -> "$n in ${fs.map { it.second }}" },
            dupes.isEmpty(),
        )
    }

    @Test
    fun `every kind the index emits is named in the tool's own description`() {
        val emitted = kindsTheIndexEmits()
        val usage = usageText()

        val unmapped = emitted.filter { it !in namedInUsageAs }
        assertTrue(
            "the index emits $unmapped and this test has no word for them. Decide what to call " +
                "each in DeviceSearchTool.usage and add it to namedInUsageAs — a corpus the model " +
                "is not told about is a corpus it has no reason to search",
            unmapped.isEmpty(),
        )

        val unnamed = emitted.filter { namedInUsageAs.getValue(it).lowercase() !in usage }
            .map { it to namedInUsageAs.getValue(it) }
        assertTrue(
            "DeviceSearchTool.usage does not mention $unnamed, so the model will not know it can " +
                "search them",
            unnamed.isEmpty(),
        )
    }

    @Test
    fun `the corpus map does not name a kind the index stopped emitting`() {
        val emitted = kindsTheIndexEmits()
        val stale = namedInUsageAs.keys.filter { it !in emitted }
        // ⚠️ The other direction, and the one an allowlist usually loses. A stale entry keeps the
        // first test green while the sentence promises a corpus that is no longer there — the model
        // would then search for something it cannot find and report an absence as a result.
        assertTrue(
            "namedInUsageAs still names $stale, which DeviceSearchIndex no longer emits — the " +
                "usage string is promising a corpus the device does not hold",
            stale.isEmpty(),
        )
    }

    @Test
    fun `the phone's deck is deliberately absent, and stays a decision rather than a drift`() {
        // ⚠️ STUDY is the one RecordKind the phone declares and does not emit. That is recorded as a
        // decision (the phone renders a flat list where the desktop groups, so cards would interleave
        // with the guides they were drawn from), not an oversight. Pinned so that wiring it up is a
        // deliberate act that updates this test, and so that nobody "fixes" the absence by accident.
        assertEquals(
            "STUDY is now emitted by the phone's index. That is fine — but give it a word in " +
                "namedInUsageAs and say so in DeviceSearchTool, then delete this test.",
            false,
            RecordKind.STUDY in kindsTheIndexEmits(),
        )
    }
}
