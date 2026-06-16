package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.space.SpaceWeather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Today in the sky" — a compact, honest, keyless digest built from data we
 * already have: sun rise/set, offline moon phase, naked-eye planets up now,
 * the Kp/aurora outlook, and whether the ISS sub-point is passing near you.
 * No pass-time prediction (that needs SGP4/TLE) — we never fabricate times.
 */
object SkyDigest {

    fun lines(orbital: OrbitalData, space: SpaceWeather?, lat: Double?, lon: Double?): List<String> {
        val out = mutableListOf<String>()

        orbital.sun?.let { s ->
            val rise = timeOrNull(s.sunriseEpochMs)
            val set = timeOrNull(s.sunsetEpochMs)
            if (rise != null || set != null) out += "☀️ Sunrise ${rise ?: "—"} · Sunset ${set ?: "—"}"
        }

        out += "${orbital.moon.emoji} ${orbital.moon.phaseName} · ${(orbital.moon.illumination * 100).roundToInt()}% lit"

        val visible = orbital.planets.filter { it.aboveHorizon }.sortedBy { it.magnitude }
        if (visible.isNotEmpty()) out += "🪐 Up now: " + visible.joinToString(", ") { it.name }

        space?.let { sw ->
            val pct = sw.auroraProbabilityPct
            if (pct != null) {
                out += "🌌 Aurora $pct% overhead" + (sw.kp?.let { " · Kp ${"%.1f".format(it)}" } ?: "")
            } else {
                sw.kp?.let { out += "🧲 Kp ${"%.1f".format(it)} · aurora ${sw.auroraChance}" }
            }
        }

        if (lat != null && lon != null) {
            orbital.iss?.let { iss ->
                val km = Geo.distanceMeters(lat, lon, iss.latitude, iss.longitude) / 1000.0
                if (km < 1200) out += "🛰️ ISS passing near — ${km.roundToInt()} km from its ground point"
            }
        }

        return out
    }

    private fun timeOrNull(epochMs: Long?): String? {
        if (epochMs == null || epochMs <= 0) return null
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
