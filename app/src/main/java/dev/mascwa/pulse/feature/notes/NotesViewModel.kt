package dev.mascwa.pulse.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.notes.Note
import dev.mascwa.pulse.data.notes.NotesStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the LIBRARY (NOTES) sub-tab. Thin over [NotesStore] — exposes the live list and the add/delete
 * actions. Notes persist on-device.
 */
class NotesViewModel(private val store: NotesStore) : ViewModel() {

    /** The categories the user can file an entry under. */
    val categories = listOf("GENERAL", "INTEL", "MISSION", "PERSONAL", "IDEAS")

    val notes: StateFlow<List<Note>> = store.notesFlow

    init {
        // Warm the list from disk on open.
        viewModelScope.launch { store.load() }
    }

    fun add(title: String, body: String, category: String) {
        viewModelScope.launch { store.add(title, body, category) }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.remove(id) }
    }
}
