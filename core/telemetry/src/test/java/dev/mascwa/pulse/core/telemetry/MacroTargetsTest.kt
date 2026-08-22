package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The calorie and macronutrient targets, and — the part that matters — the limits on them.
 *
 * This is the one core in the app whose output a person eats to, so most of what is below is about the
 * guardrails rather than the arithmetic. Every refusal and every clamp has its own case, because a
 * silently clamped number is indistinguishable from a number the app meant.
 */
class MacroTargetsTest {

    private val adult = Body.Person(85.0, 178.0, 34, Body.Sex.MALE)
    private fun known(kcal: Double, source: Expenditure.Source = Expenditure.Source.MEASURED) =
        Expenditure.Estimate.Known(kcal, 90.0, source, 28.0, 28, 1.0)

    private fun plan(
        person: Body.Person = adult,
        kcal: Double = 2600.0,
        rate: Double = -0.5,
        mode: MacroTargets.DietMode = MacroTargets.DietMode.BALANCED,
        protein: Double? = null,
        goal: Double? = null,
        source: Expenditure.Source = Expenditure.Source.MEASURED,
    ): MacroTargets.Plan = MacroTargets.plan(
        MacroTargets.Request(person, known(kcal, source), rate, mode, protein, goal),
    )

    private fun set(p: MacroTargets.Plan): MacroTargets.Plan.Set {
        assertTrue("expected a plan, got $p", p is MacroTargets.Plan.Set)
        return p as MacroTargets.Plan.Set
    }

    private fun kinds(p: MacroTargets.Plan.Set) = p.adjustments.map { it.kind }.toSet()

    // -------------------------------------------------------------------------------- the refusals

    @Test
    fun itWillNotPlanForAChild() {
        val p = plan(person = adult.copy(ageYears = 16))
        assertEquals(MacroTargets.RefusalKind.UNDER_EIGHTEEN, (p as MacroTargets.Plan.Refused).kind)
        assertTrue(p.sentence, p.sentence.isNotBlank())
    }

    @Test
    fun itWillNotPlanForABodyTheFormulasCannotDescribe() {
        assertEquals(
            MacroTargets.RefusalKind.IMPLAUSIBLE_BODY,
            (plan(person = adult.copy(heightCm = 45.0)) as MacroTargets.Plan.Refused).kind,
        )
        assertEquals(
            MacroTargets.RefusalKind.IMPLAUSIBLE_BODY,
            (plan(person = adult.copy(kg = 900.0)) as MacroTargets.Plan.Refused).kind,
        )
    }

    /** No expenditure means no target, and the refusal repeats what the expenditure core said. */
    @Test
    fun withoutAnExpenditureThereIsNoTarget() {
        val notYet = Expenditure.Estimate.NotYet(3.0, 2, 21.0, 10, "Keep logging.")
        val p = MacroTargets.plan(MacroTargets.Request(adult, notYet, -0.5)) as MacroTargets.Plan.Refused
        assertEquals(MacroTargets.RefusalKind.NO_EXPENDITURE, p.kind)
        assertEquals("Keep logging.", p.sentence)

        val doubtful = Expenditure.Estimate.Doubtful(-400.0, 28.0, 20, 0.9, "The numbers do not add up.")
        val q = MacroTargets.plan(MacroTargets.Request(adult, doubtful, -0.5)) as MacroTargets.Plan.Refused
        assertEquals(MacroTargets.RefusalKind.NO_EXPENDITURE, q.kind)
    }

    /**
     * ⚠️ THE ONE THAT MATTERS MOST. A goal weight in the underweight band is refused outright and the
     * refusal says where to take it. This app does not help anybody get there.
     */
    @Test
    fun itWillNotPlanAWayToAnUnderweightGoal() {
        // BMI 18.5 at 178 cm is 58.6 kg, so 55 is below the band.
        assertTrue(Body.bmi(55.0, 178.0) < Body.BMI_UNDERWEIGHT)
        val p = plan(goal = 55.0) as MacroTargets.Plan.Refused
        assertEquals(MacroTargets.RefusalKind.GOAL_UNDERWEIGHT, p.kind)
        assertTrue(p.sentence, p.sentence.contains("doctor"))

        // And a goal just inside the band is fine, so the check is a band and not a blanket refusal.
        assertTrue(plan(goal = 62.0) is MacroTargets.Plan.Set)
    }

