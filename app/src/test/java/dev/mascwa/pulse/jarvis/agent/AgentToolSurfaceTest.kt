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
    private val container = File("src/main/java/dev/mascwa/pulse/di/AppContainer.kt")

    /**
     * The one tool whose `name` is a string template, so no textual gate can know what it is.
     *
     * `ProposeDocTool` is constructed three times with `mode` = add/edit/delete, and its `usage` is
     * a `when` over that same `mode` with the right prefix in each branch. Pinned by name in the
     * shape `LiveChannelsTest` uses for its unverified channels: a SECOND templated tool fails
     * [everyToolsUsageLeadsWithTheNameThatDispatchesIt] and whoever adds it decides what the gate
     * should do, rather than inheriting an exemption written for somebody else's tool.
     */
    private val templatedNames = setOf("propose_doc_\$mode")

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
     * Every class that implements `JarvisTool`, paired with the file that declares it.
     *
     * ⚠️ **The pattern must cross newlines, and a naive one understates by more than half.** Most
     * of these declare their dependencies in a constructor list that wraps, so
     * `class X(...) : JarvisTool` on one line finds **30** where the real figure is 57. A gate built
     * on the naive form would have been silent about 27 tools — including every one in
     * `DeviceActionTools.kt`, which is where the collision this class exists for lived.
     */
    private fun declaredToolClasses(): Map<String, String> {
        val re = Regex("""\bclass\s+([A-Za-z0-9_]+)\s*(?:\([^{]*?\))?\s*:\s*[^{\n]*\bJarvisTool\b""")
        val found = SourceGate.kotlinFilesUnder(agentDir).flatMap { f ->
            re.findAll(SourceGate.stripComments(f.readText())).map { it.groupValues[1] to f.name }
        }.toMap()
        // ⚠️ Two self-checks, not one: DeviceSearchTool declares its constructor on a single line and
        // OpenLinkTool wraps. A pattern that had silently stopped crossing newlines would still find
        // the first, and the whole "is everything registered?" verdict below would pass vacuously.
        assertTrue(
            "the tool-class extractor missed DeviceSearchTool — the parse is broken, not the tree",
            "DeviceSearchTool" in found,
        )
        assertTrue(
            "the tool-class extractor missed OpenLinkTool, so it is no longer crossing newlines — " +
                "it would now be blind to most of the registry",
            "OpenLinkTool" in found,
        )
        return found
    }

    /**
     * Each tool's declared `name`, the first string literal of its `usage`, and its file.
     *
     * ⚠️ **[ToolText.usage] is null when the usage could not be READ, and that distinction is the
     * whole point.** My first version returned only the pairs it managed to parse, so
     * `ProposeDocTool` — whose usage is a `when (mode)` rather than a literal — was dropped on the
     * floor, and the pinned-exception assertion below saw an empty set and could not tell "no
     * templated tools exist" from "the extractor cannot see the one that does". A harness that
     * discards what it cannot read reports the same silence as a tree with nothing in it.
     */
    private data class ToolText(val name: String, val usage: String?, val file: String)

    private fun nameAndUsage(): List<ToolText> {
        val names = Regex("""override\s+val\s+name\s*=\s*"([^"]*)"""")
        val usage = Regex("""override\s+val\s+usage\s*=\s*"((?:[^"\\]|\\.)*)"""")
        val out = SourceGate.kotlinFilesUnder(agentDir).flatMap { f ->
            val src = SourceGate.stripComments(f.readText())
            val hits = names.findAll(src).toList()
            hits.mapIndexed { i, m ->
                // ⚠️ Bounded by the NEXT `name` declaration rather than by a character count. Several
                // of these files hold a dozen tools in a row, and a fixed window that overran would
                // pair one tool's name with the next tool's usage — a mismatch that reads as a real
                // finding and is purely the harness's.
                val end = hits.getOrNull(i + 1)?.range?.first ?: src.length
                val within = src.substring(m.range.last + 1, end)
                ToolText(m.groupValues[1], usage.find(within)?.groupValues?.get(1), f.name)
            }
        }
        assertTrue(
            "the name/usage extractor read nothing — the parse is broken, not the tools",
            out.any { it.name == "search" },
        )
        // ⚠️ Cross-checked against the OTHER extractor, which finds the same tools a different way.
        // Every JarvisTool must override `name`, so the two counts are the same number reached
        // independently — and a silent drop by either one stops being silent.
        assertEquals(
            "the two extractors disagree about how many tools exist, so at least one of them is " +
                "quietly skipping something and every verdict built on it is worthless. Two causes " +
                "to look for: one of the patterns stopped matching a declaration shape, or two tool " +
                "classes now share a name in different packages, which Kotlin allows and the map in " +
                "declaredToolClasses would silently collapse",
            declaredToolClasses().size,
            out.size,
        )
        return out
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
    fun `every tool class the app declares is actually constructed`() {
        val declared = declaredToolClasses()
        val src = SourceGate.stripComments(container.readText())
        val orphans = declared.filterKeys { !Regex("""\b$it\s*\(""").containsMatchIn(src) }
        // ⚠️ "Constructed anywhere in AppContainer", NOT "present in agentTools". Several tools are
        // built conditionally — the self-edit ones behind `selfEditEnabled`, `code`/`selfcode` behind
        // `selfCodingEnabled` — and LoggingTool is a decorator that wraps the list rather than an
        // entry in it. Demanding membership of that one list would report four working tools as dead.
        assertTrue(
            "these implement JarvisTool and AppContainer never builds them, so they are dead in the " +
                "same way a name collision makes a tool dead — one link earlier in the chain: " +
                orphans.map { (n, f) -> "$n ($f)" },
            orphans.isEmpty(),
        )
    }

    @Test
    fun `every tool's usage leads with the name that dispatches it`() {
        val pairs = nameAndUsage()

        // ⚠️ Keyed on what the extractor could not READ, not on a `$` in the name. Those coincide
        // today — `ProposeDocTool` is the only tool with either — but they are different properties,
        // and keying on the name would let a tool with a literal name and a `when`-based usage slip
        // through unchecked and unreported, which is the shape that already bit this harness once.
        val unreadable = pairs.filter { it.name.contains('$') || it.usage == null }.map { it.name }
        assertEquals(
            "a tool's name or usage is no longer a plain literal, so no textual gate can read it. " +
                "Check by hand that every branch of its `usage` leads with the name that branch " +
                "produces, then add it to templatedNames with a note saying you did",
            templatedNames,
            unreadable.toSet(),
        )

        // ⚠️ The same defect the `open` collision was, one step earlier: the model is told to call
        // one verb and dispatch answers to another. Nothing else in the app relates these two
        // strings, and a mismatch costs a tool exactly as much as being unreachable does.
        val mismatched = pairs.filter { it.name !in unreadable && it.usage?.trimStart()?.startsWith(it.name) == false }
        assertTrue(
            "these tools tell the model to call one thing while dispatch answers to another: " +
                mismatched.map { "${it.file}: name=${it.name}, usage starts \"${it.usage?.take(40)}\"" },
            mismatched.isEmpty(),
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
