package dev.mascwa.pulse.feature.ar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Character
import dev.mascwa.pulse.core.telemetry.WorldSite
import dev.mascwa.pulse.data.game.GameWorldStore
import dev.mascwa.pulse.data.game.SpecialGameStore
import dev.mascwa.pulse.data.objectives.Waypoint
import dev.mascwa.pulse.data.objectives.WaypointStore
import dev.mascwa.pulse.data.sensors.CompassController
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the AR wasteland camera: the live compass heading, a polled GPS fix, and the nearby geo-gated
 * [WorldSite]s (scanned by the game). The screen projects each site onto the camera picture with
 * [dev.mascwa.pulse.core.telemetry.ArProjection] (a "magic window" — no ARCore). Also feeds the same
 * travel signal to the game so walking around with the AR view open still accrues distance/exploration.
 */
class ArViewModel(
    private val location: LocationProvider,
    private val compass: CompassController,
    private val gameWorld: GameWorldStore,
    private val game: SpecialGameStore,
    private val waypoints: WaypointStore,
) : ViewModel() {

    /** The geo-gated wasteland sites near you (shared with the game's scan). */
    val sites: StateFlow<List<WorldSite>> = gameWorld.sitesFlow

    val scanning: StateFlow<Boolean> = gameWorld.scanningFlow

    /** The player's character (for the AR stats HUD — LVL/HP/CAPS). */
    val character: StateFlow<Character> = game.characterFlow

    /** The active tracked waypoint (projected as a gold objective marker), or null. */
    val activeWaypoint: StateFlow<Waypoint?> =
        waypoints.active.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), null)

    private val _gps = MutableStateFlow<DeviceLocation?>(null)
    val gps: StateFlow<DeviceLocation?> = _gps.asStateFlow()

    /** Live compass heading (degrees from true north). */
    val heading: StateFlow<Float> = compass.reading
        .map { it.trueAzimuth }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), 0f)

    /** Live camera pitch (degrees up+/down−) — drives the vertical parallax of markers. */
    val pitch: StateFlow<Float> = compass.reading
        .map { it.pitch }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), 0f)

    /** True when the compass is uncalibrated / absent — prompt a figure-8 wave. */
    val compassUnreliable: StateFlow<Boolean> = compass.reading
        .map { it.accuracyLow || !it.hasSensor }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), false)

    private var pollJob: Job? = null

    /** Begin the compass + GPS polling (tie to the screen's lifecycle). */
    fun start() {
        compass.start()
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                if (location.hasPermission()) {
                    location.current()?.let { loc ->
                        _gps.value = loc
                        compass.setLocation(loc.latitude, loc.longitude, loc.altitudeM ?: 0.0)
                        // Keep the game's travel/exploration accruing while the AR view is open.
                        gameWorld.onLocation(loc.latitude, loc.longitude, loc.accuracyM, loc.speedMps, loc.altitudeM)
                    }
                }
                delay(GPS_POLL_MS)
            }
        }
    }

    fun stop() {
        compass.stop()
        pollJob?.cancel()
        pollJob = null
    }

    /** Scan the area for nearby real sites (if none are loaded yet). */
    fun scan() {
        val loc = _gps.value ?: return
        gameWorld.refresh(loc.latitude, loc.longitude)
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val GPS_POLL_MS = 5_000L
    }
}
