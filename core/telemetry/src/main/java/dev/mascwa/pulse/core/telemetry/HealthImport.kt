package dev.mascwa.pulse.core.telemetry

/**
 * Reading back what [HealthExport] wrote.
 *
 * ## ⚠️ Deliberately NOT a general CSV importer, and that is the whole design
 *
 * Every app's export differs, and a parser guessing which column is which writes bad data into the
 * log — which the coach then reads, and which turns into a calorie target somebody eats to. The
 * honest offer is a format this app defines, can round-trip and can test, plus a **clear refusal
 * naming the header it could not understand** for anything else. A refusal somebody can act on beats
 * a silent misreading every time.
 *
 * ## The four rules that carry this file, each with a plausible wrong answer
 *
 * 1. **Columns are matched by header NAME, never by position.** The export's column list has grown
 *    twice in one session — eight micronutrients, then the rest — so a positional parser would
 *    silently misread a file this app wrote last month, shifting every number one place left.
 * 2. **The formula apostrophe is un-guarded.** [HealthExport.field] prefixes `'` to a *text* field
 *    beginning `=`, `+`, `-` or `@`, because a spreadsheet treats those as expressions. Without the
 *    inverse here, a food genuinely named `-Brand` comes back as `'-Brand`, and every export-import
 *    cycle adds another apostrophe.
 * 3. **A leading byte-order mark is stripped.** `HealthExporter` writes one on purpose — Excel on
 *    Windows reads a BOM-less UTF-8 CSV as Windows-1252 and mangles every accented food name. Without
 *    stripping it the first header is `﻿date`, header matching fails, and the app silently
 *    refuses **every file it wrote itself**.
 * 4. **An unreadable NUMBER refuses its row; an unreadable LABEL does not.** A meal genuinely eaten
 *    must not be lost because a display string was renamed, so meal and source fall back. A wrong
 *    number is the one thing this feature must never write.
 *
 * ## ⚠️ One asymmetry that cannot be fixed from this side, stated rather than hidden
 *
 * A name whose first character is already an apostrophe AND whose second is a formula leader —
 * `'=x` — is written unguarded by the exporter (its first character is not a leader) and read back
 * here as `=x`. It is the only value in the format that does not round-trip, it requires a food
 * literally named that, and fixing it means changing what the writer emits. Recorded so nobody
 * "fixes" the reader into breaking the common case.
 *
 * The caller supplies the calendar, as every core here does — see [foodLog].
 */
object HealthImport {

    /** Characters a spreadsheet reads as the start of an expression. Mirrors the writer's list. */
    private const val FORMULA_LEADERS = "=+-@"

    /** How many distinct explanations are worth carrying back to a screen. */
    const val MAX_REASONS = 5

    // ------------------------------------------------------------------------------ the reader

