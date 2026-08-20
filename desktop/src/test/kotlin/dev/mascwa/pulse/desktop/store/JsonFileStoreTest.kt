package dev.mascwa.pulse.desktop.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The rules that can lose someone's writing, exercised against a real file.
 *
 * ⚠️ Every one of these runs the SHIPPED store over a temporary directory rather than a fake. The
 * behaviour under test is entirely about what ends up on disk and when, so a fake filesystem would be
 * testing the fake.
 */
class JsonFileStoreTest {

    @Serializable
    data class Note(val title: String = "", val body: String = "")

    private fun tmpDir(): Path = Files.createTempDirectory("jsonstore")

    private fun store(dir: Path, name: String = "notes.json") = JsonFileStore(
        name = name,
        serializer = Note.serializer(),
        default = { Note() },
        path = dir.resolve(name),
    )

    @Test
    fun anAbsentFileReadsAsTheDefaultAndIsNotAFailure() = runBlocking {
        val s = store(tmpDir())
        assertEquals(Note(), s.current())
        assertFalse("a first run is not a load failure", s.loadFailed)
    }

    @Test
    fun whatWasWrittenComesBackAfterAFreshStore() = runBlocking {
        val dir = tmpDir()
        store(dir).apply {
            update { it.copy(title = "Bowline", body = "the rabbit comes out of the hole") }
            flushNow()
        }
        // A SEPARATE instance: nothing in memory carries over, so this reads the disk.
        assertEquals("Bowline", store(dir).current().title)
    }

    @Test
    fun theWriteIsAtomicSoNoTemporaryFileIsLeftBehind() = runBlocking {
        val dir = tmpDir()
        store(dir).apply {
            update { it.copy(title = "x") }
            flushNow()
        }
        val leftovers = Files.list(dir).use { it.toList() }.map { it.fileName.toString() }
        assertEquals(listOf("notes.json"), leftovers)
    }

    /**
     * ⚠️ THE ONE THAT MATTERS. A file that exists but does not parse is someone's data written by a
     * build that is not this one. Reading it as "empty" and then writing an empty blob over it turns a
     * recoverable problem into a permanent loss, along a path that looks like ordinary operation.
     */
    @Test
    fun anUnreadableFileIsNeitherReportedAsEmptyNorOverwritten() = runBlocking {
        val dir = tmpDir()
        val file = dir.resolve("notes.json")
        Files.writeString(file, "{this is not json at all")

        val s = store(dir)
        assertEquals("in memory it starts from the default", Note(), s.current())
        assertTrue("and it says so, rather than looking like a first run", s.loadFailed)

        // Now do the thing that would destroy it: change something, and force the write.
        s.update { it.copy(title = "clobbered") }
        s.flushNow()

        assertEquals(
            "the unreadable file is left exactly as it was",
            "{this is not json at all",
            Files.readString(file),
        )
    }

    @Test
    fun anUnknownKeyFromANewerBuildDoesNotMakeTheFileUnreadable() = runBlocking {
        val dir = tmpDir()
        Files.writeString(dir.resolve("notes.json"), """{"title":"kept","body":"","pinned":true}""")
        val s = store(dir)
        assertEquals("kept", s.current().title)
        assertFalse(s.loadFailed)
    }

    @Test
    fun clearingEmptiesTheFileRatherThanLeavingTheOldContents() = runBlocking {
        val dir = tmpDir()
        val s = store(dir)
        s.update { it.copy(title = "gone soon") }
        s.flushNow()
        s.clear()
        assertEquals(Note(), store(dir).current())
    }

    @Test
    fun clearingRecoversAFileThatCouldNotBeRead() = runBlocking {
        val dir = tmpDir()
        Files.writeString(dir.resolve("notes.json"), "]]not json[[")
        val s = store(dir)
        s.current()
        assertTrue(s.loadFailed)
        // Clearing is the deliberate "I accept losing it" action, so it must actually work — otherwise
        // an unreadable file would be permanently unrecoverable from inside the app.
        s.clear()
        assertFalse(s.loadFailed)
        assertEquals(Note(), store(dir).current())
        assertFalse(store(dir).loadFailed)
    }

    @Test
    fun twoStoresInTheSameDirectoryDoNotShareAFile() = runBlocking {
        val dir = tmpDir()
        val notes = store(dir, "notes.json")
        val diary = store(dir, "diary.json")
        notes.update { it.copy(title = "note") }
        diary.update { it.copy(title = "diary") }
        notes.flushNow(); diary.flushNow()
        assertEquals("note", store(dir, "notes.json").current().title)
        assertEquals("diary", store(dir, "diary.json").current().title)
    }

    @Test
    fun aNonObjectBlobWorksToo() = runBlocking {
        // The store is generic over anything serializable, not just data classes — a bare list is the
        // shape most of the phone's stores actually hold.
        val dir = tmpDir()
        val s = JsonFileStore(
            name = "ids.json",
            serializer = kotlinx.serialization.builtins.ListSerializer(String.serializer()),
            default = { emptyList() },
            path = dir.resolve("ids.json"),
        )
        s.update { it + "a" + "b" }
        s.flushNow()
        assertNotNull(Files.readString(dir.resolve("ids.json")))
        assertEquals(listOf("a", "b"), s.current())
    }
}
