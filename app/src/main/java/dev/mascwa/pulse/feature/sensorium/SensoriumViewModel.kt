package dev.mascwa.pulse.feature.sensorium

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.sensing.SensoriumService
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.launch

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
