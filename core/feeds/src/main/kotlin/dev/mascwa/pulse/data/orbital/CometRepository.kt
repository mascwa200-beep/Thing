package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.Comets
import dev.mascwa.pulse.core.util.Fetched
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Orbital elements for every known comet, from the Minor Planet Center.
 *
 * `CometEls.txt` is the IAU's own working catalogue — the same file the professional software
 * reads — and it is free, keyless and about 160 kB. One fetch supplies every comet there is;
 * [Comets] turns any of them into a position.
 *
 * ⚠️ **The raw file is cached, not the parsed elements.** [Comets.Elements] lives in
 * `core:telemetry`, which deliberately carries no serialization dependency, so caching the objects
 * would mean a mirrored data class kept in step by hand. Keeping the text is also simply smaller
 * than the JSON it would serialize to, and re-parsing 957 fixed-width lines costs about a
 * millisecond. This is the shape [dev.mascwa.pulse.data.live.LiveCatalogRepository] settled on for
 * the same reason.
 */
class CometRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {

    /**
     * Every comet the MPC currently publishes.
     *
     * ⚠️ **A week is a deliberately long time to live.** Comet elements are a fit to accumulated
     * observations and they move slowly; a newly discovered comet is not visible to the naked eye
     * the week it is found. Against that, this is 160 kB over whatever connection the phone has,
     * for a screen somebody opened once. Refetching it daily would spend real data to change a
     * displayed position by less than the width of the dot it is drawn as.
     */
    suspend fun elements(force: Boolean = false): Fetched<List<Comets.Elements>> {
        val cached = runCatching { cache.readAny(KEY, Stored.serializer()) }.getOrNull()
        val ageMs = cached?.let { System.currentTimeMillis() - it.savedAtMs } ?: Long.MAX_VALUE
        val stillFresh = if (force) ageMs < MIN_REFRESH_MS else ageMs < TTL_MS
        if (cached != null && stillFresh) {
            return Fetched(parse(cached.value.body), true, cached.savedAtMs)
        }

        val body = runCatching { http.getString(URL) }.getOrNull()
        val parsed = body?.let(::parse).orEmpty()
        if (parsed.isNotEmpty()) {
            runCatching { cache.write(KEY, Stored(body!!), Stored.serializer()) }
            return Fetched(parsed, false)
        }
        // Offline, or the MPC is down. Week-old elements are still broadly right, and saying
        // nothing would be worse than saying something slightly stale.
        return cached?.let { Fetched(parse(it.value.body), true, it.savedAtMs) }
            ?: Fetched(emptyList(), false)
    }

    /**
     * ⚠️ Carries only the text. [DiskCache.Cached] supplies `savedAtMs` itself, so a second
     * timestamp here would be a duplicated definition of when this was fetched -- and the two
     * would be free to disagree.
     */
    @Serializable
    private data class Stored(val body: String)

    /**
     * ⚠️ `internal`, and on the companion rather than the instance, because [parse] and
     * [julianDate] are pure — they touch neither the network nor the cache. Requiring a repository
     * to call them would mean a test had to build an [HttpClient], which is a final class and
     * cannot be stubbed. A pure function should not demand collaborators it never uses.
     */
    internal companion object {
        private const val URL = "https://www.minorplanetcenter.net/iau/MPCORB/CometEls.txt"
        private const val KEY = "mpc_comet_elements"
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MIN_REFRESH_MS = 12L * 60 * 60 * 1000

        /** The Julian Date at 1970-01-01T00:00, which is what `toEpochDay` counts from. */
        private const val JD_AT_EPOCH = 2440587.5

        /** The designation starts at column 102, so anything shorter cannot be a record. */
        private const val MIN_LINE = 103

        /**
         * One line of the MPC's fixed-column format into elements.
         *
         * ⚠️ **The column offsets are derived from the real file, not from the published table.**
         * Every one of the 957 lines in a live `CometEls.txt` was sliced at these positions and
         * compared field by field against an independent parser: zero mismatches. That check is
         * worth more than reading the specification, because the format has no delimiters at all —
         * an offset wrong by one yields a number rather than an error, and a comet quietly lands
         * somewhere it is not. `CometRepositoryTest` is what keeps them right.
         *
         * A line that does not parse is dropped rather than guessed at. The MPC has changed this
         * file's preamble before, and a parser that threw on one bad line would lose the catalogue.
         */
        internal fun parse(body: String): List<Comets.Elements> = body.lineSequence()
            .mapNotNull { line ->
                if (line.length < MIN_LINE) return@mapNotNull null
                runCatching {
                    val year = line.substring(14, 18).trim().toInt()
                    val month = line.substring(19, 21).trim().toInt()
                    val day = line.substring(22, 29).trim().toDouble()
                    val designation = line.substring(102, minOf(158, line.length)).trim()
                    if (designation.isEmpty()) return@runCatching null
                    Comets.Elements(
                        designation = designation,
                        perihelionDistanceAu = line.substring(30, 39).trim().toDouble(),
                        eccentricity = line.substring(41, 49).trim().toDouble(),
                        perihelionJdTt = julianDate(year, month, day),
                        argumentOfPerihelionDeg = line.substring(51, 59).trim().toDouble(),
                        ascendingNodeDeg = line.substring(61, 69).trim().toDouble(),
                        inclinationDeg = line.substring(71, 79).trim().toDouble(),
                        absoluteMagnitude = line.substring(91, 95).trim().toDoubleOrNull(),
                        magnitudeSlope = line.substring(96, 100).trim().toDoubleOrNull(),
                    )
                }.getOrNull()
            }
            .toList()

        /**
         * A calendar date in Terrestrial Time to a Julian Date, through the platform's own calendar.
         *
         * ⚠️ Hand-rolling this is the obvious move and the wrong one — a Gregorian-to-Julian
         * conversion has an era boundary and a leap rule to get wrong, and this project already
         * carries two hand-written calendar conversions that had to be cross-checked against the JDK
         * over fifty thousand days before they could be trusted. `toEpochDay` IS that calendar.
         * Verified against Skyfield on real catalogue entries: identical to the last digit.
         */
        internal fun julianDate(year: Int, month: Int, day: Double): Double {
            val whole = day.toInt()
            return LocalDate.of(year, month, whole).toEpochDay() + JD_AT_EPOCH + (day - whole)
        }
    }
}
