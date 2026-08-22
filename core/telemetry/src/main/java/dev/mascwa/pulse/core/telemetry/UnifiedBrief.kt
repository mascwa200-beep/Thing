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
 * ADVISORY is second-last because the board reads facts first and what to do about them second —
 * every row above it reports the world, and that one reports the Oracle's judgement of it. LESSON is
 * last: it is the only row that is not about today at all.
 */
enum class BriefRowKind { ALERT, NEWS, MARKETS, WEATHER, AGENDA, ADVISORY, LESSON, HEALTH }

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
    /**
     * The [StoryLedger] identity of the story this board actually printed, for the caller to persist.
     *
     * Null when no NEWS row was rendered. ⚠️ It is deliberately the *chosen* story rather than the
     * top of the feed: recording what we merely considered would burn stories the reader never saw,
     * and they would then never be shown at all.
     */
    val newsIdentity: String? = null,
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
    /**
     * Further headlines to fall back to when the top one has already been shown.
     *
     * Without these the NEWS row simply disappears the moment the lead story goes stale, which
     * trades a repeating row for an absent one. With them the board keeps finding you something you
     * have not read.
     */
    val moreHeadlines: List<String> = emptyList(),
    /**
     * Story identities the board has already shown — see [StoryLedger].
     *
     * Empty means "nothing shown yet", which is correct on a first run: everything is new.
     */
    val seenStories: Set<String> = emptySet(),
    val emergencyHeadline: String? = null,
    val emergencyMajor: Boolean = false,
    /**
     * A STRONG disaster signal ([EmergencyNews.severity] == 2), as distinct from [emergencyMajor].
     *
     * ⚠️ The two are not the same bar and conflating them is what broke this. [emergencyMajor] also
     * covers a notable death and a historic verdict — real news, worth a card, not worth an alert
     * condition. Only this one raises the board's ALERT row, and only to yellow.
     */
    val emergencySevere: Boolean = false,
    /**
     * A government-issued emergency alert covering the user's location, already graded.
     *
     * The **only** thing besides a critical device-security notice that can put the board — and
     * therefore the whole app, via `AlertStatus` — into condition red. Comes from [EmergencyAlert]
     * over a CAP/USGS feed, never from a headline.
     */
    val officialAlert: String? = null,
    /** Stable dedup key for [officialAlert] — the issuer's own alert id, so it buzzes once. */
    val officialAlertKey: String? = null,
    // Health.
    /**
     * One line on today's eating against today's target, or null when there is nothing to say.
     *
     * ⚠️ **Composed by the caller, never assembled here from parts.** The figures come from the same
     * composition the HEALTH screen draws, and passing the numbers in separately would be a second
     * place that decides how they are phrased and rounded — which is how the tray and the screen
     * start quoting different calorie counts for the same day.
     *
     * ⚠️ Null is also the honest value while there is no target and while nothing has been logged.
     * On this feature a defaulted zero is not a missing figure, it is advice.
     */
    val healthLine: String? = null,
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
     * ⚠️ **An advisory does not raise the alert condition — unless [advisoryUrgent] says the caller
     * established it clears the push-worthy bar.** A suggestion, however well reasoned, is not an
     * emergency; but of the Oracle rules that reach that bar, three already alert through their own
     * notice above (a departure, a major emergency headline, a security notice) and one does not.
     * Extreme heat danger would otherwise arrive as a silent routine row, which is the wrong way for
     * a health risk to be delivered.
     */
    val advisory: String? = null,
    /**
     * Whether the advisory clears `Oracle.pushWorthy` — URGENT or CRITICAL.
     *
     * The composer still does not reason: the caller owns the bar, as it does for the advisory text
     * itself. Defaulted false, so every existing caller and every cached blob behaves exactly as
     * before.
     */
    val advisoryUrgent: Boolean = false,
    /**
     * Identifies the RULE behind the advisory, not the sentence.
     *
     * ⚠️ Same trap as [departureKey]: an advisory's text carries live values — an apparent
     * temperature that climbs through the afternoon rewrites the line every pass. Keying the alert
     * on the text would make each rewrite look like a new urgent item and buzz the phone again.
     * The caller passes the insight's stable family.
     */
    val advisoryKey: String? = null,
    /**
     * Today's study item, already chosen by the caller.
     *
     * ⚠️ **A lesson never raises the alert condition, and it is the first row shed when the board is
     * busy** — see [UnifiedBriefComposer.trimToFive]. Learning something is worth a line on a quiet
     * board and worth nothing at all on a loud one, and a row that pushes the weather off a full
     * board to tell you about a guide has its priorities exactly backwards.
     */
    val lesson: String? = null,
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

    /** A lesson row is a title and nothing else — the reason for it lives on the study screen. */
    private const val LESSON_CAP = 70

    /**
     * Roomier than a lesson, tighter than an advisory.
     *
     * ⚠️ A health row is several numbers and their units — "1,240 left · 84 g protein to go" — and a
     * cut that lands mid-figure produces a number that is simply wrong rather than merely truncated.
     * 70 fits the longest line [dev.mascwa.pulse.core.telemetry.NutritionDay] can produce; 60 does not.
     */
    private const val HEALTH_CAP = 70

    /** The expanded notification layout has exactly this many row slots. See [trimToFive]. */
    private const val MAX_ROWS = 5

    /** Null when there is genuinely nothing to say — the caller cancels the notification. */
    fun compose(s: BriefSignals): UnifiedBrief? {
        val rows = mutableListOf<BriefRow>()

        // --- ALERT: at most one, highest-priority first. A user-set reminder outranks everything ---
        // (it's the one line the user explicitly asked to be interrupted for at this exact moment).
        var urgency = BriefUrgency.ROUTINE
        var urgencyKey: String? = null
        var chosenStory: String? = null
        val alert: String? = when {
            // ⚠️ Above the reminder, which is otherwise the one line the user explicitly asked to be
            // interrupted for. A government emergency alert is the only thing that outranks a
            // person's own stated intent, because it is the only one where being read late is
            // measured in lives rather than in inconvenience.
            !s.officialAlert.isNullOrBlank() -> {
                urgency = BriefUrgency.RED
                urgencyKey = "gov:${s.officialAlertKey ?: s.officialAlert.hashCode()}"
                s.officialAlert.trim()
            }
            !s.reminderNow.isNullOrBlank() -> {
                urgency = BriefUrgency.YELLOW; urgencyKey = "rem:${s.reminderNow.hashCode()}"
                "Reminder — ${s.reminderNow.trim()}"
            }
            !s.securityNotice.isNullOrBlank() && s.securityCritical -> {
                urgency = BriefUrgency.RED; urgencyKey = "sec:${s.securityNotice.hashCode()}"
                s.securityNotice.trim()
            }
            // ⚠️ **YELLOW, and never red.** This was the permanent-red-alert defect: it read
            // `emergencyMajor`, which fires on a notable death and — until this was fixed — on a
            // headline that merely began with the word "Breaking". Condition red recolours all
            // thirty-odd screens, so a bar this loose meant the app was always shouting, which is
            // the same as never shouting. Red now requires a government feed; a news headline, even
            // a genuine disaster, gets yellow.
            !s.emergencyHeadline.isNullOrBlank() && s.emergencySevere -> {
                urgency = BriefUrgency.YELLOW; urgencyKey = "news:${s.emergencyHeadline.hashCode()}"
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
        //
        // ⚠️ **Every candidate is filtered through [StoryLedger] against what the board has already
        // shown.** The board is one fixed notification id re-posted on every refresh, so before this
        // a story that stayed top of the feed for six hours reprinted, identically, on every pass —
        // and the only dedup in the system ([urgencyKey]) governs whether the post *buzzes*, not
        // what it *says*. When every candidate has already been shown the row is **omitted**, which
        // is the whole point: falling back to "print the top one anyway" would reinstate the defect.
        if (s.showNews) {
            val alertIsEmergency = alert != null && alert == s.emergencyHeadline?.trim()
            val candidates = buildList {
                if (!s.emergencyHeadline.isNullOrBlank() && !alertIsEmergency) add(s.emergencyHeadline.trim())
                if (!s.topHeadline.isNullOrBlank()) add(s.topHeadline.trim())
                addAll(s.moreHeadlines.filter { it.isNotBlank() }.map { it.trim() })
            }
            chosenStory = StoryLedger.firstUnseen(candidates, s.seenStories)
            chosenStory?.let { chosen ->
                // The source suffix belongs to the top story only; the emergency headline and the
                // spares arrive without one attributed to them.
                val text = if (chosen == s.topHeadline?.trim()) {
                    chosen + (s.topSource?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                } else {
                    chosen
                }
                rows += BriefRow(BriefRowKind.NEWS, cap(text, NEWS_CAP))
            }
        }

        // --- MARKETS: movers past the user's threshold; else the single biggest if it's at least 1%. ---
        if (s.showMarkets) marketsText(s)?.let { rows += BriefRow(BriefRowKind.MARKETS, it) }

        // --- WEATHER: always renders when a current temperature exists (the board's promised "temp"). ---
        if (s.showWeather && s.tempNow != null) {
            rows += BriefRow(BriefRowKind.WEATHER, weatherText(s))
        }

        // --- AGENDA: next event, else the top task; plus open-task and set-reminder counts. ---
        if (s.showAgenda) agendaText(s)?.let { rows += BriefRow(BriefRowKind.AGENDA, it) }

        // --- HEALTH: today's eating against today's target. A fact, never urgent. ---
        //
        // ⚠️ This row exists because the ADVISORY row cannot carry it. Advisories are gated at
        // IMPORTANT, and the Oracle's health rules are AMBIENT and NOTABLE by design — a calorie
        // count is worth knowing and never worth interrupting for. Without a row of its own the
        // figure would be computed on every refresh and reach nobody.
        s.healthLine?.takeIf { it.isNotBlank() }?.let {
            rows += BriefRow(BriefRowKind.HEALTH, cap(it.trim(), HEALTH_CAP))
        }

        // --- ADVISORY: the Oracle's call to action, when the caller judged one worth the space. ---
        s.advisory?.takeIf { it.isNotBlank() }?.let {
            rows += BriefRow(BriefRowKind.ADVISORY, cap(it.trim(), ADVISORY_CAP))
            // ⚠️ Only when nothing above it already spoke, and never RED. An advisory can make a
            // quiet board announce itself; it cannot outrank a security notice or a major emergency,
            // and it cannot promote the board to a red alert. The key is the rule's, not the
            // sentence's, so a line that rewrites itself as conditions move buzzes once.
            if (s.advisoryUrgent && urgency == BriefUrgency.ROUTINE) {
                urgency = BriefUrgency.YELLOW
                urgencyKey = "advisory:${s.advisoryKey ?: it.trim().hashCode()}"
            }
        }

        // --- LESSON: what to learn today. Last, and first to go when the board fills up. ---
        s.lesson?.takeIf { it.isNotBlank() }
            ?.let { rows += BriefRow(BriefRowKind.LESSON, cap(it.trim(), LESSON_CAP)) }

        if (rows.isEmpty()) return null
        trimToFive(rows)

        // The collapsed line: the most consequential row wins, in fixed preference order. ADVISORY
        // sits second because the bar for passing one is already high — if the Oracle has reasoned
        // its way to something you should do now, that outranks reporting what merely happened.
        val headline = listOf(
            BriefRowKind.ALERT, BriefRowKind.ADVISORY, BriefRowKind.NEWS,
            BriefRowKind.AGENDA, BriefRowKind.WEATHER, BriefRowKind.MARKETS,
            // ⚠️ Below the world's facts: a calorie count is the reader's own business and a poor
            // thing to read on a lock screen before anything else. It is above LESSON only so that
            // a board carrying just these two still has a headline.
            BriefRowKind.HEALTH,
            // Last, and present only so a board carrying nothing else cannot throw here.
            BriefRowKind.LESSON,
        ).firstNotNullOf { kind -> rows.firstOrNull { it.kind == kind } }.text

        return UnifiedBrief(
            headline = cap(headline, HEADLINE_CAP),
            tempLabel = s.tempNow?.let { "${it.roundToInt()}${s.tempUnit}" },
            rows = rows,
            urgency = urgency,
            urgencyKey = if (urgency == BriefUrgency.ROUTINE) null else urgencyKey,
            newsIdentity = chosenStory?.let { StoryLedger.identity(it) }?.takeIf { it.isNotBlank() },
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
     * The lesson goes first: it is the only row about nothing that is happening, and it will still be
     * there tomorrow. Markets next — of the rest it is the one thing purely informational and
     * unchanged by knowing it a refresh later. ALERT and ADVISORY are never dropped: one is the
     * reason the board is interrupting and the other is the reason it earned the extra line.
     */
    private fun trimToFive(rows: MutableList<BriefRow>) {
        // ⚠️ Ordered by what is shed FIRST. HEALTH goes second, just after LESSON, and the reason
        // is decay rather than importance: a calorie count is equally true an hour later, where a
        // headline, a forecast and an appointment all stop being true. Anything not in this list can
        // never be dropped, so a new kind missing from it would silently displace a real row on a
        // busy board -- the layout takes the first five and says nothing about the rest.
        val droppable = listOf(
            BriefRowKind.LESSON, BriefRowKind.HEALTH, BriefRowKind.MARKETS, BriefRowKind.NEWS,
            BriefRowKind.WEATHER, BriefRowKind.AGENDA,
        )
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