    @Test
    fun itWillNotPlanALossForSomebodyAlreadyUnderweight() {
        val thin = adult.copy(kg = 52.0)     // BMI 16.4 at 178 cm
        assertTrue(Body.bmi(52.0, 178.0) < Body.BMI_UNDERWEIGHT)
        assertEquals(
            MacroTargets.RefusalKind.ALREADY_UNDERWEIGHT,
            (plan(person = thin, rate = -0.5) as MacroTargets.Plan.Refused).kind,
        )
        assertTrue("but gaining is exactly what they should be doing", plan(person = thin, rate = 0.25) is MacroTargets.Plan.Set)
        assertTrue("and maintaining is not refused either", plan(person = thin, rate = 0.0) is MacroTargets.Plan.Set)
    }

    // ------------------------------------------------------------------------------ the arithmetic

    /**
     * Losing half a kilogram a week is 550 calories a day: `0.5 × 7700 / 7`. Under a 2,600 expenditure
     * that is 2,050, and nothing here should clamp it.
     */
    @Test
    fun theTargetIsExpenditurePlusTheSignedGoalRate() {
        val p = set(plan(rate = -0.5))
        assertEquals("no guardrail should bite on an ordinary plan", emptySet<Any>(), kinds(p))
        assertEquals(2600.0 - 0.5 * 7700.0 / 7.0, p.targets.kcal.toDouble(), 4.0)
        assertEquals(2050.0, p.targets.kcal.toDouble(), 4.0)

        // Maintenance is the expenditure, and gaining is above it.
        assertEquals(2600.0, set(plan(rate = 0.0)).targets.kcal.toDouble(), 4.0)
        assertTrue(set(plan(rate = 0.25)).targets.kcal > 2600)
    }

    /**
     * ⚠️ THE INVARIANT THAT KEEPS THE SURFACE TRUTHFUL. The calorie ring and the three macro rings are
     * drawn from these four numbers, and if they do not add up the screen is lying about itself.
     * Asserted across a grid rather than one case, because rounding is where this breaks.
     */
    @Test
    fun theCaloriesAreAlwaysExactlyTheGrams() {
        for (mode in MacroTargets.DietMode.entries) {
            for (kcal in listOf(1400.0, 1800.0, 2200.0, 2600.0, 3200.0, 4500.0)) {
                for (rate in listOf(-1.0, -0.5, -0.25, 0.0, 0.25, 0.5)) {
                    for (person in listOf(adult, adult.copy(kg = 55.0), adult.copy(kg = 140.0, heightCm = 190.0))) {
                        val p = plan(person = person, kcal = kcal, rate = rate, mode = mode)
                        if (p !is MacroTargets.Plan.Set) continue
                        val t = p.targets
                        assertEquals(
                            "$mode $kcal $rate ${person.kg}kg",
                            t.proteinG * 4 + t.fatG * 9 + t.carbG * 4,
                            t.kcal,
                        )
                        assertTrue("no negative gram target", t.proteinG >= 0 && t.fatG >= 0 && t.carbG >= 0)
                    }
                }
            }
        }
    }

    /**
     * ⚠️ THE FLOOR, AND THE BUG IT WAS WRITTEN FOR. Rounding each macro to the nearest gram and then
     * recomputing the calories from them landed a 50 kg person at **1,197** under a floor this file
     * promises never to go below. Three calories, and a broken promise.
     */
    @Test
    fun theAbsoluteFloorIsNeverBreached() {
        // Someone small, with a low expenditure, asking for an aggressive loss: every path pushes down.
        for (kg in listOf(45.0, 50.0, 55.0)) {
            for (kcal in listOf(1100.0, 1300.0, 1400.0)) {
                for (mode in MacroTargets.DietMode.entries) {
                    val small = Body.Person(kg, 155.0, 62, Body.Sex.FEMALE)
                    val p = plan(person = small, kcal = kcal, rate = -1.0, mode = mode)
                    if (p !is MacroTargets.Plan.Set) continue
                    assertTrue(
                        "${kg}kg on $kcal in $mode came out at ${p.targets.kcal}",
                        p.targets.kcal >= MacroTargets.ABSOLUTE_FLOOR_KCAL,
                    )
                }
            }
        }
    }

