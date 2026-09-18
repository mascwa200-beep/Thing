package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **The fixture is the test.** A round trip over tidy data exercises none of the four rules this
 * format actually rests on, and a green suite over such a fixture is the recorded "the fixture never
 * reached the branch" failure. So every entry below carries at least one hazard:
 *
 *   * a name with COMMAS in it — `Chicken breast, baked, skin not eaten` is really in the bundled
 *     seed, and an unquoted writer turns it into four columns and shifts every number one place left;
 *   * a name beginning with `-`, which a spreadsheet reads as an expression and the writer therefore
 *     guards with an apostrophe that has to come back off;
 *   * a NON-ASCII name, which is what the byte-order mark exists for;
 *   * an ABSENT micronutrient beside a recorded one, which must stay absent rather than becoming 0.0;
 *   * a quoted field containing a NEWLINE, which defeats split-by-line parsing;
 *   * and a back-dated entry whose own timestamp is on a different day from the day it counts toward.
 */
class HealthImportTest {

    /** A fixed calendar: days start at midnight UTC. Real callers pass the device's own zone. */
    private val dayMs = 86_400_000L
    private val dayStartFor: (Long) -> Long = { (it / dayMs) * dayMs }

    /** A midnight by construction, so `dayStartFor(day0) == day0` and the key test means something. */
    private val day0 = 21L * dayMs

    private fun entry(
        id: String,
        name: String,
        atMs: Long,
        dayStartMs: Long = dayStartFor(atMs),
        micros: Micronutrients.Amounts = Micronutrients.Amounts(),
        extras: NutrientSet.Amounts = NutrientSet.Amounts(),
        meal: NutritionDay.Meal = NutritionDay.Meal.LUNCH,
        source: NutritionDay.Source = NutritionDay.Source.OFFLINE,
        brand: String = "",
        serving: String = "",
    ) = NutritionDay.Entry(
        id = id,
        dayStartMs = dayStartMs,
        atMs = atMs,
        name = name,
        grams = 123.5,
        nutrients = NutritionDay.Nutrients(
            kcal = 210.0, proteinG = 12.3, fatG = 4.5, carbG = 30.1,
            fibreG = 2.2, sugarG = 8.8, satFatG = 1.1, sodiumMg = 340.0,
        ),
        brand = brand,
        servingLabel = serving,
        meal = meal,
        source = source,
        micros = micros,
        extras = extras,
    )

    private val hazards = listOf(
        entry("e1", "Chicken breast, baked, skin not eaten", day0 + 3_600_000L),
        // A leading hyphen: guarded on the way out, un-guarded on the way back.
        entry("e2", "-Brand yoghurt", day0 + 7_200_000L, brand = "@Acme", serving = "=1 pot"),
        // Non-ASCII, and a quoted newline in a field the reader must not split on.
        entry("e3", "Crème brûlée", day0 + 10_800_000L, serving = "1 ramekin\n(large)"),
        // Calcium recorded, iron NOT — the absent one must stay absent.
        entry(
            "e4", "Fortified oat drink", day0 + 14_400_000L,
            micros = Micronutrients.Amounts(
                mapOf(
                    Micronutrients.Micro.CALCIUM to 120.0,
                    Micronutrients.Micro.VITAMIN_D to 0.75,
                ),
            ),
            // ⚠️ Two of the twenty-nine, at the two ends of the precision range: added sugars in
            // whole-ish grams, and vitamin B12 at a hundredth of a microgram — which is where a
            // fixed single decimal would round the figure to nothing on the way out.
            extras = NutrientSet.Amounts(
                mapOf(
                    NutrientSet.Nutrient.ADDED_SUGARS to 7.89,
                    NutrientSet.Nutrient.VITAMIN_B12 to 0.0861,
                ),
            ),
        ),
        // ⚠️ Back-dated: logged on day 1, counting toward day 0. This app logs to the day being
        // VIEWED, so the two genuinely differ and a naive re-derivation would move it.
        entry("e5", "Late supper", day0 + dayMs + 3_600_000L, dayStartMs = day0),
    )

    private fun exported(entries: List<NutritionDay.Entry> = hazards) =
        HealthExport.foodLog(entries, dayLabel = { "d$it" }, timeLabel = { "t$it" })

    // ------------------------------------------------------------------------------ round trip

