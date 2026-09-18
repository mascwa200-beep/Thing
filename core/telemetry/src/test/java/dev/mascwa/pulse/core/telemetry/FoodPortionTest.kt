package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portions, and the four traps the live Open Food Facts data set.
 *
 * ⚠️ Every fixture here is a real value from a real probe rather than a round number I chose, because
 * three of the four rules exist to handle something the shape of the API does not suggest.
 */
class FoodPortionTest {

    private val crisps = FoodPortion.Sizes(servingGrams = 28.0, servingLabel = "1 serving (28 g)")
    private val cola = FoodPortion.Sizes(servingGrams = 330.0, servingLabel = "1 portion (330 ml)")
    private val bare = FoodPortion.Sizes()

    private fun p(amount: Double, unit: FoodPortion.Unit) = FoodPortion.Portion(amount, unit)

    // -------------------------------------------------------------------------------- conversion

    @Test
    fun gramsAreThemselves() {
        assertEquals(30.0, FoodPortion.gramsFor(p(30.0, FoodPortion.Unit.GRAM), bare)!!, 1e-9)
        assertEquals(330.0, FoodPortion.gramsFor(p(330.0, FoodPortion.Unit.MILLILITRE), bare)!!, 1e-9)
    }

    @Test
    fun aServingIsItsOwnWeightTimesHowMany() {
        assertEquals(28.0, FoodPortion.gramsFor(p(1.0, FoodPortion.Unit.SERVING), crisps)!!, 1e-9)
        assertEquals(70.0, FoodPortion.gramsFor(p(2.5, FoodPortion.Unit.SERVING), crisps)!!, 1e-9)
    }

    /**
     * ⚠️ The rule that matters most, and it is a refusal.
     *
     * A serving is not a unit of mass. Most records declare no serving weight at all, and the only
     * honest answer for "how much is one serving of this" is that the record does not say — so the
     * unit is not offered, and asking for it anyway yields nothing rather than a plausible default.
     */
    @Test
    fun aServingOfSomethingThatDeclaresNoServingIsNotAQuantity() {
        assertNull(FoodPortion.gramsFor(p(1.0, FoodPortion.Unit.SERVING), bare))
        assertNull(FoodPortion.gramsFor(p(1.0, FoodPortion.Unit.PACKAGE), bare))
        assertNull("a zero serving weight is no serving weight",
            FoodPortion.gramsFor(p(1.0, FoodPortion.Unit.SERVING), FoodPortion.Sizes(servingGrams = 0.0)))
        assertEquals(
            "so the unit is never offered either",
            listOf(FoodPortion.Unit.GRAM), FoodPortion.unitsFor(bare),
        )
    }

    @Test
    fun everyUnitAFoodCanActuallyExpressIsOffered() {
        val full = FoodPortion.Sizes(servingGrams = 28.0, packageGrams = 200.0)
        assertEquals(
            listOf(FoodPortion.Unit.GRAM, FoodPortion.Unit.SERVING, FoodPortion.Unit.PACKAGE),
            FoodPortion.unitsFor(full),
        )
    }

    @Test
    fun nonsenseAmountsYieldNothing() {
        assertNull(FoodPortion.gramsFor(p(Double.NaN, FoodPortion.Unit.GRAM), bare))
        assertNull(FoodPortion.gramsFor(p(-5.0, FoodPortion.Unit.GRAM), bare))
    }

    /**
     * The arithmetic, checked against the live figures rather than against itself.
     *
     * Probed: those crisps are 565.371024734982 kcal/100 g with a 28 g serving, and Open Food Facts'
     * own per-serving figure is 158. 565.371… × 0.28 = 158.3, which rounds to 158 — so this function
     * agreeing with the API to the printed digit is the check, and my own multiplication is not.
     */
    @Test
    fun aPortionIsTheHundredGramFigureScaledAndItAgreesWithTheSource() {
        val per100 = NutritionDay.Nutrients(kcal = 565.371024734982, proteinG = 6.0, fatG = 35.0, carbG = 53.0)
        val eaten = FoodPortion.eaten(per100, 28.0)
        assertEquals(158, Math.round(eaten.kcal).toInt())
        assertEquals(565.371024734982 * 0.28, eaten.kcal, 1e-9)
        assertEquals(1.68, eaten.proteinG, 1e-9)
        assertEquals(9.8, eaten.fatG, 1e-9)
    }

