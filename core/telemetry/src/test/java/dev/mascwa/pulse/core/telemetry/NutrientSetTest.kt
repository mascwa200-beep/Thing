package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected number here was computed from the shipped function or from the measured sample
 * before the assertion was written, and the arithmetic is in the comment beside it.
 */
class NutrientSetTest {

    private val all = NutrientSet.Nutrient.entries

    // ------------------------------------------------------------------------- the permanent ids

    /**
     * ⚠️ **THE LOAD-BEARING GUARD OF THE WHOLE FILE.** These ids are written into a prebuilt
     * database asset of several million rows. Reordering the enum, or slipping a nutrient into the
     * middle, would silently re-label every value already shipped — magnesium read back as
     * phosphorus, with no error anywhere and no way for the app to notice. Pinning the entire
     * mapping in one assertion means a reorder fails the build instead.
     *
     * A retired nutrient's id is never reused; removing one leaves a hole here, which is correct.
     */
    @Test
    fun everyIdIsPinnedSoAReorderCannotRelabelShippedData() {
        val expected = mapOf(
            "ADDED_SUGARS" to 1,
            "STARCH" to 2,
            "SUCROSE" to 3,
            "GLUCOSE" to 4,
            "FRUCTOSE" to 5,
            "MALTOSE" to 6,
            "LACTOSE" to 7,
            "GALACTOSE" to 8,
            "POLYOLS" to 9,
            "MONOUNSATURATED_FAT" to 10,
            "POLYUNSATURATED_FAT" to 11,
            "MAGNESIUM" to 12,
            "PHOSPHORUS" to 13,
            "ZINC" to 14,
            "MANGANESE" to 15,
            "COPPER" to 16,
            "IODINE" to 17,
            "SELENIUM" to 18,
            "VITAMIN_B1" to 19,
            "VITAMIN_B2" to 20,
            "NIACIN" to 21,
            "PANTOTHENIC_ACID" to 22,
            "VITAMIN_B6" to 23,
            "FOLATE" to 24,
            "VITAMIN_B12" to 25,
            "VITAMIN_E" to 26,
            "VITAMIN_K1" to 27,
            "BETA_CAROTENE" to 28,
            "WATER" to 29,
        )
        assertEquals(expected, all.associate { it.name to it.id })
    }

    @Test
    fun idsAndSourceFieldsAreUnique() {
        assertEquals("two nutrients cannot share an id", all.size, all.map { it.id }.toSet().size)
        assertEquals("two nutrients cannot read one column", all.size, all.map { it.offField }.toSet().size)
        assertTrue(all.all { it.id > 0 })
        assertTrue(all.all { it.offField.isNotBlank() && it.label.isNotBlank() })
    }

    /**
     * ⚠️ **The salt-and-sodium guard.** `salt` and `sodium` have identical non-null counts in the
     * sample and agree on every product carrying both — Open Food Facts derives one from the other,
     * so storing both would be one figure twice under two names. The same is true of any column the
     * app already keeps as a macro or a micronutrient. Listing them here is what stops the next
     * nutrient added to this file being one the app already has.
     */
    @Test
    fun nothingHereDuplicatesAColumnTheAppAlreadyStores() {
        val alreadyStored = setOf(
            // NutritionDay.Nutrients
            "energy-kcal", "energy", "proteins", "carbohydrates", "fat",
            "fiber", "sugars", "saturated-fat", "sodium",
            // …and the figure Open Food Facts derives from sodium.
            "salt",
            // Micronutrients.Micro
            "calcium", "iron", "potassium", "vitamin-a", "vitamin-c", "vitamin-d",
            "cholesterol", "trans-fat",
        )
        val clash = all.filter { it.offField in alreadyStored }
        assertTrue("already stored elsewhere: ${clash.map { it.offField }}", clash.isEmpty())
    }

    // ------------------------------------------------------------------- the resolution rule (N2)

