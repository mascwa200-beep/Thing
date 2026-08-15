package dev.mascwa.pulse.feature.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.space.SpaceWeather
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SpaceWeatherViewModel(
    private val repo: SpaceWeatherRepository,
    private val location: LocationProvider,
) : ViewModel() {
    private val _core = MutableStateFlow<Async<SpaceWeather>>(Async(loading = true))

    /**
     * The local aurora probability lives in its own flow rather than being patched into [_core].
     *
     * It used to be written back with `_core.update { copy(data = …) }` from a second coroutine
     * racing the main fetch: whenever the aurora call won that race — which is the common case,
     * since it is one small request against five — the main fetch's completion replaced `data`
     * wholesale and silently dropped the percentage again. Two independent flows combined at the
     * edge cannot clobber each other whichever order they finish in.
     */
    private val _auroraPct = MutableStateFlow<Int?>(null)

    val state: StateFlow<Async<SpaceWeather>> =
        combine(_core, _auroraPct) { core, pct ->
            if (pct == null) core else core.copy(data = core.data?.copy(auroraProbabilityPct = pct))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Async(loading = true))

    init { refresh(force = false) }

    fun refresh(force: Boolean = true) {
        // Load core space weather immediately — never block the screen on a GPS fix.
        viewModelScope.launch { _core.load(force) { repo.fetch(it, null, null) } }
        // Then resolve location in the background and fill in the local aurora %.
        viewModelScope.launch {
            val loc = if (location.hasPermission()) runCatching { location.current() }.getOrNull() else null
            if (loc != null) {
                repo.auroraFor(loc.latitude, loc.longitude)?.let { _auroraPct.value = it }
            }
        }
    }
}
