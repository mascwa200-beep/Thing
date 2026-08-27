package dev.mascwa.pulse.data.diary

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.diaryDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_diary")

/** A single dated diary entry — a personal journal note, optionally tagged with a [mood]. */
@Serializable
data class DiaryEntry(
    val id: String,
    val createdMs: Long,
    val title: String,
    val body: String,
    val mood: String = "",
)

/**
 * On-device persistence for the DIARY — the user's dated personal journal (distinct from the LIBRARY /
 * notes, which are filed snippets). J.A.R.V.I.S. can journal here on the user's behalf via the `diary`
 * tool. In-memory state (authoritative) + debounced flush, mirroring NotesStore / TaskStore. Stays
 * on-device; the user can wipe it.
 */
class DiaryStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class Stored(val entries: List<DiaryEntry> = emptyList())

    private val prefsKey = stringPreferencesKey("diary_json")
    private val mutex = Mutex()
    private var entries: List<DiaryEntry>? = null
    private var flushJob: Job? = null

    private val _entriesFlow = MutableStateFlow<List<DiaryEntry>>(emptyList())
    /** Live view of the diary (newest first) for the UI. */
    val entriesFlow: StateFlow<List<DiaryEntry>> = _entriesFlow.asStateFlow()

    private suspend fun ensureLoaded(): List<DiaryEntry> = mutex.withLock {
        entries ?: run {
            val raw = context.diaryDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.entries.orEmpty()
            loaded.also { entries = it; _entriesFlow.value = it }
        }
    }

    /** Warm the in-memory state + flow from disk (called by the ViewModel on open). */
    suspend fun load(): List<DiaryEntry> = ensureLoaded()

    /** Add a dated entry (newest first). Returns it, or null if both title and body are blank. */
    suspend fun add(title: String, body: String, mood: String = ""): DiaryEntry? {
        val t = title.trim()
        val b = body.trim()
        if (t.isBlank() && b.isBlank()) return null
        ensureLoaded()
        val entry = DiaryEntry(
            id = UUID.randomUUID().toString(),
            createdMs = System.currentTimeMillis(),
            title = t.ifBlank { b.take(40) },
            body = b,
            mood = mood.trim(),
        )
        mutex.withLock {
            val after = listOf(entry) + (entries ?: emptyList())
            entries = after
            _entriesFlow.value = after
        }
        scheduleFlush()
        return entry
    }

    /**
     * Rewrite an entry in place, keeping its id and the date it was written.
     *
     * A diary is chronological, so re-dating an entry because a word in it was corrected would be
     * worse than not being able to correct it at all. Returns null if the id is unknown or the edit
     * would leave nothing behind.
     */
    suspend fun update(id: String, title: String, body: String, mood: String = ""): DiaryEntry? {
        val t = title.trim()
        val b = body.trim()
        if (t.isBlank() && b.isBlank()) return null
        ensureLoaded()
        var edited: DiaryEntry? = null
        mutex.withLock {
            val after = (entries ?: emptyList()).map { e ->
                if (e.id != id) e else {
                    e.copy(title = t.ifBlank { b.take(40) }, body = b, mood = mood.trim())
                        .also { edited = it }
                }
            }
            if (edited != null) {
                entries = after
                _entriesFlow.value = after
            }
        }
        if (edited != null) scheduleFlush()
        return edited
    }

    /** Remove an entry by id. */
    suspend fun remove(id: String) {
        ensureLoaded()
        mutex.withLock {
            val after = (entries ?: emptyList()).filterNot { it.id == id }
            entries = after
            _entriesFlow.value = after
        }
        scheduleFlush()
    }

    suspend fun clear() {
        flushJob?.cancel()
        mutex.withLock { entries = emptyList(); _entriesFlow.value = emptyList() }
        runCatching { context.diaryDataStore.edit { it.remove(prefsKey) } }
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
        val snapshot = mutex.withLock { entries?.let { Stored(it) } } ?: return
        lastWrite = runCatching {
            context.diaryDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
