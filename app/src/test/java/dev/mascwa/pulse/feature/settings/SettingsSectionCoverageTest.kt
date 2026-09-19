package dev.mascwa.pulse.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings sections keep their search vocabulary somewhere both search surfaces can read it.
 *
 * ## Why this exists
 *
 * The vocabulary used to be 26 string literals passed to `vis(category, keywords)` — and `vis` is a
 * **local** function declared inside the `SettingsScreen` composable, so its arguments were
 * unreachable from anywhere else in the app by construction. The Settings search box could find
 * "sponsorblock", "anydesk", "stooq" and "iptv"; the app's own SEARCH screen could not find any of
 * them. Nothing said so: the code compiled, both surfaces worked, and each was individually right.
 *
 * What this gate stops is the 27th section arriving with its vocabulary trapped again.
 *
 * ## ⚠️ Why the extractor is paren-balanced and not line-oriented
 *
 * The house pattern for a textual gate is a line-oriented regex, and here it would silently under-
 * count. **One `vis(` site spans several lines** — `Texts & mail`, which is the exact section the
 * owner reported being unable to find and the reason this whole arc exists — so a line matcher sees
 * 29 sites, finds a self-consistent 29-entry table, and passes. Four more sites legitimately carry
 * a quoted string *later on the same line*, so a line-oriented "no literal here" rule would fail on
 * innocent code and the tempting repair is to widen it until it passes, which destroys the gate.
 *
 * Hence: balance parentheses from `vis(` to its match, and judge only what is inside. If this test
 * ever fails, fix the call site — do not widen the span.
 *
 * Reads the source as text, in the shape of `CredentialCoverageTest` and `ThrottleStampCoverageTest`,
 * so it cannot fail for an environmental reason and leave someone unable to tell a real break from
 * a broken harness.
 */
class SettingsSectionCoverageTest {

    private val screen = File("src/main/java/dev/mascwa/pulse/feature/settings/SettingsScreen.kt")
    private val table = File("src/main/java/dev/mascwa/pulse/feature/settings/SettingsSections.kt")
    private val index = File("src/main/java/dev/mascwa/pulse/data/search/DeviceSearchIndex.kt")