    /**
     * ⚠️ **THE RULE THAT WOULD HAVE CAUGHT N2.** Vitamin D shipped stored as an integer number of
     * milligrams against a 15 µg guideline, so a fortified yogurt at 0.4 µg was stored as **0** —
     * not a rounding error, the total loss of the figure, printed as fact. One stored step must be
     * small against what the nutrient actually measures.
     *
     * The bar is one per cent of the measured non-zero median, and the tightest case is galactose:
     * grams at a scale of 100,000 is a step of 1e-5 g against a median of 0.0104 g, whose one per
     * cent is 1.04e-4 — a margin of about ten. Vitamin B12 in micrograms is the next tightest, a
     * step of 1e-4 against 8.61e-4, a margin of about nine.
     */
    @Test
    fun oneStoredStepIsNeverEnoughToEraseTheTypicalFigure() {
        for (n in all) {
            val step = 1.0 / n.unit.scale
            val onePercentOfTypical = n.typicalPer100g * 0.01
            assertTrue(
                "${n.name}: a step of $step cannot represent a typical ${n.typicalPer100g} ${n.unit.symbol}",
                step <= onePercentOfTypical,
            )
        }
    }

    @Test
    fun everyNutrientHasAMeasuredTypicalValue() {
        assertTrue(all.all { it.typicalPer100g > 0.0 && it.typicalPer100g.isFinite() })
    }

    // ------------------------------------------------------------------------------- the ceilings

    /** A hundred grams of food holds at most a hundred grams of anything, in whatever unit. */
    @Test
    fun theCeilingIsTheWeightOfTheFoodInTheNutrientsOwnUnit() {
        assertEquals(100.0, NutrientSet.Unit.GRAM.maxPer100g, 0.0)
        assertEquals(100_000.0, NutrientSet.Unit.MILLIGRAM.maxPer100g, 0.0)
        assertEquals(100_000_000.0, NutrientSet.Unit.MICROGRAM.maxPer100g, 0.0)
    }

    /**
     * ⚠️ Room reads these columns as 32-bit `Int`, so a column that could exceed `Int.MAX_VALUE`
     * would hand Kotlin a truncated value — garbage arriving on the phone as a small, plausible
     * figure. Micrograms is the case where the cap binds first: 1e8 µg at a scale of 10,000 is 1e12,
     * so the ceiling becomes INT_MAX, which is 214,748 µg — about 0.2 g per 100 g, still orders of
     * magnitude above anything edible.
     */
    @Test
    fun noColumnCanOverflowTheIntegerRoomReadsItAs() {
        for (u in NutrientSet.Unit.entries) {
            assertTrue("${u.name} ceiling ${u.storedCeiling}", u.storedCeiling in 1..Int.MAX_VALUE)
        }
        assertEquals(10_000_000, NutrientSet.Unit.GRAM.storedCeiling)          // 100 x 100,000
        assertEquals(1_000_000_000, NutrientSet.Unit.MILLIGRAM.storedCeiling)  // 100,000 x 10,000
        assertEquals(Int.MAX_VALUE, NutrientSet.Unit.MICROGRAM.storedCeiling)  // 1e12, capped
    }

    // ------------------------------------------------------------------------------- store / read

    @Test
    fun aBelievableFigureSurvivesTheRoundTrip() {
        val n = NutrientSet.Nutrient.ADDED_SUGARS
        val stored = NutrientSet.store(n, 7.89)
        assertEquals(789_000, stored)                        // 7.89 g x 100,000
        assertEquals(7.89, NutrientSet.read(n, stored!!), 1e-9)
    }

    /**
     * ⚠️ The sample's own maxima are why this exists: added sugars reaching 3,000 g per 100 g,
     * starch 4,000, monounsaturated fat 510. None of them reads as an error on a card; each reads
     * as a food.
     */
    @Test
    fun aConstituentCannotOutweighTheFoodItIsIn() {
        assertNull(NutrientSet.store(NutrientSet.Nutrient.ADDED_SUGARS, 3_000.0))
        assertNull(NutrientSet.store(NutrientSet.Nutrient.STARCH, 4_000.0))
        assertNull(NutrientSet.sane(NutrientSet.Nutrient.MONOUNSATURATED_FAT, 510.0))
        // …and exactly a hundred grams is still a real food: pure oil is a hundred grams of fat.
        assertNotNull(NutrientSet.sane(NutrientSet.Nutrient.WATER, 100.0))
    }

