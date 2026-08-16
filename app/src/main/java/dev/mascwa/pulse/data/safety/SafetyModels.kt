package dev.mascwa.pulse.data.safety

import dev.mascwa.pulse.core.telemetry.SafetyCoverage
import kotlinx.serialization.Serializable

enum class IncidentType(val label: String) {
    EARTHQUAKE("Earthquake"),
    WEATHER("Weather"),
    DISASTER("Disaster"),
    FIRE("Wildfire"),
    CRIME("Crime"),
    OTHER("Alert"),
}

enum class Severity { LOW, MODERATE, HIGH, EXTREME }

@Serializable
data class Incident(
    val id: String,
    val type: String,             // IncidentType name
    val title: String,
    val severity: String,         // Severity name
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val bearing: Double,
    val timeEpochMs: Long,
    val source: String,
    val url: String? = null,
    val magnitude: Double? = null,
)

@Serializable
data class SafetyResult(
    val originLat: Double,
    val originLon: Double,
    val incidents: List<Incident>,
    /**
     * What each source did on this fetch, keyed by `SafetyCoverage.Source` name.
     *
     * Defaulted so results cached before this field existed still decode. Stored as strings because
     * `core:telemetry` deliberately carries no serialization dependency; converted at the boundary.
     *
     * Without this the screen could not tell three different situations apart — the source looked
     * and found nothing, the source does not operate here, and the source failed — and it asserted
     * the first one in all three cases.
     */
    val sourceStates: Map<String, String> = emptyMap(),
) {
    /**
     * [sourceStates] back as the typed map the core reasons over.
     *
     * Names that no longer resolve are dropped rather than guessed at — a result cached before a
     * source was renamed should lose that one entry, not poison the whole reading.
     */
    fun coverage(): Map<SafetyCoverage.Source, SafetyCoverage.Availability> =
        sourceStates.mapNotNull { (source, state) ->
            val s = SafetyCoverage.Source.entries.firstOrNull { it.name == source }
            val a = SafetyCoverage.Availability.entries.firstOrNull { it.name == state }
            if (s != null && a != null) s to a else null
        }.toMap()
}
