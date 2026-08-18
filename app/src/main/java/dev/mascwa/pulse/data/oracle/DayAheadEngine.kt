package dev.mascwa.pulse.data.oracle

import dev.mascwa.pulse.core.telemetry.DayAhead
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.di.AppContainer
import dev.mascwa.pulse.feature.weather.WeatherFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * How far ahead the notification path looks for a departure worth interrupting for.
     *
     * A departure is `start − travel − buffer`, so a commitment can only be imminent if it starts
     * within roughly the imminent window plus the journey. Three hours covers any journey anyone
     * wants a fifteen-minute worker nagging them about, and bounds the gate below.
     */
    private const val ALERT_LOOKAHEAD_MS = 3 * 3_600_000L

    suspend fun plan(
        container: AppContainer,
        settings: AppSettings,
        maxRouted: Int = MAX_ROUTED,
    ): List<DayAhead.Beat> {
        val now = System.currentTimeMillis()
        val horizon = now + HORIZON_HOURS * 3_600_000L

        // Times come from the calendar; coordinates come from the geocoded objectives view of the
        // same events. They join exactly rather than by title, because an objective's id is
        // "cal_<EVENT_ID>_<BEGIN>" — precisely the two fields a CalEvent already carries.
        // Off the caller's dispatcher: upcoming() is a blocking ContentResolver query and is not
        // suspend, and this runs from viewModelScope on the main thread.
        val events = withContext(Dispatchers.IO) {
            runCatching { container.calendarRepository.upcoming(now) }.getOrDefault(emptyList())
        }.filter { it.startMs < horizon }
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
        var budget = maxRouted

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
            // ⚠️ A route to a place the road network does not reach still comes back `Ok` with a
            // full duration — London to New York returns a confident 28 hours to a point snapped
            // onto Portugal. Here that number would become a "leave at" alert with no map to reveal
            // the gap, so an unreachable route is refused and the caller falls through to the
            // straight-line estimate, which already labels itself as rough.
            val est = r?.takeIf { it.reachesDestination }?.let {
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

    /**
     * The imminent departure for the board, as `(sentence, stable key)`, or null.
     *
     * ⚠️ **The gate is the point of this function.** It runs on every background pass, and the full
     * [plan] geocodes (rate-limited) and routes (a community-hosted endpoint). So it first asks the
     * calendar — a local content-provider query, no network — whether anything is even starting
     * inside [ALERT_LOOKAHEAD_MS], and returns on the spot when nothing is. Most passes stop there
     * and cost nothing, which is what makes this affordable at a fifteen-minute cadence.
     *
     * Only one journey is routed, because only the next one can be imminent.
     *
     * The key names the commitment rather than the sentence: the sentence counts down, and keying on
     * it would re-alert every pass all the way to the door.
     */
    suspend fun imminentDeparture(container: AppContainer, settings: AppSettings): Pair<String, String>? {
        val now = System.currentTimeMillis()
        val soon = withContext(Dispatchers.IO) {
            runCatching {
                container.calendarRepository.upcoming(now, horizonMs = ALERT_LOOKAHEAD_MS)
            }.getOrDefault(emptyList())
        }.any { !it.allDay && it.startMs > now }
        if (!soon) return null

        val beats = runCatching { plan(container, settings, maxRouted = 1) }.getOrDefault(emptyList())
        val beat = urgentDeparture(beats, now) ?: return null
        val subject = beat.subjectId ?: return null
        return beat.title to subject
    }
}
