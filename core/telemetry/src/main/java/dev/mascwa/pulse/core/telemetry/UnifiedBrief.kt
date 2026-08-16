package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * THE one notification — the ship's whole situation board as a single LCARS brief.
 *
 * The app posts exactly one notification (one fixed id), refreshed in place: a collapsed [headline] +
 * always-on temperature chip, and fixed-order rows — ALERT · NEWS · MARKETS · WEATHER · AGENDA · ADVISORY —
 * each one plain sentence, no jargon, no emoji, readable at a glance by anyone. Five is the everyday
 * shape; ADVISORY is the exception, and the caller is expected to pass one only when the Oracle has
 * something genuinely worth acting on. Urgency rides the Star Trek
 * alert-condition convention: [BriefUrgency.RED]/[BriefUrgency.YELLOW] make the single notification re-post
 * on an alerting channel exactly once per new [UnifiedBrief.urgencyKey]; ROUTINE refreshes stay silent.
 *
 * Pure and deterministic: the caller passes a full [BriefSignals] snapshot (missing signals are null and
 * their rows simply don't render); no Android types, no clock of its own. Deliberately a NEW composer rather
 * than an extension of the retired `Oracle.worldPulse` — that contract was a free-text priority collage,
 * while this one is typed rows + per-row toggles + an urgency output (worldPulse is deleted; this replaced it).
 */

/** Maps 1:1 onto the app's notification AlertCondition (ROUTINE/YELLOW/RED). */
enum class BriefUrgency { ROUTINE, YELLOW, RED }

/**
 * The fixed row order of the board. ALERT is only present when something is genuinely alert-worthy.
 *
 * ADVISORY is last because the board reads facts first and what to do about them second — every row
 * above it reports the world, and that one reports the Oracle's judgement of it.
 */
enum class BriefRowKind { ALERT, NEWS, MARKETS, WEATHER, AGENDA, ADVISORY }

data class BriefRow(val kind: BriefRowKind, val text: String)

data class UnifiedBrief(
    /** The single most consequential fact — the collapsed notification line. */
    val headline: String,
    /** "72°F" — present whenever a current temperature was in the snapshot; the collapsed temp chip. */
    val tempLabel: String?,
    /** At most 5 rows, always in [BriefRowKind] order; empty rows are omitted, never blank. */
    val rows: List<BriefRow>,
    val urgency: BriefUrgency,
    /** Stable identity of the urgent item (so it buzzes once, then updates silently); null when ROUTINE. */
    val urgencyKey: String?,
)

/**
 * Everything the device knows right now, distilled to display-ready primitives. Weather values arrive in
 * the user's own display unit (the core never converts units — [tempUnit] is just appended).
 */
data class BriefSignals(
    val nowMs: Long = 0L,
    // News.
    val topHeadline: String? = null,
    val topSource: String? = null,
    val emergencyHeadline: String? = null,
    val emergencyMajor: Boolean = false,
    // Markets.
    val movers: List<OracleMover> = emptyList(),
    val moveThresholdPct: Double = 3.0,
    // Weather — current temperature is the always-on "temp" the board promises.
    val tempNow: Double? = null,
    val tempUnit: String = "°C",
    val conditionText: String? = null,
    val tempHi: Double? = null,
    val tempLo: Double? = null,
    val precipPct: Int? = null,
    val uvIndex: Double? = null,
    val severeWeather: Boolean = false,
    // Canonical (Celsius, km/h) companions of the display values above. The comfort indices are
    // defined in those units and nothing else, and the board carries a temperature every time it
    // posts — so what that temperature actually does to a person belongs on the same row.
    val tempC: Double? = null,
    val humidityPct: Double? = null,
    val windKmh: Double? = null,
    val kpIndex: Double? = null,
    // Agenda.
    val nextEventTitle: String? = null,
    val nextEventStartMs: Long? = null,
    val pendingTaskCount: Int = 0,
    val topTask: String? = null,
    val pendingReminderCount: Int = 0,
    // Urgent overlays.
    val reminderNow: String? = null,
    val securityNotice: String? = null,
    val securityCritical: Boolean = false,
    val safetyNotice: String? = null,
    val safetyKey: String? = null,
    /**
     * A journey you need to start about now to reach something already in your calendar.
     *
     * The one output of [DayAhead] worth interrupting for: unlike everything else on the board it
     * expires. A safety notice read ten minutes late is still a safety notice; a departure read ten
     * minutes late is a meeting you have already missed.
     *
     * ⚠️ [departureKey] must identify the *commitment*, not the sentence. The text carries a live
     * countdown ("Leave in 12 min"), so keying on it would mint a new urgency key every pass and
     * buzz the phone all the way to the door.
     */
    val departureNotice: String? = null,
    val departureKey: String? = null,
    val opsNotice: String? = null,
    /**
     * The Oracle's single most important call to action, already filtered by the caller.
     *
     * The composer does not reason — it renders what it is handed. The bar for passing one lives at
     * the call site, which is the only place that knows the insight's urgency, and it is deliberately
     * high so this stays the exceptional sixth row rather than a permanent fixture.
     *
     * ⚠️ **An advisory never raises the alert condition.** What buzzes the phone is decided by the
     * notice chain above; a suggestion, however well reasoned, is not an emergency.
     */
    val advisory: String? = null,
    // Per-row visibility (the user's Settings toggles).
    val showNews: Boolean = true,
    val showMarkets: Boolean = true,
    val showWeather: Boolean = true,
    val showAgenda: Boolean = true,
)

object UnifiedBriefComposer {

    /** Longest collapsed headline; the expanded rows carry their own caps. */
    private const val HEADLINE_CAP = 90
    private const val NEWS_CAP = 80

    /** Roomier than a news line: an advisory carries a reason and an action, and both must survive. */
    private const val ADVISORY_CAP = 110

    /** The expanded notification layout has exactly this many row slots. See [trimToFive]. */
    private const val MAX_ROWS = 5

    /** Null when there is genuinely nothing to say — the caller cancels the notification. */
    fun compose(s: BriefSignals): UnifiedBrief? {
        val rows = mutableListOf<BriefRow>()

        // --- ALERT: at most one, highest-priority first. A user-set reminder outranks everything ---
        // (it's the one line the user explicitly asked to be interrupted for at this exact moment).
        var urgency = BriefUrgency.ROUTINE
        var urgencyKey: String? = null
        val alert: String? = when {
            !s.reminderNow.isNullOrBlank() -> {
                urgency = BriefUrgency.YELLOW; urgencyKey = "rem:${s.reminderNow.hashCode()}"
                "Reminder — ${s.reminderNow.trim()}"
            }
            !s.securityNotice.isNullOrBlank() && s.securityCritical -> {
                urgency = BriefUrgency.RED; urgencyKey = "sec:${s.securityNotice.hashCode()}"
                s.securityNotice.trim()
            }
            !s.emergencyHeadline.isNullOrBlank() && s.emergencyMajor -> {
                urgency = BriefUrgency.RED; urgencyKey = "news:${s.emergencyHeadline.hashCode()}"
                s.emergencyHeadline.trim()
            }
            // Above the other yellows because it is the only one that expires. A safety notice is
            // as true in ten minutes as it is now; a departure is not, and the window to act on it
            // closes whether or not the board is read.
            !s.departureNotice.isNullOrBlank() -> {
                urgency = BriefUrgency.YELLOW
                urgencyKey = "depart:${s.departureKey ?: s.departureNotice.hashCode()}"
                s.departureNotice.trim()
            }
            !s.safetyNotice.isNullOrBlank() -> {
                urgency = BriefUrgency.YELLOW; urgencyKey = "safety:${s.safetyKey ?: s.safetyNotice.hashCode()}"
                s.safetyNotice.trim()
            }
            !s.securityNotice.isNullOrBlank() -> {
                urgency = BriefUrgency.YELLOW; urgencyKey = "sec:${s.securityNotice.hashCode()}"
                s.securityNotice.trim()
            }
            !s.opsNotice.isNullOrBlank() -> s.opsNotice.trim() // ROUTINE — informative, not an alarm
            else -> null
        }
        alert?.let { rows += BriefRow(BriefRowKind.ALERT, cap(it, HEADLINE_CAP)) }

        // --- NEWS: the emergency headline when it isn't already the ALERT row, else the top story. ---
        if (s.showNews) {
            val alertIsEmergency = alert != null && alert == s.emergencyHeadline?.trim()
            val newsText = when {
                !s.emergencyHeadline.isNullOrBlank() && !alertIsEmergency -> s.emergencyHeadline.trim()
                !s.topHeadline.isNullOrBlank() ->
                    s.topHeadline.trim() + (s.topSource?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                else -> null
            }
            newsText?.let { rows += BriefRow(BriefRowKind.NEWS, cap(it, NEWS_CAP)) }
        }

        // --- MARKETS: movers past the user's threshold; else the single biggest if it's at least 1%. ---
        if (s.showMarkets) marketsText(s)?.let { rows += BriefRow(BriefRowKind.MARKETS, it) }

        // --- WEATHER: always renders when a current temperature exists (the board's promised "temp"). ---
        if (s.showWeather && s.tempNow != null) {
            rows += BriefRow(BriefRowKind.WEATHER, weatherText(s))
        }

        // --- AGENDA: next event, else the top task; plus open-task and set-reminder counts. ---
        if (s.showAgenda) agendaText(s)?.let { rows += BriefRow(BriefRowKind.AGENDA, it) }

        // --- ADVISORY: the Oracle's call to action, when the caller judged one worth the space. ---
        s.advisory?.takeIf { it.isNotBlank() }
            ?.let { rows += BriefRow(BriefRowKind.ADVISORY, cap(it.trim(), ADVISORY_CAP)) }

        if (rows.isEmpty()) return null
        trimToFive(rows)

        // The collapsed line: the most consequential row wins, in fixed preference order. ADVISORY
        // sits second because the bar for passing one is already high — if the Oracle has reasoned
        // its way to something you should do now, that outranks reporting what merely happened.
        val headline = listOf(
            BriefRowKind.ALERT, BriefRowKind.ADVISORY, BriefRowKind.NEWS,
            BriefRowKind.AGENDA, BriefRowKind.WEATHER, BriefRowKind.MARKETS,
        ).firstNotNullOf { kind -> rows.firstOrNull { it.kind == kind } }.text

        return UnifiedBrief(
            headline = cap(headline, HEADLINE_CAP),
            tempLabel = s.tempNow?.let { "${it.roundToInt()}${s.tempUnit}" },
            rows = rows,
            urgency = urgency,
            urgencyKey = if (urgency == BriefUrgency.ROUTINE) null else urgencyKey,
        )
    }

    /**
     * The board shows five rows, so an advisory takes a seat rather than adding one.
     *
     * ⚠️ This is not cosmetic. The expanded notification layout has exactly five row slots and the
     * renderer takes the first five — a sixth row would be silently dropped, and since ADVISORY is
     * last in row order it would be the row dropped. Doing the selection here keeps the renderer
     * dumb and makes the choice testable.
     *
     * Markets go first: of everything on the board it is the one thing that is purely informational
     * and unchanged by knowing it a refresh later. ALERT and ADVISORY are never dropped — one is the
     * reason the board is interrupting and the other is the reason it earned the extra line.
     */
    private fun trimToFive(rows: MutableList<BriefRow>) {
        val droppable = listOf(BriefRowKind.MARKETS, BriefRowKind.NEWS, BriefRowKind.WEATHER, BriefRowKind.AGENDA)
        for (kind in droppable) {
            if (rows.size <= MAX_ROWS) return
            rows.removeAll { it.kind == kind }
        }
    }

    private fun marketsText(s: BriefSignals): String? {
        val past = s.movers.filter { abs(it.changePct) >= s.moveThresholdPct }
            .sortedByDescending { abs(it.changePct) }
        val shown = when {
            past.isNotEmpty() -> past
            else -> s.movers.filter { abs(it.changePct) >= 1.0 }
                .sortedByDescending { abs(it.changePct) }.take(1)
        }
        if (shown.isEmpty()) return null
        val named = shown.take(2).joinToString(" · ") { "${it.name} ${signedPct(it.changePct)}" }
        val more = shown.size - 2
        return if (more > 0) "$named (+$more more)" else named
    }

    private fun weatherText(s: BriefSignals): String {
        val now = s.tempNow ?: return ""
        val parts = mutableListOf("${now.roundToInt()}${s.tempUnit} now")
        s.conditionText?.takeIf { it.isNotBlank() }?.let { parts += it }
        // Heat and cold the thermometer understates, right after the condition and before the
        // forecast. severeWeather covers storm codes; this covers the days that hurt without one.
        WeatherComfort.compactFeelsLike(s.tempC, s.humidityPct, s.windKmh, s.tempUnit)
            ?.let { parts += it }
        if (s.tempHi != null && s.tempLo != null) {
            parts += "High ${s.tempHi.roundToInt()} / Low ${s.tempLo.roundToInt()}"
        }
        s.precipPct?.takeIf { it >= 30 }?.let { parts += "Rain $it%" }
        s.uvIndex?.takeIf { it >= 8.0 }?.let { parts += "UV ${it.roundToInt()}" }
        s.kpIndex?.takeIf { it >= 5.0 }?.let { parts += "Aurora watch, Kp ${it.roundToInt()}" }
        if (s.severeWeather) parts += "Severe weather"
        return parts.joinToString(" · ")
    }

    private fun agendaText(s: BriefSignals): String? {
        val parts = mutableListOf<String>()
        if (!s.nextEventTitle.isNullOrBlank() && s.nextEventStartMs != null) {
            parts += "${cap(s.nextEventTitle.trim(), 40)} ${startsIn(s.nextEventStartMs, s.nowMs)}"
        } else if (!s.topTask.isNullOrBlank()) {
            parts += cap(s.topTask.trim(), 40)
        }
        if (s.pendingTaskCount > 0) {
            parts += if (s.pendingTaskCount == 1) "1 task open" else "${s.pendingTaskCount} tasks open"
        }
        if (s.pendingReminderCount > 0) {
            parts += if (s.pendingReminderCount == 1) "1 reminder set" else "${s.pendingReminderCount} reminders set"
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    // ---- small formatting helpers (pure) ----
    private fun cap(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    private fun signedPct(p: Double): String = (if (p >= 0) "+" else "") + "${(p * 10).roundToInt() / 10.0}%"

    private fun startsIn(ms: Long, nowMs: Long): String {
        // Round the TOTAL minutes first so the h/m split can never render "1h 60m".
        val total = ((ms - nowMs) / 60_000.0).roundToInt()
        return when {
            total < 1 -> "now"
            total < 60 -> "in $total min"
            else -> "in ${total / 60}h ${total % 60}m"
        }
    }
}
