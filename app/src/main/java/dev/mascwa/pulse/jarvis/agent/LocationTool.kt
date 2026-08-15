package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.weather.LocationProvider

/**
 * Lets J.A.R.V.I.S. answer "where am I?" — a GPS-first, offline-capable fix (reverse-geocoded to a place
 * name when online, raw coordinates otherwise). Read-only; needs the location permission.
 */
class LocationTool(private val location: LocationProvider) : JarvisTool {
    override val name = "location"
    override val usage = "location — your current coordinates and place (read-only)"

    override suspend fun run(arg: String): String {
        if (!location.hasPermission()) return "Location permission isn't granted."
        val loc = runCatching { location.current() }.getOrNull()
            ?: return "I couldn't get a fix — is location turned on?"
        return "You're at %.4f, %.4f — %s.".format(loc.latitude, loc.longitude, loc.name)
    }
}
