package dev.mascwa.pulse.data.objectives

import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Manual waypoints + the single "active" waypoint the NAV map renders, persisted in the app settings
 * JSON blob (consistent with saved locations / watchlist — no Room added to :app). Adding a waypoint
 * makes it active so it immediately shows on the map.
 */
class WaypointStore(private val settings: SettingsRepository) {

    val waypoints: Flow<List<Waypoint>> = settings.settings.map { it.waypoints }

    val activeId: Flow<String?> = settings.settings.map { it.activeWaypointId }

    val active: Flow<Waypoint?> = settings.settings.map { s ->
        s.waypoints.firstOrNull { it.id == s.activeWaypointId }
    }

    suspend fun add(label: String, lat: Double, lon: Double, kind: ObjectiveKind, note: String? = null): Waypoint {
        val wp = Waypoint(UUID.randomUUID().toString(), label.ifBlank { "Waypoint" }, lat, lon, kind, note)
        settings.update { it.copy(waypoints = it.waypoints + wp, activeWaypointId = wp.id) }
        return wp
    }

    suspend fun remove(id: String) = settings.update {
        it.copy(
            waypoints = it.waypoints.filterNot { w -> w.id == id },
            activeWaypointId = if (it.activeWaypointId == id) null else it.activeWaypointId,
        )
    }

    suspend fun setActive(id: String?) = settings.update { it.copy(activeWaypointId = id) }
}