    /** And the floor announces itself rather than quietly rewriting the plan. */
    @Test
    fun theFloorSaysWhenItBites() {
        val small = Body.Person(50.0, 155.0, 62, Body.Sex.FEMALE)
        val p = set(plan(person = small, kcal = 1400.0, rate = -0.5))
        assertTrue(kinds(p).contains(MacroTargets.AdjustmentKind.KCAL_RAISED_TO_FLOOR))
        assertTrue(p.adjustments.all { it.sentence.isNotBlank() })
        assertTrue(p.capped)
    }

    /** Eating below your own resting requirement is the line clinical guidance draws. */
    @Test
    fun theTargetNeverGoesUnderTheRestingRate() {
        // 150 kg at 180 cm: resting is 10·150 + 6.25·180 − 5·40 + 5 = 2430.
        val heavy = Body.Person(150.0, 180.0, 40, Body.Sex.MALE)
        assertEquals(2430.0, Body.bmr(heavy), 1e-9)
        val p = set(plan(person = heavy, kcal = 3400.0, rate = -1.5))
        assertTrue(p.targets.kcal >= 2430)
        assertTrue(kinds(p).contains(MacroTargets.AdjustmentKind.KCAL_RAISED_TO_RESTING_RATE))
    }

    /**
     * ⚠️ AND THE RATE THE SURFACE SHOWS IS RECOMPUTED FROM THE FINAL CALORIES, NEVER ECHOED BACK. A
     * person told "1.5 kg a week" who is actually on a plan that delivers 0.9 will conclude the app is
     * broken when the scale disagrees.
     */
    @Test
    fun theRateReportedIsWhatTheTargetWillActuallyDo() {
        val heavy = Body.Person(150.0, 180.0, 40, Body.Sex.MALE)
        val p = set(plan(person = heavy, kcal = 3400.0, rate = -1.5))
        val implied = (p.targets.kcal - 3400.0) * 7.0 / Expenditure.KCAL_PER_KG
        assertEquals(implied, p.effectiveRatePerWeekKg, 1e-9)
        assertTrue("the plan cannot deliver what was asked", p.effectiveRatePerWeekKg > -1.5)
        assertTrue("but it must still be a real loss", p.effectiveRatePerWeekKg < -0.5)
    }

    // ------------------------------------------------------------------------------------ the caps

    @Test
    fun lossIsCappedAtOnePerCentOfBodyweightAWeek() {
        assertEquals(0.85, MacroTargets.maxLossPerWeekKg(85.0), 1e-9)
        assertEquals("and an absolute ceiling above that", 1.5, MacroTargets.maxLossPerWeekKg(250.0), 1e-9)

        val p = set(plan(rate = -3.0))
        assertTrue(kinds(p).contains(MacroTargets.AdjustmentKind.RATE_CAPPED))
        // 0.85 kg/wk is 935 kcal/day under 2,600 — which the resting-rate floor then also raises.
        assertTrue(p.targets.kcal >= Body.bmr(adult))
    }

    @Test
    fun gainIsCappedAtHalfThatBecauseFasterIsMostlyFat() {
        assertEquals(0.425, MacroTargets.maxGainPerWeekKg(85.0), 1e-9)
        assertEquals(0.5, MacroTargets.maxGainPerWeekKg(250.0), 1e-9)
        assertTrue(MacroTargets.maxGainPerWeekKg(85.0) < MacroTargets.maxLossPerWeekKg(85.0))

        val p = set(plan(rate = 2.0))
        assertTrue(kinds(p).contains(MacroTargets.AdjustmentKind.RATE_CAPPED))
        assertTrue(p.effectiveRatePerWeekKg < 0.45)
    }

    @Test
    fun anOrdinaryRateIsNotCapped() {
        assertFalse(kinds(set(plan(rate = -0.5))).contains(MacroTargets.AdjustmentKind.RATE_CAPPED))
        assertFalse(kinds(set(plan(rate = 0.25))).contains(MacroTargets.AdjustmentKind.RATE_CAPPED))
    }

    // ---------------------------------------------------------------------------------- the macros

