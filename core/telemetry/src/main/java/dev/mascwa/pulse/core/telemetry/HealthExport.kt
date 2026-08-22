package dev.mascwa.pulse.core.telemetry

import java.util.Locale

/**
 * Everything this tab has recorded about you, in a file you own.
 *
 * ## Why it exists
 *
 * The health record is the one thing in this app that cannot be refetched. Markets, weather and news
 * all come back from a server; a year of weigh-ins and nine thousand logged meals exist on exactly one
 * phone. A person who wants to move to another tool, keep a copy, or hand a year of intake to a
 * dietitian should not have to be told the data is trapped.
 *
 * ## Three rules carry this file, and each one has a plausible-looking wrong answer
 *
 * 1. **RFC 4180 quoting is not optional here.** The bundled food seed contains
 *    `Chicken breast, baked, skin not eaten` — literally, commas and all — so an unquoted writer
 *    silently turns one food into four columns and shifts every number on that row one place left.
 *    The corruption looks like a data-entry mistake rather than a bug in the writer.
 * 2. **Every number is [Locale.US].** On a comma-decimal device `"%.1f"` writes `1,3`, which in a
 *    comma-separated file is two fields. This trap has bitten this repo repeatedly; here it does not
 *    merely misprint a figure, it destroys the row.
 * 3. **A text field beginning `=`, `+`, `-` or `@` is a formula to a spreadsheet.** Open Food Facts is
 *    crowd-sourced — anybody can name a product — so a hostile name reaching Excel is a documented
 *    attack, not a hypothetical. Such fields are prefixed with an apostrophe, which visibly alters the
 *    value rather than silently executing it. ⚠️ **Numeric fields are deliberately exempt**: a minus
 *    sign is how a negative number begins, and quoting them as text would break every chart the
 *    export exists to feed.
 *
 * The caller supplies the date formatting, as every core here does — the app layer knows the zone, and
 * a date derived from UTC inside a pure module is a day out for half the planet.
 */
object HealthExport {

    /** RFC 4180 says CRLF, and the companion app runs on Windows. Everything else copes with it. */
    const val EOL = "\r\n"

    /** Characters that make a spreadsheet treat a cell as an expression rather than a value. */
    private const val FORMULA_LEADERS = "=+-@"

    // ------------------------------------------------------------------------------------ fields

    /**
     * One field, quoted if it has to be.
     *
     * [text] marks a field whose content is prose rather than a number, which is the only kind the
     * formula guard applies to. See rule 3 above for why that distinction is load-bearing.
     */
    fun field(value: String, text: Boolean = true): String {
        val guarded =
            if (text && value.isNotEmpty() && value[0] in FORMULA_LEADERS) "'$value" else value
        val needsQuotes = guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + guarded.replace("\"", "\"\"") + "\"" else guarded
    }

