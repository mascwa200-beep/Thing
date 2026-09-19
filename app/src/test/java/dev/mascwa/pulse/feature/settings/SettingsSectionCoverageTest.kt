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

    // ---- control-level vocabulary ------------------------------------------------------------

    /**
     * The composables that draw a row you can actually *do* something with.
     *
     * ⚠️ `SingleChoiceRow` is declared `private fun <T> SingleChoiceRow(` — the generic sits between
     * `fun` and the name, which is what made the first enumerator of this list miss it entirely and
     * report twelve rows as not existing. `PrefInfo` is deliberately absent: it is a readout, not a
     * control, and nothing is lost by not being able to search for one.
     */
    private val controlNames =
        listOf("PrefSwitch", "PrefClickable", "EditableValueRow", "SingleChoiceRow", "AddTextRow")

    /**
     * Where the `SettingsScreen` composable's own body ends.
     *
     * ⚠️ Load-bearing. The private helpers at the bottom of the file call these same composables —
     * `EditableValueRow` delegates to `PrefClickable`, and the four `Add*Row` builders draw buttons —
     * so a position-based section assignment files them under the LAST section on the screen. The
     * first run of this measurement duly reported "Add symbol" as living in "Storage & about".
     */
    private fun bodyEnd(scan: String, lastGate: Int): Int =
        Regex("""^(private fun|internal fun|fun |@Composable)""", RegexOption.MULTILINE)
            .findAll(scan).map { it.range.first }.firstOrNull { it > lastGate }
            ?: error("could not find where the SettingsScreen body ends")

    /** Every call site of [name], as (offset, its argument list read from the REAL source). */
    private fun controlSites(name: String): List<Pair<Int, String>> {
        val src = screen.readText()
        val scan = blankCommentsAndStrings(src)
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (true) {
            val at = scan.indexOf("$name(", i)
            if (at < 0) break
            i = at + name.length + 1
            if (at > 0 && (scan[at - 1].isLetterOrDigit() || scan[at - 1] == '_' || scan[at - 1] == '.')) continue
            // Skip the declaration, generic or not: `fun X(` and `fun <T> X(`.
            if (Regex("""\bfun\s+(<[^>]*>\s*)?$""").containsMatchIn(scan.take(at))) continue
            var depth = 1
            var j = i
            while (j < scan.length && depth > 0) {
                if (scan[j] == '(') depth++ else if (scan[j] == ')') depth--
                j++
            }
            out += at to src.substring(i, j - 1)
        }
        return out
    }

    /** The title a control row renders, when it is a literal. `title` is the first parameter of all five. */
    private fun titleOf(args: String): String? =
        Regex("""\btitle\s*=\s*"((?:[^"\\]|\\.)*)"""").find(args)?.groupValues?.get(1)
            ?: Regex("""^\s*"((?:[^"\\]|\\.)*)"""").find(args)?.groupValues?.get(1)

    /** Each control paired with the section key whose `vis(...)` gate encloses it. */
    private fun controlsBySection(): List<Pair<String, String>> {
        val src = screen.readText()
        val scan = blankCommentsAndStrings(src)
        val gates = Regex("""vis\(\s*SettingsSections\.([A-Z_]+)\s*\)""").findAll(scan)
            .map { it.range.first to it.groupValues[1] }.toList()
        check(gates.size == 30) { "expected 30 section gates, found ${gates.size}" }
        val end = bodyEnd(scan, gates.last().first)

        val out = ArrayList<Pair<String, String>>()
        for (name in controlNames) {
            for ((at, args) in controlSites(name)) {
                if (at > end) continue
                val title = titleOf(args) ?: continue
                val key = gates.last { it.first < at }.second
                out += key to title
            }
        }
        return out
    }

    private data class Vocab(val title: String, val words: String)

    /** key -> the section's title, its keywords, and its category name. */
    private fun sectionVocab(): Map<String, Triple<String, String, String>> {
        val re = Regex(
            """val ([A-Z_]+) = SettingsSection\(\s*"[a-z_]+",\s*SettingsCategory\.([A-Z]+),\s*""" +
                """"((?:[^"\\]|\\.)*)",\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)""",
        )
        return re.findAll(table.readText()).associate { m ->
            val words = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(m.groupValues[4])
                .joinToString(" ") { it.groupValues[1] }
            m.groupValues[1] to Triple(m.groupValues[3], words, m.groupValues[2])
        }
    }

    /** category name -> its title, blurb and keywords. Keywords are the FIFTH positional argument. */
    private fun categoryVocab(): Map<String, Vocab> {
        val src = File("src/main/java/dev/mascwa/pulse/feature/settings/SettingsCategory.kt").readText()
        val re = Regex(
            """^ {4}([A-Z]+)\(\s*"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)",\s*""" +
                """"(?:[^"\\]|\\.)*",\s*Icons\.[A-Za-z.]+,\s*\n?\s*"((?:[^"\\]|\\.)*)"""",
            RegexOption.MULTILINE,
        )
        return re.findAll(src).associate { m ->
            m.groupValues[1] to Vocab(m.groupValues[2], m.groupValues[3] + " " + m.groupValues[4])
        }
    }

    /**
     * The tokens a query for [text] could plausibly be.
     *
     * ⚠️ Includes the **hyphen-collapsed** form, and only hyphens. `GuideSearch.fieldMatch` splits on
     * letter-or-digit boundaries, so "Wi-Fi" becomes "wi" and "fi" — both below the four-character
     * stem floor, so neither can ever match the keyword "wifi", which is the word a person types.
     * Periods are NOT collapsed: "NewsAPI.org" would become "newsapiorg", which nobody types.
     */
    private fun tokens(text: String): Set<String> {
        val low = text.lowercase()
        val plain = low.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val joined = low.split(Regex("[^a-z0-9-]+")).map { it.replace("-", "") }.filter { it.isNotBlank() }
        return (plain + joined).toSet()
    }

    /**
     * Would the scorer answer [token] from [haystack]?
     *
     * Mirrors `GuideSearch.wordMatch`: equality, or a prefix relation in **either** direction at four
     * characters or more. A stricter set-membership test is what made the first measurement report
     * eight Notifications rows unreachable when the category keywords carry "market" and the rows say
     * "markets".
     */
    private fun reaches(token: String, haystack: Set<String>): Boolean =
        token in haystack ||
            (
                token.length >= 4 &&
                    haystack.any { it.length >= 4 && (it.startsWith(token) || token.startsWith(it)) }
                )

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
        // ⚠️ Skipping the ROW is right; dropping its WORDS with it was not, and the sweep caught
        // that: eight words for controls in those sections reached the Settings box and nothing at
        // all in device search. The partition is what folds them onto the category row instead.
        assertTrue(
            "searchRecords() must skip a section whose title equals its category's, or three rows " +
                "duplicate an existing one exactly",
            t.contains("partition { it.title == it.category.title }"),
        )
        assertTrue(
            "a skipped section's keywords must be folded into the category row that stands in for " +
                "it, or its whole vocabulary is invisible to device search",
            t.contains("inherited"),
        )
    }

    /**
     * ⚠️ The self-check for the control gate below. Every one of its assertions is about something
     * being *absent*, so a broken extractor passes it in silence — which is how a gate like this
     * usually dies. This asserts the extractor can SEE what is known to be there first.
     */
    @Test
    fun `the control extractor sees the shapes that have broken it before`() {
        val found = controlsBySection()
        assertTrue(
            "expected well over a hundred actionable rows; ${found.size} means the extractor broke",
            found.size >= 100,
        )
        val titles = found.map { it.second }
        // A positional title, a named one, one under a GENERIC declaration, and one from the
        // collapsible-header half of the table — the four shapes that have each broken a version
        // of this extractor.
        listOf(
            "Disable all cameras",       // positional
            "Let it change the phone",   // title = "..."
            "Quiet start",               // SingleChoiceRow, declared `fun <T> SingleChoiceRow(`
            "Add symbol",                // inside a private Add*Row builder — must NOT appear
        ).forEachIndexed { i, t ->
            val seen = t in titles
            if (i < 3) {
                assertTrue("the extractor no longer sees the control titled '$t'", seen)
            } else {
                assertTrue(
                    "'$t' is drawn by a private helper BELOW the composable's body, so it has no " +
                        "enclosing section — seeing it means bodyEnd() stopped working and every " +
                        "such row is being filed under whichever section happens to come last",
                    !seen,
                )
            }
        }
        // And the reachability rule must be able to say no, or every row "passes".
        assertTrue(
            "reaches() answers true for a word nothing says — the gate below cannot fail",
            !reaches("nosuchwordanywhere", setOf("storage", "cache", "ledger")),
        )
    }

    /**
     * ⚠️ Every actionable Settings row can be found by typing its own name. This is the last layer
     * of the same defect the section table was built for.
     *
     * The words were measured, not guessed: **32 of 114 rows** could not be reached by any word of
     * their own title — `finnhub`, `eia`, `fred` and `nasa` under Optional API keys; `allergies`,
     * `medications` and `conditions` under the medical card; `interrogator`; `exploit`; `python`;
     * `anchor`. Each is a word somebody with that exact need types, and each returned nothing.
     *
     * There is no per-control gating and this does not ask for any: a control is reachable when the
     * SECTION that holds it says one of its words, which is the cheap shape and the honest one —
     * the search result already names the section, so you land on the right page.
     *
     * ⚠️ If this fails, add the missing word to that section's `keywords` in [SettingsSections].
     * Do not add it to the CATEGORY's keywords: five sections live under CONTENT, so a word lifted
     * up there makes searching it inside Settings return all five, which is the pollution the table
     * exists to avoid and is written up on the class itself.
     */
    @Test
    fun `every actionable Settings row can be found by its own name`() {
        val sections = sectionVocab()
        val categories = categoryVocab()
        assertEquals("expected 26 sections parsed from the table", 26, sections.size)
        assertEquals("expected 10 categories parsed from the enum", 10, categories.size)

        val unreachable = controlsBySection().filterNot { (key, title) ->
            val (secTitle, secWords, catName) = sections.getValue(key)
            val cat = categories.getValue(catName)
            val haystack = tokens("$secTitle $secWords ${cat.title} ${cat.words}")
            tokens(title).any { reaches(it, haystack) }
        }
        assertTrue(
            "these Settings rows cannot be found by any word of their own name — add the word to " +
                "the owning section's keywords in SettingsSections: " +
                unreachable.joinToString { "${sections.getValue(it.first).first} ▸ ${it.second}" },
            unreachable.isEmpty(),
        )
    }
}
