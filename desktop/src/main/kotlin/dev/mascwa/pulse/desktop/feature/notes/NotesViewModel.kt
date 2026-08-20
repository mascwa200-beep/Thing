package dev.mascwa.pulse.desktop.feature.notes

import dev.mascwa.pulse.desktop.notes.DiaryEntry
import dev.mascwa.pulse.desktop.notes.DiaryStore
import dev.mascwa.pulse.desktop.notes.Note
import dev.mascwa.pulse.desktop.notes.NotesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Notes, for the screen.
 *
 * ⚠️ The store is passed in, not built here, and it has to outlive the composition for the same
 * reason the settings store does: `onCloseRequest` calls `exitApplication()`, which calls
 * `System.exit(0)` immediately, and a write debounced two seconds behind it is simply lost. Anything
 * a person types has to be flushable from `Main.kt`, which means it has to be owned there.
 */
class NotesViewModel(private val scope: CoroutineScope, private val store: NotesStore) {

    val notes: Flow<List<Note>> = store.notesFlow

    val loadFailed: Boolean get() = store.loadFailed

    init {
        // Nothing reads the file until something asks. The flow starts at the default, so one read
        // here is what turns an empty screen into the real list.
        scope.launch { store.load() }
    }

    fun add(title: String, body: String, category: String) {
        scope.launch { store.add(title, body, category) }
    }

    fun update(id: String, title: String, body: String, category: String) {
        scope.launch { store.update(id, title, body, category) }
    }

    fun remove(id: String) {
        scope.launch { store.remove(id) }
    }
}

/** The journal, for the screen. Same shape as [NotesViewModel] for the same reasons. */
class DiaryViewModel(private val scope: CoroutineScope, private val store: DiaryStore) {

    val entries: Flow<List<DiaryEntry>> = store.entriesFlow

    val loadFailed: Boolean get() = store.loadFailed

    init {
        scope.launch { store.load() }
    }

    fun add(title: String, body: String, mood: String) {
        scope.launch { store.add(title, body, mood) }
    }

    fun update(id: String, title: String, body: String, mood: String) {
        scope.launch { store.update(id, title, body, mood) }
    }

    fun remove(id: String) {
        scope.launch { store.remove(id) }
    }
}