    @Test
    fun everythingWrittenComesBack() {
        val r = HealthImport.foodLog(exported(), dayStartFor)
        assertNull(r.refusal)
        assertEquals(0, r.skippedRows)
        assertEquals(hazards.size, r.entries.size)

        val back = r.entries.associateBy { it.id }
        for (original in hazards) {
            val got = back[original.id]
            assertNotNull("missing ${original.id}", got)
            requireNotNull(got)
            assertEquals("name of ${original.id}", original.name, got.name)
            assertEquals("brand of ${original.id}", original.brand, got.brand)
            assertEquals("serving of ${original.id}", original.servingLabel, got.servingLabel)
            assertEquals("meal of ${original.id}", original.meal, got.meal)
            assertEquals("source of ${original.id}", original.source, got.source)
            assertEquals("at of ${original.id}", original.atMs, got.atMs)
            assertEquals("day of ${original.id}", original.dayStartMs, got.dayStartMs)
            assertEquals("kcal of ${original.id}", original.nutrients.kcal, got.nutrients.kcal, 1e-9)
            assertEquals("protein of ${original.id}", original.nutrients.proteinG, got.nutrients.proteinG, 1e-9)
            assertEquals("sodium of ${original.id}", original.nutrients.sodiumMg, got.nutrients.sodiumMg, 1e-9)
            assertEquals("grams of ${original.id}", original.grams, got.grams, 1e-9)
        }
    }

    /**
     * ⚠️ The apostrophe the writer adds must come back OFF, or every export-import cycle adds
     * another one. Asserted on the raw CSV as well as on the parse, so a reader that happens to be
     * right for the wrong reason is still caught.
     */
    @Test
    fun theFormulaGuardIsUndone() {
        val csv = exported()
        assertTrue("the writer should have guarded it", csv.contains("'-Brand yoghurt"))
        assertTrue("and the brand", csv.contains("'@Acme"))

        val got = HealthImport.foodLog(csv, dayStartFor).entries.first { it.id == "e2" }
        assertEquals("-Brand yoghurt", got.name)
        assertEquals("@Acme", got.brand)
        assertEquals("=1 pot", got.servingLabel)
    }

    /**
     * ⚠️ **Asserted on the FIRST COLUMN'S NAME, not on the row count, and that distinction is the
     * whole test.** A byte-order mark corrupts exactly one header — the first, which today is `date`
     * — and the importer happens to require none of `date`, so a version with the strip deleted still
     * reads every row and every required column. The first draft of this test checked the sheet and
     * the entry count, passed under that deletion, and proved nothing: the recorded "the fixture
     * never reached the branch" failure, arriving through a fixture that reached the WRONG branch.
     *
     * The strip is still load-bearing rather than defensive: `sheetOf` only survives a BOM because
     * it keys on `entry_id` rather than on `date`, and the day a required column is first — or
     * anything reads `date` — a BOM'd file would be refused outright. Which file? Every file this
     * app writes, since `HealthExporter` emits one deliberately for Excel's sake.
     */
    @Test
    fun aByteOrderMarkDoesNotHideTheFirstColumn() {
        val withBom = "﻿" + exported()
        val header = HealthImport.parse(withBom).first()
        assertEquals("date", header.first())
        assertTrue("the first column has to be findable by name", HealthImport.Columns(header).has("date"))

        assertEquals(HealthImport.Sheet.FOOD, HealthImport.sheetOf(withBom))
        assertEquals(hazards.size, HealthImport.foodLog(withBom, dayStartFor).entries.size)
    }

    /** An absent nutrient must not become a measurement of none. */
    @Test
    fun anUnrecordedMicronutrientStaysUnrecorded() {
        val got = HealthImport.foodLog(exported(), dayStartFor).entries.first { it.id == "e4" }
        assertEquals(120.0, got.micros[Micronutrients.Micro.CALCIUM]!!, 1e-9)
        assertEquals(0.75, got.micros[Micronutrients.Micro.VITAMIN_D]!!, 1e-9)
        assertNull(got.micros[Micronutrients.Micro.IRON])
        assertNull(got.micros[Micronutrients.Micro.POTASSIUM])
        // And an entry that recorded none reports none rather than eight zeros.
        assertTrue(HealthImport.foodLog(exported(), dayStartFor)
            .entries.first { it.id == "e1" }.micros.values.isEmpty())
    }

