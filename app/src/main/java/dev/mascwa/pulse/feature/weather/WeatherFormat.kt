package dev.mascwa.pulse.feature.weather

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Lightweight parsing of Open-Meteo local-time ISO strings for display. */
object WeatherFormat {

    private val hourlyParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    private val dateParser = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun parseHourly(iso: String): Date? = runCatching { hourlyParser.parse(iso) }.getOrNull()

    fun hourLabel(iso: String, use24h: Boolean): String {
        val d = parseHourly(iso) ?: return iso.takeLast(5)
        val fmt = SimpleDateFormat(if (use24h) "HH:mm" else "h a", Locale.getDefault())
        return fmt.format(d)
    }

    fun timeLabel(iso: String, use24h: Boolean): String = hourLabel(iso, use24h)

    fun dayLabel(isoDate: String, index: Int): String {
        if (index == 0) return "Today"
        val d = runCatching { dateParser.parse(isoDate) }.getOrNull() ?: return isoDate
        return SimpleDateFormat("EEE", Locale.getDefault()).format(d)
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
}
