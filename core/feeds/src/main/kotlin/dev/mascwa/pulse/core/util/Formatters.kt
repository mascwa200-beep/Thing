package dev.mascwa.pulse.core.util

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Locale-aware formatting helpers used across every screen. */
object Formatters {

    fun currency(value: Double?, currencyCode: String, maxFractionDigits: Int = 2): String {
        if (value == null) return "—"
        return try {
            val nf = NumberFormat.getCurrencyInstance(Locale.getDefault())
            nf.currency = Currency.getInstance(currencyCode)
            nf.maximumFractionDigits = maxFractionDigits
            nf.minimumFractionDigits = minOf(2, maxFractionDigits)
            nf.format(value)
        } catch (_: Exception) {
            "$currencyCode ${number(value, maxFractionDigits)}"
        }
    }

    fun number(value: Double?, maxFractionDigits: Int = 2): String {
        if (value == null) return "—"
        val nf = NumberFormat.getNumberInstance(Locale.getDefault())
        nf.maximumFractionDigits = maxFractionDigits
        return nf.format(value)
    }

    /**
     * A short axis label that stays honest: 3.0 -> "3", 3.25 -> "3.3", 0.004 -> "4e-03".
     *
     * ⚠️ A different rule from [number] and [compact], and it lives here because BOTH chart kits draw
     * it — the phone's and the desktop's. The scientific tail is not decoration: an X-ray flux axis
     * runs over several decades, and rounding 4e-08 to "0.00" would make every tick on it identical.
     *
     * `Locale.US` throughout, deliberately. These are the tick labels of a chart drawn beside its own
     * gridlines, and a comma decimal separator reads as a thousands separator against them.
     */
    fun axisLabel(v: Double): String {
        // roundToInt() throws outright on NaN, and a NaN reaches here whenever any series value is
        // NaN — minOf/maxOf propagate it straight into the axis ticks.
        if (!v.isFinite()) return "—"
        val whole = v.roundToInt()
        return when {
            v == 0.0 -> "0"
            abs(v) >= 100 -> whole.toString()
            abs(v) >= 1 && v == whole.toDouble() -> whole.toString()
            abs(v) >= 1 -> String.format(Locale.US, "%.1f", v)
            abs(v) >= 0.01 -> String.format(Locale.US, "%.2f", v)
            else -> String.format(Locale.US, "%.0e", v)
        }
    }

    /** Compact, human magnitudes: 1.2K, 3.4M, 5.6B, 7.8T. */
    fun compact(value: Double?): String {
        if (value == null) return "—"
        val v = abs(value)
        val sign = if (value < 0) "-" else ""
        return when {
            v >= 1_000_000_000_000.0 -> "$sign${trim(v / 1_000_000_000_000.0)}T"
            v >= 1_000_000_000.0 -> "$sign${trim(v / 1_000_000_000.0)}B"
            v >= 1_000_000.0 -> "$sign${trim(v / 1_000_000.0)}M"
            v >= 1_000.0 -> "$sign${trim(v / 1_000.0)}K"
            else -> "$sign${trim(v)}"
        }
    }

    private fun trim(v: Double): String {
        val s = String.format(Locale.getDefault(), "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    fun signedPercent(value: Double?, digits: Int = 2): String {
        if (value == null) return "—"
        val sign = if (value > 0) "+" else ""
        return "$sign${String.format(Locale.getDefault(), "%.${digits}f", value)}%"
    }

    fun percent(value: Double?, digits: Int = 1): String {
        if (value == null) return "—"
        return "${String.format(Locale.getDefault(), "%.${digits}f", value)}%"
    }

    /**
     * A size on disk, to one decimal, in the unit a phone's own storage screen means by "MB".
     *
     * ⚠️ **Mebibytes — 1,048,576 — not a million.** The two differ by about 5%, which is not a
     * rounding difference but a different unit wearing the same name, and every file manager and
     * settings screen the reader could compare this against uses the first.
     *
     * ⚠️ **Not integer division.** `bytes / (1024 * 1024)` renders 1.9 MB as "1 MB", so a download
     * that has fetched most of a megabyte reads as having fetched none of it. One of the three
     * hand-rolled copies this replaced did exactly that.
     *
     * A negative count is unknown rather than small, and says so. Zero is a real answer — nothing
     * stored is nothing stored — so callers that mean "not fetched yet" test for it themselves.
     */
    fun megabytes(bytes: Long): String =
        if (bytes < 0L) "?" else String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

    /**
     * "3m ago", "2h ago", "yesterday", "Jun 12", "Jun 12 2024".
     *
     * Past a week this becomes a date, and the year appears as soon as the date is not in the year
     * we are currently in. Without that, a news feed shows "Aug 12" for an article from last August
     * and one from this August identically, which is precisely the confusion a timestamp exists to
     * prevent — and a resurfaced old story is exactly the case where the reader most needs to know.
     *
     * Device locale and zone throughout, deliberately: this is a date for a person to read, not a
     * value anything parses back, so the reader's own conventions and calendar are the right ones.
     * That also means "this year" means this year *where the reader is*, which is the answer they
     * would give themselves.
     *
     * ⚠️ **"Yesterday" and "N days ago" are CALENDAR claims, so they are answered by a calendar and
     * not by elapsed time.** The day branches used to divide the elapsed milliseconds — so anything
     * between 24 and 48 hours old read as "yesterday", and something posted on Monday morning was
     * still called yesterday on Wednesday. That is the shape of defect this whole family has: a
     * sentence about which day it was, decided by how much time has passed. `relativeDay` in the
     * health feature had the same one and was fixed the same way.
     *
     * ⚠️ It also means a day is not 24 hours. Where the clocks go back, a local day runs 25 hours —
     * so more than a day of elapsed time can still be the same date, and the hour reading is the
     * honest one there. The zero case below is that, not padding.
     */
    fun relativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0) return ""
        val diff = nowMs - epochMs
        if (diff < 0) return "just now"
        val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        if (mins < 1) return "just now"
        if (mins < 60) return "${mins}m ago"
        if (hours < 24) return "${hours}h ago"

        val zone = java.time.ZoneId.systemDefault()
        val then = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val calendarDays = java.time.temporal.ChronoUnit.DAYS.between(then, today)
        return when {
            calendarDays <= 0L -> "${hours}h ago"
            calendarDays == 1L -> "yesterday"
            calendarDays < 7L -> "${calendarDays}d ago"
            else -> {
                val pattern = if (then.year == today.year) "MMM d" else "MMM d yyyy"
                java.text.SimpleDateFormat(pattern, Locale.getDefault())
                    .format(java.util.Date(epochMs))
            }
        }
    }
}