    @Test
    fun anImpossiblePortionContainsNothing() {
        val per100 = NutritionDay.Nutrients(kcal = 500.0, proteinG = 10.0)
        assertTrue(FoodPortion.eaten(per100, 0.0).isEmpty)
        assertTrue(FoodPortion.eaten(per100, -30.0).isEmpty)
        assertTrue(FoodPortion.eaten(per100, Double.NaN).isEmpty)
    }

    // ------------------------------------------------------------------------- sanity of the data

    /**
     * ⚠️ The 3-gram biscuit.
     *
     * A real record, found while probing: a packet of sandwich biscuits declaring `serving_quantity:
     * 3` with a per-serving energy of 14.1 kcal computed faithfully from it. Roughly a tenth of one
     * biscuit. A serving weight multiplies, so this is wrong by a factor of ten rather than by a
     * rounding, and somebody logging "2 servings" would record 28 calories for most of a packet.
     */
    @Test
    fun anAbsurdServingWeightIsFlagged() {
        assertTrue("the real record that prompted this rule", FoodPortion.servingLooksWrong(3.0))
        assertTrue(FoodPortion.servingLooksWrong(0.0))
        assertTrue(FoodPortion.servingLooksWrong(-10.0))
        assertTrue(FoodPortion.servingLooksWrong(Double.NaN))
        assertTrue("nothing weighs three kilos a serving", FoodPortion.servingLooksWrong(3000.0))
    }

    /**
     * And the bounds are wide on purpose — flagging an ordinary portion would train people to ignore
     * the warning, which costs more than the occasional bad record it would have caught.
     */
    @Test
    fun ordinaryServingsAreNotFlagged() {
        assertFalse("a stick of gum", FoodPortion.servingLooksWrong(4.0))
        assertFalse("a bag of crisps", FoodPortion.servingLooksWrong(28.0))
        assertFalse("a can of cola", FoodPortion.servingLooksWrong(330.0))
        assertFalse("a family lasagne", FoodPortion.servingLooksWrong(1200.0))
        assertFalse("no declared serving is not a wrong one", FoodPortion.servingLooksWrong(null))
    }

    /**
     * ⚠️ One search result in eight came back with no energy and no macros at all.
     *
     * Not an error and not a network failure — a real record somebody created without filling in the
     * nutrition. It cannot be logged, so it must not be listed as though it could.
     */
    @Test
    fun aRecordWithNoEnergyCannotBeLogged() {
        assertFalse(FoodPortion.isLoggable(NutritionDay.Nutrients()))
        assertFalse(FoodPortion.isLoggable(NutritionDay.Nutrients(kcal = 0.0, proteinG = 20.0)))
        assertFalse(FoodPortion.isLoggable(NutritionDay.Nutrients(kcal = Double.NaN)))
        assertTrue(FoodPortion.isLoggable(NutritionDay.Nutrients(kcal = 42.0)))
    }

    /**
     * ⚠️ Sodium arrives in GRAMS.
     *
     * `sodium_100g` sits beside `sodium_unit: "g"` and reads 0.043 for a spread containing 43 mg —
     * probed, not recalled. Passing it through unchanged is wrong by a factor of a thousand and in
     * the direction that makes every food on the planet look sodium-free.
     */
    @Test
    fun sodiumIsConvertedFromGramsAndNotPassedThrough() {
        assertEquals("the real Nutella figure", 43.0, FoodPortion.sodiumMgFromGrams(0.043), 1e-9)
        assertEquals("the real crisps figure", 536.0, FoodPortion.sodiumMgFromGrams(0.536), 1e-9)
        assertEquals(0.0, FoodPortion.sodiumMgFromGrams(null), 1e-9)
        assertEquals(0.0, FoodPortion.sodiumMgFromGrams(Double.NaN), 1e-9)
        assertEquals(0.0, FoodPortion.sodiumMgFromGrams(-1.0), 1e-9)
    }

    // ------------------------------------------------------------------------------------ wording

    @Test
    fun aPortionSaysWhatItWas() {
        assertEquals("30 g", FoodPortion.describe(p(30.0, FoodPortion.Unit.GRAM), bare))
        assertEquals("330 ml", FoodPortion.describe(p(330.0, FoodPortion.Unit.MILLILITRE), bare))
        // One serving uses the source's own words, because they are better than any I would write.
        assertEquals("1 serving (28 g)", FoodPortion.describe(p(1.0, FoodPortion.Unit.SERVING), crisps))
        assertEquals("1 portion (330 ml)", FoodPortion.describe(p(1.0, FoodPortion.Unit.SERVING), cola))
    }

