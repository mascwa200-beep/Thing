package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.telemetry.Ephemeris
import kotlinx.serialization.Serializable

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
data class Planet(
    val name: String,
    val altitudeDeg: Double,   // above horizon if > 0
    val azimuthDeg: Double,    // from true north, clockwise
    val magnitude: Double,     // lower = brighter
    val aboveHorizon: Boolean,
)

@Serializable
data class OrbitalData(
    val iss: IssPosition? = null,
    val sun: SunTimes? = null,
    val moon: MoonInfo,
    val planets: List<Planet> = emptyList(),
    val neos: List<NeoObject> = emptyList(),
    val neoHazardousCount: Int = 0,
    /**
     * True when the near-Earth-object fetch failed, so [neos] being empty says nothing.
     *
     * Defaulted so a result cached before this field existed still decodes, and defaulted to
     * `false` because those cached entries were written from successful fetches.
     */
    val neosUnavailable: Boolean = false,
    val updatedEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Offline moon phase — still no network, now with the real Moon.
 *
 * This used to be a mean-synodic model: a fixed 29.530589-day cycle counted from one reference new
 * moon. The Moon does not keep that schedule — its orbit is eccentric and the Sun perturbs it — so
 * the error was measurable. Sampled every six hours across two years against the truncated Meeus
 * series in [Ephemeris]: illumination was off by 2.5 percentage points on average and up to 9.3,
 * and the phase age by up to 0.33 days. In practice that meant showing "39% lit" on a night the
 * Moon was actually 48% lit.
 *
 * [Ephemeris.moonPhase] is checked against the JPL DE421 ephemeris and is equally offline, so this
 * is now a thin adapter onto it.
 */
object MoonPhase {
    fun at(epochMs: Long = System.currentTimeMillis()): MoonInfo {
        val phase = Ephemeris.moonPhase(epochMs)
        return MoonInfo(phase.name, phase.illuminatedFraction, phase.emoji)
    }
}
