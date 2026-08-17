package dev.mascwa.pulse.desktop.feature.about

import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.telemetry.UpdatePolicy
import dev.mascwa.pulse.desktop.update.BuildInfo
import dev.mascwa.pulse.desktop.update.DesktopUpdate
import dev.mascwa.pulse.desktop.update.DesktopUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class AboutUiState(
    val installed: String = BuildInfo.display,
    val checking: Boolean = false,
    val verdict: UpdatePolicy.Verdict? = null,
    val latestVersionName: String? = null,
    val update: DesktopUpdate? = null,
    val notes: String = "",
    val error: String? = null,
    /** 0..100 while fetching the installer, null when not downloading. */
    val downloadPct: Int? = null,
    val downloaded: File? = null,
    val token: String = "",
    val autoCheck: Boolean = true,
)

/**
 * Drives ABOUT: which build this is, whether a newer one is published, and getting it installed.
 *
 * Checking is always explicit or on launch, and never installs anything on its own — downloading a
 * hundred and eighty megabytes and running an elevated installer are both things a person should be the
 * one to start.
 */
class AboutViewModel(
    private val scope: CoroutineScope,
    private val updater: DesktopUpdater,
    private val settings: DesktopSettingsStore,
) {
    private val _state = MutableStateFlow(AboutUiState())
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            val s = runCatching { settings.current() }.getOrNull()
            _state.value = _state.value.copy(
                token = s?.githubToken.orEmpty(),
                autoCheck = s?.autoCheckUpdates ?: true,
            )
            if (s?.autoCheckUpdates != false) check()
        }
    }

    fun check() {
        job?.cancel()
        _state.value = _state.value.copy(checking = true, error = null)
        job = scope.launch {
            val result = updater.check()
            _state.value = _state.value.copy(
                checking = false,
                verdict = result.verdict,
                latestVersionName = result.latestVersionName,
                update = result.update,
                notes = result.update?.notes.orEmpty(),
                error = result.error,
            )
        }
    }

    fun download() {
        val update = _state.value.update ?: return
        job?.cancel()
        _state.value = _state.value.copy(downloadPct = 0, error = null, downloaded = null)
        job = scope.launch {
            runCatching { updater.download(update) { pct -> _state.value = _state.value.copy(downloadPct = pct) } }
                .onSuccess { file -> _state.value = _state.value.copy(downloadPct = null, downloaded = file) }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        downloadPct = null,
                        error = e.message ?: "The download failed.",
                    )
                }
        }
    }

    /**
     * Start the installer. Returns whether it actually launched, so the caller only quits the app when
     * something is genuinely there to take over — quitting into a failed launch would look like a crash.
     */
    fun install(): Boolean {
        val file = _state.value.downloaded ?: return false
        val started = updater.launchInstaller(file)
        if (!started) {
            _state.value = _state.value.copy(
                error = "Could not start the installer. It is saved at ${file.absolutePath} — run it yourself.",
            )
        }
        return started
    }

    fun setToken(value: String) {
        _state.value = _state.value.copy(token = value)
        scope.launch { runCatching { settings.update { it.copy(githubToken = value.trim()) } } }
    }

    fun setAutoCheck(value: Boolean) {
        _state.value = _state.value.copy(autoCheck = value)
        scope.launch { runCatching { settings.update { it.copy(autoCheckUpdates = value) } } }
    }
}