    /**
     * ⚠️ Past one serving the source's label is dropped, and the reason is that keeping it produces
     * nonsense. Those labels already contain a count: "2 × 1 portion (330 ml)" states both two and
     * one in the same breath, and "1 × 1 serving (28 g) (28 g)" — which my first version emitted —
     * gives the weight twice.
     */
    @Test
    fun moreThanOneServingDescribesItselfRatherThanRepeatingTheLabel() {
        assertEquals("2 servings (660 g)", FoodPortion.describe(p(2.0, FoodPortion.Unit.SERVING), cola))
        assertEquals("3 servings (84 g)", FoodPortion.describe(p(3.0, FoodPortion.Unit.SERVING), crisps))
        val one = FoodPortion.describe(p(1.0, FoodPortion.Unit.SERVING), crisps)
        assertFalse("and one serving never doubles the weight", one.contains("(28 g) (28 g)"))
    }

    /** A serving with no weight still says how many, because that is all it honestly knows. */
    @Test
    fun aServingWithNoWeightStillNamesItself() {
        assertEquals("1 serving", FoodPortion.describe(p(1.0, FoodPortion.Unit.SERVING), bare))
        assertEquals("3 servings", FoodPortion.describe(p(3.0, FoodPortion.Unit.SERVING), bare))
    }

    /** Locale-fixed: a comma decimal reads as two numbers in a portion label. */
    @Test
    fun halfAServingIsWrittenWithAPoint() {
        val s = FoodPortion.describe(p(1.5, FoodPortion.Unit.SERVING), FoodPortion.Sizes(servingGrams = 40.0))
        assertTrue(s, s.startsWith("1.5 servings"))
        assertTrue(s, s.contains("60 g"))
    }

    // -------------------------------------------------- defining a food from what is on the label

    /**
     * Label figures for a stated weight become the per-hundred-gram form everything else uses.
     *
     * Worked from the rule before the assertion: 40 g of something at 208 kcal is
     * 208 × 100/40 = 520 kcal per 100 g, and every other field scales by the same 2.5.
     */
    @Test
    fun labelFiguresForAStatedWeightBecomeADensity() {
        val eaten = NutritionDay.Nutrients(
            kcal = 208.0, proteinG = 3.2, fatG = 11.6, carbG = 22.0, sodiumMg = 180.0,
        )
        val per100 = FoodPortion.per100gFrom(eaten, 40.0)!!
        assertEquals(520.0, per100.kcal, 1e-9)
        assertEquals(8.0, per100.proteinG, 1e-9)
        assertEquals(29.0, per100.fatG, 1e-9)
        assertEquals(55.0, per100.carbG, 1e-9)
        assertEquals(450.0, per100.sodiumMg, 1e-9)
    }

    /** A hundred grams is already the density, so it must come back untouched. */
    @Test
    fun aHundredGramLabelIsAlreadyTheAnswer() {
        val n = NutritionDay.Nutrients(kcal = 389.0, proteinG = 16.9, fatG = 6.9, carbG = 66.3)
        val per100 = FoodPortion.per100gFrom(n, 100.0)!!
        assertEquals(389.0, per100.kcal, 1e-9)
        assertEquals(16.9, per100.proteinG, 1e-9)
    }

    /**
     * ⚠️ THE LOAD-BEARING RULE. Without a weight there is no density, and the tempting fallback —
     * treating the figures as if they were already per hundred grams — makes a food that looks
     * right in the list and is wrong by whatever the real portion happened to be. Refusing lets
     * the surface say why; guessing cannot be checked by anybody afterwards.
     */
    @Test
    fun noWeightMeansNoFoodRatherThanAGuessedDensity() {
        val n = NutritionDay.Nutrients(kcal = 320.0)
        assertNull(FoodPortion.per100gFrom(n, 0.0))
        assertNull(FoodPortion.per100gFrom(n, -5.0))
        assertNull(FoodPortion.per100gFrom(n, Double.NaN))
    }

    /** And it round-trips with [FoodPortion.eaten], which is the pair that has to agree. */
    @Test
    fun definingAFoodAndThenEatingThatMuchOfItGivesTheLabelBack() {
        val label = NutritionDay.Nutrients(kcal = 137.0, proteinG = 4.4, fatG = 2.1, carbG = 25.0)
        val per100 = FoodPortion.per100gFrom(label, 62.0)!!
        val back = FoodPortion.eaten(per100, 62.0)
        assertEquals(label.kcal, back.kcal, 1e-9)
        assertEquals(label.proteinG, back.proteinG, 1e-9)
        assertEquals(label.fatG, back.fatG, 1e-9)
        assertEquals(label.carbG, back.carbG, 1e-9)
    }