    /**
     * The same rule for the twenty-nine further nutrients, and the precision that makes it real.
     *
     * ⚠️ **0.0861 µg is the case that matters.** The export chooses its decimals from each
     * nutrient's own measured typical value, so B12 gets four; the fixed single decimal the
     * micronutrient columns start from would have written `0.1`, and a round trip would then have
     * quietly changed somebody's figure by sixteen per cent. Asserting to 1e-9 is what makes that
     * a failure rather than a shrug.
     */
    @Test
    fun anUnrecordedFurtherNutrientStaysUnrecorded() {
        val got = HealthImport.foodLog(exported(), dayStartFor).entries.first { it.id == "e4" }
        assertEquals(7.89, got.extras[NutrientSet.Nutrient.ADDED_SUGARS]!!, 1e-9)
        assertEquals(0.0861, got.extras[NutrientSet.Nutrient.VITAMIN_B12]!!, 1e-9)
        assertNull(got.extras[NutrientSet.Nutrient.MAGNESIUM])
        assertNull(got.extras[NutrientSet.Nutrient.WATER])
        // And an entry that recorded none reports none rather than twenty-nine zeros.
        assertTrue(HealthImport.foodLog(exported(), dayStartFor)
            .entries.first { it.id == "e1" }.extras.values.isEmpty())
    }

    /**
     * ⚠️ Columns by NAME. Shuffling them must change nothing — a positional reader passes the round
     * trip above and fails this, which is exactly why this test is separate from it.
     */
    @Test
    fun theColumnsAreFoundByNameRatherThanByPosition() {
        val rows = HealthImport.parse(exported())
        val order = rows.first().indices.shuffled(kotlin.random.Random(7))
        // ⚠️ Assert the PREMISE. A seed that happened to leave the order alone would make every
        // assertion below pass while testing nothing — the recorded "the fixture never reached the
        // branch" failure, arriving through the fixture's own randomness.
        assertNotEquals("the shuffle has to actually shuffle", rows.first().indices.toList(), order)
        val shuffled = rows.joinToString("\r\n") { row ->
            order.joinToString(",") { i -> HealthExport.field(row.getOrElse(i) { "" }) }
        }
        val r = HealthImport.foodLog(shuffled, dayStartFor)
        assertEquals(hazards.size, r.entries.size)
        assertEquals("Chicken breast, baked, skin not eaten", r.entries.first { it.id == "e1" }.name)
    }

    // ------------------------------------------------------------------------------ the reader

    @Test
    fun aQuotedFieldMayHoldCommasQuotesAndNewlines() {
        val csv = "a,b,c\r\n\"x,y\",\"he said \"\"hi\"\"\",\"one\ntwo\"\r\n"
        val rows = HealthImport.parse(csv)
        assertEquals(2, rows.size)
        assertEquals(listOf("x,y", "he said \"hi\"", "one\ntwo"), rows[1])
    }

