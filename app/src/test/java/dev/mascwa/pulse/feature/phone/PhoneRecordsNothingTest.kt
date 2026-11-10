package dev.mascwa.pulse.feature.phone

import dev.mascwa.pulse.testing.SourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The LCARS phone reads who you call, who calls you and everybody in your phone book. It keeps none of
 * it and sends none of it anywhere — and this gate is what holds the phone packages to that, rather
 * than a promise in a comment.
 *
 * It fails the build if anything in `feature/phone/` or `data/phone/` reaches for logging, the
 * activity log, crash reports, stored preferences, a file, the network or the clipboard. A phone that
 * needs one of those one day has to change an entry here, in a diff somebody reads, which is the point.
 *
 * ⚠️ **Whole words, not substrings.** "Log." is logcat, and it is also the last four characters of
 * `CallLog.Calls`, which the call-log reader cannot avoid saying. So every pattern is a regular
 * expression with word boundaries, and a test below proves the logcat one tells the two apart.
 *
 * ⚠️ **Comments are stripped before searching** ([SourceGate.stripComments]), or the paragraphs that
 * explain why nothing is logged would fail the gate — and a gate satisfied by prose could as easily be
 * satisfied the other way round.
 */
class PhoneRecordsNothingTest {

    private val roots = listOf(
        File("src/main/java/dev/mascwa/pulse/feature/phone"),
        File("src/main/java/dev/mascwa/pulse/data/phone"),
    )

    /** Each forbidden reach, with what it would mean. */
    private val forbidden: Map<Regex, String> = mapOf(
        Regex("""\b[Uu]sageRepository\b""") to "the activity log",
        Regex("""\blogChat\b""") to "the activity log",
        Regex("""\bBreadcrumbs\b""") to "crash-report breadcrumbs",
        Regex("""\bCrashReporter\b""") to "crash reports",
        Regex("""\bDebugUploader\b""") to "debug reports",
        Regex("""\bAuditLedger""") to "the audit ledger",
        Regex("""\b[Mm]emoryStream""") to "the assistant's memory",
        Regex("""\bLog\.""") to "logcat",
        Regex("""\bprintln\(""") to "stdout",
        Regex("""\bSharedPreferences\b|\bgetSharedPreferences\b""") to "stored preferences",
        Regex("""\bDataStore\b|\bdataStore\b""") to "stored preferences",
        Regex("""\bopenFileOutput\b|\bFileOutputStream\b|\bFile\(|\bwriteText\(|\bwriteBytes\(""") to "a file",
        Regex("""\bHttpClient\b|\bOkHttp|\bURL\(|\bSocket\(""") to "the network",
        Regex("""\bClipboardManager\b""") to "the clipboard",
    )

    /**
     * The one sanctioned store. [PhoneStore] keeps the crash-loop guard's counters, the package name of
     * the dialler that held the role before LCARS, and when the guard stood the call screen down —
     * operational state, never a call, a number or a name from the phone book. So it alone is allowed
     * stored preferences, and it is allowed nothing else; every other file is allowed neither. The
     * exception lives here, in a diff somebody reads, which is exactly what the class KDoc asks for.
     */
    private val prefsAllowed = setOf("PhoneStore.kt")

    /** True when [file] is permitted this reach — only [prefsAllowed] files, and only stored preferences. */
    private fun allowed(file: String, what: String): Boolean = file in prefsAllowed && what == "stored preferences"

    private fun code(): Map<String, String> =
        roots.flatMap { SourceGate.kotlinFilesUnder(it) }
            .associate { it.name to SourceGate.stripComments(it.readText()) }

    @Test
    fun `the phone reaches for no log, no storage and no network`() {
        val found = code().flatMap { (file, src) ->
            forbidden.filterKeys { it.containsMatchIn(src) && !allowed(file, forbidden.getValue(it)) }
                .map { (pattern, what) -> "$file matches $pattern ($what)" }
        }
        assertTrue("the phone must record and send nothing:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun `the phone takes nothing from the app's container`() {
        // It needs nothing the app holds: it reads two of Android's providers and hands calls to
        // Telecom. Anything it takes from the container one day is a reach somebody has to justify here.
        val reach = Regex("""container\??\.(\w+)""")
        val taken = code().flatMap { (file, src) -> reach.findAll(src).map { "$file takes ${it.groupValues[1]}" } }
        assertEquals(emptyList<String>(), taken)
    }

    /** The logcat pattern tells logcat from the call log — without this, the first test proves nothing. */
    @Test
    fun `the logcat pattern is not fooled by the call log`() {
        val logcat = forbidden.keys.single { forbidden.getValue(it) == "logcat" }
        assertFalse(logcat.containsMatchIn("CallLog.Calls.CONTENT_URI"))
        assertTrue(logcat.containsMatchIn("android.util.Log.d(\"phone\", number)"))
        assertTrue(logcat.containsMatchIn("Log.w(TAG, it)"))
    }

    /**
     * A gate that read nothing would pass. This proves it reads the real phone: the screen, the call
     * helper and both readers are there, and the calls that matter are visible to it.
     */
    @Test
    fun `the gate is reading the real phone`() {
        val files = code()
        for (name in listOf("PhoneActivity.kt", "PhoneCalls.kt", "ContactsRepository.kt", "CallLogRepository.kt")) {
            assertTrue("$name is not being read: ${files.keys}", name in files)
        }
        assertTrue("placeCall" in files.getValue("PhoneCalls.kt"))
        assertTrue("CallLog.Calls" in files.getValue("CallLogRepository.kt"))
    }

    /**
     * The prefs allowlist is a door, so this proves only stored preferences walk through it: a logcat
     * reach planted in the allowlisted file is still caught, and the exemption is granted to no other
     * file. Without this the exception could quietly widen into a blanket pass that let the one file
     * keep call data and log it.
     */
    @Test
    fun `the prefs allowlist is scoped to preferences, nothing else`() {
        val planted = """fun f(number: String) { android.util.Log.d("phone", number) }"""
        val caught = forbidden.filterKeys { it.containsMatchIn(planted) && !allowed("PhoneStore.kt", forbidden.getValue(it)) }
        assertFalse("a logcat reach in the allowlisted file must still fail the gate", caught.isEmpty())
        assertTrue("PhoneStore may keep its prefs", allowed("PhoneStore.kt", "stored preferences"))
        assertFalse("but no other file may", allowed("MissedCallReceiver.kt", "stored preferences"))
    }
}