    /** A number, in the one locale a CSV can be read in. Blank for a value that is not a number. */
    fun num(value: Double, decimals: Int = 1): String {
        if (!value.isFinite()) return ""
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    /**
     * A whole row, terminated.
     *
     * Values are pre-rendered by the callers so each one can say whether it is text: passing raw
     * strings and guessing here would either apply the formula guard to numbers or skip it on names.
     */
    fun row(cells: List<String>): String = cells.joinToString(",") + EOL

    private fun header(vararg names: String): String = row(names.toList())

    // ------------------------------------------------------------------------------- the exports

    /** File names, in one place, so the surface and the writer cannot disagree about what is in the zip. */
    const val FOOD_FILE = "food-log.csv"
    const val DAYS_FILE = "daily-totals.csv"
    const val WEIGHT_FILE = "weigh-ins.csv"
    const val MEASURE_FILE = "measurements.csv"

    /**
     * Every logged entry, one row each.
     *
     * ⚠️ The epoch column is kept **beside** the readable date rather than instead of it. A local date
     * is what a person needs and is ambiguous across zones and clock changes; an epoch is exact and
     * unreadable. Both cost eight bytes and remove the argument.
     */
    fun foodLog(
        entries: List<NutritionDay.Entry>,
        dayLabel: (Long) -> String,
        timeLabel: (Long) -> String,
    ): String = buildString {
        append(
            header(
                "date", "time", "meal", "food", "brand", "serving", "grams",
                "energy_kcal", "protein_g", "fat_g", "carbs_g",
                "fibre_g", "sugar_g", "saturated_fat_g", "sodium_mg",
                "source", "day_epoch_ms", "logged_epoch_ms", "entry_id",
            ),
        )
        for (e in entries.sortedWith(compareBy({ it.dayStartMs }, { it.atMs }))) {
            val n = e.nutrients
            append(
                row(
                    listOf(
                        field(dayLabel(e.dayStartMs)),
                        field(timeLabel(e.atMs)),
                        field(e.meal.label),
                        field(e.name),
                        field(e.brand),
                        field(e.servingLabel),
                        num(e.grams),
                        num(n.kcal),
                        num(n.proteinG),
                        num(n.fatG),
                        num(n.carbG),
                        num(n.fibreG),
                        num(n.sugarG),
                        num(n.satFatG),
                        num(n.sodiumMg),
                        field(e.source.label),
                        e.dayStartMs.toString(),
                        e.atMs.toString(),
                        field(e.id),
                    ),
                ),
            )
        }
    }

    /**
     * What each day came to.
     *
     * ⚠️ Days with nothing logged are **absent**, exactly as they are everywhere else in this feature.
     * A row of zeros is a measurement nobody made, and a spreadsheet averaging this column would
     * report a starving person for anybody who skipped a weekend.
     */
    fun dailyTotals(
        days: Map<Long, NutritionDay.Nutrients>,
        dayLabel: (Long) -> String,
    ): String = buildString {
        append(header("date", "energy_kcal", "protein_g", "fat_g", "carbs_g", "day_epoch_ms"))
        for ((day, n) in days.entries.sortedBy { it.key }) {
            append(
                row(
                    listOf(
                        field(dayLabel(day)),
                        num(n.kcal),
                        num(n.proteinG),
                        num(n.fatG),
                        num(n.carbG),
                        day.toString(),
                    ),
                ),
            )
        }
    }

    /**
     * Every weigh-in, with the smoothed trend beside it.
     *
     * ⚠️ The trend column is what [BodyTrend] made of the readings **up to and including that day**,
     * not the whole series — a trend computed with hindsight is not the number the app showed at the
     * time, and somebody checking the export against a screenshot would find them disagreeing.
     */
    fun weighins(
        readings: List<BodyTrend.Weighin>,
        trendKgAt: (Int) -> Double?,
        dayLabel: (Long) -> String,
        noteFor: (Long) -> String,
    ): String = buildString {
        append(header("date", "weight_kg", "trend_kg", "note", "epoch_ms"))
        val sorted = readings.sortedBy { it.atMs }
        sorted.forEachIndexed { i, w ->
            append(
                row(
                    listOf(
                        field(dayLabel(w.atMs)),
                        num(w.kg, 2),
                        trendKgAt(i)?.let { num(it, 2) } ?: "",
                        field(noteFor(w.atMs)),
                        w.atMs.toString(),
                    ),
                ),
            )
        }
    }

    /** Circumference measurements, long-form — one row per reading, not a column per body part. */
    fun measurements(
        rows: List<Triple<Long, String, Double>>,
        dayLabel: (Long) -> String,
    ): String = buildString {
        append(header("date", "site", "cm", "epoch_ms"))
        for ((atMs, site, cm) in rows.sortedBy { it.first }) {
            append(row(listOf(field(dayLabel(atMs)), field(site), num(cm, 1), atMs.toString())))
        }
    }

    /**
     * The one-line summary the surface shows after writing, so nobody has to open the file to find out
     * whether it worked.
     */
    fun summarise(entries: Int, days: Int, weighins: Int, measurements: Int): String {
        val parts = buildList {
            if (entries > 0) add("$entries ${if (entries == 1) "entry" else "entries"}")
            if (days > 0) add("$days ${if (days == 1) "day" else "days"}")
            if (weighins > 0) add("$weighins ${if (weighins == 1) "weigh-in" else "weigh-ins"}")
            if (measurements > 0) add("$measurements measurements")
        }
        return if (parts.isEmpty()) {
            "Nothing recorded yet, so the files are headers only."
        } else {
            "Exported " + parts.joinToString(", ") + "."
        }
    }
}