    /**
     * ⚠️ Protein is set against a reference mass capped at the top of the healthy range, not against the
     * scale. Grams per kilogram of *current* weight badly over-prescribes for anyone carrying a lot of
     * fat, because the requirement tracks lean mass and fat mass needs none.
     */
    @Test
    fun proteinIsSetAgainstLeanMassRatherThanTheScale() {
        // 25 BMI at 178 cm is 79.21 kg, so anybody heavier than that is capped there.
        val reference = Body.kgAtBmi(Body.BMI_HEALTHY_MAX, 178.0)
        assertEquals(79.21, reference, 1e-9)

        val heavy = set(plan(person = adult.copy(kg = 140.0), kcal = 3200.0, rate = -0.5))
        assertEquals("1.8 g/kg of the reference, not of 140 kg", 1.8 * reference, heavy.targets.proteinG.toDouble(), 2.0)
        assertTrue("1.8 × 140 would be 252 g", heavy.targets.proteinG < 200)

        // Someone inside the healthy range uses their own weight.
        val light = set(plan(person = adult.copy(kg = 70.0), kcal = 2400.0, rate = -0.5))
        assertEquals(1.8 * 70.0, light.targets.proteinG.toDouble(), 2.0)
    }

    @Test
    fun proteinIsClampedAtBothEnds() {
        val low = set(plan(protein = 0.2))
        assertTrue(kinds(low).contains(MacroTargets.AdjustmentKind.PROTEIN_RAISED))
        assertTrue(low.targets.proteinG >= (MacroTargets.PROTEIN_MIN_G_PER_KG * 79.21).toInt() - 1)

        val high = set(plan(protein = 9.0, kcal = 3600.0, rate = 0.0))
        assertTrue(kinds(high).contains(MacroTargets.AdjustmentKind.PROTEIN_CAPPED))
        assertTrue(high.targets.proteinG <= (MacroTargets.PROTEIN_MAX_G_PER_KG * 79.21).toInt() + 1)
    }

    /** Protein cannot take more than its share of a small day, or nothing is left for anything else. */
    @Test
    fun proteinCannotEatTheWholeDay() {
        val p = set(plan(person = adult.copy(kg = 120.0), kcal = 1900.0, rate = -0.8, protein = 3.0))
        val share = p.targets.proteinKcal.toDouble() / p.targets.kcal
        assertTrue("protein took ${(share * 100).toInt()}% of the day", share <= MacroTargets.PROTEIN_MAX_KCAL_FRACTION + 0.01)
        assertTrue(kinds(p).contains(MacroTargets.AdjustmentKind.PROTEIN_CAPPED))
    }

    /** Essential fatty acids and the vitamins that travel with them have a floor under every mode. */
    @Test
    fun fatHasAFloorUnderEveryMode() {
        for (mode in MacroTargets.DietMode.entries) {
            val p = set(plan(mode = mode, kcal = 2000.0, rate = -0.5))
            val byMass = MacroTargets.FAT_MIN_G_PER_KG * 79.21
            val byShare = p.targets.kcal * MacroTargets.FAT_MIN_KCAL_FRACTION / 9.0
            assertTrue(
                "$mode gave ${p.targets.fatG} g against a floor of ${maxOf(byMass, byShare)}",
                p.targets.fatG >= maxOf(byMass, byShare).toInt() - 1,
            )
        }
    }

    /**
     * ⚠️ The subtraction that yields carbohydrate genuinely can go negative, and a negative gram target
     * would render on screen. It floors at zero and the calories move up to match the macros that are
     * left, which is why the invariant above recomputes rather than trusting the target.
     */
    @Test
    fun carbohydrateFloorsAtZeroAndSaysSo() {
        // Very high protein and a lot of body mass against a tiny calorie target.
        val p = set(plan(person = adult.copy(kg = 120.0), kcal = 1250.0, rate = -0.5, protein = 3.0, mode = MacroTargets.DietMode.KETO))
        assertTrue(p.targets.carbG >= 0)
        if (p.targets.carbG == 0) {
            assertTrue(kinds(p).contains(MacroTargets.AdjustmentKind.CARBS_FLOORED))
        }
    }

    @Test
    fun eachModeHasTheShapeItsNamePromises() {
        val balanced = set(plan(mode = MacroTargets.DietMode.BALANCED, rate = 0.0)).targets
        val lowerFat = set(plan(mode = MacroTargets.DietMode.LOWER_FAT, rate = 0.0)).targets
        val lowerCarb = set(plan(mode = MacroTargets.DietMode.LOWER_CARB, rate = 0.0)).targets
        val keto = set(plan(mode = MacroTargets.DietMode.KETO, rate = 0.0)).targets
        val highProtein = set(plan(mode = MacroTargets.DietMode.HIGH_PROTEIN, rate = 0.0)).targets

        assertTrue("lower fat means less fat than balanced", lowerFat.fatG < balanced.fatG)
        assertTrue("and therefore more carbohydrate", lowerFat.carbG > balanced.carbG)
        assertTrue("lower carb means less of it", lowerCarb.carbG < balanced.carbG)
        assertTrue("and more fat to make up the calories", lowerCarb.fatG > balanced.fatG)
        assertEquals("keto pins carbohydrate", MacroTargets.KETO_CARB_G, keto.carbG.toDouble(), 2.0)
        assertTrue("high protein means more protein", highProtein.proteinG > balanced.proteinG)
        assertTrue(MacroTargets.DietMode.entries.all { it.label.isNotBlank() })
    }

