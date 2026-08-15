package dev.mascwa.pulse.feature.tacnet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Cpa
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.orbital.MoonInfo
import dev.mascwa.pulse.data.orbital.MoonPhase
import dev.mascwa.pulse.data.orbital.Planet
import dev.mascwa.pulse.data.orbital.PlanetCalc
import dev.mascwa.pulse.data.radar.ContactKind
import dev.mascwa.pulse.data.radar.RadarData
import dev.mascwa.pulse.data.radar.RadarRepository
import dev.mascwa.pulse.data.space.SpaceWeather
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
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
    private val spaceWeather: SpaceWeatherRepository,
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

    private val _sky = MutableStateFlow(SkyState())
    val sky: StateFlow<SkyState> = _sky.asStateFlow()

    /**
     * Pairs of aircraft currently converging, nearest-in-time first.
     *
     * Derived from contacts already on the scope, so it costs no extra network. Straight-line
     * extrapolation, which is all the data supports — see [Cpa] for why this is a geometry
     * readout and explicitly not a collision-avoidance system.
     */
    private val _convergences = MutableStateFlow<List<Cpa.Approach>>(emptyList())
    val convergences: StateFlow<List<Cpa.Approach>> = _convergences.asStateFlow()

    private var lastLoc: DeviceLocation? = null
    private var auto: Job? = null

    val ranges = listOf(50, 100, 250, 500)

    enum class AltFilter { ALL, LOW, MID, HIGH }

    /** The sky/space picture grouped with the scope: Moon, naked-eye planets
     *  above the horizon (offline ephemeris), and NOAA space weather. */
    data class SkyState(
        val moon: MoonInfo? = null,
        val planets: List<Planet> = emptyList(),
        val space: SpaceWeather? = null,
    )

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
            // Sky bodies + space weather only refresh on the real loads (initial /
            // manual / permission grant), not the 15s aircraft auto-refresh — planets
            // barely move and space weather has its own 15-min cache.
            if (refetchLocation) refreshSky(loc, force)
            _state.load(force) { repo.fetch(loc.latitude, loc.longitude, it) }
            _state.value.data?.let { d ->
                val n = d.contacts.count { it.kind == ContactKind.AIRCRAFT.name }
                _countHistory.value = (_countHistory.value + n).takeLast(40)
                _convergences.value = findConvergences(d)
            }
        }
    }

    /**
     * Turn the aircraft on the scope into CPA tracks and look for convergences.
     *
     * Only aircraft, and only those actually reporting a heading and a speed — a contact without
     * them cannot be extrapolated, and guessing would be worse than saying nothing. Runs on the
     * calling coroutine because the pair count is small: the sweep is O(n²), but with well under a
     * hundred aircraft in range that is a few thousand cheap comparisons.
     */
    private fun findConvergences(d: RadarData): List<Cpa.Approach> = runCatching {
        val tracks = d.contacts
            .filter { it.kind == ContactKind.AIRCRAFT.name && !it.onGround }
            .mapNotNull { c ->
                val speed = c.groundSpeedKmh ?: return@mapNotNull null
                val track = c.trackDeg ?: return@mapNotNull null
                Cpa.Track(
                    id = c.id,
                    label = c.label,
                    latitudeDeg = c.latitude,
                    longitudeDeg = c.longitude,
                    altitudeM = c.altitudeM,
                    groundSpeedMs = speed / 3.6,
                    trackDeg = track,
                    verticalRateMs = c.verticalRateFpm?.toDouble()
                        ?.let { Cpa.feetPerMinuteToMs(it) } ?: 0.0,
                )
            }
        Cpa.notableApproaches(tracks)
    }.getOrDefault(emptyList())

    /** Moon + planets are pure offline computations from the GPS fix; space
     *  weather is best-effort over the network and never disturbs the scope. */
    private fun refreshSky(loc: DeviceLocation, force: Boolean) {
        val planets = PlanetCalc.planetsNow(loc.latitude, loc.longitude)
            .filter { it.aboveHorizon }
            .sortedBy { it.magnitude }
        _sky.update { it.copy(moon = MoonPhase.at(), planets = planets) }
        viewModelScope.launch {
            runCatching {
                spaceWeather.fetch(force = force, lat = loc.latitude, lon = loc.longitude).data
            }.getOrNull()?.let { sw -> _sky.update { it.copy(space = sw) } }
        }
    }
}
