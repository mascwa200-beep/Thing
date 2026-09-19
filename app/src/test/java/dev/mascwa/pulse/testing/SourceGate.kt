package dev.mascwa.pulse.testing

import java.io.File

/**
 * The shared plumbing behind the source-reading coverage gates.
 *
 * ## Why this exists
 *
 * Four gates now read the app's own source as text to prove something is not dead —
 * `CredentialCoverageTest`, `ThrottleStampCoverageTest`, `OracleSignalCoverageTest` and
 * `AmbientActionCoverageTest`. Two of them had grown a byte-identical copy of [stripComments], and
 * the third was about to. ⚠️ This repository has corrected a duplicated definition seven times, and
 * the way it usually goes wrong is not that the copies disagree from the start — it is that one
 * gains a branch and the others do not, silently. `distance()` was triplicated with one copy having
 * lost its guard; four palettes drifted; two comment strippers is where that begins.
 *
 * ## The one thing every caller depends on
 *
 * ⚠️ **A gate that reads source must strip comments, or it can be satisfied by a comment.**
 * `CredentialCoverageTest` records being negative-tested against exactly that: deleting the real
 * registration left the paragraph explaining it behind, and a substring match found the word there.
 * A gate that passes on the documentation of a thing rather than the thing is worse than no gate,
 * because it reads as covered.
 */
object SourceGate {

    /**
     * Source with its comments blanked, ready to be searched for real code.
     *
     * Block comments first, so a `//` inside one cannot swallow the rest of the file.
     *
     * ## Two limitations, both measured rather than assumed
     *
     * ⚠️ **Nested block comments leave residue.** The block pattern is non-greedy, so given an
     * outer comment containing a second opener, it strips through the FIRST closer and leaves the
     * tail — text that was a comment, now looking like code, able to satisfy a gate. Measured
     * across `app/src/main`, `core/telemetry/src/main` and `desktop/src/main`: **zero** files
     * contain one, and [nestedBlockComment] plus the sweep in `SourceGateTest` is what keeps that
     * true. The assumption is guarded rather than the case handled, deliberately — this
     * repository's own rule is "never put a block-comment opener in a KDoc", because one once ate
     * a whole function and produced an unresolved-reference cascade that looked like a dozen
     * unrelated bugs. So the right move is to make a violation fail loudly, not to make it work.
     *
     * ⚠️ And note what this paragraph is careful NOT to do: it describes those delimiters in words
     * rather than writing them. My first draft of it spelled the example out literally, opened two
     * nested comments inside this very KDoc, swallowed the rest of the file, and failed to compile
     * with sixteen "unresolved reference" errors pointing at the test below — while explaining why
     * that is dangerous. `SourceGateTest` pins the behaviour with string literals, where the
     * characters are safe.
     *
     * ⚠️ **A `//` inside a string literal takes the rest of its line.** `val u = "https://x"` strips
     * from the `//` onward. Measured: **13 lines** in those trees have a string-embedded `//` with
     * further code after it, so this is live rather than theoretical. It is tolerable because of
     * which way it fails — a real reference disappears, so a gate reports something *uncovered* and
     * goes RED. That is a loud false alarm someone will investigate, not the silent false pass the
     * stripper exists to prevent. If it ever bites, the fix is a real lexer, not a wider regex.
     */
    fun stripComments(src: String): String = src
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    /**
     * The first nested block comment in [src], or null — the assumption [stripComments] rests on.
     *
     * Returns the offending comment (truncated) so a failure names what to go and look at rather
     * than only that something is wrong somewhere.
     *
     * ⚠️ **It does not know about string literals, so a file that writes the delimiters as text —
     * as this one does — reports a false positive.** Measured across the three trees the sweep
     * covers: none contains such a literal, so it does not bite there; `app/src/test` is
     * deliberately outside the sweep for exactly this reason. Left as a scanner rather than made
     * into a lexer because its whole job is to guard a limitation, and a guard that needs its own
     * parser has stopped being cheaper than handling the case it guards.
     */
    fun nestedBlockComment(src: String): String? {
        var i = 0
        var depth = 0
        var start = 0
        while (i < src.length - 1) {
            val two = src.substring(i, i + 2)
            when {
                two == "/*" -> {
                    if (depth == 0) start = i
                    depth++
                    i += 2
                }
                two == "*/" && depth > 0 -> {
                    depth--
                    if (depth == 0 && "/*" in src.substring(start + 2, i)) {
                        return src.substring(start, i + 2).take(160).replace("\n", " ")
                    }
                    i += 2
                }
                else -> i++
            }
        }
        return null
    }

    /**
     * Every `.kt` file under [dir].
     *
     * ⚠️ Requires the directory to exist. A gate handed a path that has moved would otherwise sweep
     * nothing, find no problems, and pass — the vacuous-green shape every one of these gates carries
     * an explicit self-check against.
     */
    fun kotlinFilesUnder(dir: File): List<File> {
        require(dir.isDirectory) {
            "missing: ${dir.absolutePath} — a gate cannot read a tree that is not there, and a " +
                "sweep of nothing would pass"
        }
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
