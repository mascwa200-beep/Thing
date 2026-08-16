package dev.mascwa.pulse.data.oracle

import dev.mascwa.pulse.core.telemetry.DayAhead
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.di.AppContainer
import dev.mascwa.pulse.feature.weather.WeatherFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gathers what the day is made of and hands it to [DayAhead].
 *
 * Shaped after [OracleEngine.snapshot] and for the same reasons: every read is best-effort and every
 * failure mutes one input rather than costing the screen, and everything is fetched with
 * `force = false` so this adds reasoning to caches the app has already warmed rather than a fresh
 * round of network on a background pass.
 */
object DayAheadEngine {

    /**
     * How many journeys are worth asking the routing service about.
     *
     * Routing is a network call to a community-hosted endpoint, and a calendar can be arbitrarily
     * long. The next few departures are the ones anybody acts on; everything further out falls back
     * to the straight-line estimate, which is labelled as such all the way to the screen.
     */
    private const val MAX_ROUTED = 4

    /** How far ahead a "day ahead" reaches. Beyond tomorrow this stops being a plan and becomes a list. */
    private const val HORIZON_HOURS = 18

    /**
     * Every clock time on the timeline, in the reader's own zone.
     *
     * The single formatter for this feature: [DayAhead.plan] is handed it so the times inside a
     * beat's text go through it, and the screen calls it for the time beside the beat. One
     * implementation, so a column and the sentence next to it cannot disagree.
     *
     * ⚠️ The default zone rather than UTC — the core's own formatter is UTC and correct only in
     * London. `SimpleDateFormat` resolves the zone per call, so this also follows a daylight-saving
     * transition inside the horizon, which fixed-offset arithmetic would not.
     *
     * [Locale.US] fixes the digits to ASCII, matching the numbers the core writes into the same
     * sentence; a locale that renders its own numerals would make one line disagree with itself.
     */
    fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    suspend fun plan(container: AppContainer, settings: AppSettings): List<DayAhead.Beat> {
        val now = System.currentTimeMillis()
        val horizon = now + HORIZON_HOURS * 3_600_000L

        // Times come from the calendar; coordinates come from the geocoded objectives view of the
        // same events. They join exactly rather than by title, because an objective's id is
        // "cal_<EVENT_ID>_<BEGIN>" — precisely the two fields a CalEvent already carries.
        val events = runCatching { container.calendarRepository.upcoming(now) }.getOrDefault(emptyList())
            .filter { it.startMs < horizon }
        if (events.isEmpty()) return emptyList()

        val placed = runCatching { container.calendarObjectives.upcoming(2) }
            .getOrDefault(emptyList())
            .associateBy { it.id }

        val commitments = events.map { e ->
            val located = placed["cal_${e.id}_${e.startMs}"]
            DayAhead.Commitment(
                id = e.id.toString(),
                title = e.title,
                startMs = e.startMs,
                endMs = e.endMs,
                lat = located?.latitude,
                lon = located?.longitude,
                allDay = e.allDay,
            )
        }

        val here = runCatching { container.locationProvider.current() }.getOrNull()

        // The forecast, reduced to what a plan needs. `parseHourly` is the app's existing ISO reader,
        // used here exactly as OracleEngine uses it — the hour boundaries it produces are the device's
        // own, which is what stops this repeating the "tonight" bug that was computed against UTC.
        val hours: List<DayAhead.HourSlot> = runCatching {
            val saved = settings.savedLocations.firstOrNull()
            val lat = here?.latitude ?: saved?.latitude
            val lon = here?.longitude ?: saved?.longitude
            val name = here?.name ?: saved?.name ?: ""
            if (lat == null || lon == null) return@runCatching emptyList()
            container.weatherRepository.fetch(lat, lon, name, force = false).data.hourly
                .mapNotNull { h ->
                    val t = WeatherFormat.parseHourly(h.timeIso)?.time ?: return@mapNotNull null
                    DayAhead.HourSlot(
                        startMs = t,
                        precipProbability = h.precipProbability,
                        tempC = h.temperatureC,
                        windKmh = h.windKmh,
                    )
                }
        }.getOrDefault(emptyList())

        val topTask = runCatching {
            TaskBoard.pending(container.taskStore.all()).firstOrNull()?.title
        }.getOrNull()

        // Route only the journeys anybody will act on, nearest first, and remember what each cost so
        // the same question is never asked twice within one plan.
        val routed = HashMap<String, DayAhead.TravelEstimate?>()
        var budget = MAX_ROUTED

        suspend fun road(
            fromLat: Double?, fromLon: Double?, toLat: Double?, toLon: Double?,
        ): DayAhead.TravelEstimate? {
            if (fromLat == null || fromLon == null || toLat == null || toLon == null) return null
            val key = "$fromLat,$fromLon>$toLat,$toLon"
            routed[key]?.let { return it }
            if (routed.containsKey(key)) return null
            if (budget <= 0) return null
            budget--
            val r = runCatching { container.routingRepository.route(fromLat, fromLon, toLat, toLon) }.getOrNull()
            val est = r?.let {
                DayAhead.TravelEstimate(
                    seconds = it.durationSeconds.toLong(),
                    meters = it.distanceMeters,
                    source = DayAhead.TravelSource.ROAD,
                )
            }
            routed[key] = est
            return est
        }

        // Pre-resolve every journey the plan may ask about, because `DayAhead.plan` is pure and
        // cannot suspend. Road first, straight line where routing declined or the budget ran out.
        val toHere = HashMap<String, DayAhead.TravelEstimate>()
        commitments.filter { it.located && it.startMs > now }.forEach { c ->
            val est = road(here?.latitude, here?.longitude, c.lat, c.lon)
                ?: DayAhead.straightLineTravel(here?.latitude, here?.longitude, c.lat, c.lon)
            if (est != null) toHere[c.id] = est
        }

        val between = HashMap<String, DayAhead.TravelEstimate>()
        commitments.sortedBy { it.startMs }.zipWithNext().forEach { (a, b) ->
            if (!a.located || !b.located) return@forEach
            val est = road(a.lat, a.lon, b.lat, b.lon)
                ?: DayAhead.straightLineTravel(a.lat, a.lon, b.lat, b.lon)
            if (est != null) between["${a.id}>${b.id}"] = est
        }

        return DayAhead.plan(
            commitments = commitments,
            nowMs = now,
            hours = hours,
            topTask = topTask,
            travelTo = { toHere[it.id] },
            travelFrom = { a, b -> between["${a.id}>${b.id}"] },
            clock = ::clock,
        )
    }

    /**
     * The one beat worth interrupting for, or null.
     *
     * A departure you need to make shortly is exactly what the board's alert row exists for. Nothing
     * else here qualifies: a conflict later today is worth reading and not worth a buzz.
     */
    fun urgentDeparture(beats: List<DayAhead.Beat>, nowMs: Long): DayAhead.Beat? =
        beats.firstOrNull {
            it.kind == DayAhead.BeatKind.DEPART &&
                it.atMs - nowMs <= DayAhead.IMMINENT_MIN * 60_000L
        }
}
