package dev.mascwa.pulse.feature.objectives

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.objectives.CalendarObjectivesRepository
import dev.mascwa.pulse.data.objectives.Objective
import dev.mascwa.pulse.data.objectives.ObjectiveKind
import dev.mascwa.pulse.data.objectives.ObjectiveSource
import dev.mascwa.pulse.data.objectives.Waypoint
import dev.mascwa.pulse.data.objectives.WaypointStore
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the objectives tracker: device-calendar events (with locations) + manual waypoints, merged
 * and sorted by live distance. Selecting one makes it the active map waypoint.
 */
class ObjectivesViewModel(
    private val calendar: CalendarObjectivesRepository,
    private val waypoints: WaypointStore,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _calendar = MutableStateFlow<List<Objective>>(emptyList())
    private val _loc = MutableStateFlow<DeviceLocation?>(null)
    private val _needsCalendarPermission = MutableStateFlow(false)
    val needsCalendarPermission: StateFlow<Boolean> = _needsCalendarPermission.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val activeId: StateFlow<String?> =
        waypoints.activeId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Merged, distance-sorted objectives (manual waypoints + geocoded calendar events). A calendar
     *  event that's already been tracked (i.e. has a matching saved waypoint) is shown ONCE — the
     *  manual/tracked copy wins — so tracking never leaves a duplicate twin in the list. */
    val objectives: StateFlow<List<Objective>> =
        combine(waypoints.waypoints, _calendar, _loc) { wps, cal, loc ->
            val manual = wps.map { it.toObjective(loc) }
            val manualKeys = wps.map { coordKey(it.label, it.latitude, it.longitude) }.toSet()
            val calendarWithDist = cal
                .filterNot { coordKey(it.title, it.latitude, it.longitude) in manualKeys }
                .map { it.withDistance(loc) }
            (manual + calendarWithDist).sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _loc.value = runCatching { locationProvider.current() }.getOrNull()
            _needsCalendarPermission.value = !calendar.hasPermission()
            _calendar.value = runCatching { calendar.upcoming() }.getOrDefault(emptyList())
            _loading.value = false
        }
    }

    /** Track an objective → it becomes the active map waypoint. Idempotent: tracking a calendar event
     *  that's already a saved waypoint just re-activates it instead of creating a duplicate. */
    fun track(o: Objective) {
        viewModelScope.launch {
            if (o.source == ObjectiveSource.MANUAL) {
                waypoints.setActive(o.id)
            } else {
                val key = coordKey(o.title, o.latitude, o.longitude)
                val existing = waypoints.waypoints.first().firstOrNull { coordKey(it.label, it.latitude, it.longitude) == key }
                if (existing != null) waypoints.setActive(existing.id)
                else waypoints.add(o.title, o.latitude, o.longitude, o.kind, o.whenLabel)
            }
        }
    }

    /** Add a manual waypoint by place name (geocoded), falling back to the current location. */
    fun addManual(query: String, kind: ObjectiveKind) {
        viewModelScope.launch {
            val coords = calendar.geocode(query)
                ?: _loc.value?.let { it.latitude to it.longitude }
                ?: runCatching { locationProvider.current() }.getOrNull()?.let { it.latitude to it.longitude }
            if (coords != null) waypoints.add(query, coords.first, coords.second, kind)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch { waypoints.remove(id) }
    }

    private fun Waypoint.toObjective(loc: DeviceLocation?): Objective = Objective(
        id = id,
        title = label,
        kind = kind,
        latitude = latitude,
        longitude = longitude,
        source = ObjectiveSource.MANUAL,
        whenLabel = note,
        distanceMeters = loc?.let { Geo.distanceMeters(it.latitude, it.longitude, latitude, longitude) },
    )

    private fun Objective.withDistance(loc: DeviceLocation?): Objective =
        if (loc == null) this
        else copy(distanceMeters = Geo.distanceMeters(loc.latitude, loc.longitude, latitude, longitude))

    /**
     * Identity for de-dup: title + coordinates rounded to ~11 m, so a calendar event and the
     * waypoint it was tracked into collapse to one entry.
     *
     * ⚠️ `Locale.US`, not the device's. `"%.4f".format(v)` renders a comma decimal separator on
     * most of Europe — harmless while every key on screen was written under the same locale, and
     * wrong the moment somebody changes the phone's language: every existing waypoint's key
     * respells itself, nothing matches, and the next tap on a tracked objective drops a duplicate
     * pin instead of re-activating the one already there. That duplicate-pin bug is exactly what
     * this function was written to fix.
     *
     * The `lowercase()` beside it needs no such argument: Kotlin's no-argument overload folds under
     * `Locale.ROOT`, not the device's, so it is already stable. (The deprecated `toLowerCase()` was
     * the one that took the default locale and turned an I into a dotless ı in Turkish.)
     */
    private fun coordKey(title: String, lat: Double, lon: Double): String =
        "${title.trim().lowercase()}@" +
            "${String.format(java.util.Locale.US, "%.4f", lat)}," +
            String.format(java.util.Locale.US, "%.4f", lon)
}