    /** A file that has been through a text editor may have lost its carriage returns. */
    @Test
    fun bareNewlinesReadTheSameAsCrlf() {
        val crlf = HealthImport.parse("a,b\r\n1,2\r\n")
        val lf = HealthImport.parse("a,b\n1,2\n")
        assertEquals(crlf, lf)
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), lf)
    }

    // --------------------------------------------------------------------------- what it refuses

    @Test
    fun eachSheetIsRecognisedByItsHeader() {
        assertEquals(HealthImport.Sheet.FOOD, HealthImport.sheetOf(exported()))
        assertEquals(
            HealthImport.Sheet.WEIGHT,
            HealthImport.sheetOf(
                HealthExport.weighins(
                    listOf(BodyTrend.Weighin(day0, 80.0)),
                    trendKgAt = { null }, dayLabel = { "d" }, noteFor = { "" },
                ),
            ),
        )
        assertEquals(
            HealthImport.Sheet.MEASURE,
            HealthImport.sheetOf(
                HealthExport.measurements(listOf(Triple(day0, "Waist", 84.0)), dayLabel = { "d" }),
            ),
        )
        assertEquals(
            HealthImport.Sheet.DAILY,
            HealthImport.sheetOf(
                HealthExport.dailyTotals(mapOf(day0 to NutritionDay.Nutrients(kcal = 1.0)), { "d" }),
            ),
        )
        assertEquals(HealthImport.Sheet.UNKNOWN, HealthImport.sheetOf("apple,pear\r\n1,2\r\n"))
    }

    /**
     * ⚠️ Daily totals are DERIVED. Importing them would give one day two sets of numbers, which
     * disagree the moment a single entry is edited — so this is refused deliberately rather than
     * unsupported by accident, and the refusal says why.
     */
    @Test
    fun dailyTotalsAreRefusedRatherThanRead() {
        val csv = HealthExport.dailyTotals(mapOf(day0 to NutritionDay.Nutrients(kcal = 1800.0)), { "d" })
        val r = HealthImport.read(csv, dayStartFor)
        assertTrue(r.isEmpty)
        assertTrue(r.refusal!!.contains("worked out from the entries"))
    }

    /** A refusal names the column it wanted, so somebody can look at their file and see. */
    @Test
    fun anUnfamiliarFileIsRefusedByName() {
        val r = HealthImport.foodLog("date,time,meal,food\r\nx,y,z,w\r\n", dayStartFor)
        assertTrue(r.entries.isEmpty())
        assertTrue(r.refusal!!.contains("energy_kcal"))
    }

    /**
     * ⚠️ An unreadable NUMBER loses its row; an unreadable LABEL does not. A wrong number is the one
     * thing this must never write, and a meal genuinely eaten must not be lost because a display
     * string was reworded.
     */
    @Test
    fun aBadNumberLosesItsRowAndABadLabelDoesNot() {
        // Corrupt the energy of EXACTLY ONE row, so the test proves the row is dropped rather than
        // that the whole file failed. A blanket replace would corrupt all five and prove less.
        val lines = exported().split("\r\n").toMutableList()
        val kcalAt = HealthImport.parse(exported()).first().indexOf("energy_kcal")
        val cells = HealthImport.parse(exported())[1].toMutableList()
        cells[kcalAt] = "banana"
        lines[1] = cells.joinToString(",") { HealthExport.field(it) }
        val r = HealthImport.foodLog(lines.joinToString("\r\n"), dayStartFor)

        assertEquals(hazards.size - 1, r.entries.size)
        assertEquals(1, r.skippedRows)
        assertTrue(r.reasons.any { it.contains("energy") })
        assertTrue(r.entries.none { it.id == "e1" })

        // A meal name this app has never used still lands, as a snack, with everything else intact.
        val odd = exported(listOf(entry("z1", "Something", day0 + 60_000L)))
            .replace(",Lunch,", ",Elevenses,")
        val got = HealthImport.foodLog(odd, dayStartFor)
        assertEquals(1, got.entries.size)
        assertEquals(NutritionDay.Meal.SNACK, got.entries.first().meal)
        assertEquals("Something", got.entries.first().name)
    }

    @Test
    fun aMealOrSourceIsMatchedByLabelOrByName() {
        assertEquals(NutritionDay.Meal.BREAKFAST, HealthImport.mealOf("Breakfast"))
        assertEquals(NutritionDay.Meal.BREAKFAST, HealthImport.mealOf("BREAKFAST"))
        assertEquals(NutritionDay.Meal.SNACK, HealthImport.mealOf("Snacks"))
        assertEquals(NutritionDay.Meal.SNACK, HealthImport.mealOf("nonsense"))
        assertEquals(NutritionDay.Source.USDA, HealthImport.sourceOf("USDA"))
        assertEquals(NutritionDay.Source.OFFLINE, HealthImport.sourceOf("Bundled"))
        assertEquals(NutritionDay.Source.CUSTOM, HealthImport.sourceOf(""))
    }

    // ------------------------------------------------------------------------------- the day key

    /**
     * ⚠️ **`dayStartMs` is a KEY, not a label.** The log's index is built on it and `entriesFor`
     * looks rows up by it, so a value that is not one of this device's midnights makes a day the app
     * cannot navigate to. Kept when it is still a real day-start here; re-derived when it is not.
     */
    @Test
    fun theStoredDayIsKeptWhenItIsStillAMidnightHere() {
        val backdated = HealthImport.foodLog(exported(), dayStartFor).entries.first { it.id == "e5" }
        // Logged a day later than it counts toward, and the file's own day survives.
        assertEquals(day0, backdated.dayStartMs)
        assertEquals(day0 + dayMs + 3_600_000L, backdated.atMs)
    }

    @Test
    fun aDayFromAnotherCalendarIsReplacedByOneThisDeviceHas() {
        // Half past midnight is nobody's day-start under this test's calendar.
        val foreign = exported(listOf(entry("f1", "Abroad", day0 + 50_000_000L, dayStartMs = day0 + 1_800_000L)))
        val got = HealthImport.foodLog(foreign, dayStartFor).entries.single()
        assertEquals(dayStartFor(day0 + 50_000_000L), got.dayStartMs)
        assertEquals(0L, got.dayStartMs % dayMs)
    }

    // -------------------------------------------------------------------------------- the others

    @Test
    fun weighinsRoundTripAndTheDerivedTrendIsNotRead() {
        val readings = listOf(
            BodyTrend.Weighin(day0, 80.4),
            BodyTrend.Weighin(day0 + dayMs, 80.1),
            BodyTrend.Weighin(day0 + 2 * dayMs, 79.9),
        )
        val notes = mapOf(day0 + dayMs to "after a long walk, felt light")
        val csv = HealthExport.weighins(
            readings,
            trendKgAt = { 99.0 },  // a value nothing should carry back
            dayLabel = { "d" },
            noteFor = { notes[it].orEmpty() },
        )
        val r = HealthImport.weighins(csv)
        assertNull(r.refusal)
        assertEquals(3, r.weighins.size)
        assertEquals(80.4, r.weighins[0].kg, 1e-9)
        assertEquals(day0 + dayMs, r.weighins[1].atMs)
        assertEquals("after a long walk, felt light", r.weighins[1].note)
        // ⚠️ 99.0 was written into every trend cell and appears nowhere in what came back.
        assertTrue(r.weighins.none { it.kg == 99.0 })
    }

    @Test
    fun measurementsRoundTrip() {
        val csv = HealthExport.measurements(
            listOf(Triple(day0, "Waist", 84.5), Triple(day0 + dayMs, "Chest", 101.0)),
            dayLabel = { "d" },
        )
        val r = HealthImport.measurements(csv)
        assertEquals(2, r.measurements.size)
        assertEquals("Waist", r.measurements[0].site)
        assertEquals(84.5, r.measurements[0].cm, 1e-9)
        assertEquals(day0 + dayMs, r.measurements[1].atMs)
    }

    @Test
    fun aHeaderWithNoRowsIsNotAFailure() {
        val r = HealthImport.foodLog(exported(emptyList()), dayStartFor)
        assertNull(r.refusal)
        assertTrue(r.entries.isEmpty())
        assertEquals(0, r.skippedRows)
        assertTrue(HealthImport.summarise(r).contains("no rows"))
    }

    /**
     * ⚠️ A row with no id still has to dedupe against a SECOND import of the same file, so the
     * substitute is derived from the row rather than random.
     */
    @Test
    fun aRowWithNoIdGetsTheSameIdEveryTime() {
        val csv = exported(listOf(entry("", "No id here", day0 + 60_000L)))
        val a = HealthImport.foodLog(csv, dayStartFor).entries.single().id
        val b = HealthImport.foodLog(csv, dayStartFor).entries.single().id
        assertEquals(a, b)
        assertTrue(a.isNotBlank())
    }

    /** One unreadable sheet in an archive is a reason, not a verdict — the other three still land. */
    @Test
    fun mergingKeepsWhatWasReadableAndReportsWhatWasNot() {
        val good = HealthImport.foodLog(exported(), dayStartFor)
        val bad = HealthImport.read("apple,pear\r\n1,2\r\n", dayStartFor)
        val merged = good + bad
        assertEquals(hazards.size, merged.entries.size)
        assertNull(merged.refusal)
        assertTrue(merged.reasons.any { it.contains("does not look like") })

        // Two refusals and nothing read IS a verdict.
        assertNotNull((bad + bad).refusal)
    }

    @Test
    fun theSummarySaysWhatWasReadAndWhatWasNot() {
        val r = HealthImport.foodLog(exported(), dayStartFor)
        assertTrue(HealthImport.summarise(r).startsWith("Read 5 entries."))
        assertEquals(
            "That does not look like a file this app wrote. The first line should name its " +
                "columns — a food log starts with date, time, meal, food.",
            HealthImport.summarise(HealthImport.read("apple\r\n1\r\n", dayStartFor)),
        )
    }
}
