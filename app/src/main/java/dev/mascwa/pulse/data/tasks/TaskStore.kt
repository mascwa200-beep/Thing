package dev.mascwa.pulse.data.tasks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Task
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.core.telemetry.TaskStatus
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

private val Context.taskDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_tasks")

/**
 * On-device persistence for the [TaskBoard] — the user's ongoing tasks and goals. In-memory state
 * (authoritative) + debounced flush (mirrors ProfileStore / CerebellumStore / UsageRepository). Stays
 * on-device; the user can wipe it. The pending-task digest is injected into J.A.R.V.I.S.'s system
 * prompt every turn so it stays oriented and follows up proactively.
 */
class TaskStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredTask(
        val title: String,
        val status: String,
        val note: String,
        val createdMs: Long,
        val updatedMs: Long,
    )

    @Serializable
    private data class Stored(val tasks: List<StoredTask> = emptyList())

    private val prefsKey = stringPreferencesKey("tasks_json")
    private val mutex = Mutex()
    private var tasks: List<Task>? = null
    private var flushJob: Job? = null

    private val _tasksFlow = MutableStateFlow<List<Task>>(emptyList())
    /** Live view of the board (so a UI surface can show + curate it later). */
    val tasksFlow: StateFlow<List<Task>> = _tasksFlow.asStateFlow()

    private fun Task.stored() = StoredTask(title, status.name, note, createdMs, updatedMs)

    private fun StoredTask.domain() = Task(
        title = title,
        status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.OPEN),
        note = note,
        createdMs = createdMs,
        updatedMs = updatedMs,
    )

    private suspend fun ensureLoaded(): List<Task> = mutex.withLock {
        tasks ?: run {
            val raw = context.taskDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.tasks.orEmpty()
                .map { it.domain() }
            loaded.also { tasks = it; _tasksFlow.value = it }
        }
    }

    /** Add (or reaffirm/reopen) a task. Returns the resulting task, or null when [title] is blank. */
    suspend fun add(title: String, note: String = ""): Task? {
        if (title.isBlank()) return null
        ensureLoaded()
        val key = TaskBoard.normalize(title.trim().take(TaskBoard.MAX_TITLE_LEN))
        val result = mutex.withLock {
            val after = TaskBoard.add(tasks ?: emptyList(), title, System.currentTimeMillis(), note)
            tasks = after
            _tasksFlow.value = after
            after.firstOrNull { TaskBoard.normalize(it.title) == key }
        }
        scheduleFlush()
        return result
    }

    /** Auto-capture: track [text] only if it clearly reads as the user assigning themselves a task. */
    fun detectAndCapture(text: String) {
        val title = TaskBoard.detect(text) ?: return
        scope.launch { add(title) }
    }

    /** Change the status of the best title match. Returns the updated task, or null if none matched. */
    suspend fun setStatus(query: String, status: TaskStatus, note: String = ""): Task? {
        if (query.isBlank()) return null
        ensureLoaded()
        val matched = mutex.withLock {
            val outcome = TaskBoard.setStatus(tasks ?: emptyList(), query, status, System.currentTimeMillis(), note)
            if (outcome.matched != null) {
                tasks = outcome.tasks
                _tasksFlow.value = outcome.tasks
            }
            outcome.matched
        }
        if (matched != null) scheduleFlush()
        return matched
    }

    /** Drop the best title match. Returns the removed task, or null if none matched. */
    suspend fun remove(query: String): Task? {
        if (query.isBlank()) return null
        ensureLoaded()
        val matched = mutex.withLock {
            val outcome = TaskBoard.remove(tasks ?: emptyList(), query)
            if (outcome.matched != null) {
                tasks = outcome.tasks
                _tasksFlow.value = outcome.tasks
            }
            outcome.matched
        }
        if (matched != null) scheduleFlush()
        return matched
    }

    suspend fun all(): List<Task> = ensureLoaded()

    /** Compact digest of pending tasks for the system prompt (empty when nothing is pending). */
    suspend fun digest(): String = TaskBoard.digest(ensureLoaded())

    /** Full human-readable list for the `task` tool. */
    suspend fun summary(): String = TaskBoard.summary(ensureLoaded())

    suspend fun clear() {
        flushJob?.cancel() // so a buffered write can't resurrect cleared tasks after this returns
        mutex.withLock { tasks = emptyList(); _tasksFlow.value = emptyList() }
        runCatching { context.taskDataStore.edit { it.remove(prefsKey) } }
    }

    /** Force buffered changes to disk now (e.g. on app stop). */
    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    /** Trailing throttle (see UsageRepository): one flush per window, never deferred indefinitely. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { tasks?.let { list -> Stored(list.map { it.stored() }) } } ?: return
        runCatching {
            context.taskDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
