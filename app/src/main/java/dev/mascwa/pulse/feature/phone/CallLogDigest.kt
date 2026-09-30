package dev.mascwa.pulse.feature.phone

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The RECENTS list: Android's call log, arranged the way a phone shows it.
 *
 * Pure apart from `java.time`, so every rule runs under JUnit. Reading the log is `CallLogRepository`.
 *
 * The rules, each of which has a plausible wrong answer:
 *
 * - **Consecutive calls with one number on one local day are one row**, with a count: three missed
 *   calls from the same caller in a morning read as one line, as they do on every phone. Consecutive
 *   only: a call from somebody else in between splits them, because the list is a timeline.
 * - ⚠️ **"One day" is the local calendar date**, taken from the instant in the phone's zone — never a
 *   division by 86,400,000, which is wrong on the two days a year the clocks change.
 * - **A withheld caller is never merged with a different withheld caller.** "Private number" twice in a
 *   row is one row only because nothing can tell them apart, and the row says so ([Row.withheld]).
 * - **The row's kind is the NEWEST call's**, and [Row.kinds] keeps the rest newest first, so "missed,
 *   then they called back and you answered" reads correctly from the top.
 */
object CallLogDigest {

    // ⚠️ `CallLog.Calls` type constants, restated so this file needs no Android type. Checked with
    // `javap -constants` against the platform-35 SDK stub: INCOMING 1, OUTGOING 2, MISSED 3,
    // VOICEMAIL 4, REJECTED 5, BLOCKED 6, ANSWERED_EXTERNALLY 7; PRESENTATION_ALLOWED 1, RESTRICTED 2,
    // UNKNOWN 3, PAYPHONE 4, UNAVAILABLE 5.
    const val TYPE_INCOMING = 1
    const val TYPE_OUTGOING = 2
    const val TYPE_MISSED = 3
    const val TYPE_VOICEMAIL = 4
    const val TYPE_REJECTED = 5
    const val TYPE_BLOCKED = 6
    const val TYPE_ANSWERED_EXTERNALLY = 7

    const val PRESENTATION_ALLOWED = 1
    const val PRESENTATION_RESTRICTED = 2
    const val PRESENTATION_UNKNOWN = 3
    const val PRESENTATION_PAYPHONE = 4
    const val PRESENTATION_UNAVAILABLE = 5

    enum class Kind { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, VOICEMAIL, ELSEWHERE }

    fun kindOf(type: Int): Kind = when (type) {
        TYPE_INCOMING -> Kind.INCOMING
        TYPE_OUTGOING -> Kind.OUTGOING
        TYPE_MISSED -> Kind.MISSED
        TYPE_REJECTED -> Kind.REJECTED
        TYPE_BLOCKED -> Kind.BLOCKED
        TYPE_VOICEMAIL -> Kind.VOICEMAIL
        // Answered on another device, and any type a later Android adds: it happened, and it was not
        // answered here — the one thing it must never be shown as is missed.
        else -> Kind.ELSEWHERE
    }

    /** One row of Android's call log, as `CallLogRepository` reads it. */
    data class Entry(
        val id: Long,
        val number: String,
        /** The name Android cached with the call, or null. */
        val cachedName: String?,
        val type: Int,
        val dateMs: Long,
        val durationSec: Long,
        val presentation: Int,
        /** A missed call nobody has looked at yet (`NEW = 1`). */
        val unread: Boolean,
    )

    data class Row(
        /** The newest call's id — a stable key for the list. */
        val id: Long,
        val number: String,
        val name: String?,
        val kind: Kind,
        /** Every call in the row, newest first. */
        val kinds: List<Kind>,
        val latestMs: Long,
        val day: LocalDate,
        val withheld: Boolean,
        /** Any call in the row is a missed call nobody has seen. */
        val unread: Boolean,
        val ids: List<Long>,
    ) {
        val count: Int get() = ids.size
    }

    /** Whether [e] has no number anybody can call back. */
    fun withheld(e: Entry): Boolean =
        e.presentation != PRESENTATION_ALLOWED || PhoneMatch.key(e.number) == null

    /** How a withheld caller is named, from the reason Android gives. */
    fun withheldLabel(presentation: Int): String = when (presentation) {
        PRESENTATION_RESTRICTED -> "Private number"
        PRESENTATION_PAYPHONE -> "Payphone"
        else -> "Unknown caller"
    }

    fun rows(entries: List<Entry>, zone: ZoneId): List<Row> {
        val sorted = entries.sortedWith(compareByDescending<Entry> { it.dateMs }.thenByDescending { it.id })
        val out = ArrayList<Row>()
        var group = ArrayList<Entry>()
        fun flush() {
            if (group.isEmpty()) return
            val newest = group.first()
            out += Row(
                id = newest.id,
                number = newest.number,
                name = group.firstNotNullOfOrNull { it.cachedName?.takeIf(String::isNotBlank) },
                kind = kindOf(newest.type),
                kinds = group.map { kindOf(it.type) },
                latestMs = newest.dateMs,
                day = dayOf(newest.dateMs, zone),
                withheld = withheld(newest),
                unread = group.any { it.unread && kindOf(it.type) == Kind.MISSED },
                ids = group.map { it.id },
            )
            group = ArrayList()
        }
        for (e in sorted) {
            val last = group.lastOrNull()
            if (last != null && !belongTogether(last, e, zone)) flush()
            group += e
        }
        flush()
        return out
    }

    private fun belongTogether(a: Entry, b: Entry, zone: ZoneId): Boolean {
        if (dayOf(a.dateMs, zone) != dayOf(b.dateMs, zone)) return false
        val wa = withheld(a)
        val wb = withheld(b)
        if (wa || wb) return wa && wb && a.presentation == b.presentation
        return PhoneMatch.same(a.number, b.number)
    }

    fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** The heading a day's rows sit under: TODAY, YESTERDAY, a weekday this past week, or a date. */
    fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when {
        day == today -> "TODAY"
        day == today.minusDays(1) -> "YESTERDAY"
        // A future date (a clock corrected backwards) is a date, never "today".
        day.isAfter(today) -> DATE.withLocale(locale).format(day).uppercase(locale)
        !day.isBefore(today.minusDays(6)) -> day.dayOfWeek.displayName(locale).uppercase(locale)
        day.year == today.year -> DATE.withLocale(locale).format(day).uppercase(locale)
        else -> DATE_YEAR.withLocale(locale).format(day).uppercase(locale)
    }

    /** A call's length as a phone shows it: 0:42, 12:05, 1:02:03. */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3_600
        val m = (s % 3_600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
    }

    private fun two(n: Long): String = if (n < 10) "0$n" else "$n"

    private fun DayOfWeek.displayName(locale: Locale): String = getDisplayName(TextStyle.FULL, locale)

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
    private val DATE_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
}
