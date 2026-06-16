package dev.mascwa.pulse.core.util

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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

    /** "3m ago", "2h ago", "yesterday", "Jun 12". */
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
                val fmt = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
                fmt.format(java.util.Date(epochMs))
            }
        }
    }
}
