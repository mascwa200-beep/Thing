package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The food model and the arithmetic that turns a day of it into one set of numbers. */
class NutritionDayTest {

    private val day = 1_700_000_000_000L

    private fun n(kcal: Double, p: Double = 0.0, f: Double = 0.0, c: Double = 0.0) =
        NutritionDay.Nutrients(kcal = kcal, proteinG = p, fatG = f, carbG = c)

    private fun entry(
        id: String,
        kcal: Double,
        p: Double = 0.0,
        f: Double = 0.0,
        c: Double = 0.0,
        meal: NutritionDay.Meal = NutritionDay.Meal.SNACK,
        at: Long = day,
    ) = NutritionDay.Entry(
        id = id, dayStartMs = day, atMs = at, name = id, grams = 100.0,
        nutrients = n(kcal, p, f, c), meal = meal,
    )

    // ----------------------------------------------------------------------------------- the model

    @Test
    fun addingTwoPortionsAddsEveryField() {
        val a = NutritionDay.Nutrients(100.0, 10.0, 2.0, 8.0, 1.0, 3.0, 0.5, 40.0)
        val b = NutritionDay.Nutrients(50.0, 5.0, 1.0, 4.0, 0.5, 1.5, 0.25, 20.0)
        val sum = a + b
        assertEquals(150.0, sum.kcal, 1e-9)
        assertEquals(15.0, sum.proteinG, 1e-9)
        assertEquals(3.0, sum.fatG, 1e-9)
        assertEquals(12.0, sum.carbG, 1e-9)
        assertEquals(1.5, sum.fibreG, 1e-9)
        assertEquals(4.5, sum.sugarG, 1e-9)
        assertEquals(0.75, sum.satFatG, 1e-9)
        assertEquals(60.0, sum.sodiumMg, 1e-9)
    }

    /** A per-100-gram record scaled to a 30 g portion is 0.3 of it, in every field. */
    @Test
    fun scalingAPortionScalesEveryField() {
        val per100 = NutritionDay.Nutrients(500.0, 8.0, 25.0, 60.0, 2.0, 30.0, 12.0, 300.0)
        val eaten = per100.scaled(0.3)
        assertEquals(150.0, eaten.kcal, 1e-9)
        assertEquals(2.4, eaten.proteinG, 1e-9)
        assertEquals(7.5, eaten.fatG, 1e-9)
        assertEquals(18.0, eaten.carbG, 1e-9)
        assertEquals(90.0, eaten.sodiumMg, 1e-9)
    }

    /** A nonsense factor yields nothing rather than a nonsense meal. */
    @Test
    fun anImpossibleScaleFactorYieldsNothing() {
        val per100 = n(500.0, 8.0, 25.0, 60.0)
        assertTrue(per100.scaled(-1.0).isEmpty)
        assertTrue(per100.scaled(Double.NaN).isEmpty)
        assertTrue(per100.scaled(Double.POSITIVE_INFINITY).isEmpty)
        assertTrue("but zero is a real answer, not an error", per100.scaled(0.0).isEmpty)
    }

    // ---------------------------------------------------------------------------------- the totals

    @Test
    fun aDayIsTheSumOfWhatIsOnIt() {
        val t = NutritionDay.total(listOf(entry("a", 400.0, 30.0), entry("b", 250.0, 12.0)))
        assertEquals(650.0, t.kcal, 1e-9)
        assertEquals(42.0, t.proteinG, 1e-9)
        assertEquals("an empty day is zero, not an error", 0.0, NutritionDay.total(emptyList()).kcal, 1e-9)
    }

    /**
     * ⚠️ Meals come back in the order a day happens, not in the order things were logged — somebody who
     * enters dinner before remembering breakfast should still read down the page in time order. And a
     * meal nobody ate is absent rather than a row of zeroes, because a zero is a claim.
     */
    @Test
    fun mealsComeBackInTheOrderADayHappens() {
        val entries = listOf(
            entry("late snack", 200.0, meal = NutritionDay.Meal.SNACK),
            entry("dinner", 700.0, meal = NutritionDay.Meal.DINNER),
            entry("breakfast", 400.0, meal = NutritionDay.Meal.BREAKFAST),
        )
        val byMeal = NutritionDay.byMeal(entries)
        assertEquals(
            listOf(NutritionDay.Meal.BREAKFAST, NutritionDay.Meal.DINNER, NutritionDay.Meal.SNACK),
            byMeal.keys.toList(),
        )
        assertFalse("lunch was never eaten", byMeal.containsKey(NutritionDay.Meal.LUNCH))
        assertEquals(700.0, byMeal[NutritionDay.Meal.DINNER]!!.kcal, 1e-9)
    }

