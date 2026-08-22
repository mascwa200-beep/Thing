package dev.mascwa.pulse.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every advisory the Oracle can raise must point at a page something can open.
 *
 * ⚠️ **Written because a negative test found this guard missing and the gap was already costing a
 * real dead button.** Perturbing an `actionRoute` in `Oracle.kt` to `"nowhere"` failed no test at
 * all — and running the check for the first time turned up `"anomalies"`, the long watch's own
 * advisory, resolving on neither platform. The insight rendered, the ACT control drew, and pressing
 * it did nothing. Nothing anywhere would have said so: a route is a plain string, so a typo, a
 * renamed destination and a route that never existed are all indistinguishable from a compile.
 *
 * ⚠️ **The union of BOTH platforms, and that is the point rather than laziness.** `Oracle.kt` lives
 * in `:core:telemetry` and both applications run the same 25 rules over their own signals, so a rule
 * legitimately reaches a page only one of them has: the phone alone has the assistant, a task board
 * and ambient sensors; the desktop alone keeps the ledger the anomalies page reads. What is never
 * legitimate is a route neither platform knows, which is exactly what this asserts.
 *
 * A route that resolves on the wrong platform is a weaker claim than this test can make. It is also
 * a much smaller problem: the rule that emits it cannot fire there, because the signal it reads is
 * one that platform's engine never populates — and if that ever stops being true the failure is a
 * dead button, not a wrong answer.
 *
 * ⚠️ Textual on purpose, in the shape of [NavigationInventoryTest] and `WidgetLinkageTest`. Invoking
 * the rules would need a maximal `OracleSignals` that fires all of them, which is a fixture nobody
 * can keep complete, and reading `Routes` at runtime drags in Compose. A gate that can fail for an
 * environmental reason is worse than one that cannot.
 */
class AdvisoryRouteTest {

    private val oracle = File("../core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Oracle.kt")
    private val destinations = File("src/main/java/dev/mascwa/pulse/navigation/Destinations.kt")
    private val desktopDirectory = File("../desktop/src/main/kotlin/dev/mascwa/pulse/desktop/Directory.kt")

    /**
     * Live code only, with `//` comments removed.
     *
     * ⚠️ **Found by negative-testing this very file.** Commenting the desktop's `"anomalies"` mapping
     * out left the gate green: the pattern matched the string inside the comment, so a mapping
     * DELETED from the app still counted as present. Scoping each read to its own declaration was not
     * enough, because the comment sits inside the body. A gate that reads code somebody has taken out
     * reports on a version of the app that does not exist.
     *
     * The `inString` walk matters — routes are quoted, so a naive strip at the first `//` would cut a
     * URL in half. There are no block comments inside the three declarations read here, and if one
     * ever appears the effect is over-reporting emitted routes, which fails loudly rather than
     * passing quietly.
     */
    private fun liveCode(text: String): String = buildString {
        for (line in text.lineSequence()) {
            var inString = false
            var cut = line.length
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '\\' && inString -> i++ // an escape cannot close the string
                    ch == '"' -> inString = !inString
                    !inString && ch == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                        cut = i
                        i = line.length
                    }
                }
                i++
            }
            append(line, 0, cut).append('\n')
        }
    }

    /** Every `actionRoute = "…"` literal an insight can carry. */
    private fun emitted(): List<String> =
        Regex("""actionRoute = "([^"]*)"""").findAll(liveCode(oracle.readText()))
            .map { it.groupValues[1] }.toList()

    /** The phone's route vocabulary: the VALUES of `Routes`, since that is what an insight carries. */
    private fun androidRoutes(): Set<String> =
        Regex("""const val [A-Z_]+\s*=\s*"([^"]+)"""").findAll(liveCode(destinations.readText()))
            .map { it.groupValues[1] }.toSet()

    /**
     * The desktop's: the left-hand side of `screenForRoute`'s `when`.
     *
     * Read from inside that declaration alone — the file holds other string literals, and a
     * file-wide sweep would quietly accept a route mentioned only in a comment.
     */
    private fun desktopRoutes(): Set<String> {
        val text = desktopDirectory.readText()
        val start = text.indexOf("fun screenForRoute")
        assertTrue("screenForRoute has been renamed — this gate is reading nothing", start >= 0)
        val end = text.indexOf("\n}", start)
        assertTrue(end > start)
        return Regex(""""([^"]+)" -> Screen\.""").findAll(liveCode(text.substring(start, end)))
            .map { it.groupValues[1] }.toSet()
    }

    /**
     * The harness has to be shown to work before its verdicts mean anything.
     *
     * A regex that matched nothing would pass the assertion below without reading a line, which is
     * the failure mode this whole family of textual gates is most prone to.
     */
    @Test
    fun theGateIsActuallyReadingAllThreeFiles() {
        assertTrue("no actionRoute literals found — the pattern is wrong", emitted().size >= 10)
        assertTrue("no Routes constants found", androidRoutes().size >= 20)
        assertTrue("no desktop route mappings found", desktopRoutes().size >= 5)
        assertTrue("settings is a route on both, or the patterns are wrong", "settings" in androidRoutes())
        assertTrue("settings" in desktopRoutes())
    }

    @Test
    fun everyAdvisoryPointsSomewhereThatExists() {
        val known = androidRoutes() + desktopRoutes()
        val dead = emitted().distinct().filterNot { it in known }
        assertTrue(
            "these advisory routes resolve on neither platform, so ACT does nothing: $dead",
            dead.isEmpty(),
        )
    }

    /**
     * An insight with no route at all is fine — plenty are pure observations. An insight with a
     * BLANK one is a control that draws and does nothing, which is the same defect wearing a
     * different mask.
     */
    @Test
    fun noAdvisoryCarriesABlankRoute() {
        assertTrue(
            "an actionRoute is either absent or a real route, never an empty string",
            emitted().none { it.isBlank() },
        )
    }
}