    // ------------------------------------------------------------------------------- other reading

    /** A target resting on the formula must say so, because it is not yet about this person. */
    @Test
    fun aProvisionalExpenditureIsFlagged() {
        val formula = set(plan(source = Expenditure.Source.FORMULA))
        assertTrue(kinds(formula).contains(MacroTargets.AdjustmentKind.EXPENDITURE_PROVISIONAL))
        val blended = set(plan(source = Expenditure.Source.BLENDED))
        assertTrue(kinds(blended).contains(MacroTargets.AdjustmentKind.EXPENDITURE_PROVISIONAL))
        val measured = set(plan(source = Expenditure.Source.MEASURED))
        assertFalse(kinds(measured).contains(MacroTargets.AdjustmentKind.EXPENDITURE_PROVISIONAL))
    }

    /**
     * ⚠️ Null rather than a negative or infinite number of weeks. "−14 weeks" would render, and a caller
     * that forgot to check would print it.
     */
    @Test
    fun timeToAGoalIsNullWhenTheRateDoesNotGoThere() {
        assertEquals(20.0, MacroTargets.weeksToGoal(85.0, 75.0, -0.5)!!, 1e-9)
        assertEquals(0.0, MacroTargets.weeksToGoal(85.0, 85.0, -0.5)!!, 1e-9)
        assertNull("losing will not reach a heavier goal", MacroTargets.weeksToGoal(85.0, 95.0, -0.5))
        assertNull("nor will maintenance reach anywhere", MacroTargets.weeksToGoal(85.0, 75.0, 0.0))
        assertNull(MacroTargets.weeksToGoal(85.0, Double.NaN, -0.5))
        assertNotNull("but gaining toward a heavier goal does", MacroTargets.weeksToGoal(85.0, 95.0, 0.25))
    }

    @Test
    fun theSentenceCarriesTheNumbersAndThePace() {
        val p = set(plan(rate = -0.5))
        val s = MacroTargets.sentence(p)
        assertTrue(s, s.contains("${p.targets.proteinG} g protein"))
        assertTrue(s, s.contains("down"))
        assertTrue("holding steady must not be described as a direction",
            MacroTargets.sentence(set(plan(rate = 0.0))).contains("holding steady"))
        assertTrue(MacroTargets.sentence(set(plan(rate = 0.25))).contains("up"))

        val refused = plan(person = adult.copy(ageYears = 15))
        assertEquals((refused as MacroTargets.Plan.Refused).sentence, MacroTargets.sentence(refused))
    }

    /** The disclaimer is content the surface is expected to print, so it must actually say something. */
    @Test
    fun thereIsADisclaimerAndItNamesTheRealRisks() {
        val d = MacroTargets.DISCLAIMER
        assertTrue(d, d.contains("not medical advice"))
        assertTrue(d, d.contains("disordered eating"))
        assertTrue(d, d.contains("pregnant"))
    }

    /** Nothing above should ever produce a number that cannot be rendered. */
    @Test
    fun noPlanEverProducesSomethingUnrenderable() {
        for (kg in listOf(45.0, 70.0, 120.0, 200.0)) {
            for (cm in listOf(150.0, 178.0, 200.0)) {
                for (age in listOf(18, 40, 80)) {
                    for (rate in listOf(-5.0, -0.5, 0.0, 5.0, Double.NaN)) {
                        val p = MacroTargets.plan(
                            MacroTargets.Request(Body.Person(kg, cm, age), known(2200.0), rate),
                        )
                        if (p !is MacroTargets.Plan.Set) continue
                        assertTrue("$kg/$cm/$age/$rate", p.targets.kcal in 1..10_000)
                        assertTrue(p.effectiveRatePerWeekKg.isFinite())
                        assertTrue(abs(p.effectiveRatePerWeekKg) < 5.0)
                    }
                }
            }
        }
    }
}
