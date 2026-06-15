package dev.mascwa.pulse.data.space

import kotlinx.serialization.Serializable

@Serializable
data class SpaceAlert(
    val title: String,
    val issued: String,
    val message: String,
)

@Serializable
data class SpaceWeather(
    val kp: Double?,                       // latest planetary K-index
    val kpSeries: List<Double> = emptyList(),
    val solarWindSpeed: Double? = null,    // km/s
    val bz: Double? = null,                // nT (negative = geoeffective)
    val stormLevel: String = "None",       // NOAA G-scale label
    val auroraChance: String = "Low",
    val alerts: List<SpaceAlert> = emptyList(),
    val updatedEpochMs: Long = System.currentTimeMillis(),
) {
    companion object {
        fun stormLevelForKp(kp: Double?): String = when {
            kp == null -> "—"
            kp >= 9 -> "G5 Extreme"
            kp >= 8 -> "G4 Severe"
            kp >= 7 -> "G3 Strong"
            kp >= 6 -> "G2 Moderate"
            kp >= 5 -> "G1 Minor"
            else -> "None"
        }

        fun auroraForKp(kp: Double?): String = when {
            kp == null -> "—"
            kp >= 7 -> "Very high — visible at mid-latitudes"
            kp >= 5 -> "High — possible at higher latitudes"
            kp >= 4 -> "Moderate — high latitudes"
            else -> "Low"
        }
    }
}
