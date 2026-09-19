package dev.mascwa.pulse.data.sensing

import dev.mascwa.pulse.core.telemetry.AmbientAction
import dev.mascwa.pulse.testing.SourceGate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every capability the acting layer declares is one something actually carries out.
 *
 * ## Why this exists
 *
 * ⚠️ **Five actions were deleted from `AmbientAction` because nothing asked for them, and all five
 * were found by hand, one at a time.** `REQUEST_DND`, `RAISE_ALARM_VOLUME`, `DISABLE_CAMERA`,
 * `HIDE_STATUS_BAR` and `BLOCK_SCREENSHOTS` were each declared with a label, an undo and a hold
 * ceiling, and reached by nothing — the "computed and never used" shape this repository keeps
 * finding. One of them could not have worked even if a rule had asked: `RAISE_ALARM_VOLUME` moved
 * `STREAM_ALARM`, which nothing on that path plays.
 *
 * A capability here is dead if **either** end is missing, and the two ends cannot see each other:
 *
 *  - no rule asks for it — covered in the core by
 *    `AmbientRulesTest.every action the closed list declares can be asked for by some rule`;
 *  - nothing acts on it — covered here, because the consumers live in this module.
 *
 * This is the higher-stakes half of the pair. The list can pause every app on the phone, silence a
 * ringer and light a torch, and a declared-but-unwired action is a hold the scanner shows, the
 * board announces and the owner can "release", while nothing was ever holding.
 *
 * ## The exemption list is checked, not trusted
 *
 * ⚠️ An allowlist is how a gate like this stops working. An entry does not merely excuse an action —
 * it claims the absence is **written down at a named place**, and the gate goes and reads it. An
 * action quietly parked here with no explanation fails, which is the only failure mode worth
 * designing against.
 *
 * Reads the source as text, in the shape of
 * [dev.mascwa.pulse.data.settings.ThrottleStampCoverageTest], so it cannot fail for an environmental
 * reason and leave someone unable to tell a real break from a broken harness. The member list is the
 * real enum rather than a parse of it — this module depends on the core, so there is no reason to
 * guess at something that can simply be asked.
 *
 * ⚠️ What it cannot catch: an action referenced by a consumer that never runs. "Named outside a
 * comment" is not "carried out" — that is a question for whoever reviews the consumer, and a textual
 * gate pretending to answer it would be worse than saying so.
 */
class AmbientActionCoverageTest {

    /** ⚠️ Relative to the MODULE directory — Gradle resolves a test's relative paths from there. */
    private val appSrc = File("src/main/java/dev/mascwa/pulse")

    /**
     * Actions no consumer here carries out, and where that is written down.
     *
     * The value is the file whose comments must explain the absence; [anExemptionPointsAtRealDocumentation]
     * proves it does. The reason is below, and the gate does not take it on trust.
     */
    private val documentedAsUnconsumed = mapOf(
        // Asked for by the driving rule and acted on by nothing. There is no honest consumer at this
        // tier: the only thing that volunteers speech is the proactive remark, which is already
        // gated on the owner's own `speakProactive` and on quiet hours, and overriding either would
        // be this layer countermanding a preference rather than reading one. Its real consumer is an
        // announcement channel that does not exist yet.
        //
        // ⚠️ Deliberately kept rather than deleted, unlike the five above — an action that is merely
        // inert costs nothing but this note, where `STAND_SENSING_DOWN` would have deafened the
        // phone every night and had to go.
        AmbientAction.SPEAK_DONT_BUZZ to
            File(appSrc, "data/sensing/AmbientActuator.kt"),
    )

    /**
     * Every `AmbientAction.<NAME>` in this module, with comments removed.
     *
     * ⚠️ The stripping is the whole gate. The one action that has no consumer is *named in a KDoc
     * saying so*, so without it that KDoc would read as the consumer — the gate would pass on the
     * documentation of the defect instead of on its absence, and the exemption below would never be
     * needed. `CredentialCoverageTest` was negative-tested against exactly that shape.
     */
    private fun namesIn(code: String): Set<String> =
        Regex("""\bAmbientAction\.([A-Z][A-Z0-9_]*)\b""")
            .findAll(SourceGate.stripComments(code)).map { it.groupValues[1] }.toSet()

