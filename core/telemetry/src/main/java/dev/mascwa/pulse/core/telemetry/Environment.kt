package dev.mascwa.pulse.core.telemetry

import kotlin.math.roundToInt

/**
 * The real world the operator is standing in, distilled to what the S.P.E.C.I.A.L. game can react to.
 * This is the bridge that makes the game "bleed into reality": the on-device layer builds an [EnvContext]
 * from live device telemetry (light, barometer/altitude, motion, battery, clock) + the weather service
 * (outdoor temperature — the Pixel has no user-space ambient-temp sensor), and the pure engine bends stat
 * checks by it. Everything is nullable/defaulted so a partial reading still yields sensible neutral maths.
 *
 * All logic here is pure and deterministic → CI-testable. [Environment.effects] is the single source of
 * truth; [statBonus]/[tags]/[describe] are views over it.
 */
data class EnvContext(
    /** Outdoor air temperature in °C (from the weather API; null if unknown). */
    val outdoorTempC: Double? = null,
    /** Daylight — weather `isDay`, or a clock fallback. Drives the night/day split. */
    val isDay: Boolean = true,
    /** Ambient light in lux (light sensor); a strong signal for dark/bright even without weather. */
    val lightLux: Float? = null,
    /** Barometric pressure in hPa (may be null on devices without a barometer). */
    val pressureHpa: Float? = null,
    /** Barometric altitude in metres (null without a barometer). */
    val altitudeM: Float? = null,
    /**
     * Movement INTENSITY — ~0 at rest, rising with sustained motion (a smoothed deviation of the accel
     * magnitude from 1 g, NOT the raw ~1 g magnitude), so a still phone can't be read as "moving".
     */
    val movement: Float? = null,
    /** Battery level 0..100 (null if unknown). */
    val batteryPct: Int? = null,
    /** Whether the device is charging (a low-battery penalty lifts while charging). */
    val charging: Boolean = false,
    /** Hour of day 0..23 (local). */
    val hourOfDay: Int = 12,
    /** Whether the device currently has network. */
    val online: Boolean = true,
)

/** A single real-world modifier to a stat check: [delta] to [stat], with a short human [reason]. */
data class EnvEffect(val stat: Special, val delta: Int, val reason: String)

object Environment {
    // Temperature bands (°C) — exposure taxes ENDURANCE.
    const val FRIGID_C = -5.0
    const val COLD_C = 5.0
    const val HOT_C = 32.0
    const val SCORCHING_C = 40.0

    // Light (lux) — night vs bright daylight.
    const val DARK_LUX = 10f
    const val BRIGHT_LUX = 3000f

    // Altitude (m) — thin air taxes ENDURANCE.
    const val HIGH_ALT_M = 1500f

    // Motion (g) — moving helps AGILITY but hurts PERCEPTION focus.
    const val MOVING_INTENSITY = 0.09f  // smoothed movement intensity at/above this ≈ on the move

    // Battery — a failing device is bad luck.
    const val LOW_BATT = 15

    /**
     * The full set of active real-world modifiers for [env]. Deterministic; empty when the world is
     * neutral or unknown. Effects stack (e.g. frigid + thin air = ENDURANCE −3).
     */
    fun effects(env: EnvContext): List<EnvEffect> {
        val out = mutableListOf<EnvEffect>()

        env.outdoorTempC?.let { t ->
            val d = t.roundToInt()
            when {
                t <= FRIGID_C -> out += EnvEffect(Special.ENDURANCE, -2, "Frigid ${d}°")
                t <= COLD_C -> out += EnvEffect(Special.ENDURANCE, -1, "Cold ${d}°")
                t >= SCORCHING_C -> out += EnvEffect(Special.ENDURANCE, -2, "Scorching ${d}°")
                t >= HOT_C -> out += EnvEffect(Special.ENDURANCE, -1, "Hot ${d}°")
            }
        }

        val dark = !env.isDay || (env.lightLux != null && env.lightLux <= DARK_LUX)
        if (dark) {
            out += EnvEffect(Special.PERCEPTION, -1, "Darkness")
            out += EnvEffect(Special.AGILITY, +1, "Cover of night")
        } else if (env.lightLux != null && env.lightLux >= BRIGHT_LUX) {
            out += EnvEffect(Special.PERCEPTION, +1, "Bright daylight")
        }

        if (env.altitudeM != null && env.altitudeM >= HIGH_ALT_M) {
            out += EnvEffect(Special.ENDURANCE, -1, "Thin air")
        }

        if (env.movement != null && env.movement >= MOVING_INTENSITY) {
            out += EnvEffect(Special.AGILITY, +1, "On the move")
            out += EnvEffect(Special.PERCEPTION, -1, "Unsteady")
        }

        if (env.batteryPct != null && env.batteryPct <= LOW_BATT && !env.charging) {
            out += EnvEffect(Special.LUCK, -1, "Power failing")
        }

        return out
    }

    /** Net modifier the real world applies to a check gated by [s] (sum of matching [effects]). */
    fun statBonus(env: EnvContext, s: Special): Int = effects(env).filter { it.stat == s }.sumOf { it.delta }

    /**
     * Coarse condition tags for the current world — useful for biasing encounter selection toward
     * fitting content (a blizzard scene when it's frigid, a night ambush after dark).
     */
    fun tags(env: EnvContext): Set<String> {
        val out = mutableSetOf<String>()
        env.outdoorTempC?.let { t ->
            when {
                t <= FRIGID_C -> out += "frigid"
                t <= COLD_C -> out += "cold"
                t >= SCORCHING_C -> out += "scorching"
                t >= HOT_C -> out += "hot"
            }
        }
        val dark = !env.isDay || (env.lightLux != null && env.lightLux <= DARK_LUX)
        out += if (dark) "night" else "day"
        if (env.altitudeM != null && env.altitudeM >= HIGH_ALT_M) out += "high_altitude"
        if (env.movement != null && env.movement >= MOVING_INTENSITY) out += "moving"
        if (env.batteryPct != null && env.batteryPct <= LOW_BATT && !env.charging) out += "low_power"
        if (!env.online) out += "offline"
        return out
    }

    /** One-line labels for the UI, e.g. "END −2 · Frigid -8°". */
    fun describe(env: EnvContext): List<String> = effects(env).map { e ->
        val sign = if (e.delta >= 0) "+" else "−"
        "${e.stat.display.take(3)} $sign${kotlin.math.abs(e.delta)} · ${e.reason}"
    }
}
