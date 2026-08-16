package dev.mascwa.pulse.core.telemetry

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The rest of your day, projected.
 *
 * Everything else in this app reasons about a single instant. [Oracle] answers "what matters right
 * now"; [TemporalReasoner] answers "what happened, and how long ago". Nothing looked forward — even
 * though the device already knows you have a nine o'clock, where the nine o'clock is, how long the
 * drive takes and that it will be raining at twenty past eight. This puts those four facts together
 * and says *leave at five past, take a coat.*
 *
 * The two outputs that justify the whole thing are the ones a calendar app cannot produce: **when to
 * leave**, which needs travel time the calendar does not have, and **an impossible gap** — two
 * commitments whose spacing is shorter than the journey between them, which is invisible until you
 * are already late for the second one.
 *
 * Pure and deterministic. The clock, the position, the forecast and the travel estimates are all
 * passed in, so CI holds every rule in here.
 */
object DayAhead {

    // ---- inputs ------------------------------------------------------------------------------

    /** How a journey time was arrived at. The difference is a difference in how much to trust it. */
    enum class TravelSource {
        /** A real road route from the routing service. */
        ROAD,

        /**
         * Straight-line distance inflated by a circuity factor and divided by an assumed speed.
         *
         * A guess, and labelled as one everywhere it surfaces. It exists because the routing service
         * is a community-hosted best-effort endpoint: when it is busy this is the normal path, not a
         * rare fallback, and quietly presenting it as a road time is exactly the kind of unearned
         * confidence this app has spent a lot of effort removing.
         */
        STRAIGHT_LINE,
    }

    data class TravelEstimate(val seconds: Long, val meters: Double, val source: TravelSource)

    /** One thing already in the day. [lat]/[lon] are null when the event carries no usable location. */
    data class Commitment(
        val id: String,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val lat: Double? = null,
        val lon: Double? = null,
        val allDay: Boolean = false,
    ) {
        val located: Boolean get() = lat != null && lon != null
    }

    /** One hour of forecast, reduced to what a plan actually needs. */
    data class HourSlot(
        val startMs: Long,
        val precipProbability: Int? = null,
        val tempC: Double? = null,
        val windKmh: Double? = null,
    )

    // ---- outputs -----------------------------------------------------------------------------

    enum class BeatKind { DEPART, CONFLICT, EVENT, FOCUS, DAY_END }

    /**
     * How much weight a beat can carry.
     *
     * FIRM is a time the calendar itself states. ESTIMATED rests on a real road route. ROUGH rests on
     * a straight-line guess. A beat never reports higher than its weakest input.
     */
    enum class Confidence { FIRM, ESTIMATED, ROUGH }

    /** One entry on the timeline. [sources] names what produced it, as [Insight] does. */
    data class Beat(
        val atMs: Long,
        val kind: BeatKind,
        val title: String,
        val detail: String,
        val confidence: Confidence,
        val sources: List<String> = emptyList(),
        /**
         * The commitment this beat is about, where it is about one — for a conflict, the first of
         * the pair. Null for beats about the shape of the day rather than a thing in it.
         *
         * ⚠️ The only stable name a beat has. [atMs] is not one: a departure already past reports
         * `now`, so it moves on every pass, and [title] carries a live countdown. Anything that must
         * recognise the same beat twice — a notification deduplicating its alert, most of all — has
         * to key on this.
         */
        val subjectId: String? = null,
    )

    // ---- tunables ----------------------------------------------------------------------------

    /**
     * Margin added to every journey, for the part of arriving that is not driving — parking, the
     * walk from it, finding the room.
     *
     * Deliberately generous. The cost of leaving five minutes early is five minutes; the cost of
     * leaving five minutes late is the meeting.
     */
    const val ARRIVAL_BUFFER_MIN = 8L

    /** Within this, a departure stops being a plan and becomes something you need told now. */
    const val IMMINENT_MIN = 20L

    /** Shorter than this is a gap, not a window — not worth naming as time you could use. */
    const val FOCUS_MIN = 45L

    /** At or above this chance of precipitation, a departure is worth a warning. */
    const val WET_PROBABILITY = 45

    /**
     * Roads are longer than the straight line between their ends. About a third longer is the usual
     * figure for real networks, and erring high means the fallback tells you to leave too early
     * rather than too late.
     */
    const val CIRCUITY = 1.35

    /** Assumed door-to-door speed for the fallback, in km/h — town driving with its stops. */
    const val ASSUMED_KMH = 32.0

    private const val MINUTE = 60_000L

    // ---- travel ------------------------------------------------------------------------------

