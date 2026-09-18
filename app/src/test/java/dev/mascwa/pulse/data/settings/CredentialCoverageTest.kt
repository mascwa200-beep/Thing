package dev.mascwa.pulse.data.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every credential the settings hold must be known to the two places that keep it from leaving.
 *
 * ## Why this exists
 *
 * `AppSettings` is a ~180-field blob and three of the things in it are secrets that a person typed.
 * Two mechanisms stop them escaping, and **both are hand-maintained lists** — the shape this
 * repository has repeatedly found drifting:
 *
 *  - [allSecretValues] is what `SecretScrub` uses for its exact-match pass, which is the only thing
 *    that can catch a credential with no recognisable shape. A password is whatever somebody typed;
 *    no pattern can find it.
 *  - `SettingsBackup.redactSecrets` blanks it on export, and `merge` puts the device's own back on
 *    import.
 *
 * A new credential field added without touching either compiles, ships, works, and quietly rides out
 * in the next debug report. Nothing would say so.
 *
 * ## What it can and cannot catch, said plainly
 *
 * ⚠️ It matches on the property NAME, so it catches `password`, `apiToken`, `clientSecret` — the
 * names people actually use — and it would NOT have caught `pendingVerifier`, which is a live
 * Spotify credential named after what it does. A heuristic over names cannot do better than the
 * names. It is a floor, not a proof: it makes the common case impossible to forget and leaves the
 * unusual one to whoever reviews the change.
 *
 * Reads the source as text, in the shape of `WidgetLinkageTest`, so it cannot fail for an
 * environmental reason and leave someone unable to tell a real break from a broken harness.
 */
class CredentialCoverageTest {

    private val settingsDir = File("src/main/java/dev/mascwa/pulse/data/settings")
    private val backupSrc = File(settingsDir, "SettingsBackup.kt")

    /** Names that mean "a person typed a secret into this". */
    private val credentialish = listOf("password", "token", "secret", "apikey", "passphrase", "credential")

    /**
     * Names that match the heuristic and are not credentials.
     *
     * ⚠️ Each entry is a deliberate exemption and wants a reason, because an allowlist is how a
     * gate like this stops working.
     */
    private val notCredentials = mapOf(
        // A budget in tokens for a language model — a number's field name, nothing to do with auth.
        "maxTokens" to "a model's context budget, not an access token",
    )

    private fun read(f: File): String {
        assertTrue("missing: ${f.absolutePath}", f.isFile)
        return stripComments(f.readText())
    }

    /**
     * Every settings source, concatenated.
     *
     * ⚠️ The whole package rather than `AppSettings.kt`, and that is not tidiness. The mail account
     * started life inside that one file and was moved to its own; a gate reading a single path would
     * have stopped seeing its password and gone on passing — the "fixture never reached the branch"
     * failure, in gate form. Anything credential-shaped in this package is in scope wherever it is
     * declared.
     */
    private fun settingsSources(): String {
        assertTrue("missing: ${settingsDir.absolutePath}", settingsDir.isDirectory)
        val files = settingsDir.listFiles { f: File -> f.extension == "kt" }.orEmpty()
        assertTrue("no Kotlin sources in ${settingsDir.absolutePath}", files.isNotEmpty())
        return files.joinToString("\n") { stripComments(it.readText()) }
    }

