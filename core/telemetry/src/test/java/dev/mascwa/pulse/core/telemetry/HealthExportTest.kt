package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * ⚠️ Every expectation here was worked out from the rule — or from RFC 4180 — before the assertion
 * was written, and the reasoning sits beside it.
 *
 * The strongest test in the file is [aRealSeedNameSurvivesARoundTrip], because it does not check the
 * writer's output against a string I typed: it parses the row back with an independent reader and
 * requires the original value out the other side.
 */
class HealthExportTest {

    private val DAY = 1_700_000_000_000L / 86_400_000L * 86_400_000L

    private fun entry(
        name: String,
        grams: Double = 100.0,
        kcal: Double = 165.0,
        day: Long = DAY,
        at: Long = DAY + 3_600_000L,
        brand: String = "",
        id: String = "e1",
    ) = NutritionDay.Entry(
        id = id,
        dayStartMs = day,
        atMs = at,
        name = name,
        grams = grams,
        nutrients = NutritionDay.Nutrients(kcal = kcal, proteinG = 31.0, fatG = 3.6, carbG = 0.0),
        brand = brand,
        meal = NutritionDay.Meal.LUNCH,
        source = NutritionDay.Source.OFFLINE,
    )

    private fun log(vararg e: NutritionDay.Entry) =
        HealthExport.foodLog(e.toList(), dayLabel = { "2023-11-14" }, timeLabel = { "01:00" })

    /** A minimal RFC 4180 reader, written to be independent of the writer it checks. */
    private fun parseRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(ch)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    // ------------------------------------------------------------------------------- RFC 4180

    /**
     * ⚠️ THE LOAD-BEARING ONE. `Chicken breast, baked, skin not eaten` is a real row in the bundled
     * seed. Written unquoted it becomes three columns and every number on the line shifts two places
     * left — which reads like a data-entry mistake, not a bug in the writer.
     */
    @Test
    fun aRealSeedNameSurvivesARoundTrip() {
        val name = "Chicken breast, baked, skin not eaten"
        val rows = log(entry(name)).trimEnd().split(HealthExport.EOL)
        val header = parseRow(rows[0])
        val cells = parseRow(rows[1])
        assertEquals("the row must have exactly as many cells as the header", header.size, cells.size)
        assertEquals(name, cells[header.indexOf("food")])
        // …and the number after it is still the number, not a fragment of the name.
        assertEquals("165.0", cells[header.indexOf("energy_kcal")])
    }

    @Test
    fun aQuoteInsideANameIsDoubledAndComesBackWhole() {
        val name = """Baker's 6" sub"""
        val cells = parseRow(log(entry(name)).trimEnd().split(HealthExport.EOL)[1])
        assertTrue("the raw field must be quoted", log(entry(name)).contains("\"\""))
        assertEquals(name, cells[3])
    }

    @Test
    fun aNewlineInsideAFieldDoesNotBecomeANewRow() {
        val quoted = HealthExport.field("two\nlines")
        assertEquals("\"two\nlines\"", quoted)
        // The whole point: splitting the file on EOL must not see this as a row break.
        assertFalse(quoted.contains(HealthExport.EOL))
    }

    @Test
    fun anOrdinaryFieldIsNotQuotedForNoReason() {
        assertEquals("Oats", HealthExport.field("Oats"))
        assertEquals("", HealthExport.field(""))
    }

    // ---------------------------------------------------------------------------------- numbers

