package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.telemetry.SatellitePasses
import dev.mascwa.pulse.core.telemetry.SolarDay
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.space.SpaceWeather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Today in the sky" — a compact, honest, keyless digest built from data we
 * already have: sun rise/set, offline moon phase, naked-eye planets up now,
 * the Kp/aurora outlook, and where the ISS is in your sky right now.
 */
object SkyDigest {

    /**
     * How old a fetched ISS position may be before it stops supporting any claim about your sky.
     *
     * ⚠️ The arithmetic is the whole justification. The ground point moves about **416 km every
     * minute** — measured by propagating a real element set either side of a live sample — against
     * the 1,200 km test below. One minute of age is already a third of the threshold; five, which
     * is what the repository's cache allows, is 2,081 km, further than the range over which the
     * question has an answer at all. So past this the line is dropped rather than qualified: a
     * hedge on a number that wrong is still a number on screen.
     *
     * This only governs the *fetched* position. A [SatellitePasses.Sighting] is propagated to the
     * moment it is read and has no age.
     */
    const val FETCHED_FIX_USABLE_MS = 60_000L

    /**
     * How close the ISS ground point has to be for the fetched fallback to mention it.
     *
     * At this range the station is about 13 degrees above the horizon — genuinely in your sky, and
     * the same figure the pass search treats as the practical horizon.
     */
    private const val NEAR_GROUND_RANGE_KM = 1200.0

    /**
     * @param sighting where the ISS actually is, propagated on the device to [nowMs]. When present
     *   it is authoritative and the fetched position is not consulted at all.
     */
    fun lines(
        orbital: OrbitalData,
        space: SpaceWeather?,
        lat: Double?,
        lon: Double?,
        sighting: SatellitePasses.Sighting? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val out = mutableListOf<String>()

        orbital.sun?.let { s ->
            // Above the Arctic Circle the feed answers with a 1970 sentinel rather than a time, and
            // the two polar cases differ by one second. Reading them as clock times printed
            // "Sunrise 01:00 · Sunset 01:00" on a day the Sun never set — while the observatory
            // screen, working from the on-device Ephemeris, correctly said it does not set at all.
            when (val day = SolarDay.classify(s.sunriseEpochMs, s.sunsetEpochMs, s.dayLengthSec)) {
                SolarDay.Kind.NORMAL -> {
                    val rise = timeOrNull(s.sunriseEpochMs)
                    val set = timeOrNull(s.sunsetEpochMs)
                    if (rise != null || set != null) {
                        out += "☀️ Sunrise ${rise ?: "—"} · Sunset ${set ?: "—"}"
                    }
                }
                SolarDay.Kind.MIDNIGHT_SUN -> SolarDay.describe(day)?.let { out += "☀️ $it" }
                SolarDay.Kind.POLAR_NIGHT -> SolarDay.describe(day)?.let { out += "🌑 $it" }
                // Nothing trustworthy to say, so say nothing — the line simply does not appear.
                SolarDay.Kind.UNKNOWN -> Unit
            }
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

        issLine(orbital, lat, lon, sighting, nowMs)?.let { out += it }

        return out
    }

    /**
     * What to say about the ISS, or nothing.
     *
     * Prefers the propagated [sighting], which knows how high the station is and whether sunlight
     * is falling on it — the two facts that decide whether there is any point going outside. The
     * fetched position is a fallback with neither, and is used only while it is fresh enough to
     * mean anything.
     */
    private fun issLine(
        orbital: OrbitalData,
        lat: Double?,
        lon: Double?,
        sighting: SatellitePasses.Sighting?,
        nowMs: Long,
    ): String? {
        if (sighting != null) {
            // Below the practical horizon there is nothing to say, whatever the sky is doing.
            if (!sighting.worthLookingUp) return null
            // The compass label stays as a bearing abbreviation — "up to the SE" is how a direction
            // is given, where "up to the se" reads as a typo.
            val where = "${sighting.look.altitudeDeg.roundToInt()}° up to the ${sighting.look.cardinal}"
            return when (sighting.kind) {
                SatellitePasses.PassKind.VISIBLE ->
                    "🛰️ ISS overhead now — $where, sunlit and naked-eye"
                // Up there, lit, and completely lost in the glare. Saying "overhead" without this
                // sends somebody outside to look at a bright empty sky.
                SatellitePasses.PassKind.DAYLIGHT ->
                    "🛰️ ISS is $where — too bright to see it"
                SatellitePasses.PassKind.ECLIPSED ->
                    "🛰️ ISS is $where — in Earth's shadow, nothing to see"
            }
        }

        if (lat == null || lon == null) return null
        val iss = orbital.iss ?: return null
        // A fix with no timestamp is a cache entry written before the field was parsed; its age is
        // unknown, and unknown is not the same as fresh.
        if (iss.timestampMs <= 0L) return null
        if (nowMs - iss.timestampMs > FETCHED_FIX_USABLE_MS) return null
        val km = Geo.distanceMeters(lat, lon, iss.latitude, iss.longitude) / 1000.0
        if (km >= NEAR_GROUND_RANGE_KM) return null
        return "🛰️ ISS passing near — ${km.roundToInt()} km from its ground point"
    }

    private fun timeOrNull(epochMs: Long?): String? {
        if (epochMs == null || epochMs <= 0) return null
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
