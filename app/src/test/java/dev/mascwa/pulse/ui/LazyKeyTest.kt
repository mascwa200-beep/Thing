package dev.mascwa.pulse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The gate against a lazy list crashing on a duplicate key.
 *
 * ⚠️ **This is a defect that actually shipped, and it is the most recent crash the owner's phone
 * ever reported.** `MenuScreen` had, in one `LazyColumn`:
 *
 * ```
 *   item(key = "search") { LcarsField(...) }          // the search box, always present
 *   ...
 *   items(group.entries, key = { it.route }) { ... }  // the directory
 * ```
 *
 * and `Routes.SEARCH` is the string `"search"`. Compose builds its key→index map over a *nearest
 * range* around the visible window rather than the whole list, so the two never met until a fling
 * carried both into the same range — which is why it read as an intermittent scroll crash rather
 * than a screen that could never open. The stack confirms it: the throw arrives through
 * `androidx.compose.foundation.gestures`.
 *
 * That screen has since been rebuilt on a plain `Column`, so the instance is gone. The class is not:
 * the app has 43 route constants and 55 lazy scopes, and nothing anywhere compares them.
 *
 * ⚠️ **The scanner is a pure function over text and the rules are tested against synthetic
 * fixtures**, so a rule can be proven to fire without touching a real file — and the same function
 * is then run over the real tree of BOTH applications. A gate whose rules are only ever exercised
 * by the code they pass on is a gate nobody can trust.
 *
 * ⚠️ Two rules only, and both are exact. A third — "two `items()` blocks in one scope keyed by the
 * same expression" — was measured and deliberately left out: `OfflineSurvivalScreen` and
 * `RecipesBody` both have that shape and both are safe by construction (disjoint by `needs`, unique
 * ids from one store), so it would ship two standing false positives. A gate with standing noise is
 * one people learn to ignore.
 */
class LazyKeyTest {

    // --- the tree ---------------------------------------------------------------------------------

    /** Both applications. The nutrition module is a sibling of this one. */
    private val roots = listOf(
        File("src/main/java"),
        File("../nutrition/src/main/java"),
    )

    private val destinations = File("src/main/java/dev/mascwa/pulse/navigation/Destinations.kt")

