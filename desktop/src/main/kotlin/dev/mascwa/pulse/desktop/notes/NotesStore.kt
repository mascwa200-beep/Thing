package dev.mascwa.pulse.desktop.notes

import dev.mascwa.pulse.desktop.store.JsonFileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import dev.mascwa.pulse.desktop.AppPaths
import java.nio.file.Path
import java.util.UUID

/** A note: a filed piece of information under a named [category]. Same shape as the phone's. */
@Serializable
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val category: String,
    val createdMs: Long,
)

/** A dated journal entry, optionally tagged with a [mood]. Same shape as the phone's. */
@Serializable
data class DiaryEntry(
    val id: String,
    val createdMs: Long,
    val title: String,
    val body: String,
    val mood: String = "",
)

@Serializable
private data class NotesBlob(val notes: List<Note> = emptyList())

@Serializable
private data class DiaryBlob(val entries: List<DiaryEntry> = emptyList())

/**
 * Notes on this machine.
 *
 * ⚠️ Almost nothing here is persistence code, and that is the point of [JsonFileStore] existing: the
 * phone's equivalent is 168 lines, nearly all of them the load / debounce / flush / never-clobber
 * dance, and this is the part that is actually about notes.
 *
 * **Deliberately NOT synchronised with the phone's notes.** The remote link's command list is a
 * closed allowlist and does not carry note contents, and widening it to move a user's own writing
 * across the network is a decision with privacy consequences that has not been asked for. These are
 * this machine's notes; the phone's are the phone's. Said here so nobody assumes otherwise.
 *
 * ⚠️ No cap, matching the phone's own reasoning: every other store in this app bounds itself, but
 * those hold derived or observed data. This holds what a person wrote, and silently evicting someone's
 * own writing to bound a file is not a trade worth making.
 */
class NotesStore(path: Path = AppPaths.dataDir.resolve("notes.json")) {

    // ⚠️ Built here rather than taken as a parameter so the blob type stays private: a private type
    // in a public constructor signature does not compile, and making the wrapper public would put an
    // implementation detail in the module's API for no reason. A test injects a path instead, which
    // is the only thing a test actually needs to vary.
    private val store = JsonFileStore("notes.json", NotesBlob.serializer(), { NotesBlob() }, path)

    /**
     * Newest first, which is the order every surface wants.
     *
     * ⚠️ A plain `Flow`, not a `StateFlow`. Turning it into one would need either a scope to
     * `stateIn` on or a hand-written `StateFlow` implementation — and I wrote the second of those
     * before deleting it: implementing that interface by hand means getting `collect`'s
     * never-returns contract right for no gain at all. Sorting a few dozen notes when the list
     * changes costs nothing, and `collectAsState` at the call site supplies the initial value.
     */
    val notesFlow: Flow<List<Note>> = store.state.map { b -> b.notes.sortedByDescending { it.createdMs } }

    /** True when a file was there and could not be read — so a screen can say so rather than
     *  showing a convincing blank. See [JsonFileStore.loadFailed]. */
    val loadFailed: Boolean get() = store.loadFailed

    suspend fun load(): List<Note> = store.current().notes.sortedByDescending { it.createdMs }

    suspend fun add(title: String, body: String, category: String): Note? {
        // A note with neither a title nor a body is not a note. Returning null rather than storing an
        // empty row means the caller can leave the composer open instead of appearing to have saved.
        if (title.isBlank() && body.isBlank()) return null
        val note = Note(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            body = body.trim(),
            category = category.trim().ifBlank { "General" },
            createdMs = System.currentTimeMillis(),
        )
        store.update { it.copy(notes = it.notes + note) }
        return note
    }

    /**
     * Correct one in place.
     *
     * ⚠️ The id and the creation time are preserved, deliberately. Search results refer to a note by
     * its id, and correcting a word does not make a note new — re-dating it over a typo would be worse
     * than not being able to correct it at all.
     */
    suspend fun update(id: String, title: String, body: String, category: String): Note? {
        if (title.isBlank() && body.isBlank()) return null
        var updated: Note? = null
        store.update { blob ->
            blob.copy(
                notes = blob.notes.map { n ->
                    if (n.id != id) n
                    else n.copy(
                        title = title.trim(),
                        body = body.trim(),
                        category = category.trim().ifBlank { "General" },
                    ).also { updated = it }
                },
            )
        }
        return updated
    }

    suspend fun remove(id: String) = store.update { it.copy(notes = it.notes.filterNot { n -> n.id == id }) }

    suspend fun clear() = store.clear()

    suspend fun flushNow() = store.flushNow()
}

/**
 * The journal on this machine.
 *
 * Separate from [NotesStore] for the same reason the phone keeps them apart: a note is a filed
 * snippet and a diary entry is a dated record of a day, and mixing them makes both harder to find.
 */
class DiaryStore(path: Path = AppPaths.dataDir.resolve("diary.json")) {

    private val store = JsonFileStore("diary.json", DiaryBlob.serializer(), { DiaryBlob() }, path)

    val entriesFlow: Flow<List<DiaryEntry>> = store.state.map { b -> b.entries.sortedByDescending { it.createdMs } }

    val loadFailed: Boolean get() = store.loadFailed

    suspend fun load(): List<DiaryEntry> = store.current().entries.sortedByDescending { it.createdMs }

    suspend fun add(title: String, body: String, mood: String = ""): DiaryEntry? {
        if (title.isBlank() && body.isBlank()) return null
        val e = DiaryEntry(
            id = UUID.randomUUID().toString(),
            createdMs = System.currentTimeMillis(),
            title = title.trim(),
            body = body.trim(),
            mood = mood.trim(),
        )
        store.update { it.copy(entries = it.entries + e) }
        return e
    }

    /** ⚠️ Keeps `createdMs`. A diary entry is dated by when it happened, not by when it was fixed. */
    suspend fun update(id: String, title: String, body: String, mood: String = ""): DiaryEntry? {
        if (title.isBlank() && body.isBlank()) return null
        var updated: DiaryEntry? = null
        store.update { blob ->
            blob.copy(
                entries = blob.entries.map { e ->
                    if (e.id != id) e
                    else e.copy(title = title.trim(), body = body.trim(), mood = mood.trim())
                        .also { updated = it }
                },
            )
        }
        return updated
    }

    suspend fun remove(id: String) = store.update { it.copy(entries = it.entries.filterNot { e -> e.id == id }) }

    suspend fun clear() = store.clear()

    suspend fun flushNow() = store.flushNow()
}
