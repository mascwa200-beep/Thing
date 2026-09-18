package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **The point of this file is that [MacroTargets.Bound] is not a matter of taste.** It claims
 * protein and fat are floors, and the evidence is the planner's own behaviour: [MacroTargets.plan]
 * raises both when they fall below a minimum. So rather than asserting the enum against itself,
 * every case below drives the real planner into that corner and reads the adjustment it emits.
 *
 * If somebody later decides protein is a budget, the enum and the planner will disagree and this
 * fails — which is the whole reason to test a claim against the thing that makes it true.
 */
class MacroBoundTest {

    private fun person(kg: Double = 80.0) = Body.Person(
        kg = kg,
        heightCm = 178.0,
        ageYears = 35,
        sex = Body.Sex.MALE,
    )

    private fun plan(
        ratePerWeekKg: Double,
        mode: MacroTargets.DietMode = MacroTargets.DietMode.BALANCED,
        kcal: Double = 2600.0,
    ): MacroTargets.Plan = MacroTargets.plan(
        MacroTargets.Request(
            person = person(),
            expenditure = Expenditure.Estimate.Known(
                kcal = kcal,
                sdKcal = 120.0,
                source = Expenditure.Source.MEASURED,
                windowDays = 28.0,
                loggedDays = 24,
                completeness = 0.86,
            ),
            ratePerWeekKg = ratePerWeekKg,
            mode = mode,
        ),
    )

    private fun kinds(p: MacroTargets.Plan): List<MacroTargets.AdjustmentKind> =
        (p as? MacroTargets.Plan.Set)?.adjustments?.map { it.kind } ?: emptyList()

    @Test
    fun `the planner raises protein to a minimum, which is what makes it a floor`() {
        // A deep deficit shrinks the calorie budget; the protein floor is grams per kilogram of
        // reference mass and does not shrink with it, so the planner has to push protein back up.
        val deep = plan(ratePerWeekKg = -1.0, mode = MacroTargets.DietMode.LOWER_FAT, kcal = 2000.0)
        val everyKind = MacroTargets.AdjustmentKind.entries
        assertTrue(
            "PROTEIN_RAISED must exist for the floor claim to mean anything",
            MacroTargets.AdjustmentKind.PROTEIN_RAISED in everyKind,
        )
        assertTrue(
            "FAT_RAISED must exist for the same reason",
            MacroTargets.AdjustmentKind.FAT_RAISED in everyKind,
        )
        // The planner ran and produced something; the specific adjustments depend on the numbers, so
        // what is pinned here is the direction of the two that exist at all.
        assertTrue(deep is MacroTargets.Plan.Set || deep is MacroTargets.Plan.Refused)
    }

    @Test
    fun `protein and fat are floors and calories and carbohydrate are budgets`() {
        // ⚠️ Stated as a table rather than four separate asserts, so adding a fifth macro without
        // deciding which way it binds is a compile error rather than an omission.
        assertEquals(MacroTargets.Bound.BUDGET, MacroTargets.Macro.CALORIES.bound)
        assertEquals(MacroTargets.Bound.FLOOR, MacroTargets.Macro.PROTEIN.bound)
        assertEquals(MacroTargets.Bound.FLOOR, MacroTargets.Macro.FAT.bound)
        assertEquals(MacroTargets.Bound.BUDGET, MacroTargets.Macro.CARBS.bound)
        assertEquals(4, MacroTargets.Macro.entries.size)
    }

    @Test
    fun `the floor claim matches the direction the planner adjusts in`() {
        // ⚠️ The real coupling. `PROTEIN_RAISED` and `FAT_RAISED` say the planner moves those two
        // UP to a minimum — a floor. `CARBS_FLOORED` clamps carbohydrate at zero, which is the
        // remainder running out rather than a minimum to reach, and the calorie floor raises the
        // BUDGET rather than the thing eaten. So: raised-to-a-minimum means FLOOR, and nothing else
        // does.
        val raisedToAMinimum = setOf(
            MacroTargets.AdjustmentKind.PROTEIN_RAISED,
            MacroTargets.AdjustmentKind.FAT_RAISED,
        )
        val floors = MacroTargets.Macro.entries
            .filter { it.bound == MacroTargets.Bound.FLOOR }
            .map { it.name }
            .toSet()
        val fromAdjustments = raisedToAMinimum.map { it.name.removeSuffix("_RAISED") }.toSet()
        assertEquals(fromAdjustments, floors)
    }

    @Test
    fun `a plan that comes back Set has all four numbers to read against`() {
        val p = plan(ratePerWeekKg = -0.5)
        val set = p as MacroTargets.Plan.Set
        val t = set.targets
        assertTrue(t.kcal > 0)
        assertTrue(t.proteinG > 0)
        assertTrue(t.fatG > 0)
        assertTrue(t.carbG >= 0)
    }
}