    /** Every `vis(` call site's argument list, with comments and string bodies left intact. */
    private fun visArgs(): List<String> {
        val src = screen.readText()
        val scan = blankCommentsAndStrings(src)
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            val at = scan.indexOf("vis(", i)
            if (at < 0) break
            i = at + 4
            // Skip the declaration itself, and any longer identifier ending in "vis".
            val before = scan.take(at).trimEnd()
            if (before.endsWith("fun")) continue
            if (at > 0 && (scan[at - 1].isLetterOrDigit() || scan[at - 1] == '_' || scan[at - 1] == '.')) continue
            var depth = 1
            var j = i
            while (j < scan.length && depth > 0) {
                if (scan[j] == '(') depth++ else if (scan[j] == ')') depth--
                j++
            }
            out += src.substring(i, j - 1)
        }
        return out
    }

    /**
     * Blanks comment bodies and string bodies so brackets inside them cannot unbalance the scan.
     *
     * ⚠️ Written character-by-character rather than with `startsWith("…")` on purpose: a Kotlin
     * string holding a comment marker is perfectly legal (Kotlin lexes strings first), but it
     * confuses every simple text tool that reads this file afterwards — including this repo's own
     * missing-return gate, which reported this function as never returning. The compiler was right
     * and the gate was right to be suspicious; carrying no comment markers at all satisfies both.
     */
    private fun blankCommentsAndStrings(s: String): String {
        val out = s.toCharArray()
        val slash = '/'
        val star = '*'
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = if (i + 1 < s.length) s[i + 1] else ' '
            if (c == '"') {
                var j = i + 1
                while (j < s.length && s[j] != '"') {
                    if (s[j] == '\\') { out[j] = ' '; j++ }
                    if (j < s.length) out[j] = ' '
                    j++
                }
                i = j + 1
            } else if (c == slash && next == slash) {
                var j = s.indexOf('\n', i)
                if (j < 0) j = s.length
                for (k in i until j) out[k] = ' '
                i = j
            } else if (c == slash && next == star) {
                var j = i + 2
                while (j + 1 < s.length && !(s[j] == star && s[j + 1] == slash)) j++
                j = if (j + 1 < s.length) j + 2 else s.length
                for (k in i until j) if (out[k] != '\n') out[k] = ' '
                i = j
            } else {
                i++
            }
        }
        return String(out)
    }

    private fun tableKeys(): List<String> =
        Regex("""\bval ([A-Z][A-Z0-9_]*) = SettingsSection\(""").findAll(table.readText())
            .map { it.groupValues[1] }.toList()

    /** The entries actually listed in `ALL` — which is what every consumer iterates. */
    private fun keysInAll(): List<String> {
        val body = table.readText().substringAfter("val ALL: List<SettingsSection> = listOf(")
            .substringBefore(")\n")
        return Regex("""\b([A-Z][A-Z0-9_]*)\b""").findAll(body).map { it.value }.toList()
    }

    /**
     * What a `vis(...)` site passes, normalised.
     *
     * ⚠️ Trailing comma and surrounding whitespace are stripped because a site may legitimately be
     * formatted across several lines — `vis(\n    SettingsSections.VIEWSCREEN,\n)`. An earlier
     * version compared the raw text and broke on exactly that, which a negative test caught: the
     * gate would have failed an innocent reformat, and the tempting repair is to un-wrap the call
     * rather than fix the gate.
     */
    private fun normalisedArg(raw: String): String = raw.trim().trimEnd(',').trim()

    @Test
    fun `the extractor itself works, so a green result is not vacuous`() {
        val args = visArgs()
        // ⚠️ Anchored on the MULTI-LINE site specifically. A line-oriented matcher misses exactly
        // this one, and it is the section ("Texts & mail") the whole arc is about — so if the
        // extractor ever regresses to line-oriented, this is what says so rather than a silent 29.
        assertEquals(
            "expected 30 vis() call sites; a different number means the extractor broke or a " +
                "section was added/removed without updating this gate",
            30, args.size,
        )
        assertTrue(
            "the extractor no longer sees the multi-line Texts & mail site — it has gone " +
                "line-oriented, which is the one failure this gate is shaped to prevent",
            args.any { it.contains("TEXTS_MAIL") },
        )
    }

    @Test
    fun `no section keeps its search vocabulary as a literal at the call site`() {
        val offenders = visArgs().filter { it.contains('"') }
        assertTrue(
            "these vis() call sites still carry a literal, so their words are readable only by " +
                "the Settings search box and are invisible to the app's own search: $offenders. " +
                "Add the section to SettingsSections and pass the entry instead.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every call site names a real table entry, and every entry is used`() {
        val keys = tableKeys()
        assertEquals("the table should hold 26 sections", 26, keys.size)
        assertEquals("section keys must be unique", keys.size, keys.distinct().size)

        val used = visArgs().map { normalisedArg(it) }
        used.forEach { arg ->
            assertTrue("a vis() site passes something that is not a table entry: $arg",
                arg.startsWith("SettingsSections."))
            assertTrue("a vis() site names a section the table does not declare: $arg",
                keys.any { arg == "SettingsSections.$it" })
        }
        val referenced = used.map { it.removePrefix("SettingsSections.") }.toSet()
        val unused = keys.filterNot { it in referenced }
        assertTrue("these table entries gate nothing — dead vocabulary: $unused", unused.isEmpty())
    }

    /**
     * ⚠️ Every declared section must be in `ALL`, and this is the defect class the whole arc is
     * about, one level up.
     *
     * `ALL` is what `searchRecords()` and the MENU matcher iterate, so a section declared and left
     * out of it gates its screen perfectly, reads correctly, and is **invisible to both search
     * surfaces** — the exact silent failure the sections had before this table existed. A negative
     * test caught the first version of this gate sleeping straight through it.
     */
    @Test
    fun `every declared section is listed in ALL, or its vocabulary is trapped again`() {
        val declared = tableKeys()
        val listed = keysInAll()
        assertEquals("ALL should list every declared section", declared.size, listed.size)
        val missing = declared.filterNot { it in listed }
        assertTrue(
            "these sections are declared but missing from ALL, so device search and the MENU " +
                "matcher cannot see them and their words are unfindable again: $missing",
            missing.isEmpty(),
        )
        val phantom = listed.filterNot { it in declared }
        assertTrue("ALL names something that is not a declared section: $phantom", phantom.isEmpty())
    }

    @Test
    fun `the search index reads the table rather than restating it`() {
        assertTrue(
            "DeviceSearchIndex no longer emits the Settings sections, so their vocabulary is " +
                "unreachable from the app's own search again",
            index.readText().contains("SettingsSections.searchRecords()"),
        )
        assertTrue(
            "the Settings rows must be RecordKind.SETTING — as FEATUREs they outrank the screens " +
                "they describe (a keyword body repeats its own title, and fields sum)",
            index.readText().contains("kind = RecordKind.SETTING"),
        )
    }

    @Test
    fun `the table declares its own quirks so nobody mistakes them for accidents`() {
        val t = table.readText()
        // The routes are deliberately non-unique: a section deep-links to its CATEGORY, so several
        // rows share a destination and the row's title is what distinguishes it. Asserted on
        // purpose — every other FEATURE id in the app is unique, so this is a new property.
        assertTrue(
            "SettingsSection.route must derive from the category, which is what makes the ids " +
                "deliberately non-unique",
            t.contains("\${Routes.SETTINGS}?cat=\${category.name.lowercase()}"),
        )
        // Three sections share their category's exact title; emitting them would duplicate the
        // category row outright — same destination AND same title — and spend two of four places.
        assertTrue(
            "searchRecords() must skip a section whose title equals its category's, or three rows " +
                "duplicate an existing one exactly",
            t.contains("filter { it.title != it.category.title }"),
        )
    }
}
