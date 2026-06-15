package dev.mascwa.pulse.feature.sos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.emergency.EmergencyService
import dev.mascwa.pulse.data.sensors.SurvivalTools
import dev.mascwa.pulse.data.settings.EmergencyCard
import dev.mascwa.pulse.data.settings.EmergencyContact
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SosUiState(
    val emergencyNumber: String = "112",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String = "",
    val contacts: List<EmergencyContact> = emptyList(),
    val card: EmergencyCard = EmergencyCard(),
    val autoSendEnabled: Boolean = false,
    val active: Boolean = false,
    val lastAction: String? = null,
)

class SosViewModel(
    private val emergency: EmergencyService,
    private val settings: SettingsRepository,
    private val locationProvider: LocationProvider,
    private val tools: SurvivalTools,
) : ViewModel() {

    private val _state = MutableStateFlow(SosUiState())
    val state: StateFlow<SosUiState> = _state.asStateFlow()

    private var strobeJob: Job? = null

    init {
        viewModelScope.launch {
            val s = settings.current()
            _state.update {
                it.copy(
                    emergencyNumber = emergency.emergencyNumber(s.countryCode),
                    contacts = s.emergencyContacts,
                    card = s.emergencyCard,
                    autoSendEnabled = s.autoSendSos,
                )
            }
            refreshLocation()
        }
    }

    fun refreshLocation() {
        viewModelScope.launch {
            if (locationProvider.hasPermission()) {
                locationProvider.current()?.let { loc ->
                    _state.update { it.copy(latitude = loc.latitude, longitude = loc.longitude, locationName = loc.name) }
                }
            }
        }
    }

    private fun message(): String {
        val s = _state.value
        return emergency.buildSosMessage(s.latitude, s.longitude, s.card, includeCard = true)
    }

    fun dialEmergency() = emergency.dial(_state.value.emergencyNumber)

    fun shareLocation() {
        emergency.share(message())
        _state.update { it.copy(lastAction = "Opened share sheet") }
    }

    /** Auto-send when enabled + permitted; otherwise open the SMS app pre-filled. */
    fun textContacts() {
        val s = _state.value
        if (s.contacts.isEmpty()) {
            _state.update { it.copy(lastAction = "Add an emergency contact in Settings first") }
            return
        }
        val sent = if (s.autoSendEnabled && emergency.canAutoSendSms()) {
            emergency.autoSendSms(s.contacts, message())
        } else false
        if (sent) {
            _state.update { it.copy(lastAction = "SOS SMS sent to ${s.contacts.size} contact(s)") }
        } else {
            emergency.composeSms(s.contacts, message())
            _state.update { it.copy(lastAction = "Opened messaging app") }
        }
    }

    fun toggleSos() {
        if (_state.value.active) deactivate() else activate()
    }

    private fun activate() {
        _state.update { it.copy(active = true, lastAction = "SOS active — strobe + alarm") }
        tools.playAlarm()
        tools.vibrate(SurvivalTools.SOS_DURATIONS)
        if (tools.torchAvailable()) {
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
    }

    private fun deactivate() {
        strobeJob?.cancel(); strobeJob = null
        tools.setTorch(false)
        tools.stopAlarm()
        tools.cancelVibrate()
        _state.update { it.copy(active = false, lastAction = null) }
    }

    override fun onCleared() {
        strobeJob?.cancel()
        tools.setTorch(false)
        tools.stopAlarm()
    }
}
