package dev.mascwa.pulse.feature.jarvis

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.BuildConfig
import dev.mascwa.pulse.core.device.DeviceContextProvider
import dev.mascwa.pulse.core.telemetry.OperatorDossier
import dev.mascwa.pulse.core.telemetry.ProfileEntry
import dev.mascwa.pulse.core.telemetry.Task
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.data.profile.ProfileStore
import dev.mascwa.pulse.data.tasks.TaskStore
import dev.mascwa.pulse.data.usage.UsageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Assembles the OPERATOR DOSSIER from data already held on-device — the user profile, objectives, device
 * disposition, and the recent (content-free) activity log. Read-only: it collects and transmits nothing;
 * the dossier is just a consolidated, themed view of what J.A.R.V.I.S. already knows. The derived bits
 * (callsign, intel level, classification) come from the CI-tested [OperatorDossier] core.
 */
class JarvisDossierViewModel(
    private val profileStore: ProfileStore,
    private val taskStore: TaskStore,
    private val deviceContextProvider: DeviceContextProvider,
    private val usageRepository: UsageRepository,
) : ViewModel() {

    /** Identity / profile facts, live. */
    val profile: StateFlow<List<ProfileEntry>> = profileStore.entriesFlow

    /** Objectives: open first, then completed. */
    val objectives: StateFlow<List<Task>> = taskStore.tasksFlow
        .map { TaskBoard.pending(it) + TaskBoard.completed(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The point-in-time header of the dossier (recomputed on open / [refresh]). */
    data class Meta(
        val codename: String = "—",
        val classification: String = "—",
        val intelLevel: Int = 0,
        val device: String = "",
        val os: String = "",
        val build: String = "",
        val disposition: String = "",
        val activity: List<String> = emptyList(),
    )

    private val _meta = MutableStateFlow(Meta())
    val meta: StateFlow<Meta> = _meta.asStateFlow()

    init {
        viewModelScope.launch { runCatching { assemble() } }
    }

    /** Recompute the dossier header from the current on-device state. */
    fun refresh() {
        viewModelScope.launch { runCatching { assemble() } }
    }

    private suspend fun assemble() {
        val prof = runCatching { profileStore.all() }.getOrDefault(emptyList())
        val tasks = runCatching { taskStore.all() }.getOrDefault(emptyList())
        val open = TaskBoard.pending(tasks)
        val activity = runCatching { usageRepository.recentActivity(12) }.getOrDefault(emptyList())
        val ctx = runCatching { deviceContextProvider.snapshot() }.getOrNull()

        val seed = prof.firstOrNull()?.text ?: Build.MODEL
        val level = OperatorDossier.intelLevel(prof.size, open.size, activity.size)
        val disposition = ctx?.let {
            val pwr = if (it.batteryPct >= 0) "${it.batteryPct}%" else "—"
            val chg = if (it.isCharging) " (charging)" else ""
            "PWR $pwr$chg  ·  ${it.network.name}  ·  ${it.dayPart.name}"
        } ?: "—"

        _meta.value = Meta(
            codename = OperatorDossier.codename(seed),
            classification = OperatorDossier.classification(level),
            intelLevel = level,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            os = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
            build = "${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})",
            disposition = disposition,
            activity = activity.map { "${it.category}  ${it.label}" },
        )
    }
}