    /**
     * The source with its comments removed.
     *
     * ⚠️ **Without this the gate can be satisfied by a comment, and was.** Every check below asks
     * whether a property NAME appears in a body, by substring; this codebase writes long comments
     * explaining exactly why each credential is registered, and those comments contain the name. A
     * negative test that deleted the real `emailAccounts.map { it.password }` registration left the
     * paragraph explaining it behind — and the gate reported everything covered. The comment that
     * documents a rule must not be able to stand in for the rule.
     *
     * Block comments first, so a `//` inside one cannot swallow the rest of the file.
     */
    private fun stripComments(src: String): String = src
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    /** Every `val <name>: String` declared anywhere in the settings source. */
    private fun stringProperties(src: String): List<String> =
        Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*String\b""")
            .findAll(src).map { it.groupValues[1] }.toList()

    /**
     * The first balanced `(…)` or `{…}` group at or after [from].
     *
     * ⚠️ Returns the group INCLUDING its delimiters, and stops at the matching close rather than at
     * the first close — nested calls are the normal case in every body this reads.
     */
    private fun group(src: String, from: Int): Pair<String, Int> {
        var depth = 0
        var started = false
        val out = StringBuilder()
        for (i in from until src.length) {
            val ch = src[i]
            if (ch == '(' || ch == '{') { depth++; started = true }
            if (started) out.append(ch)
            if (ch == ')' || ch == '}') {
                depth--
                if (depth == 0 && started) return out.toString() to i
            }
        }
        return out.toString() to src.length
    }

    /**
     * The body of a named declaration.
     *
     * ⚠️ [skipParams] is not a convenience. A function declaration's own parameter list is a
     * balanced group too, so taking "the first group after the name" gives `()` for
     * `allSecretValues()` and `(s: AppSettings)` for `redactSecrets` — and my first version of this
     * did exactly that, then reported every credential in the app as uncovered. A harness that
     * declares the thing it is checking broken, when the harness is what is broken, is worse than
     * no harness. A `data class X(` declaration IS its own parameter list, so it passes false.
     */
    private fun bodyOf(src: String, declaration: String, skipParams: Boolean = true): String {
        val at = src.indexOf(declaration)
        assertTrue("could not find `$declaration` — has it been renamed?", at >= 0)
        if (!skipParams) return group(src, at).first
        val (params, end) = group(src, at)
        assertTrue("`$declaration` has no parameter list to skip", params.startsWith("("))
        return group(src, end + 1).first
    }

    @Test
    fun `every credential-shaped field is named in the scrub list`() {
        val src = settingsSources()
        val scrub = bodyOf(src, "fun AppSettings.allSecretValues()")
        val missing = stringProperties(src)
            .filter { name -> credentialish.any { it in name.lowercase() } }
            .filterNot { it in notCredentials }
            .distinct()
            .filterNot { it in scrub }
        assertTrue(
            "these look like credentials and allSecretValues() does not name them, so SecretScrub " +
                "cannot remove them from a debug report: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every credential-shaped field is blanked on export`() {
        val src = settingsSources()
        val redact = bodyOf(read(backupSrc), "fun redactSecrets(")
        // A field may be covered either by name or by its whole enclosing type being reconstructed
        // fresh — `apiKeys = ApiKeys()` blanks all seven at once, which is the tidier way to say it.
        val wholesale = Regex("""=\s*([A-Z][A-Za-z0-9_]*)\(\s*\)""").findAll(redact)
            .map { it.groupValues[1] }.toSet()
        val wholesaleFields = wholesale.flatMap { type ->
            stringProperties(bodyOf(src, "data class $type(", skipParams = false))
        }.toSet()

        val missing = stringProperties(src)
            .filter { name -> credentialish.any { it in name.lowercase() } }
            .filterNot { it in notCredentials }
            .distinct()
            .filterNot { it in redact || it in wholesaleFields }
        assertTrue(
            "these look like credentials and redactSecrets does not blank them, so an exported " +
                "backup would carry them in cleartext: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `a blanked credential is put back on import`() {
        // ⚠️ The export half ALONE is a data-loss bug rather than an incomplete feature: restoring a
        // backup would write the blanks over working credentials and sign the user out of
        // everything. Whatever `redactSecrets` blanks, `merge` has to restore from the device.
        val backup = read(backupSrc)
        val redact = bodyOf(backup, "fun redactSecrets(")
        val merge = bodyOf(backup, "fun merge(")
        val blanked = Regex("""^\s*(\w+)\s*=""", RegexOption.MULTILINE)
            .findAll(redact).map { it.groupValues[1] }.toSet() - setOf("s")
        val notRestored = blanked.filterNot { it in merge }
        assertTrue(
            "redactSecrets blanks these and merge does not mention them, so restoring a backup " +
                "would wipe them off the device: $notRestored",
            notRestored.isEmpty(),
        )
    }

    @Test
    fun `the heuristic is actually finding the fields it is meant to`() {
        // ⚠️ Without this the three tests above pass trivially the day the regex stops matching —
        // an empty list satisfies every "nothing is missing" assertion. These are the credentials
        // known to exist today; the gate must be able to see them.
        val found = stringProperties(settingsSources())
            .filter { name -> credentialish.any { it in name.lowercase() } }
        for (expected in listOf("password", "githubToken", "modelToken", "cloudApiKey", "accessToken", "refreshToken")) {
            assertTrue("the scan no longer finds `$expected` — the regex has stopped working", expected in found)
        }
    }
}
