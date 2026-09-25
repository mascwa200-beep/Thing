package dev.mascwa.pulse.sky

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * The one rule for which instant the map is drawing.
 *
 * ⚠️ **This exists because the chart shipped with its clock frozen at the moment the screen
 * composed, and with a second clock for the tap.** `SkyChart` held the instant in
 * `remember(hours, site)`, so it changed only when the scrubber moved or the fix did; the Sun,
 * Moon and planets were recomputed only then too; and `identify` read `System.currentTimeMillis()`
 * live. The sky turns at 15.04 arcseconds a second — **a quarter of a degree a minute, a whole
 * quarter-degree field in one minute, two and a half degrees in ten** — so a map left open while
 * somebody found their way round the constellations was drawing a sky that was no longer overhead,
 * and after about ten minutes a tap on a drawn star resolved, in the live frame, to a place the star
 * was no longer drawn at. Two clocks for one screen is exactly what `refreshDeep`'s note warns
 * against, and this is where the single derivation now lives.
 *
 * ## The time control's reach
 *
 * The scrubber used to offer a day either way in whole hours. It now offers **any instant from
 * [MIN_YEAR] to [MAX_YEAR]**, and those two numbers are not taste: they are the span over which
 * every piece of arithmetic under the map is validated. VSOP87 is fitted for 1900–2100 (the
 * builder's truncation bar was measured against DE421 over exactly that span), the star catalogue's
 * proper motions are linear extrapolations that stop being honest much past a century, and
 * `EconomyVintage`-style calendar arithmetic elsewhere in this repository has already shown what a
 * date outside the range something was designed for does — it produces a plausible number. A date
 * outside the span is refused rather than drawn wrong; [boundedOffset] is the refusal.
 *
 * ⚠️ **A scrubbed time is an OFFSET from now, not a frozen instant** — `SkyMapViewModel` says why —
 * so the bound is applied to the INSTANT the offset produces, and the offset is then whatever
 * carries now to that instant. Bounding the offset itself would let the instant walk out of range as
 * real time passed.
 *
 * Pure and Android-free so the arithmetic is held by a test that runs here rather than only in CI.
 * `java.util.Calendar` rather than `java.time`, because this module's floor is API 23 and
 * `java.time` arrives at 26 — and lint's `NewApi` is fatal in this build precisely so that a
 * reach for the newer API fails the build instead of throwing on the oldest phone.
 */
object SkyClock {

    /**
     * How often the drawn instant advances while the chart is on screen.
     *
     * ⚠️ Half a second, and the trade is stated rather than hidden. At the widest field the sky
     * moves a fifth of a pixel per tick, so the cadence is invisible; at the quarter-degree floor a
     * pixel is 0.8 arcseconds, so each tick is a nine-pixel step — visible if you stare, and the
     * price of not recomposing the whole canvas sixty times a second for a drift most fields cannot
     * show. A frame is two vectors, so the cost of the tick is the recomposition, not the maths.
     */
    const val TICK_MS = 500L

    /**
     * How far the instant may move before the deep field and the constellation cut are asked to
     * re-check what they hold.
     *
     * A minute. Neither depends on the clock except through proper motion, which `StarField`
     * already tolerates for a tenth of a year, so re-planning on every tick would be a coroutine
     * launched twice a second to answer "unchanged".
     */
    const val RELOAD_BUCKET_MS = 60_000L

    /**
     * How far the instant may move before the equatorial furniture — the equator, the ecliptic,
     * the equatorial grid and its labels — is rebuilt for the new date.
     *
     * A day. Precession carries those circles about half an arcminute a YEAR, so across a day the
     * change is a tenth of an arcsecond, far under any pixel; across the century the time control
     * now reaches it is a degree, which is a grid visibly off its own equator. Rebuilding on the
     * day boundary is therefore free in the scrubbing case and correct in the date-picker case.
     * The same bucket paces re-carrying the bright catalogue's proper motion, which has the same
     * shape: nothing across a day, arcminutes across the range.
     */
    const val FRAME_BUCKET_MS = 86_400_000L

    /** The earliest and latest years the map will draw — the span the arithmetic is validated over. */
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2100

    /** The bounds as instants: the first moment of [MIN_YEAR] and the last of [MAX_YEAR], UTC. */
    val MIN_INSTANT_MS: Long = utc(MIN_YEAR, Calendar.JANUARY, 1, 0, 0)
    val MAX_INSTANT_MS: Long = utc(MAX_YEAR, Calendar.DECEMBER, 31, 23, 59) + 59_999L

    /** The three steps the time control offers, so both screens' buttons move by one definition. */
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L

    /** The instant to draw: the wall clock, offset by the scrubber. */
    fun instantOf(nowMs: Long, offsetMs: Long): Long = nowMs + offsetMs

    /** Which reload bucket an instant falls in — see [RELOAD_BUCKET_MS]. */
    fun reloadBucket(instantMs: Long): Long = Math.floorDiv(instantMs, RELOAD_BUCKET_MS)

    /** Which frame bucket an instant falls in — see [FRAME_BUCKET_MS]. */
    fun frameBucket(instantMs: Long): Long = Math.floorDiv(instantMs, FRAME_BUCKET_MS)

    /**
     * The offset that lands the drawn instant inside [MIN_INSTANT_MS]..[MAX_INSTANT_MS], given the
     * one asked for.
     *
     * An offset inside the span comes back unchanged; one that would carry the instant past either
     * end comes back as exactly the offset that reaches that end, so a `+1D` pressed at the last day
     * of 2100 parks the map on the last minute of the century rather than refusing to move at all.
     */
    fun boundedOffset(nowMs: Long, wantedOffsetMs: Long): Long =
        instantOf(nowMs, wantedOffsetMs).coerceIn(MIN_INSTANT_MS, MAX_INSTANT_MS) - nowMs

    /**
     * The start of the LOCAL calendar day that holds [instantMs], in [zone].
     *
     * ⚠️ Through the calendar, never `instant − (instant mod 86_400_000)`: that is the start of the
     * UTC day, which is the wrong evening for most of the planet, and a local day is 23 hours long
     * one night a year and 25 another — the trap `HealthDays` in this repository exists to close.
     * Rise and set "today" are asked over this day.
     */
    fun localDayStart(instantMs: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone, Locale.US)
        c.timeInMillis = instantMs
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * The local date and time of an instant, for a readout: `Wed 23 Sep 2026 · 22:15`.
     *
     * English day and month abbreviations regardless of locale, deliberately: every other word on
     * that readout is English, both applications ship with English resources only, and a French
     * weekday beside "Looking SW" would be the one localised token on the screen.
     */
    fun formatLocal(instantMs: Long, zone: TimeZone): String {
        val c = Calendar.getInstance(zone, Locale.US)
        c.timeInMillis = instantMs
        val day = DAYS[c.get(Calendar.DAY_OF_WEEK) - 1]
        val month = MONTHS[c.get(Calendar.MONTH)]
        return "$day ${c.get(Calendar.DAY_OF_MONTH)} $month ${c.get(Calendar.YEAR)} · ${formatTime(instantMs, zone)}"
    }

    /** The local wall-clock time of an instant, `HH:MM`, 24-hour. */
    fun formatTime(instantMs: Long, zone: TimeZone): String {
        val c = Calendar.getInstance(zone, Locale.US)
        c.timeInMillis = instantMs
        return two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE))
    }

    /**
     * An offset from now in words: `+1d 2h`, `−10m`, `+3h 5m`. Whole minutes; seconds are not
     * shown because the control never moves by less than ten of them.
     */
    fun describeOffset(offsetMs: Long): String {
        if (offsetMs == 0L) return "now"
        val sign = if (offsetMs < 0) "−" else "+"
        var rest = abs(offsetMs) / MINUTE_MS
        val days = rest / (DAY_MS / MINUTE_MS)
        rest -= days * (DAY_MS / MINUTE_MS)
        val hours = rest / (HOUR_MS / MINUTE_MS)
        val minutes = rest - hours * (HOUR_MS / MINUTE_MS)
        val parts = ArrayList<String>(3)
        if (days > 0) parts += "${days}d"
        if (hours > 0) parts += "${hours}h"
        if (minutes > 0) parts += "${minutes}m"
        // An offset under a minute rounds to nothing above; it is still not "now".
        if (parts.isEmpty()) parts += "<1m"
        return sign + parts.joinToString(" ")
    }

    /**
     * What the readout says about WHEN: `now` when the map is live, else the absolute local
     * date-time and how far that is from now — `Wed 23 Sep 2026 · 22:15 (+1d 2h)`.
     *
     * Both halves, because they answer different questions: the date is what the picture is OF,
     * and the offset is how far you have scrubbed and which way the NOW button will take you.
     */
    fun whenLabel(instantMs: Long, offsetMs: Long, zone: TimeZone): String =
        if (offsetMs == 0L) "now" else "${formatLocal(instantMs, zone)} (${describeOffset(offsetMs)})"

    /**
     * A typed local date-time, `2026-09-23 22:15`, as an instant in [zone] — or null when it is
     * not one.
     *
     * ⚠️ Lenient in exactly one way: a missing time means midnight. Strict in every other: a month
     * of 13 or a day of 32 is refused rather than rolled forward into the next month, because a
     * rolled date is a date the person did not type, drawn with the same confidence as one they
     * did. The year is refused outside [MIN_YEAR]..[MAX_YEAR] for the reason the class says.
     */
    fun parseLocal(text: String, zone: TimeZone): Long? {
        val m = LOCAL_FORM.matchEntire(text.trim()) ?: return null
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].ifEmpty { "0" }.toInt()
        val minute = m.groupValues[5].ifEmpty { "0" }.toInt()
        if (year !in MIN_YEAR..MAX_YEAR || month !in 1..12 || day !in 1..31) return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val c = Calendar.getInstance(zone, Locale.US)
        c.isLenient = false
        c.clear()
        c.set(year, month - 1, day, hour, minute, 0)
        return runCatching { c.timeInMillis }.getOrNull()
    }

    /** A local calendar date and wall-clock time as an instant in [zone]; the picker's arithmetic. */
    fun localInstant(year: Int, month1: Int, day: Int, hour: Int, minute: Int, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone, Locale.US)
        c.clear()
        c.set(year, month1 - 1, day, hour, minute, 0)
        return c.timeInMillis
    }

    /** `[year, month (1-12), day, hour, minute]` of an instant in [zone] — what a picker is seeded with. */
    fun localFields(instantMs: Long, zone: TimeZone): IntArray {
        val c = Calendar.getInstance(zone, Locale.US)
        c.timeInMillis = instantMs
        return intArrayOf(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
        )
    }

    /**
     * The instant as the typed form [parseLocal] reads back: `2026-09-23 22:15`, in [zone].
     *
     * What the LCARS field is seeded with when it opens, so the person edits the date the map is
     * already drawing rather than typing one from nothing. Round-trips through [parseLocal] to the
     * minute, which is a property a test holds because the field and the parser are two functions.
     */
    fun formatEntry(instantMs: Long, zone: TimeZone): String {
        val f = localFields(instantMs, zone)
        return "${f[0]}-${two(f[1])}-${two(f[2])} ${two(f[3])}:${two(f[4])}"
    }

    /**
     * The LOCAL calendar date of an instant, in the form Material's date picker speaks: UTC
     * midnight of that date.
     *
     * ⚠️ The picker's `selectedDateMillis` is defined as midnight UTC of the picked day, not as an
     * instant in any zone — so seeding it with the instant itself puts the picker on the WRONG DAY
     * for everybody east of Greenwich late in the evening, and everybody west of it early in the
     * morning. The date is read in [zone] and then re-expressed in UTC; [pickerFields] is the
     * inverse, reading the picker's answer back in UTC.
     */
    fun pickerDateOf(instantMs: Long, zone: TimeZone): Long {
        val f = localFields(instantMs, zone)
        return utc(f[0], f[1] - 1, f[2], 0, 0)
    }

    /** `[year, month (1-12), day]` of a date picker's UTC-midnight selection — see [pickerDateOf]. */
    fun pickerFields(utcMidnightMs: Long): IntArray {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        c.timeInMillis = utcMidnightMs
        return intArrayOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun utc(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        c.clear()
        c.set(year, month0, day, hour, minute, 0)
        return c.timeInMillis
    }

    private fun two(n: Int): String = if (n < 10) "0$n" else n.toString()

    private val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    private val LOCAL_FORM = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2}))?""")
}
