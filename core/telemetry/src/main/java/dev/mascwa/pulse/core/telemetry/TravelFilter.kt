package dev.mascwa.pulse.core.telemetry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The running travel anchor + the metres a new fix contributed. [anchorLat]/[anchorLon] null = no anchor yet. */
data class TravelStep(val addedM: Double, val anchorLat: Double?, val anchorLon: Double?)

/**
 * Turns a stream of GPS fixes into honest "distance walked" — the maths behind the game's travel tracker,
 * pure + CI-tested. The naive "sum every delta" approach counts a stationary phone's GPS wander as walking
 * (the reported bug: distance climbs while you sit still). Instead we keep an ANCHOR and only count movement
 * once a fix clears an uncertainty-sized radius from it, so jitter never accrues but real walking does.
 */
object TravelFilter {
    const val MIN_STEP_M = 12.0         // a step must be at least this far, even with a pristine fix
    const val MAX_STEP_M = 250.0        // farther than this between fixes is a glitch/teleport, not a step
    const val MAX_ACCURACY_M = 35.0     // fixes worse than this are too noisy to use at all
    const val DEFAULT_ACCURACY_M = 20.0 // step threshold when a fix reports no accuracy

    /** Great-circle metres between two lat/lon points (self-contained — core has no app Geo dep). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Fold a new fix into the running (anchor, distance). Returns the metres to ADD (0 if none) and the new
     * anchor. [accuracyM] is the fix's horizontal accuracy in metres; null = unknown (treated as
     * [DEFAULT_ACCURACY_M]). A fix must move farther than the GPS uncertainty (with a hard [MIN_STEP_M]
     * floor) to count — so a stationary phone's wander adds nothing.
     */
    fun step(anchorLat: Double?, anchorLon: Double?, lat: Double, lon: Double, accuracyM: Double?): TravelStep {
        // Hopelessly noisy fix (indoors these are often 30–100 m): ignore it, keep the old anchor.
        if (accuracyM != null && accuracyM > MAX_ACCURACY_M) return TravelStep(0.0, anchorLat, anchorLon)
        // No anchor yet: set it here, count nothing.
        if (anchorLat == null || anchorLon == null) return TravelStep(0.0, lat, lon)
        val d = distanceMeters(anchorLat, anchorLon, lat, lon)
        val threshold = maxOf(MIN_STEP_M, accuracyM ?: DEFAULT_ACCURACY_M)
        return when {
            d > MAX_STEP_M -> TravelStep(0.0, lat, lon)       // teleport/glitch: re-anchor, don't count
            d >= threshold -> TravelStep(d, lat, lon)          // real movement
            else -> TravelStep(0.0, anchorLat, anchorLon)      // within the jitter radius: ignore
        }
    }
}
