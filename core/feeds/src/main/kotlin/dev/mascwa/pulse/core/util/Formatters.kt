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
     */
    fun relativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0) return ""
        val diff = nowMs - epochMs
        if (diff < 0) return "just now"
        val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            hours < 24 -> "${hours}h ago"
            days < 2 -> "yesterday"
            days < 7 -> "${days}d ago"
            else -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = nowMs
                val nowYear = cal.get(java.util.Calendar.YEAR)
                cal.timeInMillis = epochMs
                val thenYear = cal.get(java.util.Calendar.YEAR)
                val pattern = if (thenYear == nowYear) "MMM d" else "MMM d yyyy"
                java.text.SimpleDateFormat(pattern, Locale.getDefault())
                    .format(java.util.Date(epochMs))
            }
        }
    }
}
