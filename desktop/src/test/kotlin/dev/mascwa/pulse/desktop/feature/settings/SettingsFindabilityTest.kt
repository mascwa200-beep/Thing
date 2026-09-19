package dev.mascwa.pulse.desktop.feature.settings

import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.GuideSearch
import dev.mascwa.pulse.desktop.DESK_ENTRIES
import dev.mascwa.pulse.desktop.Screen
import dev.mascwa.pulse.desktop.search.DesktopSearchIndex
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings screen can be found by what it actually controls.
 *
 * ## Why this exists
 *
 * Measured before the vocabulary was added: **all nineteen controls on that screen, and six of its
 * seven sections, could be reached by no word of their own name.** Only UNITS was findable, through
 * the single term `units`. So somebody hunting the Fahrenheit switch, the country code, the GitHub
 * token, the EIA key or the screensaver got nothing — from the command bar and from SEARCH's GO
 * HERE block alike, since both read the same directory entry. Nothing said so: every screen was
 * listed, the search worked, and each half was individually right.
 *
 * What this gate stops is the next control arriving with its word trapped on the screen.
 *
 * ## ⚠️ It runs the shipped search, not a copy of its rules
 *
 * `GuideSearch.wordMatch` is `internal`, and `internal` is scoped to a Gradle module — so this test
 * cannot call it and **must not restate it**. A mirrored stem rule would drift from the ranker
 * silently and this gate would then be measuring itself. Instead it asks
 * [DesktopSearchIndex.screenRecords] and [DeviceSearch.search] the same question the application
 * asks, so "findable" here means findable there by construction, capping and all.
 *
 * ## ⚠️ Why the property is per-SECTION, not per-control
 *
 * Four control labels are deliberately unreachable, and one of them settles the design: *"Look for
 * a newer build on launch"* has exactly one distinctive word, `launch`, and **OBSERVATORY owns it**
 * (rocket launches). A rule demanding every control be individually typeable would force this entry
 * to steal that word from the screen that is genuinely the better answer for it. So the honest
 * property is that every *section* is reachable — by its own heading or by anything on it — which
 * is what actually matters, because the search result names the section and lands you on the page.
 *
 * The four are pinned by name in [notIndividuallyReachable] rather than skipped by a rule, so a
 * NEW unreachable control fails and these do not. ⚠️ Adding to that list needs the same reason the
 * existing entries carry — that the label's only distinctive word belongs to another screen, or is
 * an action you press once you are already on the page. It is not a way to silence a failure.
 */
class SettingsFindabilityTest {

    private val source =
        File("src/main/kotlin/dev/mascwa/pulse/desktop/feature/settings/SettingsScreen.kt")

    /**
     * Labels that no word of their own can reach, each for a stated reason.
     *
     * Every one of them sits inside a section that IS reachable, so the page is found and the
     * control is on it.
     */
    private val notIndividuallyReachable = mapOf(
        "GUESS FROM MY CONNECTION" to
            "a button inside WHERE YOU ARE, which `location`/`place`/`latitude` already reach",
        "FORGET WHAT I TYPED" to
            "likewise, and it only appears once a place has been typed by hand",
        "Look for a newer build on launch" to
            "`launch` is OBSERVATORY's and `build`/`update` are ABOUT's — all three belong there",
        "Keep it in front" to
            "generic English; STANDBY DISPLAY is reached by `standby`/`screensaver`/`wallpaper`",
    )

    // ---- the corpus, from the shipped index -----------------------------------------------------

    private val records = DesktopSearchIndex.screenRecords()

    private fun findsSettings(query: String): Boolean =
        DeviceSearch.search(records, query).any { it.id == Screen.SETTINGS.name }

    /** Any word of [text] that the shipped search answers with Settings. */
    private fun reachingWords(text: String): List<String> =
        GuideSearch.tokens(text).filter { findsSettings(it) }

    // ---- reading the screen ---------------------------------------------------------------------

