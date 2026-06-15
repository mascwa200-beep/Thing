package dev.mascwa.pulse.data.orbital

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos

@Serializable
data class IssPosition(
    val latitude: Double,
    val longitude: Double,
    val altitudeKm: Double,
    val velocityKmh: Double,
)

@Serializable
data class SunTimes(
    val sunriseEpochMs: Long?,
    val sunsetEpochMs: Long?,
    val dayLengthSec: Long?,
)

@Serializable
data class MoonInfo(
    val phaseName: String,
    val illumination: Double,   // 0..1
    val emoji: String,
)

@Serializable
data class NeoObject(
    val name: String,
    val diameterMetersMax: Double?,
    val missDistanceKm: Double?,
    val velocityKmh: Double?,
    val hazardous: Boolean,
    val closeApproach: String?,
)

@Serializable
data class OrbitalData(
    val iss: IssPosition? = null,
    val sun: SunTimes? = null,
    val moon: MoonInfo,
    val neos: List<NeoObject> = emptyList(),
    val neoHazardousCount: Int = 0,
    val updatedEpochMs: Long = System.currentTimeMillis(),
)

/** Offline moon-phase computation (no network) — good for survival/offline mode. */
object MoonPhase {
    private const val SYNODIC = 29.530588853
    // Reference new moon: 2000-01-06 18:14 UTC.
    private const val REF_NEW_MOON_MS = 947182440000.0

    fun at(epochMs: Long = System.currentTimeMillis()): MoonInfo {
        val days = (epochMs - REF_NEW_MOON_MS) / 86_400_000.0
        var phase = (days / SYNODIC) % 1.0
        if (phase < 0) phase += 1.0
        val illumination = (1 - cos(2 * PI * phase)) / 2.0
        val name = when {
            phase < 0.03 || phase > 0.97 -> "New Moon"
            phase < 0.22 -> "Waxing Crescent"
            phase < 0.28 -> "First Quarter"
            phase < 0.47 -> "Waxing Gibbous"
            phase < 0.53 -> "Full Moon"
            phase < 0.72 -> "Waning Gibbous"
            phase < 0.78 -> "Last Quarter"
            else -> "Waning Crescent"
        }
        val emoji = when {
            phase < 0.03 || phase > 0.97 -> "🌑"
            phase < 0.22 -> "🌒"
            phase < 0.28 -> "🌓"
            phase < 0.47 -> "🌔"
            phase < 0.53 -> "🌕"
            phase < 0.72 -> "🌖"
            phase < 0.78 -> "🌗"
            else -> "🌘"
        }
        return MoonInfo(name, illumination, emoji)
    }
}