    /**
     * A journey time from coordinates alone, when no road route could be obtained.
     *
     * Null if either end is unknown — a missing estimate is honest and a fabricated one is not.
     */
    fun straightLineTravel(
        fromLat: Double?, fromLon: Double?,
        toLat: Double?, toLon: Double?,
    ): TravelEstimate? {
        if (fromLat == null || fromLon == null || toLat == null || toLon == null) return null
        if (!fromLat.isFinite() || !fromLon.isFinite() || !toLat.isFinite() || !toLon.isFinite()) return null
        val direct = Geodesy.distanceMeters(fromLat, fromLon, toLat, toLon)
        if (!direct.isFinite()) return null
        val meters = direct * CIRCUITY
        val seconds = (meters / (ASSUMED_KMH * 1000.0 / 3600.0)).roundToLong()
        return TravelEstimate(seconds, meters, TravelSource.STRAIGHT_LINE)
    }

    /** When to leave to arrive on time, buffer included. */
    fun leaveBy(startMs: Long, travel: TravelEstimate): Long =
        startMs - travel.seconds * 1000L - ARRIVAL_BUFFER_MIN * MINUTE

    private fun confidenceOf(travel: TravelEstimate) =
        if (travel.source == TravelSource.ROAD) Confidence.ESTIMATED else Confidence.ROUGH

    // ---- the plan ----------------------------------------------------------------------------

    /**
     * Build the timeline.
     *
     * [travelTo] and [travelFrom] are supplied by the caller rather than computed here — the core
     * stays pure, and the caller decides how much routing it is willing to pay for. Either may
     * return null, which suppresses the beats that depend on it rather than guessing.
     *
     * [clock] is injected for the same reason and one more: the core has no zone, and every time it
     * writes into a beat's text is read by a person. A caller rendering for a device passes a
     * formatter in the device's own zone — which is also DST-correct, where an offset arithmetic
     * would not be across a transition. The default is UTC, for tests.
     *
     * ⚠️ [Beat.atMs] stays true epoch throughout, deliberately: it is compared against a real clock
     * downstream and sorted on. Shifting times into a display zone on the way *in* would corrupt
     * both, which is why the zone enters as a formatter rather than as an offset.
     *
     * Commitments that have already ended are dropped; ones under way are kept, because "you are in
     * this until 15:00" shapes everything after it.
     */
    fun plan(
        commitments: List<Commitment>,
        nowMs: Long,
        hours: List<HourSlot> = emptyList(),
        topTask: String? = null,
        travelTo: (Commitment) -> TravelEstimate? = { null },
        travelFrom: (Commitment, Commitment) -> TravelEstimate? = { _, _ -> null },
        clock: (Long) -> String = ::clockOf,
    ): List<Beat> {
        val live = commitments
            .filter { !it.allDay && it.endMs > nowMs }
            .sortedBy { it.startMs }
        if (live.isEmpty()) return emptyList()

        val beats = mutableListOf<Beat>()

        // Departures, for anything not yet started that we can estimate a journey to.
        live.filter { it.startMs > nowMs }.forEach { c ->
            val travel = travelTo(c) ?: return@forEach
            val depart = leaveBy(c.startMs, travel)
            val minutes = ((depart - nowMs) / MINUTE)
            val basis = if (travel.source == TravelSource.ROAD) "road route" else "straight-line estimate"
            val title = when {
                depart <= nowMs -> "Leave now for ${c.title}"
                minutes <= IMMINENT_MIN -> "Leave in $minutes min for ${c.title}"
                else -> "Leave by ${clock(depart)} for ${c.title}"
            }
            val wet = hourAt(hours, depart)?.precipProbability?.takeIf { it >= WET_PROBABILITY }
            val detail = buildString {
                append("${minutesOf(travel.seconds)} travel plus $ARRIVAL_BUFFER_MIN min to arrive, by $basis")
                if (wet != null) append(" · $wet% chance of rain when you set off")
            }
            beats += Beat(
                atMs = max(depart, nowMs),
                kind = BeatKind.DEPART,
                title = title,
                detail = detail,
                confidence = confidenceOf(travel),
                sources = buildList {
                    add("calendar")
                    add(basis)
                    if (wet != null) add("forecast")
                },
                subjectId = c.id,
            )
        }

        // Impossible gaps: the journey between two commitments is longer than the space between them.
        live.zipWithNext().forEach { (a, b) ->
            if (!a.located || !b.located) return@forEach
            val travel = travelFrom(a, b) ?: return@forEach
            val gapMs = b.startMs - a.endMs
            val needMs = travel.seconds * 1000L + ARRIVAL_BUFFER_MIN * MINUTE
            if (gapMs >= needMs) return@forEach
            val shortBy = ((needMs - gapMs) / MINUTE).coerceAtLeast(1)
            beats += Beat(
                atMs = a.endMs,
                kind = BeatKind.CONFLICT,
                title = "\"${a.title}\" runs into \"${b.title}\"",
                detail = "You have ${minutesOf(gapMs / 1000)} between them and the journey needs " +
                    "${minutesOf(needMs / 1000)} — about $shortBy min short.",
                confidence = confidenceOf(travel),
                sources = listOf("calendar", if (travel.source == TravelSource.ROAD) "road route" else "straight-line estimate"),
                subjectId = a.id,
            )
        }

        // The commitments themselves, so the timeline reads as a day rather than a warning list.
        live.forEach { c ->
            beats += Beat(
                atMs = c.startMs,
                kind = BeatKind.EVENT,
                title = c.title,
                detail = "${clock(c.startMs)} – ${clock(c.endMs)}",
                confidence = Confidence.FIRM,
                sources = listOf("calendar"),
                subjectId = c.id,
            )
        }

        // The best stretch of unclaimed time, offered to whatever you said you were working on.
        freeWindows(live, nowMs, minMinutes = FOCUS_MIN).maxByOrNull { it.second - it.first }
            ?.let { (from, to) ->
                val mins = (to - from) / MINUTE
                beats += Beat(
                    atMs = from,
                    kind = BeatKind.FOCUS,
                    title = topTask?.let { "Clear stretch for: $it" } ?: "Clear stretch",
                    detail = "${clock(from)} – ${clock(to)}, about ${minutesOf(mins * 60)} with nothing booked.",
                    confidence = Confidence.FIRM,
                    sources = buildList { add("calendar"); if (topTask != null) add("tasks") },
                )
            }

        live.maxByOrNull { it.endMs }?.let { last ->
            beats += Beat(
                atMs = last.endMs,
                kind = BeatKind.DAY_END,
                title = "Last commitment ends",
                detail = "After ${clock(last.endMs)} the day is yours.",
                confidence = Confidence.FIRM,
                sources = listOf("calendar"),
            )
        }

        // Chronological, and where two land together the more consequential goes first — a conflict
        // or a departure is the reason you are reading this at all.
        return beats.sortedWith(compareBy({ it.atMs }, { it.kind.ordinal }))
    }

