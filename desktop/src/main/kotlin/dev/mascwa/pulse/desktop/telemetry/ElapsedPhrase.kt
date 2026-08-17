// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/ElapsedPhrase.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * "3 hours ago", "yesterday", "2 years ago" — a duration said the way a person would say it.
 *
 * Extracted from [TemporalReasoner], which still exposes it under its own name so every existing caller
 * is untouched. It lives on its own because it is the one part of that file with no dependency on the
 * memory stream, and two things now need it: the memory timeline, and [Freshness] telling you how old
 * the numbers on screen are. Splitting it is what lets the second travel to the desktop without dragging
 * the whole memory subsystem along, and keeps both platforms saying it the same way.
 *
 * Calendar-free on purpose: no time zone, no locale, no `Calendar`. That makes it pure, testable with no
 * clock, and safe to mirror.
 */
object ElapsedPhrase {

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
    private const val MONTH = 30 * DAY
    private const val YEAR = 365 * DAY

    /** Sign-agnostic: pass an absolute elapsed value, or don't — the magnitude is what is described. */
    fun describe(elapsedMs: Long): String {
        val ms = abs(elapsedMs)
        return when {
            ms < 45 * SECOND -> "just now"
            ms < 90 * SECOND -> "a minute ago"
            ms < 45 * MINUTE -> "${(ms.toDouble() / MINUTE).roundToLong()} minutes ago"
            ms < 90 * MINUTE -> "an hour ago"
            ms < 22 * HOUR -> "${(ms.toDouble() / HOUR).roundToLong()} hours ago"
            ms < 36 * HOUR -> "yesterday"
            ms < 25 * DAY -> "${(ms.toDouble() / DAY).roundToLong()} days ago"
            ms < 11 * WEEK -> "${(ms.toDouble() / WEEK).roundToLong()} weeks ago"
            ms < 320 * DAY -> "${(ms.toDouble() / MONTH).roundToLong()} months ago"
            ms < 548 * DAY -> "a year ago"
            else -> "${(ms.toDouble() / YEAR).roundToLong()} years ago"
        }
    }
}
