package dev.mascwa.pulse.data.notes

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.notesDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_notes")

/** A single LIBRARY entry — a note or saved piece of information, filed under a [category]. */
@Serializable
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val category: String,
    val createdMs: Long,
)

/**
 * On-device persistence for the LIBRARY / NOTES — the user's notes and saved information, sorted into
 * named categories. In-memory state (authoritative) + debounced flush, mirroring
 * TaskStore / ProfileStore. Stays on-device; the user can wipe it.
 */
class NotesStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class Stored(val notes: List<Note> = emptyList())

    private val prefsKey = stringPreferencesKey("notes_json")
    private val mutex = Mutex()
    private var notes: List<Note>? = null
    private var flushJob: Job? = null

    private val _notesFlow = MutableStateFlow<List<Note>>(emptyList())
    /** Live view of the library (newest first) for the UI. */
    val notesFlow: StateFlow<List<Note>> = _notesFlow.asStateFlow()

    private suspend fun ensureLoaded(): List<Note> = mutex.withLock {
        notes ?: withContext(Dispatchers.IO) {
            val raw = context.notesDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.notes.orEmpty()
            loaded.also { notes = it; _notesFlow.value = it }
        }
    }

    /** Warm the in-memory state + flow from disk (called by the ViewModel on open). */
    suspend fun load(): List<Note> = ensureLoaded()

    /** Add an entry (newest first). Returns it, or null if both title and body are blank. */
    suspend fun add(title: String, body: String, category: String): Note? {
        val t = title.trim()
        val b = body.trim()
        if (t.isBlank() && b.isBlank()) return null
        ensureLoaded()
        val note = Note(
            id = UUID.randomUUID().toString(),
            title = t.ifBlank { b.take(40) },
            body = b,
            category = category.trim().ifBlank { "GENERAL" }.uppercase(),
            createdMs = System.currentTimeMillis(),
        )
        mutex.withLock {
            val after = listOf(note) + (notes ?: emptyList())
            notes = after
            _notesFlow.value = after
        }
        scheduleFlush()
        return note
    }

    /**
     * Rewrite an entry in place, keeping its id and the date it was written.
     *
     * Editing is not a delete-and-re-add: that would move the note to the top of its category and
     * restamp it as written today, which is wrong — fixing a typo does not make the note new. It also
     * keeps the id stable, which matters now that search results and the assistant refer to notes by
     * it. Returns null if the id is unknown or the edit would leave nothing behind.
     */
    suspend fun update(id: String, title: String, body: String, category: String): Note? {
        val t = title.trim()
        val b = body.trim()
        if (t.isBlank() && b.isBlank()) return null
        ensureLoaded()
        var edited: Note? = null
        mutex.withLock {
            val after = (notes ?: emptyList()).map { n ->
                if (n.id != id) n else {
                    n.copy(
                        title = t.ifBlank { b.take(40) },
                        body = b,
                        category = category.trim().ifBlank { n.category }.uppercase(),
                    ).also { edited = it }
                }
            }
            if (edited != null) {
                notes = after
                _notesFlow.value = after
            }
        }
        if (edited != null) scheduleFlush()
        return edited
    }

    /** Remove an entry by id. */
    suspend fun remove(id: String) {
        ensureLoaded()
        mutex.withLock {
            val after = (notes ?: emptyList()).filterNot { it.id == id }
            notes = after
            _notesFlow.value = after
        }
        scheduleFlush()
    }

    suspend fun clear() {
        flushJob?.cancel()
        mutex.withLock { notes = emptyList(); _notesFlow.value = emptyList() }
        runCatching { context.notesDataStore.edit { it.remove(prefsKey) } }
    }

    /** Force buffered changes to disk now (e.g. on app stop). */
    /**
     * The outcome of the most recent write, so an explicit [flushNow] can report a failure it would
     * otherwise swallow.
     *
     * ⚠️ **Both callers of [flushNow] already wrap it in a reporter that could never fire.** Every
     * store of this shape catches its own DataStore edit and discards the `Result`, so the "the
     * store could not be written to disk; anything recorded since is lost" report in `MainActivity`
     * and `NutritionContainer` was structurally unreachable — a claim in a KDoc that nothing could
     * make true. The debounced background flush still swallows, deliberately: an exception thrown
     * there escapes into a launched coroutine and takes the process with it.
     */
    @Volatile
    private var lastWrite: Result<*>? = null

    suspend fun flushNow() {
        flushJob?.cancel()
        // ⚠️ Cleared first: [flush] returns early when nothing is owed, and a stale failure
        // from an earlier write would then be reported against a write no longer outstanding.
        lastWrite = null
        flush()
        lastWrite?.getOrThrow()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { notes?.let { Stored(it) } } ?: return
        lastWrite = withContext(Dispatchers.IO) {
            runCatching {
                context.notesDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
            }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