    /**
     * ⚠️ **A branch [sane] alone cannot cover, and it took writing the negative test to notice it was
     * untested.** For micrograms the integer cap binds long before physics does: `sane` admits
     * anything up to 100,000,000 µg, but 214,749 µg scaled by 10,000 already exceeds
     * `Int.MAX_VALUE`. Without this check the value would be truncated on the way into the column
     * and arrive on the phone as a small, plausible figure — the exact failure the ceiling exists to
     * prevent, arriving by the one route the ceiling does not close.
     */
    @Test
    fun aFigureTooLargeForTheColumnIsRefusedRatherThanTruncated() {
        val b12 = NutrientSet.Nutrient.VITAMIN_B12
        // 300,000 µg is 0.3 g per 100 g — physically possible, so `sane` lets it through…
        assertNotNull(NutrientSet.sane(b12, 300_000.0))
        // …and 300,000 x 10,000 is 3e9, past Int.MAX_VALUE, so storing it would truncate.
        assertNull(NutrientSet.store(b12, 300_000.0))
        // Just below the cap it stores exactly: 200,000 x 10,000 = 2e9.
        assertEquals(2_000_000_000, NutrientSet.store(b12, 200_000.0))
    }

    @Test
    fun nonsenseIsRefusedRatherThanStored() {
        val n = NutrientSet.Nutrient.ZINC
        assertNull(NutrientSet.sane(n, null))
        assertNull(NutrientSet.sane(n, -1.0))
        assertNull(NutrientSet.sane(n, Double.NaN))
        assertNull(NutrientSet.sane(n, Double.POSITIVE_INFINITY))
        assertNull(NutrientSet.store(n, null))
    }

    /**
     * ⚠️ Null in, null out. Zero is a claim that a food contains none of something, which is a
     * different fact from nobody having measured it — and a defaulted zero here would put "0 mg
     * magnesium" on a glass of milk with the same confidence as a laboratory analysis.
     */
    @Test
    fun aFigureNobodyRecordedStaysUnrecordedThroughTheConversion() {
        assertNull(NutrientSet.fromGrams(NutrientSet.Nutrient.MAGNESIUM, null))
        assertNull(NutrientSet.fromGrams(NutrientSet.Nutrient.MAGNESIUM, -0.001))
        // Open Food Facts publishes every one of these in grams.
        assertEquals(41.2, NutrientSet.fromGrams(NutrientSet.Nutrient.MAGNESIUM, 0.0412)!!, 1e-9)
        assertEquals(9.43, NutrientSet.fromGrams(NutrientSet.Nutrient.IODINE, 9.43e-6)!!, 1e-9)
        assertEquals(31.3, NutrientSet.fromGrams(NutrientSet.Nutrient.WATER, 31.3)!!, 1e-9)
    }

    @Test
    fun lookupsAreExactAndAnUnknownKeyIsData() {
        for (n in all) {
            assertEquals(n, NutrientSet.byId(n.id))
            assertEquals(n, NutrientSet.byOffField(n.offField))
        }
        assertNull(NutrientSet.byId(9_999))
        assertNull(NutrientSet.byOffField("unobtanium"))
    }

    // ------------------------------------------------------------------------------------ amounts

    @Test
    fun anAbsentNutrientIsAbsentAndNotZero() {
        val a = NutrientSet.Amounts(mapOf(NutrientSet.Nutrient.ZINC to 2.0))
        assertEquals(2.0, a[NutrientSet.Nutrient.ZINC]!!, 0.0)
        assertNull("nothing recorded magnesium", a[NutrientSet.Nutrient.MAGNESIUM])
    }

    @Test
    fun scalingAPortionKeepsAbsencesAbsent() {
        val a = NutrientSet.Amounts(mapOf(NutrientSet.Nutrient.ZINC to 2.0))
        val half = a.scaled(0.5)
        assertEquals(1.0, half[NutrientSet.Nutrient.ZINC]!!, 0.0)
        assertNull(half[NutrientSet.Nutrient.MAGNESIUM])
        // ⚠️ A nonsensical factor yields nothing rather than the unscaled record, which would claim
        // a portion of unknown size contains exactly the per-100-gram figures.
        assertTrue(a.scaled(Double.NaN).isEmpty)
        assertTrue(a.scaled(-1.0).isEmpty)
    }

