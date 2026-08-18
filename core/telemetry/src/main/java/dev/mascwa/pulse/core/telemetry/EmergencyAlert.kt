package dev.mascwa.pulse.core.telemetry

/**
 * When an official alert is worth taking over the screen and sounding an alarm for.
 *
 * The app has been fetching, parsing and grading government alerts for some time — `SafetyRepository`
 * reads the NWS CAP feed down to urgency, certainty, expiry and the official instruction text, and
 * [CapAlerts.grade] already yields EXTREME — and the loudest thing any of it could produce was a
 * yellow line on a notification. Meanwhile the *news* feed could drive the app to condition red on a
 * headline that merely began with the word "Breaking". Exactly backwards. This is the piece that
 * decides, and it decides from the government's own grading rather than from a keyword list.
 *
 * ⚠️ **This app cannot fire before a Wireless Emergency Alert and nothing here claims to.** WEA is
 * delivered by the modem to the system CellBroadcastService; no ordinary app can receive, intercept
 * or preempt it. What is real: NWS publishes the same hazard as CAP, so a fast poll often sees it at
 * or before the broadcast reaches the handset — sometimes after. The screen says which source and
 * when, and never implies it beat anything.
 *
 * Pure and deterministic: the clock and the feed are passed in, so CI holds every rule.
 */
object EmergencyAlert {

    /** The ship's condition an official alert justifies. */
    enum class Tier { NONE, ADVISORY, YELLOW, RED }

    /** One official alert, flattened from whichever feed carried it. */
    data class Official(
        val id: String,
        /** The event type in the issuer's own words — "Tornado Warning", "Tsunami Warning". */
        val event: String,
        /** The issuer's headline, if it published one distinct from the event. */
        val headline: String = "",
        /** The area the issuer says it covers. */
        val area: String = "",
        val severity: String? = null,
        val urgency: String? = null,
        val certainty: String? = null,
        /** The official "what to do" text. Never paraphrased — it is the part that saves lives. */
        val instruction: String? = null,
        val expiresMs: Long? = null,
        val effectiveMs: Long = 0L,
        /** Who issued it, shown so the reader can judge it. */
        val source: String = "",
    )

    /**
     * Event types that are RED whatever the feed's own severity field says.
     *
     * ⚠️ A belt-and-braces floor, and deliberately short. NWS event names are a controlled
     * vocabulary, and these are the ones where being late or quiet is not survivable. The severity
     * field is almost always right; this exists for the case where it is not, or is absent, because
     * the cost of the two errors is nowhere near symmetric.
     *
     * Matched on a lowercase substring, so "Tornado Warning" and "PDS Tornado Warning" both hit.
     */
    private val ALWAYS_RED = listOf(
        "tornado warning", "tsunami warning", "flash flood emergency", "extreme wind warning",
        "civil danger", "nuclear power plant warning", "radiological hazard warning",
        "hazardous materials warning", "shelter in place warning", "evacuation immediate",
        "law enforcement warning", "fire warning", "volcano warning", "earthquake warning",
    )

    /**
     * The condition this alert justifies, or NONE.
     *
     * The grading is [CapAlerts.grade]'s, not a second opinion: it already folds urgency and
     * certainty into severity (a *severe* thunderstorm that is *immediate* and *observed* is lifted
     * to EXTREME; a *moderate* flood that is *future* and merely *possible* is dropped to LOW). Any
     * other mapping here would be a quietly competing definition of the same thing, which this
     * codebase has had to correct four times over palettes alone.
     *
     * An expired alert is NONE regardless — the feed is cached and served offline, and an alert that
     * ended two hours ago is not a danger.
     */
    fun tierFor(a: Official, nowMs: Long): Tier {
        if (CapAlerts.hasExpired(a.expiresMs, nowMs)) return Tier.NONE
        if (ALWAYS_RED.any { it in (a.event + " " + a.headline).lowercase() }) return Tier.RED
        return when (CapAlerts.grade(a.severity, a.urgency, a.certainty)) {
            CapAlerts.Grade.EXTREME -> Tier.RED
            CapAlerts.Grade.HIGH -> Tier.YELLOW
            CapAlerts.Grade.MODERATE -> Tier.ADVISORY
            CapAlerts.Grade.LOW -> Tier.NONE
        }
    }

    /**
     * Whether this alert earns the full-screen takeover and the alarm.
     *
     * RED and nothing less. A yellow alert belongs on the board, where it can be read when the
     * reader is ready; an alarm that sounds for a coastal flood advisory teaches its owner to ignore
     * the one that sounds for a tornado, which makes the feature worse than not having it.
     */
    fun warrantsTakeover(a: Official, nowMs: Long): Boolean = tierFor(a, nowMs) == Tier.RED

    /**
     * The one alert to take the screen for, or null.
     *
     * Newest first among those that qualify and have not already been raised, because two live
     * warnings mean the later one is the newer information. Already-raised ids are skipped rather
     * than re-raised: an alert stays active for its whole life, and re-sounding the alarm every
     * poll for the same tornado is how a person ends up putting the phone in a drawer.
     */
    fun pick(alerts: List<Official>, raisedIds: Collection<String>, nowMs: Long): Official? =
        alerts.asSequence()
            .filter { it.id.isNotBlank() && it.id !in raisedIds }
            .filter { warrantsTakeover(it, nowMs) }
            .maxByOrNull { it.effectiveMs }

    /** The highest condition any live alert justifies — what the app's own alert state should read. */
    fun highest(alerts: List<Official>, nowMs: Long): Tier =
        alerts.map { tierFor(it, nowMs) }.maxByOrNull { it.ordinal } ?: Tier.NONE

    /** "RED ALERT" / "YELLOW ALERT" / "ADVISORY", the way the console says it. */
    fun condition(tier: Tier): String = when (tier) {
        Tier.RED -> "RED ALERT"
        Tier.YELLOW -> "YELLOW ALERT"
        Tier.ADVISORY -> "ADVISORY"
        Tier.NONE -> ""
    }

    /**
     * One line naming the hazard and where — for the board's ALERT row and for speech.
     *
     * The event type leads because it is the word that decides what you do. The issuer's headline
     * often repeats the area and the times, so it is used only when it adds something.
     */
    fun summary(a: Official): String {
        val head = a.event.trim().ifBlank { a.headline.trim() }.ifBlank { "Emergency alert" }
        val where = a.area.trim().takeIf { it.isNotBlank() && !head.contains(it, ignoreCase = true) }
        return listOfNotNull(head, where).joinToString(" — ")
    }

    /**
     * How long is left, in words, or null when the alert did not say.
     *
     * Null rather than "unknown": an expiry the issuer did not publish is not information, and a row
     * that prints the word "unknown" has spent a line telling the reader nothing.
     */
    fun remaining(a: Official, nowMs: Long): String? {
        val e = a.expiresMs ?: return null
        if (e <= 0L) return null
        val left = e - nowMs
        if (left <= 0L) return null
        val mins = left / 60_000L
        return when {
            mins < 1L -> "under a minute left"
            mins < 60L -> "$mins min left"
            else -> {
                val h = mins / 60L
                val m = mins % 60L
                if (m == 0L) "${h}h left" else "${h}h ${m}m left"
            }
        }
    }
}
