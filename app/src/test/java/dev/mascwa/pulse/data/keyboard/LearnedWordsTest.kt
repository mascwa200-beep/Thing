package dev.mascwa.pulse.data.keyboard

import dev.mascwa.pulse.feature.keyboard.LearnedCounts.Entry
import dev.mascwa.pulse.testing.SourceGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The one place the keyboard keeps anything typed, held to what it promises.
 *
 * The Keystore cannot run on a build machine, so a stand-in cipher does its job here: it wraps the text
 * so that a word written in the clear would show. What `AppContainer` actually hands the store is
 * checked at the bottom, against the source.
 */
class LearnedWordsTest {

    private val dir: File = Files.createTempDirectory("learned").toFile()
    private val file = File(dir, LearnedWords.FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Reversed and marked: sealed text that still contained a learned word would be a plain copy. */
    private val seal: (String) -> String? = { "SEALED:" + it.reversed() }
    private val unseal: (String) -> String? = { if (it.startsWith("SEALED:")) it.removePrefix("SEALED:").reversed() else null }

    private fun store(seal: (String) -> String? = this.seal, saveAfterMs: Long = 60_000) =
        LearnedWords(file, scope, seal, unseal, now = { 1_000L }, saveAfterMs = saveAfterMs)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `what is kept is only ever the sealed text`() = runBlocking {
        val s = store().also { it.load() }
        s.saw("Zorkmid")
        s.saw("zorkmid")
        s.flushNow()
        val onDisk = file.readText()
        assertTrue(onDisk.startsWith("SEALED:"))
        assertFalse("a learned word is on the disk in the clear", "orkmid" in onDisk.lowercase())
    }

    @Test
    fun `when the cipher cannot seal, nothing is written — and nothing is written over`() = runBlocking {
        val refusing = store(seal = { null }).also { it.load() }
        refusing.saw("zorkmid")
        refusing.flushNow()
        assertFalse("written unsealed", file.exists())

        store().also { it.load(); it.accept("grue"); it.flushNow() }
        val before = file.readBytes()
        store(seal = { null }).also { it.load(); it.accept("zorkmid"); it.flushNow() }
        assertArrayEquals("a failed seal replaced what was kept", before, file.readBytes())
    }

    @Test
    fun `what was learned is there the next time`() = runBlocking {
        store().also { it.load(); it.saw("zorkmid"); it.saw("zorkmid"); it.flushNow() }
        val again = store().also { it.load() }
        assertEquals(listOf("zorkmid"), again.known().toList())
    }

    @Test
    fun `a kept file that cannot be opened is left alone until it is forgotten`() = runBlocking {
        file.writeText("enc1:not-ours")
        val before = file.readBytes()
        val s = store().also { it.load() }
        assertTrue(s.summary().unreadable)
        s.accept("zorkmid")
        s.flushNow()
        assertArrayEquals("an unreadable file was written over", before, file.readBytes())

        s.forget()
        assertFalse(file.exists())
        assertFalse(s.summary().unreadable)
        s.accept("zorkmid")
        s.flushNow()
        assertTrue("learning did not resume after forgetting", file.exists())
    }

    @Test
    fun `nothing is written before the file has been read`() = runBlocking {
        store().also { it.load(); it.accept("grue"); it.flushNow() }
        val before = file.readBytes()
        val early = store(saveAfterMs = 20)
        early.accept("zorkmid")
        early.flushNow()
        assertArrayEquals("a save before loading replaced what was kept", before, file.readBytes())
        early.load()
        assertEquals(setOf("grue", "zorkmid"), early.known().toSet())
        // And once it has been read, the word held back is saved on its own, beside what was there.
        val deadline = System.currentTimeMillis() + 5_000
        while (file.readBytes().contentEquals(before) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(setOf("grue", "zorkmid"), store().also { it.load() }.known().toSet())
    }

    @Test
    fun `forgetting takes the words off the phone and out of memory`() = runBlocking {
        val s = store().also { it.load(); it.accept("zorkmid"); it.flushNow() }
        assertTrue(file.exists())
        s.forget()
        assertFalse(file.exists())
        assertTrue(s.known().isEmpty())
        assertEquals(LearnedWords.Summary(0, 0, false), s.summary())
    }

    @Test
    fun `a change is saved on its own, after the pause`() = runBlocking {
        val s = store(saveAfterMs = 20).also { it.load() }
        s.accept("zorkmid")
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("the pending save never ran", file.exists())
    }

    @Test
    fun `words learned before the file was read are added to it, not lost`() {
        val merged = LearnedWords.merge(
            mapOf("zorkmid" to Entry("zorkmid", 3, 5L)),
            mapOf("zorkmid" to Entry("Zorkmid", 1, 9L), "grue" to Entry("grue", 1, 9L)),
        )
        assertEquals(Entry("Zorkmid", 4, 9L), merged["zorkmid"])
        assertEquals(Entry("grue", 1, 9L), merged["grue"])
    }

    // ── what the app actually hands the store ───────────────────────────────────────────────────

    private val main = File("src/main/java/dev/mascwa/pulse")

    @Test
    fun `the app seals with the Keystore, and keeps the file out of backups`() {
        val container = SourceGate.stripComments(File(main, "di/AppContainer.kt").readText())
        val at = container.indexOf("LearnedWords(")
        assertTrue("AppContainer does not build the learned-word store", at >= 0)
        // The constructor call itself, to its closing parenthesis — not the rest of the file.
        var depth = 0
        var end = at + "LearnedWords".length
        do {
            when (container[end]) { '(' -> depth++; ')' -> depth-- }
            end++
        } while (depth > 0 && end < container.length)
        val call = container.substring(at, end)
        for (needed in listOf("SecretCrypto::encrypt", "SecretCrypto::decrypt", "noBackupFilesDir")) {
            assertTrue("the store is not built with $needed:\n$call", needed in call)
        }
    }

    @Test
    fun `nothing else builds the store, so nothing can hand it a weaker cipher`() {
        val builders = SourceGate.kotlinFilesUnder(main)
            .filter { "LearnedWords(" in SourceGate.stripComments(it.readText()) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("AppContainer.kt", "LearnedWords.kt"), builders)
    }

    @Test
    fun `the store reaches for no log, no report and no network`() {
        val src = SourceGate.stripComments(File(main, "data/keyboard/LearnedWords.kt").readText())
        val forbidden = listOf(
            "Log.", "println(", "UsageRepository", "usageRepository", "Breadcrumbs", "CrashReporter",
            "HttpClient", "OkHttp", "URL(", "Socket(", "DebugUploader", "AuditLedger",
        )
        val found = forbidden.filter { it in src }
        assertTrue("the learned-word store reaches for $found", found.isEmpty())
        assertTrue("the gate is not reading the store", "seal(" in src)
    }
}
