package dev.mascwa.pulse.data.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every `last…Ms` stamp in the settings blob must be read by something.
 *
 * ## Why this exists
 *
 * Three of them were not. `lastCuriosityMs`, `lastLedgerAnchorMs` and `lastAttestationCheckMs` were
 * stamped by the background worker on every run so that something could pace a pass by them, and
 * nothing ever read one — so a full cloud agent loop with web search, a StrongBox keypair generation
 * and a call to a free public timestamping service each ran on every tick, up to ninety-six times a
 * day. A throttle whose stamp is never read does not throttle, and **nothing said so**: the code
 * compiled, the passes worked, and three separate comments plus the handoff described all three as
 * throttled.
 *
 * ## Why the settings blob specifically, and not every `last…Ms` in the app
 *
 * ⚠️ Measured before scoping this: every other `last…Ms` in the app module is alive —
 * `lastScanMs`, `lastEpochMs`, `lastUsedMs`, `lastStudiedAtMs`, `lastShownMs`, `lastSeenMs`,
 * `lastRefusalMs`, `lastAccessedMs`. That is not luck, it is structural. Those are `var`s and map
 * entries on live objects, so the write and the read sit a few lines apart in one file, where a
 * missing read is obvious to anyone reading the file at all. A **persisted** field's writer and
 * reader are in different files with nothing connecting them, which is exactly the gap this covers.
 *
 * ## What it can and cannot catch, said plainly
 *
 * It matches the naming convention, so a throttle stamp called something else is out of scope — a
 * heuristic over names cannot do better than the names. And it proves a stamp is READ, not that it
 * is read *as a throttle*: `lastReflectionMs` passes and is a bookmark rather than a timer, which is
 * correct and is why this does not try to judge how a stamp is used. It is a floor that makes the
 * silent case impossible, and leaves the rest to whoever reviews the change.
 *
 * ⚠️ It would also miss a field read only by a derived getter inside `AppSettings` itself, since the
 * declaring file is excluded from the reader search. None of the four is, today.
 *
 * Reads the source as text, in the shape of [CredentialCoverageTest], so it cannot fail for an
 * environmental reason and leave someone unable to tell a real break from a broken harness.
 */
class ThrottleStampCoverageTest {

    private val appSrc = File("src/main/java/dev/mascwa/pulse")
    private val settingsDir = File(appSrc, "data/settings")

    /**
     * The files that only move the blob around, so a mention in one is not a reader.
     *
     * `SettingsBackup` names every field mechanically for export and import, and `SettingsRepository`
     * encodes and decodes it; neither is a consumer. Counting them would make every field look alive
     * and the gate would pass on exactly the defect it exists to catch.
     */
    private val plumbing = setOf("AppSettings.kt", "SettingsRepository.kt", "SettingsBackup.kt")

    /**
     * Stamps that may legitimately have no reader.
     *
     * ⚠️ Empty on purpose, and that is the statement: a timestamp nobody reads has no purpose, so
     * there is no honest reason to be on this list. An entry here wants a written reason, because an
     * allowlist is how a gate like this stops working.
     */
    private val exempt = emptyMap<String, String>()

    /** Block comments first, so a `//` inside one cannot swallow the rest of the file. */
    private fun stripComments(src: String): String = src
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    private fun kotlinFilesUnder(dir: File): List<File> {
        assertTrue("missing: ${dir.absolutePath}", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Every `val last…Ms: Long` declared anywhere in the settings package. */
    private fun stamps(): List<String> {
        val src = kotlinFilesUnder(settingsDir).joinToString("\n") { stripComments(it.readText()) }
        return Regex("""\bval\s+(last[A-Za-z0-9]*Ms)\s*:\s*Long\b""")
            .findAll(src).map { it.groupValues[1] }.distinct().toList()
    }

    @Test
    fun `the search itself works, so a green result is not vacuous`() {
        val found = stamps()
        // ⚠️ Without this, a regex that matched nothing would report every stamp covered and pass
        // for ever. Anchored on one that certainly exists rather than on a count, so adding a stamp
        // does not break the self-check.
        assertTrue("found no last…Ms stamps at all — the declaration search is broken", found.isNotEmpty())
        assertTrue(
            "the declaration search no longer finds lastCuriosityMs, so it has stopped working: $found",
            "lastCuriosityMs" in found,
        )
    }

    @Test
    fun `every settings stamp is read by something outside the settings plumbing`() {
        val readers = kotlinFilesUnder(appSrc)
            .filter { it.name !in plumbing }
            .joinToString("\n") { stripComments(it.readText()) }

        // A read is a dotted access (`settings.lastCuriosityMs`); a write inside a `copy(…)` has no
        // dot in front of it, which is exactly what tells the two apart. That distinction is the
        // whole test: all three defects had writes and no reads.
        val unread = stamps().filter { name ->
            name !in exempt && !Regex("""\.$name\b""").containsMatchIn(readers)
        }

        assertTrue(
            "these settings stamps are written and never read, so whatever they were meant to pace " +
                "is not paced: $unread. Either read the stamp (see PassThrottle.due) or delete the " +
                "field — do not leave it, and do not describe the pass as throttled.",
            unread.isEmpty(),
        )
    }
}
