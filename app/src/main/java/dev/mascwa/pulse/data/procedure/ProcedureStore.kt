package dev.mascwa.pulse.data.procedure

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Procedure
import dev.mascwa.pulse.core.telemetry.ProcedureLibrary
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

private val Context.procedureDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_procedures")

/**
 * On-device persistence + wiring for the Mnemosyne **procedure library** (the "skills" layer). Holds the
 * learned procedures in memory (authoritative), persists them with a debounced flush (a burst = one write),
 * and exposes the library operations to the rest of the app.
 *
 * Fed from the agent's *completed runs*: [observe] takes the request, the ordered tool sequence it used and
 * whether it succeeded, and folds it into [ProcedureLibrary]. Content-safe — a procedure stores a keyword
 * cue + tool names, never raw user content. Stays on-device; wiped by "Reset learned procedures" in Settings.
 */
class ProcedureStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredProcedure(
        val name: String,
        val cueKeywords: List<String>,
        val steps: List<String>,
        val timesApplied: Int,
        val timesSucceeded: Int,
        val createdMs: Long,
        val lastUsedMs: Long,
    )

    @Serializable
    private data class Stored(val procedures: List<StoredProcedure> = emptyList())

    private val prefsKey = stringPreferencesKey("procedures_json")
    private val mutex = Mutex()
    private var procedures: List<Procedure>? = null
    private var flushJob: Job? = null

    private val _proceduresFlow = MutableStateFlow<List<Procedure>>(emptyList())
    /** Live list for a Memory-screen surface (most-reliable first). */
    val proceduresFlow: StateFlow<List<Procedure>> = _proceduresFlow.asStateFlow()

    private fun StoredProcedure.domain() =
        Procedure(name, cueKeywords, steps, timesApplied, timesSucceeded, createdMs, lastUsedMs)

    private fun Procedure.stored() =
        StoredProcedure(name, cueKeywords, steps, timesApplied, timesSucceeded, createdMs, lastUsedMs)

    private suspend fun ensureLoaded(): List<Procedure> = mutex.withLock {
        procedures ?: withContext(Dispatchers.IO) {
            val raw = context.procedureDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.procedures.orEmpty()
                .map { it.domain() }
            procedures = loaded
            _proceduresFlow.value = loaded
            loaded
        }
    }

    /** Learn from one completed agent run: the [request], the ordered [toolSequence] it used, and whether
     *  it [success]eeded. Fire-and-forget; sub-2-step / failed-novel runs are ignored by the library. */
    fun observe(request: String, toolSequence: List<String>, success: Boolean) {
        if (request.isBlank() || toolSequence.size < ProcedureLibrary.MIN_STEPS) return
        scope.launch {
            val current = ensureLoaded()
            val updated = ProcedureLibrary.learn(current, request, toolSequence, success, System.currentTimeMillis())
            if (updated !== current) {
                mutex.withLock {
                    procedures = updated
                    _proceduresFlow.value = updated
                }
                scheduleFlush()
            }
        }
    }

    /** The best practiced, reliable procedure matching [request], if any. */
    suspend fun recall(request: String): Procedure? = ProcedureLibrary.recall(ensureLoaded(), request)

    /** A compact "procedures you've learned" block for the system prompt (empty until something is trusted). */
    suspend fun digest(): String = ProcedureLibrary.digest(ensureLoaded())

    /** A snapshot of all learned procedures. */
    suspend fun all(): List<Procedure> = ensureLoaded()

    /** Forget one procedure by name. */
    suspend fun forget(name: String) {
        ensureLoaded()
        mutex.withLock {
            val after = (procedures ?: emptyList()).filterNot { it.name == name }
            procedures = after
            _proceduresFlow.value = after
        }
        scheduleFlush()
    }

    /** Forget all learned procedures. */
    suspend fun clear() {
        flushJob?.cancel()
        mutex.withLock {
            procedures = emptyList()
            _proceduresFlow.value = emptyList()
        }
        runCatching { context.procedureDataStore.edit { it.remove(prefsKey) } }
    }

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
        val snapshot = mutex.withLock {
            procedures?.let { ps -> Stored(ps.map { it.stored() }) }
        } ?: return
        lastWrite = withContext(Dispatchers.IO) {
            runCatching {
                context.procedureDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
            }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
