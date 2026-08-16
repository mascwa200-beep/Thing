package dev.mascwa.pulse.feature.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.diary.DiaryEntry
import dev.mascwa.pulse.data.diary.DiaryStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the DIARY feed. Thin over [DiaryStore] — the live (newest-first) entry list plus add/delete/clear.
 * The diary persists on-device; J.A.R.V.I.S. can also write entries here via the `diary` tool.
 */
class DiaryViewModel(private val store: DiaryStore) : ViewModel() {

    /** Optional quick moods to tag an entry with ("" = none). */
    val moods = listOf("", "GREAT", "GOOD", "OKAY", "LOW", "TIRED", "ANXIOUS")

    val entries: StateFlow<List<DiaryEntry>> = store.entriesFlow

    init {
        // Warm the list from disk on open.
        viewModelScope.launch { store.load() }
    }

    fun add(title: String, body: String, mood: String) {
        viewModelScope.launch { store.add(title, body, mood) }
    }

    /** Rewrite an entry in place. A diary is chronological, so a correction must not re-date it. */
    fun update(id: String, title: String, body: String, mood: String) {
        viewModelScope.launch { store.update(id, title, body, mood) }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.remove(id) }
    }

    fun clear() {
        viewModelScope.launch { store.clear() }
    }
}