    private fun consumedNames(): Set<String> =
        namesIn(SourceGate.kotlinFilesUnder(appSrc).joinToString("\n") { it.readText() })

    @Test
    fun `the search itself works, so a green result is not vacuous`() {
        val found = consumedNames()
        // Without this, a regex that matched nothing would report every action exempt-or-missing and
        // the failure message would be a wall rather than a finding. Anchored on one that certainly
        // has a consumer rather than on a count, so adding an action does not break the self-check.
        assertTrue("found no AmbientAction references at all — the search is broken", found.isNotEmpty())
        assertTrue(
            "the search no longer finds PAUSE_VIDEO, which four files consume: $found",
            "PAUSE_VIDEO" in found,
        )
    }

    /**
     * ⚠️ A fixture rather than the live tree, deliberately.
     *
     * The obvious version asserts that `SPEAK_DONT_BUZZ` — named only in a KDoc — is absent from the
     * real scan. That works today and is **brittle in the wrong direction**: the day somebody wires
     * a consumer for it, that assertion fails saying "the stripper is not removing comments", which
     * is a false diagnosis of a correct change. A fixture tests the function rather than the
     * current state of the exemption, so it stays true whatever happens to that action.
     */
    @Test
    fun `a commented reference is not a consumer`() {
        val src = """
            fun live() = AmbientHolds.isHeld(AmbientAction.REALLY_USED)
            // AmbientAction.LINE_COMMENTED is named here and consumed nowhere
            /** [AmbientAction.KDOC_ONLY] is documented here and consumed nowhere. */
        """.trimIndent()
        val got = namesIn(src)
        assertTrue("a real consumer was lost", "REALLY_USED" in got)
        assertTrue("a line-commented mention counted as a consumer: $got", "LINE_COMMENTED" !in got)
        assertTrue("a KDoc mention counted as a consumer: $got", "KDOC_ONLY" !in got)
    }

    @Test
    fun `every action a rule can ask for is carried out by something`() {
        val consumed = consumedNames()
        val inert = AmbientAction.entries
            .filter { it.name !in consumed && it !in documentedAsUnconsumed }

        assertTrue(
            "these actions are asked for by a rule and acted on by nothing, so a hold appears in " +
                "the scanner and on the board while nothing is held: $inert. Either wire a consumer, " +
                "or delete the action as five others were — and if it must stay inert, say so at a " +
                "named place and add it to documentedAsUnconsumed.",
            inert.isEmpty(),
        )
    }

    @Test
    fun `an exemption points at real documentation`() {
        // ⚠️ Deliberately reads the RAW source, not the stripped source: the thing being verified is
        // a comment. That inversion is the point — the consumption test must not see comments and
        // this one must see nothing else.
        for ((action, where) in documentedAsUnconsumed) {
            assertTrue("missing: ${where.absolutePath}", where.isFile)
            val comments = Regex("""/\*.*?\*/|//[^\n]*""", RegexOption.DOT_MATCHES_ALL)
                .findAll(where.readText()).joinToString("\n") { it.value }
            assertTrue(
                "${where.name} does not mention $action, so the exemption claims documentation " +
                    "that is not there",
                action.name in comments,
            )
            assertTrue(
                "${where.name} mentions $action but never says it has no consumer — an exemption " +
                    "has to be explained where somebody reading the code will find it",
                "no consumer" in comments,
            )
        }
    }

    @Test
    fun `an exemption is removed once the action gains a consumer`() {
        // The other direction, and the one that makes an allowlist rot: an action gets wired, the
        // exemption stays, and the gate quietly stops covering it for ever after.
        val consumed = consumedNames()
        val stale = documentedAsUnconsumed.keys.filter { it.name in consumed }
        assertTrue(
            "these are exempted as unconsumed and something now consumes them: $stale. Remove the " +
                "exemption and the note beside it.",
            stale.isEmpty(),
        )
    }
}
