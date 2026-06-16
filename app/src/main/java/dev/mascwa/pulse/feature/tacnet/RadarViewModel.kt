package dev.mascwa.pulse.feature.tacnet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.radar.ContactKind
import dev.mascwa.pulse.data.radar.RadarData
import dev.mascwa.pulse.data.radar.RadarRepository
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RadarViewModel(
    private val repo: RadarRepository,
    private val location: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow<Async<RadarData>>(Async(loading = true))
    val state: StateFlow<Async<RadarData>> = _state.asStateFlow()

    private val _rangeKm = MutableStateFlow(100)
    val rangeKm: StateFlow<Int> = _rangeKm.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _needsPermission = MutableStateFlow(false)
    val needsPermission: StateFlow<Boolean> = _needsPermission.asStateFlow()

    private val _altFilter = MutableStateFlow(AltFilter.ALL)
    val altFilter: StateFlow<AltFilter> = _altFilter.asStateFlow()

    private val _milOnly = MutableStateFlow(false)
    val milOnly: StateFlow<Boolean> = _milOnly.asStateFlow()

    private val _emergOnly = MutableStateFlow(false)
    val emergOnly: StateFlow<Boolean> = _emergOnly.asStateFlow()

    private val _countHistory = MutableStateFlow<List<Int>>(emptyList())
    val countHistory: StateFlow<List<Int>> = _countHistory.asStateFlow()

    private var lastLoc: DeviceLocation? = null
    private var auto: Job? = null

    val ranges = listOf(50, 100, 250, 500)

    enum class AltFilter { ALL, LOW, MID, HIGH }

    fun setAltFilter(f: AltFilter) { _altFilter.value = f }
    fun toggleMil() { _milOnly.value = !_milOnly.value }
    fun toggleEmerg() { _emergOnly.value = !_emergOnly.value }

    init { load(force = false, refetchLocation = true) }

    fun refresh() = load(force = true, refetchLocation = true)
    fun setRange(km: Int) { _rangeKm.value = km }
    fun select(id: String?) { _selected.value = if (_selected.value == id) null else id }

    fun onPermissionResult(granted: Boolean) {
        if (granted) load(force = true, refetchLocation = true) else _needsPermission.value = true
    }

    /** Auto-refresh aircraft while the scope is on screen (reusing the GPS fix). */
    fun startAuto() {
        if (auto?.isActive == true) return
        auto = viewModelScope.launch {
            while (true) {
                delay(15_000)
                load(force = true, refetchLocation = false)
            }
        }
    }

    fun stopAuto() { auto?.cancel() }

    private fun load(force: Boolean, refetchLocation: Boolean) {
        viewModelScope.launch {
            if (!location.hasPermission()) {
                _needsPermission.value = true
                _state.update { it.copy(loading = false) }
                return@launch
            }
            _needsPermission.value = false
            val loc = (if (refetchLocation) location.current() else null) ?: lastLoc ?: location.current()
            if (loc == null) {
                _state.update { it.copy(loading = false, error = it.error ?: "Couldn't get a GPS fix.") }
                return@launch
            }
            lastLoc = loc
            _state.load(force) { repo.fetch(loc.latitude, loc.longitude, it) }
            _state.value.data?.let { d ->
                val n = d.contacts.count { it.kind == ContactKind.AIRCRAFT.name }
                _countHistory.value = (_countHistory.value + n).takeLast(40)
            }
        }
    }
}
