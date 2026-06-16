package dev.mascwa.pulse.data.safety

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
)