    /** What is left, and what is over. Both matter and the sign is what tells them apart. */
    @Test
    fun remainingGoesNegativeWhenTheDayIsOverspent() {
        val targets = MacroTargets.Targets(kcal = 2000, proteinG = 150, fatG = 60, carbG = 215)
        val under = NutritionDay.remaining(n(1400.0, 100.0, 40.0, 150.0), targets)
        assertEquals(600, under.kcal)
        assertEquals(50, under.proteinG)
        assertFalse(under.overKcal)

        val over = NutritionDay.remaining(n(2350.0, 170.0, 80.0, 240.0), targets)
        assertEquals(-350, over.kcal)
        assertEquals(-20, over.proteinG)
        assertTrue(over.overKcal)
    }

    // ------------------------------------------------------------------------------ self-checking

    @Test
    fun theEnergyMacrosImplyIsFourNineFour() {
        assertEquals(
            10 * 4.0 + 5 * 9.0 + 20 * 4.0,
            NutritionDay.energyFromMacros(n(0.0, 10.0, 5.0, 20.0)),
            1e-9,
        )
        assertEquals(165.0, NutritionDay.energyFromMacros(n(0.0, 10.0, 5.0, 20.0)), 1e-9)
    }

    /**
     * ⚠️ BOTH bars must be cleared, and the pair is the whole design.
     *
     * Crowd-sourced records are typed off a label by volunteers and labels round, so a 250 kcal snack
     * whose macros come to 230 is transcription rather than an error worth interrupting anyone over — the
     * *fraction* lets that pass. A 12 kcal cup of tea four calories out of step is 33% wrong and utterly
     * unremarkable — the *floor* lets that pass too. Only something like a per-100-gram figure entered as
     * a per-serving one clears both, and that is exactly the mistake worth catching.
     */
    @Test
    fun onlyAnEnergyErrorWorthMentioningIsFlagged() {
        // Ordinary label rounding: 250 stated, 230 implied. 8% out, and 20 kcal — under BOTH bars.
        assertFalse(NutritionDay.energyLooksWrong(n(250.0, 10.0, 10.0, 25.0)))

        // ⚠️ A big meal, where the discrepancy is large in calories and small in proportion. This is
        // the ONLY shape that tests the fraction: 40 P + 30 F + 120 C implies 910 against a stated
        // 1,000, so the gap is 90 kcal — three times the floor — and 9%, well inside the fraction. My
        // first version of this test used the 20 kcal case above for both halves, which the floor
        // already caught, so deleting the fraction check changed nothing and the guard slept.
        val bigMeal = n(1000.0, 40.0, 30.0, 120.0)
        assertEquals("the fixture must clear the floor or it tests nothing",
            910.0, NutritionDay.energyFromMacros(bigMeal), 1e-9)
        assertTrue(kotlin.math.abs(1000.0 - 910.0) > NutritionDay.ENERGY_MISMATCH_FLOOR_KCAL)
        assertFalse("90 calories out of a thousand is a rounded label, not a mistake",
            NutritionDay.energyLooksWrong(bigMeal))

        // Large in proportion, tiny in absolute terms — the floor must swallow it.
        assertTrue("the fixture must clear the fraction or it tests nothing",
            NutritionDay.energyFromMacros(n(12.0, 0.0, 0.0, 2.0)) == 8.0)
        assertFalse("4 kcal is a third of a cup of tea and means nothing",
            NutritionDay.energyLooksWrong(n(12.0, 0.0, 0.0, 2.0)))

        // A per-100 g figure logged against a 30 g portion: macros for 30 g, calories for 100 g.
        val mistake = NutritionDay.Nutrients(kcal = 500.0, proteinG = 2.4, fatG = 7.5, carbG = 18.0)
        assertTrue("150 implied against 500 stated is the mistake this exists for",
            NutritionDay.energyLooksWrong(mistake))
    }

    /** Nothing to check is not the same as something wrong. */
    @Test
    fun arecordWithNothingInItIsNotAnError() {
        assertFalse(NutritionDay.energyLooksWrong(NutritionDay.Nutrients()))
        assertFalse("no stated energy, nothing to disagree with", NutritionDay.energyLooksWrong(n(0.0, 10.0, 5.0, 20.0)))
        assertFalse("no macros either", NutritionDay.energyLooksWrong(n(500.0)))
        assertFalse(NutritionDay.energyLooksWrong(n(Double.NaN, 10.0)))
    }

    // ----------------------------------------------------------------------------------- wording

    @Test
    fun theSummaryCarriesTheCaloriesAndTheThreeMacros() {
        val s = NutritionDay.summarise(n(1847.4, 132.2, 60.8, 189.0))
        assertEquals("1,847 kcal · 132 P / 61 F / 189 C", s)
        // Locale-fixed: a comma decimal separator would read as a completely different number.
        assertTrue(s.contains("1,847"))
    }

    @Test
    fun everyLabelSaysSomething() {
        assertTrue(NutritionDay.Meal.entries.all { it.label.isNotBlank() })
        assertTrue(NutritionDay.Source.entries.all { it.label.isNotBlank() })
    }
}