    /**
     * The source with comments blanked and string bodies kept.
     *
     * ⚠️ **Prophylactic, and measured to be so rather than assumed.** The screen contains no
     * commented-out control today — checked, zero of the five composables appear inside a comment
     * — so removing this changes nothing about the real file right now. It is here because a
     * commented-out control is an entirely ordinary thing to leave in a screen, and the day one
     * appears this gate would demand a search word for something that no longer exists. The
     * tempting repair for that failure is to add the word, which is exactly wrong.
     *
     * Its behaviour is pinned by [theExtractorIgnoresCommentsAndDeclarations] against a fixture,
     * **not** by hoping the real file happens to contain the case. An earlier version asserted
     * that `"Boot sequence"` — quoted in the paragraph explaining why there is no APPEARANCE
     * section — never becomes a label, and that assertion could not fail: it is not inside a
     * control call, so no extractor would have picked it up either way.
     */
    private fun blankComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                src[i] == '"' -> {
                    // Copy the literal whole, so a // or /* inside one is not mistaken for a comment.
                    val triple = src.startsWith("\"\"\"", i)
                    var j = i + if (triple) 3 else 1
                    while (j < src.length) {
                        if (triple && src.startsWith("\"\"\"", j)) { j += 3; break }
                        if (!triple && src[j] == '"') { j++; break }
                        if (!triple && src[j] == '\\') j++
                        j++
                    }
                    out.append(src, i, minOf(j, src.length)); i = j
                }
                two == "//" -> {
                    var j = src.indexOf('\n', i); if (j < 0) j = src.length
                    repeat(j - i) { out.append(' ') }; i = j
                }
                two == "/*" -> {
                    var j = src.indexOf("*/", i); j = if (j < 0) src.length else j + 2
                    repeat(j - i) { out.append(' ') }; i = j
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun closingParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            if (text[i] == '(') depth++ else if (text[i] == ')') { depth--; if (depth == 0) return i }
            i++
        }
        return -1
    }

    /** The labelled, actionable rows of the real screen, each paired with the heading above it. */
    private fun controlsBySection(): List<Pair<String, String>> =
        extract(blankComments(source.readText()))

    /**
     * The extractor proper, over already-blanked code.
     *
     * ⚠️ Separated from [controlsBySection] so the comment rule can be pinned against a fixture
     * that reaches it. It was written before the real file was measured and has no case in it, so
     * perturbing it left the suite green — which reads as "the rule does nothing" and is really
     * "nothing here exercises it".
     */
    private fun extract(code: String): List<Pair<String, String>> {
        val headings = Regex("""SectionTitle\("([^"]+)"\)""").findAll(code).toList()
        val out = ArrayList<Pair<String, String>>()
        for (name in CONTROLS) {
            for (m in Regex("""\b$name\s*\(""").findAll(code)) {
                // Skip the declaration itself, generic or not: `fun X(` and `fun <T> X(`.
                //
                // ⚠️ **Belt and braces: removing this fails nothing today, and that was measured
                // rather than assumed.** A declaration's parameter list cannot yield a label
                // through either pattern below — `label: String = "x"` has `: String` between the
                // name and the `=`, so the named-argument pattern misses it, and the positional
                // one needs a quote as the very first token. So the label rules already exclude
                // declarations and this is a second lock on the same door. It stays because the
                // first lock is incidental: loosen either pattern to catch some new call shape and
                // a declaration is caught with it, silently, as a control nobody can type.
                if (Regex("""\bfun\s+(<[^>]*>\s*)?$""").containsMatchIn(code.take(m.range.first))) continue
                val end = closingParen(code, m.range.last)
                if (end < 0) continue
                val args = code.substring(m.range.last + 1, end)
                val label = Regex("""label\s*=\s*"([^"]*)"""").find(args)?.groupValues?.get(1)
                    ?: Regex("""^\s*"([^"]*)"""").find(args)?.groupValues?.get(1)
                    ?: continue
                // A label built from a template is not a word anybody types.
                if ('$' in label) continue
                val section = headings.lastOrNull { it.range.first < m.range.first }
                    ?.groupValues?.get(1) ?: continue
                out += section to label
            }
        }
        return out
    }

    // ---- the gate -------------------------------------------------------------------------------

    @Test
    fun theExtractorSeesTheShapesThatHaveBrokenItBefore() {
        val found = controlsBySection()
        assertTrue(
            "expected the whole Settings screen; ${found.size} rows means the extractor broke",
            found.size >= 18,
        )
        val labels = found.map { it.second }
        val sections = found.map { it.first }.distinct()
        assertTrue("expected every section to hold at least one control, saw $sections", sections.size >= 7)

        // A positional label, a named one, and the two shapes that have each broken a version of
        // this extractor elsewhere in the project.
        for (t in listOf("GUESS FROM MY CONNECTION", "Fahrenheit", "Country code", "Keep recording")) {
            assertTrue("the extractor no longer sees the control labelled '$t'", t in labels)
        }
        // A template label is not a word; if this appears, the '$' guard stopped working.
        assertTrue("a template label leaked in", labels.none { '$' in it })

        // And the reachability rule must be able to say no, or everything below "passes".
        assertTrue(
            "the shipped search answers Settings for a word nothing says — this gate cannot fail",
            !findsSettings("nosuchwordanywhere"),
        )
    }

    /**
     * A control that is commented out, or merely declared, is not a control.
     *
     * ⚠️ Pinned against a fixture rather than against the real screen, and the reason is worth
     * keeping: perturbing the comment rule left the whole suite green, because the screen contains
     * no commented-out control at all. A guard whose case does not exist in the corpus reads as
     * dead when it is merely unexercised — so the fixture supplies the case, and blanking can now
     * fail. The second half is the control run: the same fixture through the *unblanked* extractor
     * must yield the commented labels, or "blanking removed them" would be a claim about something
     * that was never there.
     *
     * ⚠️ **The declaration half passes for a different reason than you would guess, and saying so
     * is the point.** Disabling the `fun` skip still leaves `"Declared default"` out, because the
     * two label patterns already cannot read a parameter list — see the note at that line. The
     * assertion is kept because *"a declaration is never a control"* is the property that matters
     * however it is enforced; what is not claimed is that the skip is what enforces it.
     */
    @Test
    fun theExtractorIgnoresCommentsAndDeclarations() {
        val fixture = """
            SectionTitle("FIXTURE")
            LcarsSwitch("Live control", s.x, vm::setX)
            // LcarsSwitch("Line commented", s.y, vm::setY)
            /* LcarsButton("Block commented", { }) */
            private fun NumberField(label: String = "Declared default", value: String) { }
        """.trimIndent()

        val blanked = extract(blankComments(fixture)).map { it.second }
        assertTrue("the fixture's real control vanished — the extractor is broken", "Live control" in blanked)
        assertTrue("a line-commented control was read as real", "Line commented" !in blanked)
        assertTrue("a block-commented control was read as real", "Block commented" !in blanked)
        // Enforced by the label patterns rather than by the `fun` skip — see the KDoc above.
        assertTrue("a DECLARATION's defaulted label was read as a control", "Declared default" !in blanked)

        // ⚠️ Control run. Without blanking, the two commented labels must come back — otherwise the
        // assertions above would pass on a fixture the extractor never saw, which is exactly the
        // failure this whole test was written to correct.
        val raw = extract(fixture).map { it.second }
        assertTrue(
            "the commented controls are invisible even unblanked, so the fixture proves nothing " +
                "about blankComments",
            "Line commented" in raw && "Block commented" in raw,
        )
    }

    /**
     * ⚠️ Every section of the Settings screen can be found by typing something on it.
     *
     * If this fails, add the missing word to the Settings entry's `searchTerms` in `Directory.kt`
     * — **unless another screen already owns it**, in which case it stays there and the label goes
     * in [notIndividuallyReachable] with that reason. `long watch` is ANOMALIES', `channels` is
     * LIVE's, `library` is LIBRARY's, `launch` is OBSERVATORY's; Settings holding the switch does
     * not make it the better answer for the word.
     */
    @Test
    fun everySettingsSectionCanBeFoundByWhatIsOnIt() {
        val bySection = controlsBySection().groupBy({ it.first }, { it.second })
        val unreachable = bySection.filterNot { (heading, labels) ->
            reachingWords(heading + " " + labels.joinToString(" ")).isNotEmpty()
        }
        assertTrue(
            "no word of these sections' own headings or controls finds the Settings screen — add " +
                "one to the Settings entry's searchTerms in Directory.kt: ${unreachable.keys}",
            unreachable.isEmpty(),
        )
    }

    /**
     * Every control is findable by its own name, except the four pinned above.
     *
     * ⚠️ The assertion is on the EXCEPTIONS, not on the rule: pinning the list of things that do
     * not hold is what makes a new one fail, where asserting the rule and allowing a skip predicate
     * would quietly absorb it. Same shape as `LiveChannelsTest`, for the same reason.
     */
    @Test
    fun onlyTheFourNamedControlsCannotBeFoundByTheirOwnName() {
        val unreachable = controlsBySection()
            .filter { (_, label) -> reachingWords(label).isEmpty() }
            .map { it.second }
            .distinct()
            .toSet()
        val unexpected = unreachable - notIndividuallyReachable.keys
        assertTrue(
            "these controls cannot be found by any word of their own name and are not accounted " +
                "for — add the word to the Settings entry's searchTerms in Directory.kt: $unexpected",
            unexpected.isEmpty(),
        )
        // The other direction: a pinned label that became reachable should leave the list, or it
        // reads as a standing limitation when it is not one.
        val stale = notIndividuallyReachable.keys - unreachable
        assertTrue("these are reachable now and should come off the pinned list: $stale", stale.isEmpty())
    }

    /** The entry really is the one the screen records are built from. */
    @Test
    fun theSettingsEntryIsTheOneThatCarriesTheVocabulary() {
        val entry = DESK_ENTRIES.getValue(Screen.SETTINGS)
        assertTrue("the Settings entry lost its search terms", entry.searchTerms.size >= 20)
        assertTrue(
            "search terms must be lowercase single words — the index splits them on whitespace",
            entry.searchTerms.all { it == it.lowercase() && it.isNotBlank() },
        )
    }

    private companion object {
        /** The labelled, actionable composables the Settings screen is built from. */
        val CONTROLS = listOf(
            "LcarsSwitch", "LcarsTextField", "NumberField", "LcarsButton", "LcarsGhostButton",
        )
    }
}