    /**
     * ⚠️ The union, not the intersection. One ingredient recording zinc and another not means the
     * sum is the one figure there is; treating the silent one as zero understates the total, and
     * refusing to add reports nothing for a dish that partly knows.
     */
    @Test
    fun addingTwoRecordsTakesTheUnion() {
        val a = NutrientSet.Amounts(mapOf(NutrientSet.Nutrient.ZINC to 2.0))
        val b = NutrientSet.Amounts(
            mapOf(NutrientSet.Nutrient.ZINC to 1.0, NutrientSet.Nutrient.WATER to 40.0),
        )
        val sum = a + b
        assertEquals(3.0, sum[NutrientSet.Nutrient.ZINC]!!, 0.0)
        assertEquals(40.0, sum[NutrientSet.Nutrient.WATER]!!, 0.0)
        assertEquals(2, sum.values.size)
    }

    // ---------------------------------------------------------------------------------------- day

    /**
     * ⚠️ The denominator is every food eaten, including the ones that reported nothing. Otherwise a
     * day mostly made of records that say nothing reports perfect coverage.
     */
    @Test
    fun aFoodThatRecordsNothingIsStillAFoodThatWasEaten() {
        var day = NutrientSet.Day()
        day = NutrientSet.add(day, NutrientSet.Amounts(mapOf(NutrientSet.Nutrient.ZINC to 2.0)))
        day = NutrientSet.add(day, NutrientSet.Amounts())
        assertEquals(2, day.entries)
        assertEquals(2.0, day[NutrientSet.Nutrient.ZINC]!!.total, 0.0)
        assertEquals(1, day[NutrientSet.Nutrient.ZINC]!!.reported)
        assertEquals(0.5, day.coverage(NutrientSet.Nutrient.ZINC)!!, 0.0)
    }

    @Test
    fun nothingLoggedIsNotTheSameAsNothingReported() {
        val empty = NutrientSet.Day()
        assertNull(empty.coverage(NutrientSet.Nutrient.ZINC))
        assertNull(empty.caveat(NutrientSet.Nutrient.ZINC))
    }

    @Test
    fun aThinlyFoundedTotalSaysHowThinlyFounded() {
        var day = NutrientSet.Day()
        day = NutrientSet.add(day, NutrientSet.Amounts(mapOf(NutrientSet.Nutrient.ZINC to 2.0)))
        repeat(3) { day = NutrientSet.add(day, NutrientSet.Amounts()) }
        assertEquals(
            "From 1 of 4 foods — the rest do not record it.",
            day.caveat(NutrientSet.Nutrient.ZINC),
        )
        assertEquals(
            "None of today's food records this.",
            day.caveat(NutrientSet.Nutrient.WATER),
        )
        // …and a well-covered figure needs no caveat, because one printed every time is unread.
        var full = NutrientSet.Day()
        repeat(4) { full = NutrientSet.add(full, NutrientSet.Amounts(mapOf(NutrientSet.Nutrient.ZINC to 1.0))) }
        assertNull(full.caveat(NutrientSet.Nutrient.ZINC))
    }

    /** A figure that cannot be believed is not a measurement, so it must not raise the count either. */
    @Test
    fun anUnbelievableFigureIsNotCountedAsAReport() {
        val day = NutrientSet.add(
            NutrientSet.Day(),
            NutrientSet.Amounts(
                mapOf(
                    NutrientSet.Nutrient.ZINC to Double.NaN,
                    NutrientSet.Nutrient.WATER to -5.0,
                ),
            ),
        )
        assertEquals(1, day.entries)
        assertNull(day[NutrientSet.Nutrient.ZINC])
        assertNull(day[NutrientSet.Nutrient.WATER])
    }

    /** Every nutrient belongs to a group, so a picker can offer them in an order that makes sense. */
    @Test
    fun everyNutrientIsFiledSomewhere() {
        for (g in NutrientSet.Group.entries) assertTrue(g.label.isNotBlank())
        assertTrue(all.all { it.group in NutrientSet.Group.entries })
        // Each group actually has something in it — an empty heading is a row that is always blank.
        for (g in NutrientSet.Group.entries) {
            assertTrue("$g is empty", all.any { it.group == g })
        }
    }
}
