package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ORACLE — J.A.R.V.I.S.'s predictive cortex. The capstone that fuses EVERYTHING the app senses and knows —
 * time, location, movement, calendar, tasks, interests, weather, market movers, breaking emergencies, space
 * weather, device state, your usage rhythms —
 * into a single ranked stream of proactive [Insight]s: what's about to happen, what to do now to be ready,
 * what to avoid, and what opportunity is open. It's the answer to "what's the ONE thing I should know right
 * now?" — computed before you ask.
 *
 * This is the pure, deterministic, CI-tested reasoning engine: a library of cross-signal RULES (each combines
 * several domains — e.g. *calendar event + travel distance + rain* → "leave 10 min early, take an umbrella")
 * scored by urgency × timeliness × relevance, deduped and ranked. No Android types, no network, no clock of
 * its own — the caller passes a full [OracleSignals] snapshot (missing signals are null and their rules
 * simply don't fire). The on-device layer gathers the snapshot from every store/repo and renders the result.
 */

enum class InsightKind { PREDICTION, PREPARATION, RISK, OPPORTUNITY, REMINDER, ANOMALY, INSIGHT }

/** How much this should grab your attention. [weight] drives ranking + whether it warrants a push. */
enum class Urgency(val weight: Int) { AMBIENT(1), NOTABLE(2), IMPORTANT(3), URGENT(4), CRITICAL(5) }

/** One thing the Oracle wants you to know or do, with why it fired (for transparency) and how to act on it. */
data class Insight(
    val id: String,               // stable key (for dedup + notification throttle)
    val kind: InsightKind,
    val urgency: Urgency,
    val title: String,            // the headline
    val detail: String,           // the reasoning + the action
    val score: Double,            // ranking (higher = more important now)
    val actionRoute: String? = null, // an app deep-link to act on it, if any
    val sources: List<String> = emptyList(), // which signal domains combined to fire it
)

/** A single upcoming calendar event, distilled for the Oracle. */
data class OracleEvent(
    val title: String,
    val startMs: Long,
    val hasLocation: Boolean = false,
    /** Straight-line metres to the event location, if known. */
    val distanceM: Double? = null,
)

/** A market instrument that moved notably today. */
data class OracleMover(val name: String, val changePct: Double, val onWatchlist: Boolean = false)

/**
 * The full snapshot of everything the device knows right now. Every field is optional/neutral so a rule
 * only fires when its inputs are present — the on-device layer fills in what it can.
 */
data class OracleSignals(
    val nowMs: Long = 0L,
    val hourOfDay: Int = 12,       // 0..23 local
    val minuteOfDay: Int = 12 * 60, // 0..1439 local
    val dayOfWeek: Int = 1,        // 1=Mon .. 7=Sun
    // Location & movement
    val lat: Double? = null,
    val lon: Double? = null,
    val placeName: String? = null,
    val movement: Float = 0f,      // smoothed motion intensity, ~0 at rest
    val speedMps: Double? = null,  // ground speed if moving
    val awayFromHome: Boolean? = null,
    // Calendar
    val events: List<OracleEvent> = emptyList(),
    // Tasks
    val pendingTasks: List<String> = emptyList(),
    // Profile
    val interests: List<String> = emptyList(),
    // Weather
    val tempC: Double? = null,
    val precipChancePct: Int? = null,
    val uvIndex: Double? = null,
    val windKmh: Double? = null,
    // Markets
    val movers: List<OracleMover> = emptyList(),
    // News / emergencies
    val emergencyHeadline: String? = null,
    // Space weather
    val kpIndex: Double? = null,
    // Device state
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    val storageFreePct: Int? = null,
    val onCellular: Boolean? = null,
    // Usage rhythm — the feature you usually open around now (route) + its label
    val habitualRoute: String? = null,
    val habitualLabel: String? = null,
    // Sensorium — the ambient environment read ("Indoors · still · quiet · lit · alone"), the top
    // learned-normal deviation ("noise unusually loud for 03:00 on a weekday"), and the storm signal
    val envDescription: String? = null,
    val envAnomaly: String? = null,
    val pressureFallingFast: Boolean = false,
) {
    val minutesNow: Int get() = minuteOfDay
    val isNight: Boolean get() = hourOfDay >= 22 || hourOfDay < 6
}

object Oracle {

    private const val WALK_MPS = 1.35        // average walking pace
    private const val DRIVE_MPS = 11.0       // ~40 km/h effective urban drive
    private const val ARRIVE_BUFFER_MIN = 5  // be there a few minutes early
    private const val MOVEMENT_THRESHOLD = 0.09f // smoothed motion intensity above which you're "moving"

    private fun minutesUntil(ms: Long, nowMs: Long): Double = (ms - nowMs) / 60_000.0

    /** Estimated minutes to reach a point [distanceM] away, walking unless clearly driving. */
    private fun travelMinutes(distanceM: Double, driving: Boolean): Double {
        val mps = if (driving) DRIVE_MPS else WALK_MPS
        return distanceM / mps / 60.0
    }

    // ---- The rule library. Each returns an Insight when its combined conditions hold, else null. ----
    // Rules are pure functions of the snapshot; ordering here doesn't matter (divine ranks by score).

    private fun leaveNow(s: OracleSignals): Insight? {
        val ev = s.events.filter { it.startMs > s.nowMs }.minByOrNull { it.startMs } ?: return null
        val dist = ev.distanceM ?: return null
        if (!ev.hasLocation || dist < 120) return null // already there / no travel
        val driving = (s.speedMps ?: 0.0) > 6.0 || dist > 4000
        var travel = travelMinutes(dist, driving)
        // Rain/storm adds slack.
        val wet = (s.precipChancePct ?: 0) >= 50
        if (wet) travel += 5
        val leaveInMin = minutesUntil(ev.startMs, s.nowMs) - travel - ARRIVE_BUFFER_MIN
        if (leaveInMin > 45 || leaveInMin < -20) return null // not yet relevant / long past
        val urgency = when {
            leaveInMin <= 2 -> Urgency.CRITICAL
            leaveInMin <= 10 -> Urgency.URGENT
            else -> Urgency.IMPORTANT
        }
        val when0 = if (leaveInMin <= 1) "now" else "in ${leaveInMin.roundToInt()} min"
        val umbrella = if (wet) " Take an umbrella." else ""
        return Insight(
            id = "leave_${ev.title.hashCode()}",
            kind = InsightKind.PREPARATION, urgency = urgency,
            title = "Leave $when0 for \"${ev.title}\"",
            detail = "${km(dist)} away (~${travel.roundToInt()} min ${if (driving) "driving" else "on foot"}); starts ${startsIn(ev.startMs, s.nowMs)}.$umbrella",
            score = urgency.weight * 1000.0 + (60 - leaveInMin).coerceIn(0.0, 60.0),
            actionRoute = "nav",
            sources = buildList { add("calendar"); add("location"); if (wet) add("weather") },
        )
    }

    private fun chargeNow(s: OracleSignals): Insight? {
        val batt = s.batteryPct ?: return null
        if (s.charging || batt > 35) return null
        // A demand ahead: an event later, or the evening/commute, or you're already out.
        val eventLater = s.events.any { minutesUntil(it.startMs, s.nowMs) in 30.0..600.0 }
        val demandAhead = eventLater || (s.hourOfDay in 6..10) || (s.awayFromHome == true)
        val urgency = if (batt <= 15) Urgency.URGENT else if (demandAhead) Urgency.IMPORTANT else Urgency.NOTABLE
        val why = when {
            eventLater -> "you've plans later"
            s.awayFromHome == true -> "you're out"
            else -> "the day's ahead of you"
        }
        return Insight(
            id = "charge_low", kind = InsightKind.PREPARATION, urgency = urgency,
            title = "Charge now — battery $batt%",
            detail = "Top up while you can; $why and a dead phone strands you.",
            score = urgency.weight * 1000.0 + (40 - batt),
            sources = listOf("device", if (eventLater) "calendar" else "time"),
        )
    }

    private fun weatherPrep(s: OracleSignals): Insight? {
        val chance = s.precipChancePct ?: return null
        if (chance < 55) return null
        // Only actionable if you're heading out: an event with a location soon, or you're already out.
        val outSoon = s.events.any { it.hasLocation && minutesUntil(it.startMs, s.nowMs) in 0.0..240.0 }
        if (!outSoon && s.awayFromHome != true) return null
        return Insight(
            id = "weather_rain", kind = InsightKind.PREPARATION, urgency = Urgency.NOTABLE,
            title = "Rain likely ($chance%) — grab an umbrella",
            detail = if (outSoon) "You've somewhere to be and it's set to rain. Take cover before you go." else "You're out and about; it's about to come down.",
            score = Urgency.NOTABLE.weight * 1000.0 + chance,
            actionRoute = "weather",
            sources = listOf("weather", if (outSoon) "calendar" else "location"),
        )
    }

    private fun uvWarn(s: OracleSignals): Insight? {
        val uv = s.uvIndex ?: return null
        if (uv < 7.0) return null
        val outdoor = s.awayFromHome == true
        if (!outdoor && s.hourOfDay !in 10..16) return null
        return Insight(
            id = "uv_high", kind = InsightKind.RISK, urgency = Urgency.NOTABLE,
            title = "High UV (index ${uv.roundToInt()}) — cover up",
            detail = "Sunburn territory. Sunscreen, hat, shade — especially midday.",
            score = Urgency.NOTABLE.weight * 1000.0 + uv,
            actionRoute = "weather",
            sources = listOf("weather", "location"),
        )
    }

    private fun marketMove(s: OracleSignals): Insight? {
        // A watchlist name that moved hard is worth a heads-up; else the single biggest mover if large.
        val watch = s.movers.filter { it.onWatchlist && abs(it.changePct) >= 3.0 }
            .maxByOrNull { abs(it.changePct) }
        val mover = watch ?: s.movers.filter { abs(it.changePct) >= 6.0 }.maxByOrNull { abs(it.changePct) }
        ?: return null
        val up = mover.changePct >= 0
        val urgency = if (watch != null && abs(mover.changePct) >= 6.0) Urgency.IMPORTANT else Urgency.NOTABLE
        return Insight(
            id = "market_${mover.name.hashCode()}", kind = InsightKind.OPPORTUNITY, urgency = urgency,
            title = "${mover.name} ${if (up) "▲" else "▼"} ${fmtPct(mover.changePct)}",
            detail = (if (watch != null) "On your watchlist. " else "") + "A ${if (up) "sharp gain" else "sharp drop"} today${if (watch == null) " (broad market)" else ""}.",
            score = urgency.weight * 1000.0 + abs(mover.changePct),
            actionRoute = "markets",
            sources = buildList { add("markets"); if (watch != null) add("profile") },
        )
    }

    private fun emergency(s: OracleSignals): Insight? {
        val h = s.emergencyHeadline?.takeIf { it.isNotBlank() } ?: return null
        return Insight(
            id = "emergency", kind = InsightKind.RISK, urgency = Urgency.CRITICAL,
            title = "🚨 $h", detail = "A major event is unfolding. Tap for the full coverage.",
            score = Urgency.CRITICAL.weight * 1000.0 + 100,
            actionRoute = "news",
            sources = listOf("news"),
        )
    }

    private fun aurora(s: OracleSignals): Insight? {
        val kp = s.kpIndex ?: return null
        if (kp < 5.0) return null
        val likesSky = s.interests.any { it.contains("astro", true) || it.contains("space", true) || it.contains("sky", true) || it.contains("aurora", true) }
        if (!s.isNight && !likesSky) return null
        val clear = (s.precipChancePct ?: 0) < 30
        val urgency = if (kp >= 7.0) Urgency.IMPORTANT else Urgency.NOTABLE
        return Insight(
            id = "aurora", kind = InsightKind.OPPORTUNITY, urgency = urgency,
            title = "Aurora likely tonight (Kp ${kp.roundToInt()})",
            detail = "Geomagnetic storm underway${if (clear) " and skies look clear" else ""}. Get away from lights and look north.",
            score = urgency.weight * 1000.0 + kp + (if (likesSky) 10 else 0),
            actionRoute = "space_wx",
            sources = buildList { add("space-weather"); if (likesSky) add("profile"); if (clear) add("weather") },
        )
    }

    private fun focusMoment(s: OracleSignals): Insight? {
        val task = s.pendingTasks.firstOrNull() ?: return null
        // A good moment = you're settled (still) and nothing imminent.
        val settled = s.movement < MOVEMENT_THRESHOLD
        val imminent = s.events.any { minutesUntil(it.startMs, s.nowMs) in 0.0..20.0 }
        if (!settled || imminent || s.isNight) return null
        return Insight(
            id = "focus_task", kind = InsightKind.OPPORTUNITY, urgency = Urgency.NOTABLE,
            title = "Good moment to knock out: $task",
            detail = "You're settled and clear for a bit — a solid window to get it done.",
            score = Urgency.NOTABLE.weight * 1000.0 + 20,
            actionRoute = "jarvis",
            sources = listOf("tasks", "perception"),
        )
    }

    private fun storageCleanup(s: OracleSignals): Insight? {
        val free = s.storageFreePct ?: return null
        if (free > 8) return null
        return Insight(
            id = "storage_low", kind = InsightKind.RISK, urgency = if (free <= 3) Urgency.IMPORTANT else Urgency.NOTABLE,
            title = "Storage almost full ($free% free)",
            detail = "Photos/apps will start failing to save. Clear some space soon.",
            score = (if (free <= 3) Urgency.IMPORTANT else Urgency.NOTABLE).weight * 1000.0 + (10 - free),
            actionRoute = "settings",
            sources = listOf("device"),
        )
    }

    private fun windDown(s: OracleSignals): Insight? {
        if (!s.isNight || s.hourOfDay < 23) return null
        if (s.awayFromHome != false) return null
        return Insight(
            id = "wind_down", kind = InsightKind.REMINDER, urgency = Urgency.AMBIENT,
            title = "It's late — start winding down",
            detail = "Screens off soon pays back tomorrow. Tomorrow-you will thank you.",
            score = Urgency.AMBIENT.weight * 1000.0 + (s.hourOfDay - 22),
            sources = listOf("time", "location"),
        )
    }

    private fun habitPrefetch(s: OracleSignals): Insight? {
        val route = s.habitualRoute ?: return null
        val label = s.habitualLabel ?: return null
        return Insight(
            id = "habit_$route", kind = InsightKind.PREDICTION, urgency = Urgency.AMBIENT,
            title = "You usually check $label about now",
            detail = "Ready when you are — it's teed up.",
            score = Urgency.AMBIENT.weight * 1000.0 + 5,
            actionRoute = route,
            sources = listOf("usage"),
        )
    }

    private fun interestPulse(s: OracleSignals): Insight? {
        if (s.interests.isEmpty() || s.movers.isEmpty()) return null
        // A mover whose name matches one of your interests — a nudge on something you care about.
        val hit = s.movers.firstOrNull { m -> s.interests.any { m.name.contains(it, true) || it.contains(m.name, true) } }
            ?: return null
        if (abs(hit.changePct) < 2.0) return null
        return Insight(
            id = "interest_${hit.name.hashCode()}", kind = InsightKind.INSIGHT, urgency = Urgency.AMBIENT,
            title = "${hit.name} is moving (${fmtPct(hit.changePct)})",
            detail = "Something you follow is on the move today.",
            score = Urgency.AMBIENT.weight * 1000.0 + abs(hit.changePct),
            actionRoute = "markets",
            sources = listOf("profile", "markets"),
        )
    }

    private fun meetingPrep(s: OracleSignals): Insight? {
        val ev = s.events.filter { minutesUntil(it.startMs, s.nowMs) in 5.0..30.0 }.minByOrNull { it.startMs } ?: return null
        // Only if there's nothing already firing a "leave now" (no location, or you're basically there).
        if (ev.hasLocation && (ev.distanceM ?: 0.0) > 300) return null
        return Insight(
            id = "prep_${ev.title.hashCode()}", kind = InsightKind.PREPARATION, urgency = Urgency.IMPORTANT,
            title = "\"${ev.title}\" ${startsIn(ev.startMs, s.nowMs)}",
            detail = "Coming up. Gather what you need and get set.",
            score = Urgency.IMPORTANT.weight * 1000.0 + (30 - minutesUntil(ev.startMs, s.nowMs)),
            actionRoute = "objectives",
            sources = listOf("calendar"),
        )
    }

    /** A storm front read from the phone's own barometer — hyper-local, works offline, and often
     *  ahead of a forecast refresh. Fired only on the fast fall (the Sensorium's PLUNGING band). */
    private fun stormFront(s: OracleSignals): Insight? {
        if (!s.pressureFallingFast) return null
        return Insight(
            id = "storm_front", kind = InsightKind.PREPARATION, urgency = Urgency.IMPORTANT,
            title = "Pressure is plunging — weather's turning",
            detail = "The phone's own barometer reads a fast 3-hour fall, the classic storm-front " +
                "signature. If you're heading out, take the layer/umbrella now.",
            score = Urgency.IMPORTANT.weight * 1000.0 + 30,
            actionRoute = "weather",
            sources = listOf("sensorium", "weather"),
        )
    }

    /** The learned-normal deviation, surfaced as an ambient awareness line ("unusually loud for
     *  03:00 on a weekday"). Never urgent by itself — the Sensorium raises true ALERT events on its
     *  own path; this is the Oracle knowing the room feels different. */
    private fun envAnomaly(s: OracleSignals): Insight? {
        val anomaly = s.envAnomaly ?: return null
        return Insight(
            id = "env_anomaly", kind = InsightKind.ANOMALY, urgency = Urgency.NOTABLE,
            title = "Your environment is off its normal",
            detail = anomaly.replaceFirstChar { it.uppercase() } + ".",
            score = Urgency.NOTABLE.weight * 1000.0 + 5,
            actionRoute = "sensorium",
            sources = listOf("sensorium"),
        )
    }

    private val RULES: List<(OracleSignals) -> Insight?> = listOf(
        ::emergency, ::leaveNow, ::meetingPrep, ::chargeNow,
        ::weatherPrep, ::uvWarn, ::marketMove, ::aurora, ::focusMoment, ::storageCleanup,
        ::windDown, ::habitPrefetch, ::interestPulse, ::stormFront, ::envAnomaly,
    )

    /**
     * The whole point: read [s], fire every applicable rule, and return the ranked, deduped list of what
     * matters now — most important first. [limit] caps the surface (default 8).
     */
    fun divine(s: OracleSignals, limit: Int = 8): List<Insight> =
        RULES.mapNotNull { it(s) }
            .distinctBy { it.id }
            .sortedByDescending { it.score }
            .take(limit)

    /** The single most important thing right now, or null if all quiet. */
    fun focus(s: OracleSignals): Insight? = divine(s, limit = 1).firstOrNull()

    /** A one-line J.A.R.V.I.S. briefing of the top few insights, in priority order. */
    fun briefing(insights: List<Insight>): String {
        if (insights.isEmpty()) return "All quiet — nothing needs you right now."
        return insights.take(3).joinToString(" ") { it.title.trimEnd('.') + "." }
    }

    /** Insights urgent enough to warrant a proactive push (URGENT or CRITICAL). */
    fun pushWorthy(insights: List<Insight>): List<Insight> =
        insights.filter { it.urgency.weight >= Urgency.URGENT.weight }

    // ---- small formatting helpers (pure) ----
    private fun km(m: Double): String = if (m >= 1000) "${(m / 100).roundToInt() / 10.0} km" else "${m.roundToInt()} m"
    private fun fmtPct(p: Double): String = (if (p >= 0) "+" else "") + "${(p * 10).roundToInt() / 10.0}%"
    private fun startsIn(ms: Long, nowMs: Long): String {
        val m = minutesUntil(ms, nowMs)
        return when {
            m < 1 -> "now"
            m < 60 -> "in ${m.roundToInt()} min"
            else -> "in ${(m / 60).roundToInt()}h ${(m % 60).roundToInt()}m"
        }
    }
}
