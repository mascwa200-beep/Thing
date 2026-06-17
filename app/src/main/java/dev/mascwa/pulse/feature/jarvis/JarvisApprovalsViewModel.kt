package dev.mascwa.pulse.feature.jarvis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.selfedit.ArtifactVersion
import dev.mascwa.pulse.data.selfedit.PendingAction
import dev.mascwa.pulse.data.selfedit.SelfEditStore
import dev.mascwa.pulse.jarvis.selfedit.ApprovalGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Approvals screen. APPROVE/REJECT/ROLLBACK are the ONLY ways a self-change is applied —
 * each routes through [ApprovalGate] from this user-driven ViewModel, never from the agent loop.
 */
class JarvisApprovalsViewModel(
    private val selfEdit: SelfEditStore,
    private val gate: ApprovalGate,
) : ViewModel() {

    val pending: StateFlow<List<PendingAction>> =
        selfEdit.state.map { it.pendingActions }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Newest version first. */
    val versions: StateFlow<List<ArtifactVersion>> =
        selfEdit.state.map { it.versions.reversed() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Registered authored Lua tools (enable/disable/delete). */
    val tools: StateFlow<List<dev.mascwa.pulse.data.selfedit.AuthoredTool>> =
        selfEdit.state.map { it.authoredTools }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setToolEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch { runCatching { selfEdit.setAuthoredToolEnabled(name, enabled) } }
    }

    fun removeTool(name: String) {
        viewModelScope.launch { runCatching { selfEdit.removeAuthoredTool(name) }; _result.value = "Removed tool \"$name\"." }
    }

    private val _result = MutableStateFlow("")
    val result: StateFlow<String> = _result.asStateFlow()

    fun approve(action: PendingAction) {
        viewModelScope.launch { _result.value = runCatching { gate.apply(action) }.getOrElse { "Failed: ${it.message}" } }
    }

    fun reject(id: String) {
        viewModelScope.launch { runCatching { gate.reject(id) }; _result.value = "Rejected." }
    }

    fun rollback(version: ArtifactVersion) {
        viewModelScope.launch { _result.value = runCatching { gate.rollback(version) }.getOrElse { "Failed: ${it.message}" } }
    }
}
