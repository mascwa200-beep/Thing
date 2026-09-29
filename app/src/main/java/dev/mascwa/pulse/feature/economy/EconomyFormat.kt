package dev.mascwa.pulse.feature.economy

import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.economy.IndicatorSeries
import dev.mascwa.pulse.data.economy.ValueFormat

/**
 * The latest figure of a series, in the unit a reader expects: "3.0%", "$82,769", "334M".
 *
 * Moved out of `EconomyComponents.kt` — same package, same name, so the screen did not move — into a
 * file with no Compose in it, because the widget needs the same rule and a JVM test can now hold it.
 */
fun formatLatest(series: IndicatorSeries): String {
    val v = series.latest?.value ?: return "—"
    return when (series.format) {
        ValueFormat.PERCENT -> Formatters.percent(v)
        ValueFormat.CURRENCY_USD -> Formatters.currency(v, "USD", 0)
        ValueFormat.COMPACT -> Formatters.compact(v)
        ValueFormat.NUMBER -> Formatters.number(v)
    }
}

/**
 * One line for a glance: "Inflation (CPI) 3.0% · 2025", or null when the series has no figure.
 *
 * ⚠️ **The widget built this by hand, and it came out as "Inflation (CPI) 3annual % · 2025" and
 * "Unemployment 4.8% labour f · 2025".** It glued a bare number to the series' raw unit with no
 * space, dropped the decimal the Economy screen shows, and then cut the unit at ten characters,
 * mid-word. The unit string exists to caption the screen's large figure; on a line this short the
 * percent sign carries the unit and the year carries "annual". So the line takes its number from
 * [formatLatest], the screen's own rule, and the two cannot disagree about what 3.0 looks like.
 *
 * ⚠️ The year is not decoration. These are World Bank ANNUAL series, and "2.9%" without it reads
 * as this month's print.
 */
fun economyLine(series: IndicatorSeries): String? {
    val year = series.latest?.year ?: return null
    return "${series.indicatorTitle} ${formatLatest(series)} · $year"
}