    private fun sources(): List<File> = roots.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Every value a route constant can hold — the right-hand side, not the name. */
    private fun routeValues(): Set<String> =
        Regex("""const\s+val\s+\w+\s*=\s*"([^"$]+)"""")
            .findAll(destinations.readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun scopesInTree(): List<LazyKeyScan.Scope> =
        sources().flatMap { f -> LazyKeyScan.scan(f.readText(), f.path) }

    // --- the fixture guard ------------------------------------------------------------------------

    @Test
    fun `the fixture finds the sources and the routes it is meant to police`() {
        // Guards the whole file: every assertion below is vacuously true over an empty list, so a
        // moved module would turn this suite green while checking nothing at all.
        val files = sources()
        assertTrue("no Kotlin sources found — have the module paths moved?", files.size > 300)
        assertTrue(
            "the nutrition module was not scanned — is it still a sibling of :app?",
            files.any { it.path.contains("nutrition") },
        )
        assertTrue("Destinations.kt not found at ${destinations.absolutePath}", destinations.isFile)
        assertTrue("no route constants parsed out of Destinations.kt", routeValues().size > 20)
        assertTrue(
            "found only ${scopesInTree().size} lazy scopes — has the scanner stopped matching?",
            scopesInTree().size > 40,
        )
    }

    // --- the two rules, over the real tree ---------------------------------------------------------

    @Test
    fun `no lazy scope uses the same fixed key twice`() {
        val broken = scopesInTree().mapNotNull { scope ->
            val seen = mutableMapOf<String, Int>()
            val dupes = scope.literals.mapNotNull { (value, line) ->
                val first = seen.put(value, line)
                if (first != null) "$value (line $first and line $line)" else null
            }
            if (dupes.isEmpty()) null else "${scope.file}: lazy scope at line ${scope.line} repeats ${dupes.joinToString("; ")}"
        }
        assertEquals("duplicate fixed keys in one lazy scope — this throws on scroll", emptyList<String>(), broken)
    }

    @Test
    fun `no fixed key collides with a route in a scope that keys items by route`() {
        val routes = routeValues()
        val broken = scopesInTree().mapNotNull { scope ->
            if (scope.dynamic.none { it.contains("route", ignoreCase = true) }) return@mapNotNull null
            val hits = scope.literals.filter { it.first in routes }
            if (hits.isEmpty()) null else {
                "${scope.file}: lazy scope at line ${scope.line} has fixed key(s) " +
                    hits.joinToString { "\"${it.first}\" (line ${it.second})" } +
                    " that are also route values, beside a key expression derived from a route"
            }
        }
        assertEquals(
            "a fixed key equals a route in a scope keyed by route — this is the MenuScreen crash",
            emptyList<String>(),
            broken,
        )
    }

    // --- the rules, proven to fire ----------------------------------------------------------------

    /** The historical `MenuScreen`, reduced to the shape that crashed. */
    private val theCrash = """
        @Composable
        fun MenuScreen(onOpen: (String) -> Unit) {
            LazyColumn(modifier = Modifier.padding(14.dp)) {
                item(key = "search") { LcarsField(value = query, onValueChange = { query = it }) }
                GROUPS.forEach { group ->
                    item(key = "hdr_${'$'}{group.label}") { LcarsHeaderBar(group.label) }
                    items(group.entries, key = { it.route }) { entry -> MenuRow(entry, onOpen) }
                }
            }
        }
    """.trimIndent()

    @Test
    fun `the route rule fires on the shape that actually crashed`() {
        val scopes = LazyKeyScan.scan(theCrash, "MenuScreen.kt")
        assertEquals(1, scopes.size)
        val scope = scopes.single()
        assertTrue(
            "the scanner did not read the fixed key — it cannot be testing anything",
            scope.literals.any { it.first == "search" },
        )
        assertTrue(
            "the scanner did not read the route key expression",
            scope.dynamic.any { it.contains("it.route") },
        )
        // A template is not a fixed key: "hdr_${group.label}" must not be read as a literal.
        assertTrue(
            "a string template was misread as a fixed key",
            scope.literals.none { it.first.startsWith("hdr_") },
        )
        assertTrue("\"search\" is a real route value", "search" in routeValues())
    }

    @Test
    fun `the duplicate rule fires on two identical fixed keys`() {
        val scopes = LazyKeyScan.scan(
            """
            LazyColumn {
                item(key = "header") { A() }
                item(key = "body") { B() }
                item(key = "header") { C() }
            }
            """.trimIndent(),
            "Fixture.kt",
        )
        assertEquals(listOf("header", "body", "header"), scopes.single().literals.map { it.first })
    }

    @Test
    fun `a nested lazy scope is its own scope and does not pollute its parent`() {
        // A LazyRow inside a LazyColumn item has a SEPARATE key namespace, so a key repeated across
        // the two is legal. Folding them together would have produced a false positive on every
        // horizontal shelf in the app.
        val scopes = LazyKeyScan.scan(
            """
            LazyColumn {
                item(key = "shelf") {
                    LazyRow {
                        item(key = "shelf") { A() }
                        items(rows, key = { it.route }) { B(it) }
                    }
                }
            }
            """.trimIndent(),
            "Nested.kt",
        )
        assertEquals(2, scopes.size)
        val outer = scopes.first { it.line == 1 }
        val inner = scopes.first { it.line != 1 }
        assertEquals(listOf("shelf"), outer.literals.map { it.first })
        assertEquals(emptyList<String>(), outer.dynamic)
        assertEquals(listOf("shelf"), inner.literals.map { it.first })
        assertTrue(inner.dynamic.single().contains("it.route"))
    }

    @Test
    fun `braces and openers inside strings and comments are not read as code`() {
        // The masker is what makes brace matching survive real source. Without it a `"{"` in a
        // placeholder, or a LazyColumn named in a KDoc, silently mis-scopes everything after it.
        val scopes = LazyKeyScan.scan(
            """
            /** Draws a LazyColumn( of things, with a { in the doc. */
            fun x() {
                val hint = "type { here }"   // LazyRow( in a comment
                LazyColumn {
                    item(key = "only") { Text(hint) }
                }
            }
            """.trimIndent(),
            "Masked.kt",
        )
        assertEquals(1, scopes.size)
        assertEquals(listOf("only"), scopes.single().literals.map { it.first })
    }
}

/**
 * Reads lazy-list keys out of Kotlin source text.
 *
 * ⚠️ Deliberately text over the real files rather than anything that touches a Compose class: this
 * runs under `:app:testDebugUnitTest` on a plain JVM, so it cannot fail for an environmental reason
 * and leave somebody unable to tell a real break from a broken harness. Same reasoning as
 * `WidgetLinkageTest`.
 */
internal object LazyKeyScan {

    /**
     * One lazy list's key namespace: the fixed keys it declares (value and 1-indexed line) and the
     * text of every key expression it computes.
     */
    data class Scope(
        val file: String,
        val line: Int,
        val literals: List<Pair<String, Int>>,
        val dynamic: List<String>,
    )

    private val OPENER = Regex("""\bLazy(?:Column|Row|VerticalGrid|HorizontalGrid|VerticalStaggeredGrid)\s*[({]""")
    private val CALL = Regex("""\b(?:item|items|itemsIndexed|stickyHeader)\s*\(""")

    fun scan(text: String, file: String): List<Scope> {
        val masked = mask(text)
        val bodies = mutableListOf<IntRange>()
        for (m in OPENER.findAll(masked)) {
            val open = openBraceOf(masked, m.range.last) ?: continue
            val close = matchBrace(masked, open) ?: continue
            bodies += (open + 1) until close
        }
        return bodies.map { body ->
            // A nested scope owns its own keys, so blank its body out of this one before reading.
            val own = StringBuilder(masked.substring(body))
            for (other in bodies) {
                if (other != body && other.first >= body.first && other.last <= body.last) {
                    for (i in other) own[i - body.first] = ' '
                }
            }
            val literals = mutableListOf<Pair<String, Int>>()
            val dynamic = mutableListOf<String>()
            for (call in CALL.findAll(own)) {
                val lp = call.range.last
                val rp = matchParen(own, lp) ?: continue
                val expr = keyExpression(own, lp + 1, rp) ?: continue
                val raw = text.substring(body.first + expr.first, body.first + expr.last + 1).trim()
                val literal = raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') &&
                    !raw.substring(1, raw.length - 1).contains('$')
                if (literal) {
                    literals += raw.substring(1, raw.length - 1) to lineOf(text, body.first + expr.first)
                } else {
                    dynamic += raw
                }
            }
            Scope(file, lineOf(text, body.first), literals, dynamic)
        }
    }

    /** The `key = <expr>` span inside one `item(...)` call, or null when the call declares none. */
    private fun keyExpression(s: CharSequence, from: Int, to: Int): IntRange? {
        var depth = 0
        var i = from
        while (i < to) {
            when (s[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                'k' -> if (depth == 0 && s.startsWith("key", i) &&
                    (i == from || !s[i - 1].isLetterOrDigit() && s[i - 1] != '_')
                ) {
                    var j = i + 3
                    while (j < to && s[j].isWhitespace()) j++
                    if (j < to && s[j] == '=' && (j + 1 >= to || s[j + 1] != '=')) {
                        var start = j + 1
                        while (start < to && s[start].isWhitespace()) start++
                        var end = start
                        var d = 0
                        while (end < to) {
                            val ch = s[end]
                            if (ch == '(' || ch == '[' || ch == '{') d++
                            if (ch == ')' || ch == ']' || ch == '}') d--
                            if (d == 0 && ch == ',') break
                            end++
                        }
                        while (end > start && s[end - 1].isWhitespace()) end--
                        return if (end > start) start until end else null
                    }
                }
            }
            i++
        }
        return null
    }

    private fun openBraceOf(s: CharSequence, at: Int): Int? {
        if (s[at] == '{') return at
        val close = matchParen(s, at) ?: return null
        var i = close + 1
        while (i < s.length && s[i].isWhitespace()) i++
        return if (i < s.length && s[i] == '{') i else null
    }

    private fun matchParen(s: CharSequence, open: Int): Int? = match(s, open, '(', ')')

    private fun matchBrace(s: CharSequence, open: Int): Int? = match(s, open, '{', '}')

    private fun match(s: CharSequence, open: Int, l: Char, r: Char): Int? {
        var depth = 0
        for (i in open until s.length) {
            if (s[i] == l) depth++
            if (s[i] == r) {
                depth--
                if (depth == 0) return i
            }
        }
        return null
    }

    private fun lineOf(text: CharSequence, index: Int): Int =
        1 + text.subSequence(0, index).count { it == '\n' }

    /**
     * The same text with comments blanked and every string's CONTENTS replaced, keeping length and
     * newlines so every offset still lines up with the original.
     *
     * ⚠️ Contents rather than the quotes: brace matching must not see a `{` typed inside a
     * placeholder, and must not see a `LazyColumn(` named in a KDoc — but the quotes themselves have
     * to survive so a fixed key is still recognisable as a string when it is read back out of the
     * original text.
     */
    private fun mask(text: String): String {
        val out = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') { out[i] = ' '; i++ }
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    while (i < end) { if (text[i] != '\n') out[i] = ' '; i++ }
                }
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    i += 3
                    while (i < end - 3) { if (text[i] != '\n') out[i] = BLANK; i++ }
                    i = end
                }
                text[i] == '"' -> {
                    i++
                    // Stops at a newline as well as at the quote: a single-quoted Kotlin string
                    // cannot span lines, so an unterminated one is a typo — and without this
                    // guard it would blank the rest of the file and mis-scope everything after.
                    while (i < text.length && text[i] != '"' && text[i] != '\n') {
                        if (text[i] == '\\') { out[i] = BLANK; i++ }
                        if (i < text.length) out[i] = BLANK
                        i++
                    }
                    i++
                }
                text[i] == '\'' -> {
                    i++
                    while (i < text.length && text[i] != '\'' && text[i] != '\n') {
                        if (text[i] == '\\') { out[i] = BLANK; i++ }
                        if (i < text.length) out[i] = BLANK
                        i++
                    }
                    i++
                }
                else -> i++
            }
        }
        return out.toString()
    }

    /**
     * What a masked-out character becomes.
     *
     * ⚠️ Not a space and not a letter: it has to be a character that is neither a bracket, a
     * quote, whitespace, a comma nor an identifier character, so a masked region can never close
     * a bracket the scanner is counting, look like the start of `key`, or end a `key = `
     * expression early. Length is preserved so every offset still lines up with the original.
     */
    private const val BLANK = '\u0001'
}
