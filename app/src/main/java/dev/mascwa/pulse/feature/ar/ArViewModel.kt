package dev.mascwa.pulse.feature.ar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.BuildingFootprint
import dev.mascwa.pulse.core.telemetry.BuildingFootprints
import dev.mascwa.pulse.core.telemetry.Character
import dev.mascwa.pulse.core.telemetry.ElevationField
import dev.mascwa.pulse.core.telemetry.LocalFootprint
import dev.mascwa.pulse.core.telemetry.Setting
import dev.mascwa.pulse.core.telemetry.TravelFilter
import dev.mascwa.pulse.core.telemetry.WorldSite
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.ar.BuildingRepository
import dev.mascwa.pulse.data.ar.ElevationRepository
import dev.mascwa.pulse.data.game.GameWorldStore
import dev.mascwa.pulse.data.game.SpecialGameStore
import dev.mascwa.pulse.data.objectives.Waypoint
import dev.mascwa.pulse.data.objectives.WaypointStore
import dev.mascwa.pulse.data.perception.IndoorOutdoorDetector
import dev.mascwa.pulse.data.sensors.CompassController
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val indoorDetector: IndoorOutdoorDetector,
    private val buildings: BuildingRepository,
    private val elevationRepo: ElevationRepository,
) : ViewModel() {

    /** The geo-gated wasteland sites near you (shared with the game's scan). */
    val sites: StateFlow<List<WorldSite>> = gameWorld.sitesFlow

    /** INDOOR/OUTDOOR/VEHICLE read of the AR camera → drives the wasteland ground render mode. */
    val setting: StateFlow<Setting> = indoorDetector.setting

    val scanning: StateFlow<Boolean> = gameWorld.scanningFlow

    /** The player's character (for the AR stats HUD — LVL/HP/CAPS). */
    val character: StateFlow<Character> = game.characterFlow

    /** The active tracked waypoint (projected as a gold objective marker), or null. */
    val activeWaypoint: StateFlow<Waypoint?> =
        waypoints.active.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), null)

    private val _gps = MutableStateFlow<DeviceLocation?>(null)
    val gps: StateFlow<DeviceLocation?> = _gps.asStateFlow()

    // The motion-gated STABLE anchor — advances only when a fix clears the GPS-uncertainty radius (via the
    // CI-tested [TravelFilter], the same filter the game's distance tracker uses). So a stationary phone's GPS
    // wander never drifts the projected wasteland, while real walking snaps the anchor forward to track you.
    // The AR display + building projection use THIS, not the raw jittery [_gps].
    private var anchorLat: Double? = null
    private var anchorLon: Double? = null
    private var lastMoveMs = 0L
    private val _anchor = MutableStateFlow<DeviceLocation?>(null)
    /** The stable fix the AR view + building projection ride (jitter-free when you're still). */
    val anchor: StateFlow<DeviceLocation?> = _anchor.asStateFlow()

    private val _moving = MutableStateFlow(false)
    /** True while genuinely in motion (a fix cleared the uncertainty radius recently); false = stationary. */
    val moving: StateFlow<Boolean> = _moving.asStateFlow()

    // Raw OSM building footprints near the last fetch point; re-fetched as you move (see [maybeFetchBuildings]).
    private val _footprints = MutableStateFlow<List<BuildingFootprint>>(emptyList())

    /**
     * The real buildings projected into the local AR frame around the STABLE [anchor] (not the raw fix), so
     * they sit rock-still when you're stationary and snap forward to track your real position as you walk —
     * the Fallout buildings staying in place of the real ones. Empty until footprints load (renderer keeps the
     * procedural skyline). Declared after [_anchor] so its initializer sees an initialized flow.
     */
    val localBuildings: StateFlow<List<LocalFootprint>> =
        combine(_anchor, _footprints) { a, fps ->
            if (a == null || fps.isEmpty()) emptyList()
            else BuildingFootprints.project(a.latitude, a.longitude, fps)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), emptyList())

    private val _elevation = MutableStateFlow<ElevationField?>(null)
    /** Real DEM elevation around the fix (the invisible ground anchor); null → flat-anchored procedural. */
    val elevation: StateFlow<ElevationField?> = _elevation.asStateFlow()

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
        // Prime the on-device indoor/outdoor classifier (model fetch + open, off the main thread).
        viewModelScope.launch { indoorDetector.prepare() }
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                if (location.hasPermission()) {
                    location.current()?.let { loc ->
                        _gps.value = loc
                        compass.setLocation(loc.latitude, loc.longitude, loc.altitudeM ?: 0.0)
                        // Advance the stable anchor only on real movement (jitter holds it → no drift when still).
                        updateAnchor(loc)
                        // Keep the game's travel/exploration accruing while the AR view is open.
                        gameWorld.onLocation(loc.latitude, loc.longitude, loc.accuracyM, loc.speedMps, loc.altitudeM)
                        // Pull the real OSM buildings + DEM elevation around you (first fix + on a big move).
                        maybeFetchGeo(loc.latitude, loc.longitude)
                    }
                    // Decay to "stationary" once you've held still past the move window (even with no new fix).
                    if (_moving.value && System.currentTimeMillis() - lastMoveMs >= MOVING_WINDOW_MS) {
                        _moving.value = false
                    }
                }
                delay(GPS_POLL_MS)
            }
        }
    }

    fun stop() {
        compass.stop()
        indoorDetector.close()
        pollJob?.cancel()
        pollJob = null
    }

    /** Scan the area for nearby real sites (if none are loaded yet). */
    fun scan() {
        val loc = _gps.value ?: return
        gameWorld.refresh(loc.latitude, loc.longitude)
    }

    /**
     * Fold a fix into the stable [anchor] via [TravelFilter]: the anchor advances (and we flag MOVING) only
     * when the fix clears the GPS-uncertainty radius; within that radius it holds, so GPS wander while you
     * stand still never moves the projected wasteland. The first fix seeds the anchor without flagging motion.
     */
    private fun updateAnchor(loc: DeviceLocation) {
        val hadAnchor = anchorLat != null
        val res = TravelFilter.step(anchorLat, anchorLon, loc.latitude, loc.longitude, loc.accuracyM?.toDouble())
        val advanced = res.anchorLat != anchorLat || res.anchorLon != anchorLon
        anchorLat = res.anchorLat
        anchorLon = res.anchorLon
        if (advanced) {
            _anchor.value = loc // loc sits at ~the new anchor
            if (hadAnchor && res.addedM > 0.0) { // genuine step (not the first seed, not a teleport re-anchor)
                lastMoveMs = System.currentTimeMillis()
                _moving.value = true
            }
        }
    }

    // Fetch the OSM buildings + DEM elevation once, then again only after you've walked far enough that the
    // set would meaningfully change — so it's not hammering the APIs every 5 s poll. Guarded against overlap.
    private var lastFetchLat = Double.NaN
    private var lastFetchLon = Double.NaN
    private var fetchingGeo = false

    private fun maybeFetchGeo(lat: Double, lon: Double) {
        val moved = lastFetchLat.isNaN() ||
            Geo.distanceMeters(lastFetchLat, lastFetchLon, lat, lon) > REFETCH_M
        if (!moved || fetchingGeo) return
        fetchingGeo = true
        lastFetchLat = lat; lastFetchLon = lon
        viewModelScope.launch {
            val fps = buildings.near(lat, lon)
            if (fps.isNotEmpty()) _footprints.value = fps
            val field = elevationRepo.near(lat, lon)
            if (field != null) _elevation.value = field
            fetchingGeo = false
        }
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val GPS_POLL_MS = 5_000L
        const val REFETCH_M = 150.0 // re-pull OSM buildings after moving this far from the last fetch point
        const val MOVING_WINDOW_MS = 8_000L // hold the MOVING flag this long after the last real step
    }
}
