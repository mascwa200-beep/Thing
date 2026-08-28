package dev.mascwa.pulse.feature.sensorium

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.sensing.SensoriumService
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin projection of the Sensorium's live state for the scanner screen — every flow here is owned by
 * the engine/store/fusion singletons (the service keeps them fed whether or not the screen is open;
 * the scanner is a window, not a driver).
 */
class SensoriumViewModel(private val c: AppContainer) : ViewModel() {
    val reading = c.sensoriumEngine.reading
    val anomalies = c.sensoriumEngine.anomalies
    val normalLine = c.sensoriumEngine.normalLine
    val micArmed = c.sensoriumEngine.micArmed
    val camArmed = c.sensoriumEngine.camArmed
    val level = c.sensoriumEngine.level
    val events = c.sensoriumStore.eventsFlow
    val fusion = c.sensorFusion.snapshot

    /** Ask the eyes to look now — honored on the engine's next heartbeat. */
    fun lookNow() = c.sensoriumEngine.requestLook()

    private val _modelBytes = MutableStateFlow(0L)

    /**
     * What the two classifiers are holding on disk.
     *
     * ⚠️ **Nothing reported this and nothing could free it.** The samplers fetch YAMNet and
     * EfficientNet — about 4 MB each — into `filesDir` on first use, and `SensingSettings.enabled`
     * defaults on, so they land on an ordinary install that never opened this screen. That makes
     * them the storage in this app least likely to be explicable to whoever is looking for space:
     * the adjudicator at least followed a tap. An interrupted fetch can leave far more, since each
     * download is capped at 24 MB rather than the finished ~4.
     */
    val modelBytes: StateFlow<Long> = _modelBytes.asStateFlow()

    init {
        refreshModelBytes()
    }

    private fun refreshModelBytes() {
        viewModelScope.launch {
            _modelBytes.value = withContext(Dispatchers.IO) {
                runCatching {
                    c.ambientAudioSampler.bytesOnDisk() + c.ambientCameraSampler.bytesOnDisk()
                }.getOrDefault(0L)
            }
        }
    }

    /**
     * Give the storage back.
     *
     * ⚠️ **Stands ambient sensing down first, and that is not politeness.** A sampler that is still
     * armed re-downloads its model on the very next sip, so discarding underneath a running service
     * would spend the user's data and leave the disk exactly where it started — a control that
     * appears to work and does nothing. Switching `sensing.enabled` off is the same lever the
     * notification's Stop action uses, so `RefreshWorker` will not restart the service behind us
     * either; ARM turns it back on.
     *
     * ⚠️ The order matters the other way too: the samplers close their classifier before deleting,
     * so this cannot leave MediaPipe holding a path to a file that is no longer there.
     */
    fun discardModels(context: Context) {
        viewModelScope.launch {
            runCatching { c.settingsRepository.update { it.copy(sensing = it.sensing.copy(enabled = false)) } }
            runCatching { SensoriumService.stop(context) }
            runCatching { c.ambientAudioSampler.discardModel() }
            runCatching { c.ambientCameraSampler.discardModel() }
            refreshModelBytes()
        }
    }

    /**
     * Re-arm from a foreground context (after a permission grant, or when the scanner notices the
     * service is running on the standby path).
     *
     * ⚠️ Turns the feature back on first. The notification's Stop action now switches
     * `sensing.enabled` off — otherwise `RefreshWorker` would restart the service on its next run
     * and Stop would undo itself — so without this, ARM would start the service and the loop's
     * first iteration would read the setting and immediately stand it down again. A button that
     * silently does nothing is worse than the bug it was added to fix.
     */
    fun rearm(context: Context) {
        viewModelScope.launch {
            runCatching { c.settingsRepository.update { it.copy(sensing = it.sensing.copy(enabled = true)) } }
            runCatching { SensoriumService.start(context, foregroundLaunch = true) }
        }
    }
}
