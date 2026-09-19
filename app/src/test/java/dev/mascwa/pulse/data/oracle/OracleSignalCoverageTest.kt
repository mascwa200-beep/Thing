package dev.mascwa.pulse.data.oracle

import dev.mascwa.pulse.testing.SourceGate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every field of `OracleSignals` is actually filled in by something.
 *
 * ## Why this exists
 *
 * ⚠️ **`windKmh` was declared in `OracleSignals` and populated by nothing**, so any rule reading
 * wind could never have fired — and nothing said so. The engine compiled, the rules compiled, the
 * rules' own unit tests passed because they hand-build a snapshot, and on the device the feature
 * was silent. It was found only when the comfort core was wired months later and someone noticed
 * the field had no producer.
 *
 * That is the other half of [dev.mascwa.pulse.core.telemetry.Oracle]'s reachability problem.
 * `OracleReachabilityTest` proves each RULE can fire given a snapshot; this proves the snapshot the
 * device actually builds is not missing a field those rules read. A rule is dead if either half
 * fails, and neither half can see the other's defect.
 *
 * ## The exemption list is checked, not trusted
 *
 * ⚠️ An allowlist is how a gate like this stops working: the tempting repair for a failure is to
 * add the field and move on. So an entry here does not merely excuse a field — it **claims another
 * platform populates it**, and that claim is verified against that platform's source. A field
 * populated by nobody cannot be parked here, which is the only failure mode worth designing
 * against.
 *
 * Reads the source as text, in the shape of [dev.mascwa.pulse.data.settings.ThrottleStampCoverageTest],
 * so it cannot fail for an environmental reason and leave someone unable to tell a real break from
 * a broken harness.
 *
 * ⚠️ What it cannot catch: a field assigned a value that is always null or always the neutral
 * default. "Named in the construction" is not "carries information" — `awayFromHome` passing here
 * says nothing about whether the trusted-network layer can ever decide. That is a question for
 * whoever reviews the producer, and pretending a textual gate could answer it would be worse than
 * saying so.
 */
class OracleSignalCoverageTest {

    /** ⚠️ Relative to the MODULE directory — Gradle resolves a test's relative paths from there. */
    private val declaration = File("../core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Oracle.kt")
    private val phoneEngine = File("src/main/java/dev/mascwa/pulse/data/oracle/OracleEngine.kt")
    private val desktopGatherer =
        File("../desktop/src/main/kotlin/dev/mascwa/pulse/desktop/standby/OracleSnapshot.kt")

    /**
     * Fields the phone deliberately never fills, and who does.
     *
     * The value is the file that must be shown to populate it. The reason is in the comment, and
     * the gate proves the claim rather than taking it.
     */
    private val elsewhere = mapOf(
        // The long watch is a desktop feature: only that machine keeps a ledger of what it has
        // measured over months, because only that machine is always on and mains-powered. The phone
        // has nothing to put here, and the rule that reads it is correspondingly quiet there.
        "ledgerHeadline" to desktopGatherer,
        "ledgerBits" to desktopGatherer,
    )

    /**
     * A construction of `OracleSignals`, not a mention of the name inside a longer identifier.
     *
     * ⚠️ The lookbehind is load-bearing and its absence was caught by this gate's own exemption
     * check rather than by reading. A bare `OracleSignals\(` also matches inside
     * `gatherOracleSignals(` — the desktop's builder function — so the harness parsed that
     * FUNCTION'S PARAMETER LIST instead of the construction thirty lines below, found none of the
     * fields, and reported the desktop as not populating what it demonstrably does. A harness that
     * mis-locates its target accuses the thing it is checking.
     */
    private val construction = Regex("""(?<![A-Za-z0-9_])OracleSignals\(""")

