package dev.mascwa.pulse.feature.survive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.sensors.SurvivalTools
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ToolsViewModel(
    private val tools: SurvivalTools,
) : ViewModel() {

    private val _strobe = MutableStateFlow(false)
    val strobe: StateFlow<Boolean> = _strobe.asStateFlow()

    private val _alarm = MutableStateFlow(false)
    val alarm: StateFlow<Boolean> = _alarm.asStateFlow()

    val torchAvailable: Boolean get() = tools.torchAvailable()

    private var strobeJob: Job? = null

    fun toggleStrobe() {
        if (_strobe.value) stopStrobe() else startStrobe()
    }

    private fun startStrobe() {
        if (!tools.torchAvailable()) return
        _strobe.value = true
        strobeJob = viewModelScope.launch {
            while (isActive) {
                SurvivalTools.SOS_STATES.forEachIndexed { i, on ->
                    if (!isActive) return@launch
                    tools.setTorch(on)
                    delay(SurvivalTools.SOS_DURATIONS[i])
                }
            }
        }
    }

    private fun stopStrobe() {
        strobeJob?.cancel()
        strobeJob = null
        tools.setTorch(false)
        _strobe.value = false
    }

    fun toggleAlarm() {
        if (_alarm.value) {
            tools.stopAlarm(); _alarm.value = false
        } else {
            tools.playAlarm(); _alarm.value = true
        }
    }

    fun sosVibrate() = tools.vibrate(SurvivalTools.SOS_DURATIONS)

    override fun onCleared() {
        stopStrobe()
        tools.stopAlarm()
        tools.cancelVibrate()
    }
}
