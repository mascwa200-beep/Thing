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
}