    /**
     * ⚠️ On a comma-decimal device the platform default writes `1,3`, which inside a comma-separated
     * file is two fields — so this does not merely misprint a figure, it destroys the row.
     */
    @Test
    fun numbersAreWrittenInTheOneLocaleACsvCanBeReadIn() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.3", HealthExport.num(1.3))
            assertEquals("1.25", HealthExport.num(1.25, 2))
            // And end to end: the row must still have the right number of cells.
            val rows = log(entry("Oats", kcal = 389.5)).trimEnd().split(HealthExport.EOL)
            assertEquals(parseRow(rows[0]).size, parseRow(rows[1]).size)
            assertEquals("389.5", parseRow(rows[1])[parseRow(rows[0]).indexOf("energy_kcal")])
        } finally {
            Locale.setDefault(original)
        }
    }

    /** A figure that is not a number is blank, never `NaN` — which a spreadsheet reads as text. */
    @Test
    fun anImpossibleNumberIsBlankRatherThanNaN() {
        assertEquals("", HealthExport.num(Double.NaN))
        assertEquals("", HealthExport.num(Double.POSITIVE_INFINITY))
    }

    // ------------------------------------------------------------------------- formula injection

    /**
     * ⚠️ Open Food Facts is crowd-sourced, so a product name is attacker-controlled text arriving in
     * a spreadsheet. The guard alters the value visibly rather than letting it execute.
     */
    @Test
    fun aNameThatWouldBeAFormulaIsNeutralised() {
        for (lead in listOf("=", "+", "-", "@")) {
            val out = HealthExport.field("${lead}HYPERLINK(\"http://x\")")
            assertTrue("$lead should be guarded, got $out", out.contains("'$lead"))
        }
        assertEquals("'=1+1", HealthExport.field("=1+1"))
    }

    /**
     * ⚠️ …and numbers are exempt, which is the half that is easy to get wrong. A minus sign is how a
     * negative number begins; guarding it would turn every loss on the trend into text and break the
     * charts the export exists to feed.
     */
    @Test
    fun aNegativeNumberIsNotTreatedAsAFormula() {
        assertEquals("-0.45", HealthExport.num(-0.45, 2))
        assertEquals("-5", HealthExport.field("-5", text = false))
    }

    // -------------------------------------------------------------------------------- the sheets

    @Test
    fun entriesComeOutInDayThenTimeOrder() {
        val a = entry("Late", day = DAY, at = DAY + 20 * 3_600_000L, id = "a")
        val b = entry("Early", day = DAY, at = DAY + 2 * 3_600_000L, id = "b")
        val c = entry("Yesterday", day = DAY - 86_400_000L, at = DAY - 3_600_000L, id = "c")
        val names = log(a, b, c).trimEnd().split(HealthExport.EOL).drop(1).map { parseRow(it)[3] }
        assertEquals(listOf("Yesterday", "Early", "Late"), names)
    }

    /**
     * ⚠️ An unlogged day is absent, not a row of zeros — the same rule the rest of this feature
     * follows. A spreadsheet averaging a zero column reports a starving person for anybody who
     * skipped a weekend.
     */
    @Test
    fun anEmptyRecordIsHeadersOnlyRatherThanAFabricatedRow() {
        val out = HealthExport.dailyTotals(emptyMap(), dayLabel = { "x" })
        assertEquals(1, out.trimEnd().split(HealthExport.EOL).size)
        assertTrue(out.startsWith("date,energy_kcal"))
        assertTrue(out.endsWith(HealthExport.EOL))
    }

    /**
     * ⚠️ The trend beside a reading is the one computed from the readings UP TO IT. A trend taken
     * with hindsight is not the number the app showed that morning, and somebody checking the export
     * against a screenshot would find them disagreeing.
     */
    @Test
    fun theTrendColumnIsAskedForByIndexSoItCanBeTheValueOfTheDay() {
        val asked = mutableListOf<Int>()
        val out = HealthExport.weighins(
            readings = listOf(BodyTrend.Weighin(DAY, 80.0), BodyTrend.Weighin(DAY + 86_400_000L, 79.6)),
            trendKgAt = { i -> asked += i; 80.0 - i * 0.2 },
            dayLabel = { "d" },
            noteFor = { "" },
        )
        assertEquals(listOf(0, 1), asked)
        val rows = out.trimEnd().split(HealthExport.EOL)
        assertEquals("80.00", parseRow(rows[1])[2])
        assertEquals("79.80", parseRow(rows[2])[2])
    }

    /** A trend that cannot be computed yet is blank, not zero — nobody has ever weighed zero. */
    @Test
    fun anUnknowableTrendIsBlank() {
        val out = HealthExport.weighins(
            listOf(BodyTrend.Weighin(DAY, 80.0)), { null }, { "d" }, { "" },
        )
        assertEquals("", parseRow(out.trimEnd().split(HealthExport.EOL)[1])[2])
    }

    @Test
    fun measurementsAreLongFormSoANewSiteNeedsNoNewColumn() {
        val out = HealthExport.measurements(
            listOf(Triple(DAY, "Waist", 84.5), Triple(DAY - 1000L, "Hips", 96.0)),
            dayLabel = { "d" },
        )
        val rows = out.trimEnd().split(HealthExport.EOL)
        assertEquals("date,site,cm,epoch_ms", rows[0])
        assertEquals("Hips", parseRow(rows[1])[1])   // older first
        assertEquals("84.5", parseRow(rows[2])[2])
    }

    @Test
    fun everySheetEndsItsLastRowSoTwoFilesCouldBeConcatenated() {
        assertTrue(log(entry("Oats")).endsWith(HealthExport.EOL))
        assertTrue(HealthExport.measurements(emptyList()) { "d" }.endsWith(HealthExport.EOL))
    }

    // -------------------------------------------------------------------------------- the summary

    @Test
    fun theSummarySaysWhatLandedAndAdmitsWhenNothingDid() {
        assertEquals(
            "Nothing recorded yet, so the files are headers only.",
            HealthExport.summarise(0, 0, 0, 0),
        )
        assertEquals("Exported 1 entry, 1 day.", HealthExport.summarise(1, 1, 0, 0))
        assertEquals(
            "Exported 902 entries, 180 days, 61 weigh-ins, 4 measurements.",
            HealthExport.summarise(902, 180, 61, 4),
        )
    }
}
