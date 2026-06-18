package dev.mascwa.pulse.feature.jarvis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.db.AgentNoteEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Memory screen — the user's view of (and control over) everything J.A.R.V.I.S. has learned.
 * Every durable note is editable and deletable here, code-enforced like the rest of the app.
 */
class JarvisMemoryViewModel(private val memory: JarvisMemory) : ViewModel() {

    val notes: StateFlow<List<AgentNoteEntity>> =
        memory.notesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
}
