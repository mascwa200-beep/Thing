package dev.mascwa.pulse.feature.jarvis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.ProfileEntry
import dev.mascwa.pulse.core.telemetry.Task
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.db.AgentNoteEntity
import dev.mascwa.pulse.data.profile.ProfileStore
import dev.mascwa.pulse.data.tasks.TaskStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Memory screen — the user's view of (and control over) everything J.A.R.V.I.S. has learned.
 * Every durable note is editable and deletable here; the structured profile (preferences / interests /
 * projects) is also visible and curatable, code-enforced like the rest of the app.
 */
class JarvisMemoryViewModel(
    private val memory: JarvisMemory,
    private val profileStore: ProfileStore,
    private val taskStore: TaskStore,
) : ViewModel() {

    val notes: StateFlow<List<AgentNoteEntity>> =
        memory.notesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live structured profile (preferences / interests / projects). */
    val profile: StateFlow<List<ProfileEntry>> = profileStore.entriesFlow

    /** Live task board, ordered pending-first then completed, for the user to view and curate. */
    val tasks: StateFlow<List<Task>> = taskStore.tasksFlow
        .map { TaskBoard.pending(it) + TaskBoard.completed(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Trigger a load so the profile + task flows populate when the screen opens.
        viewModelScope.launch { runCatching { profileStore.all() } }
        viewModelScope.launch { runCatching { taskStore.all() } }
    }

    fun edit(id: Long, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { runCatching { memory.updateNote(id, text) } }
    }

    fun delete(id: Long) {
        viewModelScope.launch { runCatching { memory.deleteNote(id) } }
    }

    fun clearAll() {
        viewModelScope.launch { runCatching { memory.clearNotes() } }
    }

    /** Forget a single remembered profile fact. */
    fun forgetProfile(text: String) {
        runCatching { profileStore.forget(text) }
    }

    /** Forget the whole structured profile. */
    fun clearProfile() {
        viewModelScope.launch { runCatching { profileStore.clear() } }
    }

    /** Stop tracking a single task. */
    fun forgetTask(title: String) {
        viewModelScope.launch { runCatching { taskStore.remove(title) } }
    }

    /** Forget the whole task board. */
    fun clearTasks() {
        viewModelScope.launch { runCatching { taskStore.clear() } }
    }
}
