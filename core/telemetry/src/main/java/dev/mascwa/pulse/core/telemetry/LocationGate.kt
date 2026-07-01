package dev.mascwa.pulse.core.telemetry

/**
 * Physical-presence gating for the Pokémon-Go map — you must actually BE at a real location to trade or
 * talk there. Pure + CI-tested; the on-device layer feeds it the live GPS fix and the target [GameLocation].
 * Reuses [TravelFilter.distanceMeters] (same module, no app Geo dependency), so the reach test matches the
 * distance maths the travel tracker already uses.
 */
object LocationGate {
    /** How close (metres) you must be to a location to interact — matches the map's "reached" radius. */
    const val REACH_RADIUS_M = 60.0

    /** Great-circle metres from the player to [location], or null when the player's position is unknown. */
    fun distanceTo(playerLat: Double?, playerLon: Double?, location: GameLocation): Double? {
        if (playerLat == null || playerLon == null) return null
        return TravelFilter.distanceMeters(playerLat, playerLon, location.lat, location.lon)
    }

    /** True only when the player is physically within [radiusM] of [location] (false if position unknown). */
    fun isAtLocation(
        playerLat: Double?,
        playerLon: Double?,
        location: GameLocation,
        radiusM: Double = REACH_RADIUS_M,
    ): Boolean {
        val d = distanceTo(playerLat, playerLon, location) ?: return false
        return d <= radiusM
    }

    /** A short "walk here" hint for a location out of reach — "120 m away" / "1.4 km away", or null if at it. */
    fun reachHint(playerLat: Double?, playerLon: Double?, location: GameLocation, radiusM: Double = REACH_RADIUS_M): String? {
        val d = distanceTo(playerLat, playerLon, location) ?: return "location unknown"
        if (d <= radiusM) return null
        return if (d < 1_000.0) "${d.toInt()} m away" else "%.1f km away".format(d / 1_000.0)
    }
}
