package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected number was computed from the defining formula before the assertion was written,
 * and the arithmetic is in the comment beside it. The stew below is the fixture throughout:
 *
 *   500 g mince    at 250 kcal / 26 P / 15 F /  0 C per 100 g
 *   400 g tomatoes at  20 kcal /  1 P /  0 F /  4 C per 100 g
 *   raw  = 900 g
 *   total = 250*5 + 20*4 = 1330 kcal, 134 P, 75 F, 16 C
 */
class RecipesTest {

    private val mince = Recipes.Component(
        "mince", "Beef mince",
        NutritionDay.Nutrients(kcal = 250.0, proteinG = 26.0, fatG = 15.0, carbG = 0.0),
        grams = 500.0,
    )
    private val tomatoes = Recipes.Component(
        "tom", "Chopped tomatoes",
        NutritionDay.Nutrients(kcal = 20.0, proteinG = 1.0, fatG = 0.0, carbG = 4.0),
        grams = 400.0,
    )
    private fun stew(yieldG: Double? = null, servings: Int = 4) =
        Recipes.Recipe("r1", "Stew", listOf(mince, tomatoes), cookedYieldG = yieldG, servings = servings)

    // ----------------------------------------------------------------------------------- totals

    @Test
    fun theTotalIsTheSumOfWhatWentIn() {
        val t = Recipes.total(stew())
        assertEquals(1330.0, t.kcal, 1e-9)
        assertEquals(134.0, t.proteinG, 1e-9)
        assertEquals(75.0, t.fatG, 1e-9)
        assertEquals(16.0, t.carbG, 1e-9)
        assertEquals(900.0, Recipes.rawGrams(stew()), 1e-9)
    }

    /**
     * ⚠️ THE RULE THAT IS EASY TO GET BACKWARDS, and getting it wrong under-reports every cooked
     * dish silently — the number would simply look a bit low.
     *
     * Simmering removes water: the same calories in less mass, so per-100 g goes UP. The totals are
     * untouched either way.
     */
    @Test
    fun aReductionRaisesTheDensityAndLeavesTheTotalAlone() {
        // 1330 kcal in 600 g = 221.667 per 100 g. Dividing by the RAW 900 g would give 147.8.
        val reduced = Recipes.per100g(stew(yieldG = 600.0))!!
        assertEquals(221.6666667, reduced.kcal, 1e-6)
        assertEquals(1330.0, Recipes.total(stew(yieldG = 600.0)).kcal, 1e-9)

        // 1330 kcal in 1200 g = 110.833 — rice and pasta absorb water, which is legitimate.
        assertEquals(110.8333333, Recipes.per100g(stew(yieldG = 1200.0))!!.kcal, 1e-6)

        // With no weighed yield, the raw weight stands in: 1330/900 * 100 = 147.778.
        assertEquals(147.7777778, Recipes.per100g(stew())!!.kcal, 1e-6)
    }

    // ---------------------------------------------------------------------------------- portions

    /**
     * ⚠️ The two routes to a helping must agree. They are the commonest way a recipe feature goes
     * wrong: one path quietly uses the raw weight while the other uses the cooked one, and nothing
     * says so because both produce a plausible number.
     */
    @Test
    fun aCountedHelpingAndAWeighedOneAgree() {
        val r = stew(yieldG = 600.0, servings = 4)
        // 600 g into 4 = 150 g each; 1330/4 = 332.5 kcal each.
        assertEquals(150.0, Recipes.servingGrams(r)!!, 1e-9)
        val counted = Recipes.eatenServings(r, 1.0)!!
        val weighed = Recipes.eatenGrams(r, 150.0)!!
        assertEquals(332.5, counted.kcal, 1e-9)
        assertEquals(counted.kcal, weighed.kcal, 1e-6)
        assertEquals(counted.proteinG, weighed.proteinG, 1e-6)
        assertEquals(counted.fatG, weighed.fatG, 1e-6)
        assertEquals(counted.carbG, weighed.carbG, 1e-6)
    }

    /** And they still agree when nothing was weighed after cooking. */
    @Test
    fun theTwoRoutesAgreeWithNoWeighedYieldEither() {
        val r = stew(servings = 3)
        val g = Recipes.servingGrams(r)!!   // 900/3 = 300
        assertEquals(300.0, g, 1e-9)
        assertEquals(
            Recipes.eatenServings(r, 1.0)!!.kcal,
            Recipes.eatenGrams(r, g)!!.kcal,
            1e-6,
        )
    }

    @Test
    fun halfAPortionIsHalfOfOne() {
        val r = stew(yieldG = 600.0, servings = 4)
        assertEquals(166.25, Recipes.eatenServings(r, 0.5)!!.kcal, 1e-9)   // 332.5 / 2
        assertEquals(665.0, Recipes.eatenServings(r, 2.0)!!.kcal, 1e-9)    // 332.5 * 2
    }

    // ---------------------------------------------------------------------------------- refusals

