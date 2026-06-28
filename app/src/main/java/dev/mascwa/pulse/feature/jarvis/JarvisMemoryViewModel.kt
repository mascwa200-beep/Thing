package dev.mascwa.pulse.feature.jarvis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Memory
import dev.mascwa.pulse.core.telemetry.Procedure
import dev.mascwa.pulse.core.telemetry.ProfileEntry
import dev.mascwa.pulse.core.telemetry.Task
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.data.findings.Finding
import dev.mascwa.pulse.data.findings.FindingStore
import dev.mascwa.pulse.data.interests.Interest
import dev.mascwa.pulse.data.interests.InterestStore
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.db.AgentNoteEntity
import dev.mascwa.pulse.data.memory.MemoryStreamStore
import dev.mascwa.pulse.data.procedure.ProcedureStore
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
    private val memoryStream: MemoryStreamStore,
    private val interestStore: InterestStore,
    private val findingStore: FindingStore,
    private val procedureStore: ProcedureStore,
) : ViewModel() {

    val notes: StateFlow<List<AgentNoteEntity>> =
        memory.notesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live structured profile (preferences / interests / projects). */
    val profile: StateFlow<List<ProfileEntry>> = profileStore.entriesFlow

    /** Live task board, ordered pending-first then completed, for the user to view and curate. */
    val tasks: StateFlow<List<Task>> = taskStore.tasksFlow
        .map { TaskBoard.pending(it) + TaskBoard.completed(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live episodic memory stream (newest first) — what J.A.R.V.I.S. has observed, view + curate. */
    val episodic: StateFlow<List<Memory>> = memoryStream.memoriesFlow
        .map { list -> list.sortedByDescending { it.createdMs } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Standing interests — the owner's standing orders + J.A.R.V.I.S.'s own curiosities (owner first). */
    val interests: StateFlow<List<Interest>> = interestStore.interestsFlow

    /** J.A.R.V.I.S.'s curated findings (newest first), unseen surfaced with a badge. */
    val findings: StateFlow<List<Finding>> = findingStore.findingsFlow

    /** Learned procedures ("skills"), most-reliable first — view + curate. */
    val procedures: StateFlow<List<Procedure>> = procedureStore.proceduresFlow
        .map { list -> list.sortedWith(compareByDescending<Procedure> { it.reliability }.thenByDescending { it.lastUsedMs }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Trigger a load so the profile + task + episodic + interest + finding flows populate on open.
        viewModelScope.launch { runCatching { profileStore.all() } }
        viewModelScope.launch { runCatching { taskStore.all() } }
        viewModelScope.launch { runCatching { memoryStream.all() } }
        viewModelScope.launch { runCatching { interestStore.all() } }
        viewModelScope.launch { runCatching { findingStore.load() } }
        viewModelScope.launch { runCatching { procedureStore.all() } }
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

    /** Forget a single episodic memory. */
    fun forgetMemory(id: Long) {
        viewModelScope.launch { runCatching { memoryStream.forget(id) } }
    }

    /** Forget the whole episodic stream. */
    fun clearEpisodic() {
        viewModelScope.launch { runCatching { memoryStream.clear() } }
    }

    /** Drop a single standing interest (by topic). */
    fun forgetInterest(topic: String) {
        viewModelScope.launch { runCatching { interestStore.remove(topic) } }
    }

    /** Forget all standing interests. */
    fun clearInterests() {
        viewModelScope.launch { runCatching { interestStore.clear() } }
    }

    /** Drop a single finding. */
    fun forgetFinding(id: String) {
        viewModelScope.launch { runCatching { findingStore.remove(id) } }
    }

    /** Mark a finding as seen (clears its unseen badge). */
    fun markFindingSeen(id: String) {
        viewModelScope.launch { runCatching { findingStore.markSeen(id) } }
    }

    /** Forget all findings. */
    fun clearFindings() {
        viewModelScope.launch { runCatching { findingStore.clear() } }
    }

    /** Forget a single learned procedure (by name). */
    fun forgetProcedure(name: String) {
        viewModelScope.launch { runCatching { procedureStore.forget(name) } }
    }

    /** Forget all learned procedures. */
    fun clearProcedures() {
        viewModelScope.launch { runCatching { procedureStore.clear() } }
    }
}