    // -------------------------------------------------------------- what a hundred grams can hold

    /**
     * ⚠️ THE REASON THIS RULE EXISTS, in the shape it actually arrived in.
     *
     * The barcode builder's one value ceiling was applied to the *raw* figure, so a record saying
     * `proteins_100g: 5000` — five kilograms of protein in a hundred grams — was stored and rendered
     * as a food. There is nothing about it on a card that reads as an error.
     */
    @Test
    fun aConstituentCannotOutweighTheFood() {
        val absurd = NutritionDay.Nutrients(kcal = 250.0, proteinG = 5000.0)
        assertEquals(0.0, FoodPortion.sane(absurd).proteinG, 1e-9)
        // ⚠️ And the rest of the record survives. Dropping the food over one bad field would throw
        // away a perfectly good calorie count, which is the field people actually log.
        assertEquals(250.0, FoodPortion.sane(absurd).kcal, 1e-9)
    }

    /**
     * ⚠️ THE OTHER HALF, and the one a nutritional opinion would get wrong: a genuinely extreme food
     * passes untouched. A whey isolate really is eighty grams of protein per hundred and olive oil
     * really is a hundred grams of fat at 884 kcal.
     *
     * Figures are the USDA analyses, not round numbers.
     */
    @Test
    fun theDensestRealFoodsAreLeftAlone() {
        val oil = NutritionDay.Nutrients(kcal = 884.0, fatG = 100.0, satFatG = 13.8)
        assertEquals(884.0, FoodPortion.sane(oil).kcal, 1e-9)
        assertEquals(100.0, FoodPortion.sane(oil).fatG, 1e-9)

        val isolate = NutritionDay.Nutrients(kcal = 373.0, proteinG = 80.0, fatG = 5.0, carbG = 8.0)
        assertEquals(80.0, FoodPortion.sane(isolate).proteinG, 1e-9)
        assertEquals(373.0, FoodPortion.sane(isolate).kcal, 1e-9)
    }

    /**
     * ⚠️ When the macros outweigh the food, the WHOLE nutrition block goes.
     *
     * There is no way to know which of the three is wrong, and a record whose macros are impossible
     * has not earned belief about its energy either. 60 + 60 + 60 is 180 g of constituents in 100 g
     * of food, and each field on its own is inside every bound.
     */
    @Test
    fun aRecordThatContradictsItselfKeepsNoNumbersAtAll() {
        val contradictory = NutritionDay.Nutrients(kcal = 800.0, proteinG = 60.0, fatG = 60.0, carbG = 60.0)
        val out = FoodPortion.sane(contradictory)
        assertEquals(0.0, out.kcal, 1e-9)
        assertEquals(0.0, out.proteinG, 1e-9)
        assertFalse(FoodPortion.isLoggable(out))
    }

    /** 35 + 35 + 35 is 105 g, which is the slack rounding is allowed. It stays. */
    @Test
    fun theSlackForRoundingIsRealSlack() {
        val dense = NutritionDay.Nutrients(kcal = 500.0, proteinG = 35.0, fatG = 35.0, carbG = 35.0)
        assertEquals(500.0, FoodPortion.sane(dense).kcal, 1e-9)
    }

    /**
     * ⚠️ Every micronutrient is bounded by its own DECLARED unit, so one added later is bounded the
     * moment it exists. This is the test that makes that safe: an unrecognised unit falls through to
     * no bound at all, which is exactly the failure the whole rule exists to stop.
     */
    @Test
    fun everyMicronutrientDeclaresAUnitThisRuleUnderstands() {
        for (m in Micronutrients.Micro.entries) {
            assertTrue(
                "${m.name} declares the unit '${m.unit}', which FoodPortion.maxPer100g does not " +
                    "recognise — it would be admitted unbounded",
                FoodPortion.maxPer100g(m) < Double.MAX_VALUE,
            )
        }
    }

    /**
     * The vitamin A case that prompted the micronutrient half: `vitamin-a_100g` is documented in
     * grams, a contributor typed 1500 international units into it, and the grams-to-micrograms
     * conversion turned that into one and a half billion micrograms.
     */
    @Test
    fun anImpossibleMicronutrientIsDroppedRatherThanZeroed() {
        val amounts = Micronutrients.Amounts(
            mapOf(
                Micronutrients.Micro.VITAMIN_A to 1_500_000_000.0,
                Micronutrients.Micro.CALCIUM to 120.0,
            ),
        )
        val out = FoodPortion.saneMicros(amounts)
        // ⚠️ Absent, not zero. A zero says somebody measured none of it.
        assertNull(out[Micronutrients.Micro.VITAMIN_A])
        assertEquals(120.0, out[Micronutrients.Micro.CALCIUM]!!, 1e-9)
    }