    /**
     * The body of the first `(` … `)` after [decl], brace-matched so a multi-line call survives.
     *
     * ⚠️ **Comments are stripped first, and both halves of that matter.** Without it a
     * commented-out named argument — the natural way somebody disables a signal for an afternoon —
     * reads as a populated field, so this gate would pass on the documentation of a defect rather
     * than on its absence, which is the exact failure `CredentialCoverageTest` was negative-tested
     * against. And the paren matching needs it too: an unbalanced bracket inside a comment
     * (`// closes the loop )`) would end the body early and silently drop every field after it.
     *
     * Measured at the time of writing: the phone's construction carries no comments at all and the
     * desktop's carries four lines contributing no names, so this is **latent rather than live** —
     * one edit away, in the file most likely to get one.
     */
    private fun argsAfter(rawSrc: String, decl: Regex): String {
        val src = SourceGate.stripComments(rawSrc)
        val m = decl.find(src) ?: error("not found in source: ${decl.pattern}")
        val open = src.indexOf('(', m.range.last)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return src.substring(open + 1, i) }
            }
            i++
        }
        error("unbalanced parentheses after ${decl.pattern}")
    }

    /**
     * Names given as a named argument.
     *
     * Anchored to a `(`, `,` or line start, so a nested call's own arguments are still seen while
     * an infix `=` buried inside a value expression is not mistaken for one.
     *
     * ⚠️ `=(?!=)` is **belt and braces today, and that was measured rather than assumed.** There is
     * exactly one `==` inside the real construction and the anchor already excludes it, so removing
     * the lookahead changes nothing here — 42 names either way. It stops being decorative the moment
     * a value starts with a comparison right after a bracket or comma (`foo = listOf(a == b)`),
     * where `a` would otherwise be captured as a field this gate then believes is populated. That is
     * a plausible enough edit to guard against, and cheap; what would not be honest is calling it
     * load-bearing.
     */
    private fun namedArgs(body: String): Set<String> =
        Regex("""(?:^|[(,])\s*(\w+)\s*=(?!=)""", RegexOption.MULTILINE)
            .findAll(body).map { it.groupValues[1] }.toSet()

    private fun declaredFields(): List<String> =
        Regex("""\bval (\w+):""")
            .findAll(argsAfter(declaration.readText(), Regex("""data class OracleSignals\(""")))
            .map { it.groupValues[1] }.toList()

    @Test
    fun `the harness can see the declaration and the construction`() {
        val fields = declaredFields()
        assertTrue("no OracleSignals fields parsed — the declaration's shape has changed", fields.size > 30)
        assertTrue("the field extractor missed a field known to be there", "windKmh" in fields)

        val built = namedArgs(argsAfter(phoneEngine.readText(), construction))
        assertTrue("no named arguments parsed from the engine's construction", built.size > 30)
        assertTrue("the argument extractor missed one known to be there", "hourOfDay" in built)
    }

    @Test
    fun `every signal the rules can read is filled in by the phone, or by a named platform that does`() {
        val built = namedArgs(argsAfter(phoneEngine.readText(), construction))
        val missing = declaredFields().filterNot { it in built || it in elsewhere }
        assertTrue(
            "these OracleSignals fields are declared and the phone's OracleEngine never sets them, " +
                "so every rule that reads one is silent on this platform and nothing reports it — " +
                "the exact shape `windKmh` shipped in. Populate them, or add them to `elsewhere` " +
                "with the platform that does: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * ⚠️ A fixture rather than the live source, and that is the whole reason it exists.
     *
     * The stripping in [argsAfter] guards a case that is **latent**: measured at the time of
     * writing, the phone's construction carries no comments at all and the desktop's carries four
     * that contribute no names. So deleting the strip changes nothing about today's tree — a
     * negative test against the live source would report the guard asleep when it is simply not
     * reached, which is this repository's third recorded way a green test proves nothing.
     *
     * A fixture reaches the branch. Both halves are here because they fail differently: a
     * commented-out argument is a **silent false pass** (a field nobody sets reads as populated),
     * while an unbalanced bracket inside a comment ends the body early and **silently drops every
     * field after it**, which would report live fields as missing.
     */
    @Test
    fun `a commented-out argument is not a populated field`() {
        // ⚠️ The COMMA inside the comment is what makes this fixture reach the branch, and finding
        // that out took a negative test. A plain `// dead = 2,` is already excluded by `namedArgs`'
        // own anchor — the name has to follow a `(`, a `,` or a line start, and the `//` sits
        // between — so a fixture built only from those passes with the stripping deleted and proves
        // nothing. A comma inside ordinary English prose ("see the note above, disabled = 3") puts
        // the name straight after an anchor, and that one really does get through.
        val src = """
            fun build() = OracleSignals(
                alive = 1,
                // dead = 2,
                // see the note above, disabled = 3
                /* and here too, blocked = 4 */
                stillAlive = 5,
            )
        """.trimIndent()
        val got = namedArgs(argsAfter(src, construction))
        assertTrue("a live argument was lost", "alive" in got && "stillAlive" in got)
        assertTrue("a commented-out argument was counted as populated: $got", "dead" !in got)
        assertTrue("a name after a comma in a line comment was counted: $got", "disabled" !in got)
        assertTrue("a name after a comma in a block comment was counted: $got", "blocked" !in got)
    }

    @Test
    fun `a bracket inside a comment does not end the argument list early`() {
        val src = """
            fun build() = OracleSignals(
                first = 1,
                // this closes the loop )
                last = 2,
            )
        """.trimIndent()
        val got = namedArgs(argsAfter(src, construction))
        assertTrue("the body ended at a comment's bracket and lost everything after it: $got", "last" in got)
    }

    @Test
    fun `an exempt signal is really populated by the platform its entry names`() {
        elsewhere.forEach { (field, file) ->
            assertTrue("the file named for '$field' does not exist: $file", file.isFile)
            val built = namedArgs(argsAfter(file.readText(), construction))
            assertTrue(
                "'$field' is exempt on the grounds that ${file.name} populates it, and ${file.name} " +
                    "does not — so the field is set by nobody and the exemption is hiding exactly " +
                    "the defect this gate exists to catch",
                field in built,
            )
        }
    }
}