    /**
     * ⚠️ Null rather than a zeroed record. An empty recipe has no density, and `Nutrients()` would
     * log as a real food worth nothing — a wrong entry rather than a missing one.
     */
    @Test
    fun anEmptyRecipeHasNoNutritionRatherThanZeroNutrition() {
        val empty = Recipes.Recipe("r", "Nothing")
        assertNull(Recipes.per100g(empty))
        assertNull(Recipes.perServing(empty))
        assertNull(Recipes.eatenGrams(empty, 100.0))
        assertNull(Recipes.eatenServings(empty, 1.0))
        assertNull(Recipes.servingGrams(empty))
        assertNull(Recipes.summary(empty))
    }

    /** Ingredients that all weigh nothing are the same case, and must not divide by zero. */
    @Test
    fun ingredientsWeighingNothingProduceNoDensity() {
        val weightless = Recipes.Recipe(
            "r", "Air", listOf(mince.copy(grams = 0.0), tomatoes.copy(grams = 0.0)),
        )
        assertNull(Recipes.per100g(weightless))
        assertNull(Recipes.servingGrams(weightless))
        assertTrue(Recipes.problems(weightless).any { it.contains("weighs nothing") })
    }

    /** A yield that is not a weight falls back to the raw total rather than producing infinity. */
    @Test
    fun aNonsenseYieldFallsBackToTheRawWeight() {
        for (bad in listOf(0.0, -100.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val r = stew(yieldG = bad)
            assertEquals("yield $bad", 900.0, Recipes.yieldGrams(r), 1e-9)
            assertEquals("yield $bad", 147.7777778, Recipes.per100g(r)!!.kcal, 1e-6)
        }
    }

    /** Zero portions is not a division anybody wants; one is the floor. */
    @Test
    fun zeroServingsIsTreatedAsOne() {
        val r = stew(yieldG = 600.0, servings = 0)
        assertEquals(1330.0, Recipes.perServing(r)!!.kcal, 1e-9)
        assertEquals(600.0, Recipes.servingGrams(r)!!, 1e-9)
    }

    // ---------------------------------------------------------------------------------- warnings

    /** An ordinary recipe complains about nothing. */
    @Test
    fun aPlausibleRecipeHasNothingToSay() {
        assertEquals(emptyList<String>(), Recipes.problems(stew(yieldG = 600.0, servings = 4)))
    }

    /**
     * ⚠️ Warnings, not refusals. People cook strange things, and a builder that argues about a
     * reduction is one they stop using — but a portion figure out by a factor of four is worth a
     * sentence, because this tab tells a real person how much to eat.
     */
    @Test
    fun anAbsurdYieldIsFlaggedAndStillComputes() {
        // 900 g in, 100 g out = ratio 0.111, under the 0.25 floor.
        val shrunk = stew(yieldG = 100.0)
        assertTrue(Recipes.problems(shrunk).any { it.contains("much lighter") })
        assertEquals("it still gives an answer", 1330.0, Recipes.per100g(shrunk)!!.kcal, 1e-9)

        // 900 g in, 5000 g out = ratio 5.56, over the 4.0 ceiling.
        assertTrue(Recipes.problems(stew(yieldG = 5000.0)).any { it.contains("much heavier") })
        // 900 -> 600 is a normal reduction and must stay quiet.
        assertTrue(Recipes.problems(stew(yieldG = 600.0)).none { it.contains("lighter") })
        // 900 -> 1800 is rice absorbing water, ratio 2.0, and must stay quiet too.
        assertTrue(Recipes.problems(stew(yieldG = 1800.0)).none { it.contains("heavier") })
    }

    /** A portion weight is a multiplier, so an absurd one is wrong by a factor, not a rounding. */
    @Test
    fun anImplausiblePortionWeightIsFlagged() {
        // 900 g into 300 portions = 3 g each, under FoodPortion.MIN_SERVING_G.
        assertTrue(
            Recipes.problems(stew(servings = 300)).any { it.contains("does not look like a portion") },
        )
        // And the count itself gets its own line above 50.
        assertTrue(Recipes.problems(stew(servings = 300)).any { it.contains("300 portions") })
    }

    /**
     * Energy the macros cannot account for almost always means one ingredient went in in the wrong
     * unit — the mistake that turns a 400 kcal lunch into a 4,000 kcal one.
     */
    @Test
    fun energyThatTheMacrosCannotAccountForIsFlagged() {
        // The honest stew: 4*134 + 9*75 + 4*16 = 1275 against 1330 stated, 4.1% out. Quiet.
        assertTrue(Recipes.problems(stew(yieldG = 600.0)).none { it.contains("disagree") })

        // Ten times the mince energy with the same macros — the wrong-unit signature.
        val wrong = stew(yieldG = 600.0).let {
            it.copy(components = listOf(mince.copy(per100g = mince.per100g.copy(kcal = 2500.0)), tomatoes))
        }
        assertTrue(Recipes.problems(wrong).any { it.contains("disagree") })
    }

    @Test
    fun anUnnamedRecipeIsMentionedButStillWorks() {
        val unnamed = stew(yieldG = 600.0).copy(name = "  ")
        assertTrue(Recipes.problems(unnamed).any { it.contains("no name") })
        assertEquals(332.5, Recipes.perServing(unnamed)!!.kcal, 1e-9)
    }

    @Test
    fun theSummaryIsTheWholeRecipeInOneLine() {
        assertEquals(
            "Serves 4 · 150 g each · 333 kcal a portion",
            Recipes.summary(stew(yieldG = 600.0, servings = 4)),
        )
    }

    // ------------------------------------------------------------------ vitamins and minerals

    /**
     * Mince records iron and cholesterol; tomatoes record iron and vitamin C. Arithmetic, before the
     * assertion:
     *
     *   iron        500 g at 2.6 mg/100 g = 13.0   +  400 g at 0.5 = 2.0   -> 15.0 mg
     *   cholesterol 500 g at  70 mg/100 g = 350.0  +  (not recorded)       -> 350.0 mg
     *   vitamin C   (not recorded)                 +  400 g at 9.0 = 36.0  ->  36.0 mg
     */
    private val minceWithMicros = mince.copy(
        micros = Micronutrients.Amounts(
            mapOf(
                Micronutrients.Micro.IRON to 2.6,
                Micronutrients.Micro.CHOLESTEROL to 70.0,
            ),
        ),
    )
    private val tomatoesWithMicros = tomatoes.copy(
        micros = Micronutrients.Amounts(
            mapOf(
                Micronutrients.Micro.IRON to 0.5,
                Micronutrients.Micro.VITAMIN_C to 9.0,
            ),
        ),
    )

    private fun richStew(yieldG: Double? = null, servings: Int = 4) = Recipes.Recipe(
        "r1", "Stew", listOf(minceWithMicros, tomatoesWithMicros),
        cookedYieldG = yieldG, servings = servings,
    )

    @Test
    fun thePotSumsWhicheverMicronutrientsTheIngredientsRecorded() {
        val t = Recipes.totalMicros(richStew())
        assertEquals(15.0, t[Micronutrients.Micro.IRON]!!, 1e-9)
        assertEquals(350.0, t[Micronutrients.Micro.CHOLESTEROL]!!, 1e-9)
        assertEquals(36.0, t[Micronutrients.Micro.VITAMIN_C]!!, 1e-9)
        // ⚠️ Nothing recorded calcium, so the dish reports none rather than zero. `Day.coverage` is
        // what tells the reader how much of a total was drawn from — a 0.0 here would claim a
        // measurement.
        assertNull(t[Micronutrients.Micro.CALCIUM])
    }

    /**
     * ⚠️ **THE LOAD-BEARING RULE: a helping's micronutrients and its calories describe the SAME
     * portion.** Two routes to one helping — 150 g of a 600 g yield is exactly one of four servings —
     * and the commonest way this goes wrong is one half scaling by the raw weight while the other uses
     * the cooked one. The macros already have this test; the micronutrients now share it.
     *
     *   yield 600 g, so a 150 g helping is a quarter: iron 15.0 / 4 = 3.75 mg, kcal 1330 / 4 = 332.5
     */
    @Test
    fun aHelpingsMicronutrientsDescribeTheSamePortionAsItsCalories() {
        val r = richStew(yieldG = 600.0, servings = 4)

        val byGrams = Recipes.eatenGramsMicros(r, 150.0)!!
        val byServings = Recipes.eatenServingsMicros(r, 1.0)!!
        assertEquals(3.75, byGrams[Micronutrients.Micro.IRON]!!, 1e-9)
        assertEquals(byGrams[Micronutrients.Micro.IRON]!!, byServings[Micronutrients.Micro.IRON]!!, 1e-9)

        // And the same fraction as the macros took, which is the property that actually matters.
        val macroShare = Recipes.eatenGrams(r, 150.0)!!.kcal / Recipes.total(r).kcal
        val microShare = byGrams[Micronutrients.Micro.IRON]!! /
            Recipes.totalMicros(r)[Micronutrients.Micro.IRON]!!
        assertEquals(macroShare, microShare, 1e-12)
    }

    /**
     * ⚠️ Every recipe saved before this existed has components with no micronutrients, and must still
     * work — the field is defaulted for exactly that reason. A dish that records nothing reports
     * nothing rather than refusing or returning zeros.
     */
    @Test
    fun aRecipeBuiltBeforeMicronutrientsExistedStillWorks() {
        val old = stew(yieldG = 600.0)
        assertEquals(332.5, Recipes.perServing(old)!!.kcal, 1e-9)
        assertTrue(Recipes.totalMicros(old).isEmpty)
        // Not null — the recipe is perfectly valid, it simply has nothing to report.
        assertTrue(Recipes.eatenGramsMicros(old, 150.0)!!.isEmpty)
    }

    /** An empty recipe has no density for micronutrients either, exactly as it has none for macros. */
    @Test
    fun anEmptyRecipeRefusesBothHalvesTheSameWay() {
        val empty = Recipes.Recipe("r0", "Nothing")
        assertNull(Recipes.per100g(empty))
        assertNull(Recipes.per100gMicros(empty))
        assertNull(Recipes.eatenGramsMicros(empty, 100.0))
        assertNull(Recipes.eatenServingsMicros(empty, 1.0))
    }
}
