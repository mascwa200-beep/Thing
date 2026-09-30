package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.testing.SourceGate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The LCARS keyboard sees every word typed on the phone, passwords included. It keeps none of them and
 * sends none of them anywhere — and this gate is what holds the package to that, rather than a promise
 * in a comment.
 *
 * It fails the build if anything in `feature/keyboard/` reaches for storage, logging, the activity
 * log, crash reports or the network. A keyboard that needs one of those one day has to delete an entry
 * here, in a diff somebody reads, which is the point.
 *
 * ⚠️ **Comments are stripped before searching**, or the paragraph in `LcarsKeyboard` that explains why
 * nothing is logged would itself fail the gate — and, worse, a gate satisfied by prose could be
 * satisfied the other way round too. See [SourceGate.stripComments].
 */
class KeyboardRecordsNothingTest {

    private val keyboardSrc = File("src/main/java/dev/mascwa/pulse/feature/keyboard")

    /** Each forbidden reach, with what it would mean. Matched as plain substrings of real code. */
    private val forbidden = mapOf(
        "UsageRepository" to "the activity log",
        "usageRepository" to "the activity log",
        "logChat" to "the activity log",
        "Breadcrumbs" to "crash-report breadcrumbs",
        "CrashReporter" to "crash reports",
        "Log." to "logcat",
        "println(" to "stdout",
        "SharedPreferences" to "stored preferences",
        "getSharedPreferences" to "stored preferences",
        "DataStore" to "stored preferences",
        "dataStore" to "stored preferences",
        "openFileOutput" to "a file",
        "FileOutputStream" to "a file",
        "File(" to "a file",
        "writeText(" to "a file",
        "HttpClient" to "the network",
        "OkHttp" to "the network",
        "URL(" to "the network",
        "Socket(" to "the network",
        "ClipboardManager" to "the clipboard",
    )

    private fun code(): Map<String, String> =
        SourceGate.kotlinFilesUnder(keyboardSrc).associate { it.name to SourceGate.stripComments(it.readText()) }

    @Test
    fun `the keyboard reaches for no storage, no log and no network`() {
        val found = code().flatMap { (file, src) ->
            forbidden.filterKeys { it in src }.map { (token, what) -> "$file uses $token ($what)" }
        }
        assertTrue("the keyboard must record and send nothing:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /**
     * A gate that read nothing would pass. This proves it reads the real keyboard: the service and the
     * model are both there, and the one call that types — `commitText` — is visible to it.
     */
    @Test
    fun `the gate is reading the real keyboard`() {
        val files = code()
        assertTrue(files.keys.toString(), "LcarsKeyboard.kt" in files && "KeyboardModel.kt" in files)
        assertTrue("commitText" in files.getValue("LcarsKeyboard.kt"))
    }
}