    /**
     * Unclaimed stretches from [fromMs] to the last commitment of the day.
     *
     * Overlapping commitments are merged first, so a gap is only reported when nothing at all is
     * booked across it. Returned as `(start, end)` pairs.
     */
    fun freeWindows(
        commitments: List<Commitment>,
        fromMs: Long,
        minMinutes: Long = FOCUS_MIN,
    ): List<Pair<Long, Long>> {
        val busy = commitments.filter { !it.allDay && it.endMs > fromMs }
            .map { max(it.startMs, fromMs) to it.endMs }
            .sortedBy { it.first }
        if (busy.isEmpty()) return emptyList()

        val merged = mutableListOf<Pair<Long, Long>>()
        busy.forEach { (s, e) ->
            val last = merged.lastOrNull()
            if (last != null && s <= last.second) merged[merged.lastIndex] = last.first to max(last.second, e)
            else merged += s to e
        }

        val out = mutableListOf<Pair<Long, Long>>()
        var cursor = fromMs
        merged.forEach { (s, e) ->
            if (s - cursor >= minMinutes * MINUTE) out += cursor to s
            cursor = max(cursor, e)
        }
        return out
    }

    /** The forecast hour containing [ms], or null when the forecast does not reach it. */
    fun hourAt(hours: List<HourSlot>, ms: Long): HourSlot? =
        hours.lastOrNull { it.startMs <= ms && ms < it.startMs + 3_600_000L }

    /**
     * The driest run of hours between [fromMs] and [toMs], or null if nothing is forecast or every
     * hour is equally wet.
     *
     * Used to answer "when is the gap in the rain", which is only worth saying when there is one.
     */
    fun driestWindow(hours: List<HourSlot>, fromMs: Long, toMs: Long): Pair<Long, Long>? {
        val span = hours.filter { it.startMs >= fromMs && it.startMs < toMs && it.precipProbability != null }
        if (span.size < 2) return null
        val driest = span.minOf { it.precipProbability!! }
        if (span.all { it.precipProbability == driest }) return null
        val run = span.dropWhile { it.precipProbability != driest }
            .takeWhile { it.precipProbability == driest }
        if (run.isEmpty()) return null
        return run.first().startMs to run.last().startMs + 3_600_000L
    }

    // ---- formatting --------------------------------------------------------------------------

    /**
     * A duration in the plainest form that stays honest — never "0 min", because a journey that
     * rounds to nothing still has to be made.
     */
    internal fun minutesOf(seconds: Long): String {
        val mins = (seconds + 30) / 60
        return when {
            mins < 1 -> "under a minute"
            mins < 60 -> "$mins min"
            else -> {
                val h = mins / 60
                val m = mins % 60
                if (m == 0L) "${h}h" else "${h}h ${m}m"
            }
        }
    }

    /**
     * A wall clock time in UTC — the default [plan] falls back to, and what the tests assert against.
     *
     * ⚠️ The core cannot know the reader's zone and must not pretend to. Anything rendering for a
     * person passes its own formatter into [plan] instead, exactly as this repo learned to do after
     * shipping a "tonight" that was computed against UTC midnight.
     */
    internal fun clockOf(ms: Long): String {
        val minutesOfDay = Math.floorMod(ms / MINUTE, 1440L)
        val h = minutesOfDay / 60
        val m = minutesOfDay % 60
        return "${if (h < 10) "0" else ""}$h:${if (m < 10) "0" else ""}$m"
    }
}
