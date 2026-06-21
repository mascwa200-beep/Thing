package dev.mascwa.pulse.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Task
import dev.mascwa.pulse.data.tasks.TaskStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the ITEMS > TASKS inventory view. Thin over [TaskStore] — exposes the live task list (the
 * deliberate-goal layer J.A.R.V.I.S. captures from "I need to…"/"todo:" and the `task` tool). Read-only
 * here; tasks are curated from J.A.R.V.I.S. / the Memory screen.
 */
class TasksViewModel(private val store: TaskStore) : ViewModel() {

    val tasks: StateFlow<List<Task>> = store.tasksFlow

    init {
        // Warm the list from disk on open (tasksFlow stays empty until something touches the store).
        viewModelScope.launch { store.all() }
    }
}
