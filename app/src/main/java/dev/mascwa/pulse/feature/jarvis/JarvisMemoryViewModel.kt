package dev.mascwa.pulse.feature.jarvis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.ProfileEntry
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.db.AgentNoteEntity
import dev.mascwa.pulse.data.profile.ProfileStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    val notes: StateFlow<List<AgentNoteEntity>> =
        memory.notesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live structured profile (preferences / interests / projects). */
    val profile: StateFlow<List<ProfileEntry>> = profileStore.entriesFlow

    init {
        // Trigger a load so the profile flow populates when the screen opens.
        viewModelScope.launch { runCatching { profileStore.all() } }
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
}
