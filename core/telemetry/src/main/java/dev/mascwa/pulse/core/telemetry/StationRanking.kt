package dev.mascwa.pulse.core.telemetry

/**
 * How a "stations near you" list should actually be ordered.
 *
 * ⚠️ **Raw distance is the wrong sort for this list, and the measurement is what says so.** Radio
 * Browser publishes `clickcount` and `votes` on every station in the response; the app parsed
 * neither and ordered a 200 km geo search purely by haversine distance. Measured against a live New
 * York payload of 181 stations: the thirty shown spanned **0.20 km to 4.31 km** — a range over
 * which "nearer" carries no meaning to a listener, since every one of them is the same city — while
 * eight of the thirty had **zero** clicks ever, the most-listened station in the visible list had
 * ten, and a station with **193 clicks and 178,774 votes** sat at 5.03 km, which is distance-rank
 * 35 and therefore cut entirely. Six stations with fifty or more clicks were cut the same way.
 *
 * So the list was sorted by a figure that does not discriminate, and the figure that does was
 * thrown away. The app's own other station lists already know this: both the country browse and the
 * name search ask the server for `order=clickcount&reverse=true`. The geo query was the inconsistent
 * one.
 *
 * The fix is **not** to sort by popularity instead — that would put a station across the region
 * above the one down the road and stop the list being local at all. Distance is banded: inside a
 * band it is treated as carrying no information (which the New York spread shows it does not), and
 * popularity orders within it; across bands, nearer still wins outright. In a dense city that means
 * the whole visible list is one band and the stations people actually listen to rise; in a sparse
 * region the bands separate and the nearest transmitter is still first.
 */
object StationRanking {

    /**
     * The width over which "how far away" stops being a meaningful difference between two local
     * stations, in metres.
     *
     * Ten kilometres is chosen to be a little wider than the measured spread of a dense-city result
     * (4.31 km), so that a city collapses to a single band by construction rather than by luck,
     * while a genuinely distant station in a sparse region still sorts behind a near one. It is
     * deliberately coarse: a finer band would re-admit the noise this exists to remove.
     */
    const val BAND_METERS = 10_000.0

    /**
     * One station as far as ordering is concerned. [id] is opaque here — the caller's own stream URL
     * or name — and exists only so a test can name what came back.
     */
    data class Candidate(
        val id: String,
        val distanceMeters: Double,
        val clicks: Int = 0,
        val votes: Int = 0,
    )

    /**
     * Which distance band a station falls in.
     *
     * ⚠️ **An unknown distance sorts LAST, not first**, and getting that backwards is easy: the
     * obvious guard `if (!isFinite()) return 0` reads as defensive and quietly promotes a station
     * whose coordinates could not be worked out to the top of a list headed "near you". NaN and
     * infinity therefore band to [Int.MAX_VALUE]. A negative distance is impossible from a
     * haversine and can only be a data error, so it is clamped to the nearest band rather than
     * given a negative one that would outrank a station genuinely underfoot.
     *
     * ⚠️ Note that plain truncation does **not** save you here: `Double.NaN.toInt()` and
     * `(-1.0 / BAND_METERS).toInt()` are both `0` in Kotlin, so a test written around small
     * absurd values passes whether this guard exists or not. It takes a large negative, or an
     * infinity, to tell the two apart.
     */
    fun band(distanceMeters: Double): Int {
        if (distanceMeters.isNaN()) return Int.MAX_VALUE
        if (distanceMeters <= 0.0) return 0
        val bands = distanceMeters / BAND_METERS
        return if (bands >= Int.MAX_VALUE.toDouble()) Int.MAX_VALUE else bands.toInt()
    }

    /**
     * Nearest band first; inside a band the most-listened first, then the best-voted, and only then
     * the physically nearest as a stable final tiebreak.
     *
     * ⚠️ [limit] is applied **after** ordering, which is the whole point — the audit's excluded
     * 193-click station was cut by a top-30 taken over the wrong order, not by the limit itself.
     */
    fun order(candidates: List<Candidate>, limit: Int = Int.MAX_VALUE): List<Candidate> {
        if (limit <= 0) return emptyList()
        return candidates
            .sortedWith(
                compareBy<Candidate> { band(it.distanceMeters) }
                    .thenByDescending { it.clicks }
                    .thenByDescending { it.votes }
                    .thenBy { it.distanceMeters },
            )
            .take(limit)
    }
}
