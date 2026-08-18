package dev.mascwa.pulse.core.telemetry

/**
 * Public weather and civil-emergency alerts, in the terms the standard actually defines.
 *
 * The US National Weather Service publishes in CAP — the Common Alerting Protocol — which grades an
 * alert on **three independent axes**, not one:
 *
 *  - **severity**  — how bad it would be. Extreme, Severe, Moderate, Minor.
 *  - **urgency**   — how soon. Immediate, Expected, Future, Past.
 *  - **certainty** — how sure. Observed, Likely, Possible, Unlikely.
 *
 * The app read severity alone, so a *Severe / Future / Possible* watch — something that might
 * happen tomorrow — was graded identically to a *Severe / Immediate / Observed* warning, which is
 * happening now and has been seen. Those are not the same message and should not look the same.
 *
 * It also discarded `instruction`, which is the field that says **what to do**, in a safety
 * feature, on 78 of 80 live alerts.
 *
 * Pure and CI-tested; the caller passes the strings straight from the feed.
 */
object CapAlerts {

    /** The four grades the app colours and alerts by. Mirrors `Severity` in the app module. */
    enum class Grade { LOW, MODERATE, HIGH, EXTREME }

    /** How soon, ordered so that later is more pressing. */
    enum class Urgency { UNKNOWN, PAST, FUTURE, EXPECTED, IMMEDIATE }

    /** How sure, ordered so that later is more confident. */
    enum class Certainty { UNKNOWN, UNLIKELY, POSSIBLE, LIKELY, OBSERVED }

    fun urgency(raw: String?): Urgency = when (raw?.lowercase()?.trim()) {
        "immediate" -> Urgency.IMMEDIATE
        "expected" -> Urgency.EXPECTED
        "future" -> Urgency.FUTURE
        "past" -> Urgency.PAST
        else -> Urgency.UNKNOWN
    }

    fun certainty(raw: String?): Certainty = when (raw?.lowercase()?.trim()) {
        "observed" -> Certainty.OBSERVED
        "likely" -> Certainty.LIKELY
        "possible" -> Certainty.POSSIBLE
        "unlikely" -> Certainty.UNLIKELY
        else -> Certainty.UNKNOWN
    }

    /**
     * Severity, adjusted by how soon and how sure.
     *
     * The rules, and why each one:
     *
     * 1. **Severity sets the base**, exactly as the app already graded it, so an alert carrying
     *    nothing else is unchanged.
     * 2. **Something already happening is not a forecast.** Immediate *and* observed lifts one
     *    grade — this is the combination that means go and look out of the window.
     * 3. **A distant maybe is not a warning.** Future or past, together with merely possible or
     *    unlikely, drops one grade. Both halves are required: a *possible* but *immediate*
     *    tornado stays where it is, and so does a *future* but *observed* river flood.
     * 4. **Unknown moves nothing.** As everywhere else here, absent is not evidence.
     */
    fun grade(severity: String?, urgency: String? = null, certainty: String? = null): Grade {
        val base = when (severity?.lowercase()?.trim()) {
            "extreme" -> Grade.EXTREME
            "severe" -> Grade.HIGH
            "moderate" -> Grade.MODERATE
            else -> Grade.LOW
        }
        val u = urgency(urgency)
        val c = certainty(certainty)
        val happeningNow = u == Urgency.IMMEDIATE && c == Certainty.OBSERVED
        val distantMaybe = (u == Urgency.FUTURE || u == Urgency.PAST) &&
            (c == Certainty.POSSIBLE || c == Certainty.UNLIKELY)
        return when {
            happeningNow -> Grade.entries[minOf(Grade.entries.lastIndex, base.ordinal + 1)]
            distantMaybe -> Grade.entries[maxOf(0, base.ordinal - 1)]
            else -> base
        }
    }

    /**
     * The short tag a row can show: "HAPPENING NOW", "LIKELY SOON", "FORECAST".
     *
     * Null when the feed said nothing useful, so the row stays clean rather than showing the word
     * "unknown", which tells the reader nothing they did not already have.
     */
    fun timing(urgency: String?, certainty: String?): String? {
        val u = urgency(urgency)
        val c = certainty(certainty)
        return when {
            u == Urgency.IMMEDIATE && c == Certainty.OBSERVED -> "HAPPENING NOW"
            u == Urgency.IMMEDIATE -> "IMMEDIATE"
            u == Urgency.EXPECTED && c >= Certainty.LIKELY -> "LIKELY SOON"
            u == Urgency.EXPECTED -> "EXPECTED"
            u == Urgency.FUTURE -> "FORECAST"
            u == Urgency.PAST -> "NO LONGER EXPECTED"
            else -> null
        }
    }

    /**
     * Whether an alert's own stated expiry has passed.
     *
     * Worth checking even though the endpoint is called "active": the result is cached, and served
     * from cache when offline for as long as it is all there is. Without this an alert that ended
     * hours ago is presented as a current danger, which is the same class of error as showing a
     * stale price as live.
     *
     * Unparseable or absent expiry returns false — an alert is not dismissed for a missing field.
     */
    fun hasExpired(expiresEpochMs: Long?, nowMs: Long): Boolean =
        expiresEpochMs != null && expiresEpochMs > 0L && expiresEpochMs < nowMs

    /**
     * The official instruction, tidied for a phone.
     *
     * CAP instruction text arrives wrapped at the width of a 1990s teletype, so the newlines fall
     * mid-sentence. Collapsing them is the difference between a readable paragraph and a ragged
     * column. Blank in, null out — the caller shows nothing rather than an empty row.
     */
    fun instruction(raw: String?, limit: Int = 400): String? {
        val flat = raw?.replace(Regex("\\s*\\n\\s*"), " ")?.replace(Regex("\\s{2,}"), " ")?.trim()
        if (flat.isNullOrBlank()) return null
        if (flat.length <= limit) return flat
        // Cut at a sentence end where there is one reasonably near the limit, so the text does not
        // stop mid-clause; otherwise fall back to a word boundary.
        val window = flat.take(limit)
        val stop = window.lastIndexOf(". ")
        return if (stop > limit / 2) window.take(stop + 1) else window.substringBeforeLast(' ') + "…"
    }
}
