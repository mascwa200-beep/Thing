package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.Sgp4
import dev.mascwa.pulse.core.telemetry.Tle
import dev.mascwa.pulse.core.util.Fetched
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/**
 * Orbital element sets from Celestrak — keyless, free, and the canonical public source.
 *
 * [Sgp4] and [dev.mascwa.pulse.core.telemetry.SatellitePasses] can predict a pass to the second,
 * but only against current elements: TLE age, not the arithmetic, is the real error term. So this
 * exists to keep them fresh without being a nuisance to a service that gives its data away.
 *
 * Deliberately no bundled fallback element set. A stale TLE produces a confidently wrong pass time,
 * which is worse than no time at all — with nothing cached the caller gets an empty list and the
 * surface says so plainly.
 */
class TleRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {

    /** The catalogue groups worth carrying. Each maps to a Celestrak GROUP query. */
    enum class Group(val id: String, val label: String) {
        /** Crewed stations — the ISS and Tiangong, the two things most people want to see. */
        STATIONS("stations", "Space stations"),

        /** The brightest objects in orbit: the ones a visible-pass prediction is actually for. */
        VISUAL("visual", "Brightest"),

        WEATHER("weather", "Weather satellites"),
        SCIENCE("science", "Science missions"),
        AMATEUR("amateur", "Amateur radio"),
    }

    /**
     * Elements for [group], newest first.
     *
     * Never throws: a failed fetch falls back to whatever is cached, and an empty list if there is
     * nothing cached either.
     */
    suspend fun elements(group: Group, force: Boolean = false): Fetched<List<Sgp4.Elements>> {
        val key = "tle_${group.id}"
        val cached = cache.readAny(key, Stored.serializer())

        // Honour the refresh floor even when the caller insists. Celestrak asks clients not to
        // re-request a group more often than roughly every two hours, and element sets do not
        // change meaningfully inside that window anyway.
        val ageMs = cached?.let { System.currentTimeMillis() - it.savedAtMs } ?: Long.MAX_VALUE
        val stillFresh = if (force) ageMs < MIN_REFRESH_MS else ageMs < TTL_MS
        if (cached != null && stillFresh) {
            return Fetched(parse(cached.value.text), true, cached.savedAtMs)
        }

        val text = runCatching {
            gate.withPermit { http.getString(URL_PREFIX + group.id + URL_SUFFIX) }
        }.getOrNull()

        // A truncated or error body must not overwrite good elements: a valid response is always
        // at least one complete three-line entry.
        val parsed = text?.let { parse(it) }.orEmpty()
        if (text != null && parsed.isNotEmpty()) {
            runCatching { cache.write(key, Stored(text), Stored.serializer()) }
            return Fetched(parsed, false)
        }
        // The fetch failed. Stale elements still beat nothing — a day-old TLE is good to seconds.
        return cached
            ?.let { Fetched(parse(it.value.text), true, it.savedAtMs) }
            ?: Fetched(emptyList(), false)
    }

    /** Every element set across [groups], de-duplicated by catalogue number. */
    suspend fun elements(
        groups: Collection<Group>,
        force: Boolean = false,
    ): Fetched<List<Sgp4.Elements>> {
        val results = groups.map { elements(it, force) }
        val merged = LinkedHashMap<Int, Sgp4.Elements>()
        for (result in results) {
            for (element in result.data) merged.putIfAbsent(element.noradId, element)
        }
        return Fetched(
            merged.values.toList(),
            results.all { it.fromCache },
            results.minOfOrNull { it.timestampEpochMs } ?: System.currentTimeMillis(),
        )
    }

    /** One satellite by catalogue number, or null if it is not in [group]. */
    suspend fun element(noradId: Int, group: Group = Group.STATIONS): Sgp4.Elements? =
        elements(group).data.firstOrNull { it.noradId == noradId }

    /**
     * Elements are DERIVED from the cached text, not stored as objects. [Sgp4.Elements] lives in
     * `core:telemetry`, which has no serialization dependency and should keep it; re-parsing the
     * text the server sent is cheap and keeps exactly one representation on disk.
     */
    private fun parse(text: String): List<Sgp4.Elements> =
        runCatching { Tle.parseBlock(text) }.getOrDefault(emptyList())

    @Serializable
    private data class Stored(val text: String)

    private companion object {
        const val URL_PREFIX = "https://celestrak.org/NORAD/elements/gp.php?GROUP="
        const val URL_SUFFIX = "&FORMAT=tle"

        /** Element sets stay usable for days, so a cold start should not touch the network. */
        const val TTL_MS = 12 * 60 * 60 * 1000L

        /** Celestrak's published request-rate guidance, applied even to a forced refresh. */
        const val MIN_REFRESH_MS = 2 * 60 * 60 * 1000L

        /** One gate per host, the same rule every other feed in this app follows. */
        val gate = Semaphore(2)
    }
}