    /**
     * ⚠️ A person typing their own numbers is TOLD, where a parser is not.
     *
     * 320 kcal in 20 g is 1600 per hundred, which nothing edible reaches — and the likeliest cause is
     * the weight beside it rather than the calories, which is why the sentence says so. Silently
     * emptying what somebody just entered would look like the app losing their food.
     */
    @Test
    fun aHandEnteredDensityIsRefusedWithAReason() {
        val eaten = NutritionDay.Nutrients(kcal = 320.0)
        val density = FoodPortion.per100gFrom(eaten, 20.0)!!
        val why = FoodPortion.densityLooksWrong(density)
        assertTrue(why != null && why.contains("1600"))
        assertTrue(why!!.contains("weight"))

        // An ordinary label says nothing at all.
        val fine = FoodPortion.per100gFrom(
            NutritionDay.Nutrients(kcal = 137.0, proteinG = 4.4, fatG = 2.1, carbG = 25.0), 62.0,
        )!!
        assertNull(FoodPortion.densityLooksWrong(fine))
    }

    /**
     * The other direction for the sparse layers, which is what a hand-typed label needs.
     *
     * ⚠️ Expected values worked out from the rule before the assertion: 120 mg of calcium in 50 g is
     * 120 × 100/50 = **240 mg per hundred grams**, and 41.2 mg of magnesium in 50 g is **82.4**.
     * Both were then confirmed by running the shipped function over exactly this input.
     */
    @Test
    fun theSparseLayersConvertToADensityTheSameWay() {
        val eatenM = Micronutrients.Amounts(mapOf(Micronutrients.Micro.CALCIUM to 120.0))
        val eatenE = NutrientSet.Amounts(mapOf(NutrientSet.Nutrient.MAGNESIUM to 41.2))

        assertEquals(240.0, FoodPortion.per100gMicrosFrom(eatenM, 50.0)[Micronutrients.Micro.CALCIUM]!!, 1e-9)
        assertEquals(82.4, FoodPortion.per100gExtrasFrom(eatenE, 50.0)[NutrientSet.Nutrient.MAGNESIUM]!!, 1e-9)

        // And it round-trips through the portion conversion, which is the property that matters:
        // what goes into a saved food, scaled back to the portion, is what was eaten.
        val backM = FoodPortion.eatenMicros(FoodPortion.per100gMicrosFrom(eatenM, 50.0), 50.0)
        assertEquals(120.0, backM[Micronutrients.Micro.CALCIUM]!!, 1e-9)
    }

    /**
     * ⚠️ **Empty, not null, and not the input.**
     *
     * `per100gFrom` returns null without a weight because a food with no density is not a food, and
     * that refusal gates the whole save. These ride along on the same weight, so a second refusal
     * would only be a second spelling of it. What must never happen is the eaten figures being
     * passed through unchanged — that would record a 30 g biscuit's magnesium as if it were a
     * hundred grams of it, while the macros beside them converted properly.
     */
    @Test
    fun noWeightYieldsNothingRatherThanTheEatenFiguresUnchanged() {
        val eatenM = Micronutrients.Amounts(mapOf(Micronutrients.Micro.CALCIUM to 120.0))
        val eatenE = NutrientSet.Amounts(mapOf(NutrientSet.Nutrient.MAGNESIUM to 41.2))
        for (bad in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue("weight $bad", FoodPortion.per100gMicrosFrom(eatenM, bad).isEmpty)
            assertTrue("weight $bad", FoodPortion.per100gExtrasFrom(eatenE, bad).isEmpty)
        }
    }

    /**
     * A nutrient nobody typed does not acquire a zero density on the way through.
     *
     * ⚠️ This is the whole discipline of the sparse layer restated at the conversion: absence is the
     * absence of a key, and a conversion that mapped over the enum instead of over the keys present
     * would quietly turn "nobody measured the iron" into "this food contains no iron".
     */
    @Test
    fun absentStaysAbsentThroughTheConversion() {
        val only = Micronutrients.Amounts(mapOf(Micronutrients.Micro.CALCIUM to 120.0))
        val density = FoodPortion.per100gMicrosFrom(only, 50.0)
        assertEquals(1, density.values.size)
        assertNull(density[Micronutrients.Micro.IRON])
    }
}
