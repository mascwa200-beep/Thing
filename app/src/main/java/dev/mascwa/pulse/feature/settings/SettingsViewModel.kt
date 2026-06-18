package dev.mascwa.pulse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.selfcode.SelfCoder
import dev.mascwa.pulse.data.update.UpdateInfo
import dev.mascwa.pulse.data.update.UpdateRepository
import dev.mascwa.pulse.notifications.NotificationScheduler
import dev.mascwa.pulse.notifications.Notifier
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: NotificationScheduler,
    private val diskCache: DiskCache,
    private val notifier: Notifier,
    private val updates: UpdateRepository,
    private val selfCoder: SelfCoder,
) : ViewModel() {

    private val _selfCode = MutableStateFlow("")
    /** Status line for the self-coding "propose a change" action. */
    val selfCodeStatus: StateFlow<String> = _selfCode

    /** Have J.A.R.V.I.S. draft a change for [goal] and stage it for approval. [path] is optional — leave
     *  it blank and J.A.R.V.I.S. picks the file itself; approve the staged change to open the PR. */
    fun proposeSelfChange(goal: String, path: String) {
        if (goal.isBlank()) { _selfCode.value = "Enter a goal."; return }
        _selfCode.value = "Drafting a change…"
        viewModelScope.launch {
            _selfCode.value = runCatching { selfCoder.propose(goal, path) }
                .getOrElse { SelfCoder.Result(false, it.message ?: "failed") }
                .message
        }
    }

    val settings: StateFlow<AppSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** In-app updater UI state. */
    sealed interface UpdateUi {
        data object Idle : UpdateUi
        data object Checking : UpdateUi
        data object UpToDate : UpdateUi
        data class Available(val info: UpdateInfo) : UpdateUi
        data class Downloading(val pct: Int) : UpdateUi
        data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateUi
        data class Error(val message: String) : UpdateUi
    }

    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val updateState: StateFlow<UpdateUi> = _update

    /** Currently-installed version name, e.g. "1.0.42-debug". */
    val installedVersion: String get() = updates.currentVersionName

    /** Check the `latest` release for a newer build. Safe to call on screen open. */
    fun checkForUpdate() {
        if (_update.value is UpdateUi.Checking || _update.value is UpdateUi.Downloading) return
        _update.value = UpdateUi.Checking
        viewModelScope.launch {
            _update.value = runCatching { updates.check() }.fold(
                onSuccess = { info -> if (info == null) UpdateUi.UpToDate else UpdateUi.Available(info) },
                onFailure = {
                    val msg = if (updates.token() == null) {
                        "Your repo is private — add a GitHub token (repo scope) in J.A.R.V.I.S. Setup, or make the repo public, sir."
                    } else {
                        "Couldn't reach the update server — check your connection or token, sir."
                    }
                    UpdateUi.Error(msg)
                },
            )
        }
    }

    /** Download the available update; on success the screen installs the returned file. */
    fun downloadUpdate() {
        val info = (_update.value as? UpdateUi.Available)?.info
            ?: (_update.value as? UpdateUi.ReadyToInstall)?.info ?: return
        _update.value = UpdateUi.Downloading(0)
        viewModelScope.launch {
            val file = runCatching { updates.download(info) { pct -> _update.value = UpdateUi.Downloading(pct) } }.getOrNull()
            _update.value = if (file != null) UpdateUi.ReadyToInstall(info, file) else UpdateUi.Error("Download failed — try again, sir.")
        }
    }

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize

    init {
        refreshCacheSize()
        // Keep the background worker in sync with notification/refresh prefs.
        viewModelScope.launch {
            repo.settings
                .map { Triple(it.notifications.masterEnabled, it.refreshIntervalMinutes, it.refreshOnlyOnWifi) }
                .distinctUntilChanged()
                .collect { (master, interval, wifi) ->
                    if (master) scheduler.schedule(interval, wifi) else scheduler.cancel()
                }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repo.replace(AppSettings()) }
    }

    fun refreshCacheSize() {
        viewModelScope.launch { _cacheSize.value = diskCache.sizeBytes() }
    }

    fun clearCache() {
        viewModelScope.launch {
            diskCache.clear()
            refreshCacheSize()
        }
    }

    fun sendTestNotification() {
        // Use the high-importance breaking channel so it pops as a heads-up.
        notifier.notifyBreaking(
            id = 9999,
            title = "Pulse notifications are working",
            body = "If you can see this, alerts are enabled. Breaking news, market, weather, sky and safety alerts will appear like this.",
            route = "grid",
        )
    }
}
