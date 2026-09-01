package dev.mascwa.pulse.feature.weather

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lightweight parsing of Open-Meteo local-time ISO strings for display.
 *
 * ## ⚠️ Why `java.time` and not `SimpleDateFormat`
 *
 * This object is a process-wide singleton and a `SimpleDateFormat` keeps a mutable `Calendar`
 * inside it, so holding one here as a field was the same defect `SafetyRepository` already carried
 * and fixed: concurrent `parse` does not merely throw, it can return a time assembled from two
 * different strings.
 *
 * ⚠️ **The exposure is measured, not assumed — there are three independent callers on different
 * threads.** `WeatherScreen` parses from composition on the main thread; `OracleEngine.snapshot`
 * walks the hourly array from the background worker AND from its own view model; `DayAheadEngine`
 * does the same from `BriefEngine.publish`. That last one is the consequential one: it computes
 * **when to leave**, so a scrambled timestamp there is a wrong departure time on the ALERT row of
 * the one notification. `DateTimeFormatter` is immutable and safe to share.
 *
 * The second reason is allocation. The display formatters were built PER CALL, and the hourly
 * labels are drawn once per row on a screen that shows 48 of them.
 *
 * ## What is deliberately unchanged
 *
 * ⚠️ Parsing still resolves through the DEVICE's zone, exactly as before, and [parseHourly] still
 * returns a `Date` so the ten call sites did not move. Open-Meteo returns wall-clock time local to
 * the place asked about, so reading it in the device zone and formatting it back in the device zone
 * round-trips to the same clock face — which is the label a reader wants. It is only the
 * *comparison* in [nowIndex] that assumes the device is in that place's zone, and that is a
 * pre-existing design point rather than something this change touches.
 */
object WeatherFormat {

    private val HOURLY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.US)
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun parseHourly(iso: String): Date? = runCatching {
        Date.from(LocalDateTime.parse(iso, HOURLY).atZone(ZoneId.systemDefault()).toInstant())
    }.getOrNull()

    fun parseDate(isoDate: String): Date? = runCatching {
        Date.from(LocalDate.parse(isoDate, DATE).atStartOfDay(ZoneId.systemDefault()).toInstant())
    }.getOrNull()

    fun hourLabel(iso: String, use24h: Boolean): String {
        val d = parseHourly(iso) ?: return iso.takeLast(5)
        return display(if (use24h) "HH:mm" else "h a").format(toLocal(d))
    }

    fun timeLabel(iso: String, use24h: Boolean): String = hourLabel(iso, use24h)

    fun dayLabel(isoDate: String, index: Int): String {
        if (index == 0) return "Today"
        return shortDayLabel(isoDate)
    }

    /** "Mon". A chart slot is too narrow for "Today", and every day needs the same width. */
    fun shortDayLabel(isoDate: String): String {
        val d = parseDate(isoDate) ?: return isoDate.takeLast(2)
        return display("EEE").format(toLocal(d))
    }

    /** Index of the hourly slot at/after "now", so we show the upcoming hours. */
    fun nowIndex(times: List<String>): Int {
        val now = Calendar.getInstance().time
        val idx = times.indexOfFirst { (parseHourly(it)?.after(now) ?: false) }
        return (idx - 1).coerceAtLeast(0)
    }

    fun aqiLabel(aqi: Double?): String = when {
        aqi == null -> "—"
        aqi <= 20 -> "Good"
        aqi <= 40 -> "Fair"
        aqi <= 60 -> "Moderate"
        aqi <= 80 -> "Poor"
        aqi <= 100 -> "Very poor"
        else -> "Extremely poor"
    }

    private fun toLocal(d: Date): LocalDateTime =
        LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault())

    /**
     * A display formatter in the reader's own language, built at most once per (pattern, locale).
     *
     * ⚠️ **Not a plain constant, and the reason is a real if rare wrong.** `DateTimeFormatter
     * .ofPattern(p)` resolves the locale at CONSTRUCTION, so a formatter held as a top-level `val`
     * would keep printing day names in whatever language the process started in — and this object
     * outlives the Activity that Android recreates on a locale change. The old code dodged that by
     * building a `SimpleDateFormat` per call, which was correct and wasteful; this keeps the
     * correctness and pays the construction once.
     *
     * ⚠️ Unlocked on purpose. `DateTimeFormatter` is immutable and the map is only ever grown, so a
     * race costs one duplicate construction — a lock on a path called once per row would be the
     * worse trade. `java.util.concurrent` rather than a plain map because the callers really are on
     * different threads, which is the whole subject of this file's header.
     */
    private val displayCache = java.util.concurrent.ConcurrentHashMap<String, DateTimeFormatter>()

    private fun display(pattern: String): DateTimeFormatter {
        val locale = Locale.getDefault()
        val key = "$pattern|$locale"
        return displayCache.getOrPut(key) { DateTimeFormatter.ofPattern(pattern, locale) }
    }
}