    /**
     * RFC 4180, including the parts that are easy to skip.
     *
     * Doubled quotes inside a quoted field, commas inside a quoted field, and **newlines** inside a
     * quoted field — the last is why this is a character scanner rather than `split('\n')` followed
     * by `split(',')`. A diary note with a line break in it would otherwise become two broken rows.
     *
     * Accepts CRLF or LF line endings: the writer emits CRLF, and anything that has passed through a
     * text editor may not.
     */
    fun parse(csv: String): List<List<String>> {
        val text = csv.removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        var sawAny = false

        fun endCell() {
            row.add(cell.toString())
            cell.setLength(0)
        }

        fun endRow() {
            endCell()
            // A trailing newline should not produce a final row of one empty cell.
            if (row.size > 1 || row.firstOrNull()?.isNotEmpty() == true) rows.add(row)
            row = mutableListOf()
        }

        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> endCell()
                !quoted && (ch == '\n' || ch == '\r') -> {
                    endRow()
                    // Consume the LF of a CRLF so it does not open an empty row of its own.
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(ch)
            }
            sawAny = true
            i++
        }
        if (sawAny) endRow()
        return rows
    }

    /** The inverse of the writer's formula guard. See rule 2 and the asymmetry note. */
    fun unguard(value: String): String =
        if (value.length >= 2 && value[0] == '\'' && value[1] in FORMULA_LEADERS) value.substring(1)
        else value

    // ------------------------------------------------------------------------- which sheet is it

    /**
     * Which of the exported sheets a header row describes.
     *
     * ⚠️ Decided by the HEADER, not by the file name. A zip entry keeps its name; a single CSV
     * forwarded through a mail client or renamed on a desktop does not, and the header is the thing
     * that actually says what the rows are.
     */
    enum class Sheet { FOOD, WEIGHT, MEASURE, DAILY, UNKNOWN }

    fun sheetOf(csv: String): Sheet = sheetOfHeader(parse(csv).firstOrNull().orEmpty())

    internal fun sheetOfHeader(header: List<String>): Sheet {
        val names = header.map { it.trim().lowercase() }.toSet()
        return when {
            "entry_id" in names || ("food" in names && "energy_kcal" in names) -> Sheet.FOOD
            "weight_kg" in names -> Sheet.WEIGHT
            "site" in names && "cm" in names -> Sheet.MEASURE
            // ⚠️ Recognised so it can be REFUSED by name rather than falling into "unknown". It is
            // derived from the entries, and importing it would give one day two sources of truth
            // that disagree the moment a single entry is edited.
            "energy_kcal" in names -> Sheet.DAILY
            else -> Sheet.UNKNOWN
        }
    }

    // ---------------------------------------------------------------------------------- results

    /** A weigh-in as the file states it. The app decides which day it counts toward. */
    data class Weighin(val atMs: Long, val kg: Double, val note: String = "")

    /** A circumference reading. [site] is the exported label; the app maps it to its own enum. */
    data class Measurement(val atMs: Long, val site: String, val cm: Double)

    /**
     * What one sheet yielded.
     *
     * [refusal] non-null means nothing was read and the sentence says why — a whole-file problem.
     * [skippedRows] counts individual rows dropped from an otherwise good file, with up to
     * [MAX_REASONS] distinct explanations, because "412 rows imported, 3 skipped" is only useful if
     * it also says what was wrong with the three.
     */
    data class Result(
        val entries: List<NutritionDay.Entry> = emptyList(),
        val weighins: List<Weighin> = emptyList(),
        val measurements: List<Measurement> = emptyList(),
        val skippedRows: Int = 0,
        val reasons: List<String> = emptyList(),
        val refusal: String? = null,
    ) {
        val isEmpty: Boolean
            get() = entries.isEmpty() && weighins.isEmpty() && measurements.isEmpty()

        /** Merge two sheets of one archive. Refusals accumulate as reasons rather than as a verdict. */
        operator fun plus(other: Result): Result = Result(
            entries = entries + other.entries,
            weighins = weighins + other.weighins,
            measurements = measurements + other.measurements,
            skippedRows = skippedRows + other.skippedRows,
            reasons = (reasons + other.reasons + listOfNotNull(refusal, other.refusal))
                .distinct().take(MAX_REASONS),
            // ⚠️ A combined result refuses only if BOTH halves did. One unreadable sheet in a zip is
            // a reason, not a failure — the other three should still land.
            refusal = if (refusal != null && other.refusal != null) refusal else null,
        )
    }

    // ------------------------------------------------------------------------------- the sheets

    /**
     * Every logged entry the file holds.
     *
     * @param dayStartFor the start of the day an instant belongs to, **in the importing device's own
     *   calendar**. Supplied by the caller for the reason every core here states: a day boundary
     *   taken in UTC is a day out for most of the world.
     *
     * ⚠️ **The day is taken from the file when it is still a real day-start here, and re-derived when
     * it is not.** `dayStartMs` is a KEY: the log's index is built on it and `entriesFor` looks rows
     * up by it, so a value that is not one of this device's midnights creates a day the app cannot
     * navigate to. Equally, re-deriving unconditionally would MOVE every back-dated entry — this app
     * logs to the day being viewed, not to today, so an entry's own timestamp and the day it counts
     * toward genuinely differ. Checking `dayStartFor(stored) == stored` gets both: a same-device
     * round trip is exact, and a file from another zone lands on the nearest honest day.
     */
    fun foodLog(csv: String, dayStartFor: (Long) -> Long): Result {
        val rows = parse(csv)
        val header = rows.firstOrNull() ?: return Result(refusal = "That file is empty.")
        val h = Columns(header)
        h.missing("food", "energy_kcal", "logged_epoch_ms")?.let {
            return Result(refusal = notOurs("food log", it))
        }

        val out = mutableListOf<NutritionDay.Entry>()
        val reasons = linkedSetOf<String>()
        var skipped = 0

        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            val name = h.text(row, "food")
            val kcal = h.number(row, "energy_kcal")
            val at = h.long(row, "logged_epoch_ms")
            if (kcal == null || at == null) {
                skipped++
                reasons += if (at == null) {
                    "A row had no readable timestamp, so there was no day to put it in."
                } else {
                    "A row's energy was not a number, so it was left out rather than logged as zero."
                }
                continue
            }
            val stored = h.long(row, "day_epoch_ms")
            val day = stored?.takeIf { dayStartFor(it) == it } ?: dayStartFor(at)
            out += NutritionDay.Entry(
                // ⚠️ The exported id when there is one — it is the dedupe key, and keeping it is what
                // makes importing the same file twice a no-op. When there is not, a value DERIVED
                // from the row rather than a random one, so a second import still recognises it.
                id = h.text(row, "entry_id").ifBlank { derivedId(at, name) },
                dayStartMs = day,
                atMs = at,
                name = name.ifBlank { "Imported entry" },
                grams = h.number(row, "grams") ?: 0.0,
                nutrients = NutritionDay.Nutrients(
                    kcal = kcal,
                    proteinG = h.number(row, "protein_g") ?: 0.0,
                    fatG = h.number(row, "fat_g") ?: 0.0,
                    carbG = h.number(row, "carbs_g") ?: 0.0,
                    fibreG = h.number(row, "fibre_g") ?: 0.0,
                    sugarG = h.number(row, "sugar_g") ?: 0.0,
                    satFatG = h.number(row, "saturated_fat_g") ?: 0.0,
                    sodiumMg = h.number(row, "sodium_mg") ?: 0.0,
                ),
                brand = h.text(row, "brand"),
                servingLabel = h.text(row, "serving"),
                meal = mealOf(h.text(row, "meal")),
                source = sourceOf(h.text(row, "source")),
                micros = microsOf(h, row),
            )
        }
        return Result(entries = out, skippedRows = skipped, reasons = reasons.take(MAX_REASONS))
    }

    fun weighins(csv: String): Result {
        val rows = parse(csv)
        val header = rows.firstOrNull() ?: return Result(refusal = "That file is empty.")
        val h = Columns(header)
        h.missing("weight_kg", "epoch_ms")?.let { return Result(refusal = notOurs("weigh-in", it)) }

        val out = mutableListOf<Weighin>()
        val reasons = linkedSetOf<String>()
        var skipped = 0
        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            val kg = h.number(row, "weight_kg")
            val at = h.long(row, "epoch_ms")
            if (kg == null || at == null || kg <= 0.0) {
                skipped++
                reasons += "A weigh-in had no readable weight or date."
                continue
            }
            // ⚠️ `trend_kg` is deliberately NOT read. It is DERIVED from the readings by BodyTrend,
            // and importing a stored trend would leave a number on screen that the smoother did not
            // produce and cannot reproduce.
            out += Weighin(atMs = at, kg = kg, note = h.text(row, "note"))
        }
        return Result(weighins = out, skippedRows = skipped, reasons = reasons.take(MAX_REASONS))
    }

    fun measurements(csv: String): Result {
        val rows = parse(csv)
        val header = rows.firstOrNull() ?: return Result(refusal = "That file is empty.")
        val h = Columns(header)
        h.missing("site", "cm", "epoch_ms")?.let {
            return Result(refusal = notOurs("measurement", it))
        }

        val out = mutableListOf<Measurement>()
        val reasons = linkedSetOf<String>()
        var skipped = 0
        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            val cm = h.number(row, "cm")
            val at = h.long(row, "epoch_ms")
            val site = h.text(row, "site")
            if (cm == null || at == null || site.isBlank() || cm <= 0.0) {
                skipped++
                reasons += "A measurement had no readable site, figure or date."
                continue
            }
            out += Measurement(atMs = at, site = site, cm = cm)
        }
        return Result(measurements = out, skippedRows = skipped, reasons = reasons.take(MAX_REASONS))
    }

    /**
     * Read whichever sheet this is, or say why not.
     *
     * The single entry point the app uses for a lone CSV, and for each entry of a zip.
     */
    fun read(csv: String, dayStartFor: (Long) -> Long): Result = when (sheetOf(csv)) {
        Sheet.FOOD -> foodLog(csv, dayStartFor)
        Sheet.WEIGHT -> weighins(csv)
        Sheet.MEASURE -> measurements(csv)
        Sheet.DAILY -> Result(
            refusal = "Daily totals are worked out from the entries, so they are not imported — " +
                "importing them would give a day two sets of numbers that could disagree.",
        )
        Sheet.UNKNOWN -> Result(
            refusal = "That does not look like a file this app wrote. The first line should name " +
                "its columns — a food log starts with date, time, meal, food.",
        )
    }

    // ------------------------------------------------------------------------------- the report

    /** One sentence for the screen: what was read, what was not, and why. */
    fun summarise(r: Result): String {
        r.refusal?.let { return it }
        val parts = buildList {
            if (r.entries.isNotEmpty()) {
                add("${r.entries.size} ${if (r.entries.size == 1) "entry" else "entries"}")
            }
            if (r.weighins.isNotEmpty()) {
                add("${r.weighins.size} ${if (r.weighins.size == 1) "weigh-in" else "weigh-ins"}")
            }
            if (r.measurements.isNotEmpty()) add("${r.measurements.size} measurements")
        }
        if (parts.isEmpty()) {
            return "Nothing to import — the file has headers but no rows." +
                if (r.reasons.isEmpty()) "" else " " + r.reasons.joinToString(" ")
        }
        val head = "Read " + parts.joinToString(", ") + "."
        val tail = if (r.skippedRows > 0) {
            " Skipped ${r.skippedRows} ${if (r.skippedRows == 1) "row" else "rows"}. " +
                r.reasons.joinToString(" ")
        } else {
            ""
        }
        return (head + tail).trim()
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * Header name to index, resolved once per sheet.
     *
     * Names are trimmed and lower-cased on both sides, so a spreadsheet that has capitalised a header
     * on the way through still reads.
     */
    internal class Columns(header: List<String>) {
        private val index: Map<String, Int> =
            header.withIndex().associate { (i, name) -> name.trim().lowercase() to i }

        fun has(name: String): Boolean = name in index

        /** The first required column that is absent, or null when all of them are present. */
        fun missing(vararg required: String): String? = required.firstOrNull { !has(it) }

        fun raw(row: List<String>, name: String): String =
            index[name]?.let { row.getOrNull(it) }.orEmpty()

        fun text(row: List<String>, name: String): String = unguard(raw(row, name)).trim()

        /**
         * ⚠️ `toDoubleOrNull` rather than a locale-aware parse, and that matches the writer: every
         * number is emitted in [java.util.Locale.US], so the decimal separator is a point wherever
         * the file is read. A locale-aware read would turn `1.5` into 15 on a comma-decimal device.
         */
        fun number(row: List<String>, name: String): Double? =
            raw(row, name).trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

        fun long(row: List<String>, name: String): Long? =
            raw(row, name).trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    }

    private fun notOurs(what: String, missing: String): String =
        "That does not look like a $what this app wrote — it has no \"$missing\" column."

    /**
     * ⚠️ Deterministic, not random. A row with no id still has to dedupe against a second import of
     * the same file, and a fresh UUID each time would silently double every such row.
     */
    internal fun derivedId(atMs: Long, name: String): String = "imported-$atMs-${name.hashCode()}"

    /**
     * ⚠️ Matches the display label OR the enum name, case-insensitively, and falls back rather than
     * refusing. The export writes `meal.label`, which is display copy and may be reworded; losing a
     * meal somebody genuinely ate because "Snacks" became "Snack" would be the worse failure.
     */
    internal fun mealOf(s: String): NutritionDay.Meal =
        NutritionDay.Meal.entries.firstOrNull {
            it.label.equals(s, ignoreCase = true) || it.name.equals(s, ignoreCase = true)
        } ?: NutritionDay.Meal.SNACK

    /**
     * ⚠️ **Preserved, not rewritten to "imported".** The point of this feature is that an export and
     * a re-import give the same day back; stamping every row as imported would lose the USDA badge
     * and say strictly less than the file did. Which rows came from a file is already answerable
     * from their ids.
     */
    internal fun sourceOf(s: String): NutritionDay.Source =
        NutritionDay.Source.entries.firstOrNull {
            it.label.equals(s, ignoreCase = true) || it.name.equals(s, ignoreCase = true)
        } ?: NutritionDay.Source.CUSTOM

    /**
     * ⚠️ **A blank cell is an ABSENT micronutrient, never a zero**, which is the whole reason
     * [Micronutrients.Amounts] is a map. The export writes an empty cell for a nutrient nobody
     * measured; reading it back as 0.0 would turn "unknown" into "none" on the way in, and the day's
     * calcium coverage would then claim a measurement that was never made.
     */
    private fun microsOf(h: Columns, row: List<String>): Micronutrients.Amounts {
        val values = mutableMapOf<Micronutrients.Micro, Double>()
        for (m in Micronutrients.Micro.entries) {
            // ⚠️ The column is asked for BY MICRONUTRIENT, through the same function that names it on
            // the way out — not by index into `MICRO_COLUMNS`. Pairing two lists positionally is the
            // coupling rule 1 exists to forbid, and it fails silently rather than loudly.
            h.number(row, HealthExport.microColumn(m))?.takeIf { it >= 0.0 }?.let { values[m] = it }
        }
        return Micronutrients.Amounts(values)
    }
}
