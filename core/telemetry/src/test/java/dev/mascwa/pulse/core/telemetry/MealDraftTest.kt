package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The staging plate.
 *
 * ⚠️ Every expected figure below is worked out from the shipped rule with the arithmetic left in the
 * comment. This repo has recorded roughly seventeen occasions where an expectation of mine was wrong
 * and the code was right, and the habit that prevents it is doing the sum first.
 */
class MealDraftTest {

    private val day = 1_770_000_000_000L

    private fun entry(
        kcal: Double,
        protein: Double = 0.0,
        fat: Double = 0.0,
        carb: Double = 0.0,
        on: Long = day,
        name: String = "thing",
    ) = NutritionDay.Entry(
        id = name + on,
        dayStartMs = on,
        atMs = on + 1,
        name = name,
        grams = 100.0,
        nutrients = NutritionDay.Nutrients(kcal = kcal, proteinG = protein, fatG = fat, carbG = carb),
    )

    /** 2,400 kcal, 150 g protein, 70 g fat, 270 g carbohydrate. */
    private val targets = MacroTargets.Targets(kcal = 2400, proteinG = 150, fatG = 70, carbG = 270)

    private fun lineFor(e: MealDraft.Effect, m: MacroTargets.Macro) = e.lines.first { it.macro == m }

    // ------------------------------------------------------------------------------------- totals

    @Test
    fun `the plate adds to the day rather than replacing it`() {
        val logged = listOf(entry(500.0, protein = 30.0), entry(300.0, protein = 10.0))
        val staged = listOf(entry(600.0, protein = 40.0, name = "staged"))
        val e = MealDraft.effect(staged, logged, targets)

        assertEquals(1, e.items)
        assertEquals(600.0, e.plate.kcal, 1e-9)
        assertEquals(800.0, e.before.kcal, 1e-9)      // 500 + 300
        assertEquals(1400.0, e.after.kcal, 1e-9)      // 800 + 600
        assertEquals(80.0, e.after.proteinG, 1e-9)    // 30 + 10 + 40
        assertFalse(e.isEmpty)
    }

    @Test
    fun `an empty plate leaves the day exactly where it was`() {
        val logged = listOf(entry(700.0))
        val e = MealDraft.effect(emptyList(), logged, targets)

        assertTrue(e.isEmpty)
        assertEquals(0.0, e.plate.kcal, 1e-9)
        assertEquals(e.before, e.after)
        // The lines are still produced: "where do I stand right now" is a fair question with an
        // empty plate, and a card that went blank until something was staged would be less useful
        // than one that says where the day is.
        assertEquals(4, e.lines.size)
    }

    @Test
    fun `a line's delta is what the plate itself contributes`() {
        val e = MealDraft.effect(listOf(entry(600.0)), listOf(entry(800.0)), targets)
        assertEquals(600.0, lineFor(e, MacroTargets.Macro.CALORIES).delta, 1e-9)
    }

    // ------------------------------------------------------------------------------------ targets

    @Test
    fun `no plan means no comparison, and no invented target`() {
        val e = MealDraft.effect(listOf(entry(600.0)), listOf(entry(800.0)), targets = null)
        // ⚠️ The totals still stand — the plate knows what is on it. What it declines to do is say
        // what that means, because nothing has told it.
        assertEquals(600.0, e.plate.kcal, 1e-9)
        assertEquals(1400.0, e.after.kcal, 1e-9)
        assertTrue("a refused plan must not produce lines", e.lines.isEmpty())
    }

    @Test
    fun `a target of zero produces no line rather than an infinitely-over one`() {
        // The planner produces whole Targets; a zero in one of them is a number it has not worked
        // out, and comparing against it would report every plate as past it.
        val t = MacroTargets.Targets(kcal = 2400, proteinG = 0, fatG = 70, carbG = 270)
        val e = MealDraft.effect(listOf(entry(100.0, protein = 5.0)), emptyList(), t)
        assertEquals(3, e.lines.size)
        assertTrue(e.lines.none { it.macro == MacroTargets.Macro.PROTEIN })
    }

    // ------------------------------------------------------------------------------------ verdicts

    @Test
    fun `landing within the tolerance reads as on target`() {
        // ON_TOLERANCE is 0.03, so a 2,400 kcal budget tolerates 72 kcal either way.
        // 2,400 - 72 = 2,328 exactly, which must still be ON (the bound is inclusive).
        val e = MealDraft.effect(listOf(entry(328.0)), listOf(entry(2000.0)), targets)
        assertEquals(2328.0, e.after.kcal, 1e-9)
        val l = lineFor(e, MacroTargets.Macro.CALORIES)
        assertEquals(MealDraft.Verdict.ON, l.verdict)
        assertEquals("That lands on your calorie budget.", l.sentence)
    }

    @Test
    fun `a calorie one past the tolerance is over`() {
        // 2,400 + 72 = 2,472 is the last ON figure; 2,473 is the first OVER one.
        val on = MealDraft.effect(listOf(entry(472.0)), listOf(entry(2000.0)), targets)
        assertEquals(MealDraft.Verdict.ON, lineFor(on, MacroTargets.Macro.CALORIES).verdict)

        val over = MealDraft.effect(listOf(entry(473.0)), listOf(entry(2000.0)), targets)
        val l = lineFor(over, MacroTargets.Macro.CALORIES)
        assertEquals(MealDraft.Verdict.OVER, l.verdict)
        // 2,473 - 2,400 = 73.
        assertEquals("That puts you 73 kcal over your calorie budget.", l.sentence)
    }

    @Test
    fun `the tolerance scales with the target rather than being a fixed figure`() {
        // ⚠️ **The first version of this test proved nothing, and a negative test is the only
        // reason I know.** It used 72 g of fat against a 70 g floor and 2,402 kcal against a 2,400
        // budget — both exactly two away — and a perturbation replacing `target * ON_TOLERANCE`
        // with a flat `2.0` left every one of its answers unchanged, because 2.0 <= 2.0 either way.
        // The fixture never reached the branch where the two rules differ. It is recorded as one of
        // the four ways a green test proves nothing, and this is what it looks like in practice.
        //
        // The pair below pins the rule from both sides, so no single fixed figure can satisfy it.
        //
        // Fifty calories out of 2,400 is ON: 2,400 * 0.03 = 72, and 50 <= 72. Any fixed tolerance
        // small enough to be useful on a 70 g target would call this OVER.
        val kcal = MealDraft.effect(listOf(entry(2450.0)), emptyList(), targets)
        assertEquals(2450.0, kcal.after.kcal, 1e-9)
        assertEquals(MealDraft.Verdict.ON, lineFor(kcal, MacroTargets.Macro.CALORIES).verdict)

        // Three grams out of 70 is OVER: 70 * 0.03 = 2.1, and 3 > 2.1. Any fixed tolerance large
        // enough to admit the fifty calories above would call this ON.
        val fat = MealDraft.effect(listOf(entry(0.0, fat = 73.0)), emptyList(), targets)
        assertEquals(MealDraft.Verdict.OVER, lineFor(fat, MacroTargets.Macro.FAT).verdict)
    }

    // ------------------------------------------------------------------------------ floor v budget

    @Test
    fun `short of a floor is worth saying and short of a budget is headroom`() {
        // ⚠️ The defect `MacroTargets.Bound` exists to stop, in the one place a plate could
        // reintroduce it. 100 g of protein against a 150 g floor and 1,000 kcal against a 2,400
        // budget are the same arithmetic and mean opposite things.
        val e = MealDraft.effect(listOf(entry(1000.0, protein = 100.0)), emptyList(), targets)

        val protein = lineFor(e, MacroTargets.Macro.PROTEIN)
        assertEquals(MealDraft.Verdict.UNDER, protein.verdict)
        // 150 - 100 = 50.
        assertEquals("That leaves you 50 g short of your protein floor.", protein.sentence)

        val calories = lineFor(e, MacroTargets.Macro.CALORIES)
        assertEquals(MealDraft.Verdict.UNDER, calories.verdict)
        // 2,400 - 1,000 = 1,400, and it is stated as what is LEFT rather than what is missing.
        assertEquals("That leaves 1400 kcal of your calorie budget.", calories.sentence)
    }

    @Test
    fun `past a floor is stated and past a budget is flagged, in words rather than by tone`() {
        // 200 g of protein past a 150 g floor, and 2,700 kcal past a 2,400 budget.
        val e = MealDraft.effect(listOf(entry(2700.0, protein = 200.0)), emptyList(), targets)

        val protein = lineFor(e, MacroTargets.Macro.PROTEIN)
        assertEquals(MealDraft.Verdict.OVER, protein.verdict)
        // 200 - 150 = 50. "past", never "over" — eating beyond a floor is the point of having one.
        assertEquals("That puts you 50 g past your protein floor.", protein.sentence)

        val calories = lineFor(e, MacroTargets.Macro.CALORIES)
        // 2,700 - 2,400 = 300.
        assertEquals("That puts you 300 kcal over your calorie budget.", calories.sentence)

        // ⚠️ And nothing anywhere calls it a failure. A surface that renders an honest over-budget
        // meal as an error teaches somebody to stop logging, which is what actually breaks the
        // twenty-eight-day expenditure window.
        for (l in e.lines) {
            for (word in listOf("too much", "failed", "fail", "bad", "should not", "avoid")) {
                assertFalse("'$word' in: ${l.sentence}", l.sentence.lowercase().contains(word))
            }
        }
    }

    @Test
    fun `every macro produces a line and each one names itself`() {
        val e = MealDraft.effect(listOf(entry(100.0, protein = 1.0, fat = 1.0, carb = 1.0)), emptyList(), targets)
        assertEquals(MacroTargets.Macro.entries.size, e.lines.size)
        assertEquals(MacroTargets.Macro.entries.toList(), e.lines.map { it.macro })
        for (l in e.lines) {
            assertTrue("blank sentence for ${l.macro}", l.sentence.isNotBlank())
            val unit = if (l.macro == MacroTargets.Macro.CALORIES) "kcal" else " g "
            assertTrue("${l.macro} does not name its unit: ${l.sentence}", l.sentence.contains(unit))
        }
    }

    // ------------------------------------------------------------------------------------- summary

    @Test
    fun `an empty plate says so rather than reporting a row of zeros`() {
        assertEquals(
            "Nothing on the plate yet.",
            MealDraft.summary(MealDraft.effect(emptyList(), listOf(entry(500.0)), targets)),
        )
    }

    @Test
    fun `the summary counts the items and leads with calories`() {
        val staged = listOf(entry(400.0, name = "a"), entry(200.0, name = "b"))
        val s = MealDraft.summary(MealDraft.effect(staged, listOf(entry(2000.0)), targets))
        // 400 + 200 = 600 on the plate; 2,000 + 600 = 2,600 against a 2,400 budget, so 200 over.
        assertEquals("2 items · 600 kcal — that puts you 200 kcal over your calorie budget.", s)
    }

    @Test
    fun `one item is one item and not one items`() {
        val s = MealDraft.summary(MealDraft.effect(listOf(entry(400.0)), emptyList(), targets))
        assertTrue(s, s.startsWith("1 item · 400 kcal"))
    }

    @Test
    fun `with no plan the summary states the plate and stops`() {
        val s = MealDraft.summary(MealDraft.effect(listOf(entry(400.0)), emptyList(), targets = null))
        assertEquals("1 item · 400 kcal.", s)
    }

    // ---------------------------------------------------------------------------- days covered

    @Test
    fun `the plate reports which days it would write to`() {
        // ⚠️ **Three days, staged in an order that is neither sorted nor reverse-sorted.** The first
        // version of this used two, and `distinct().reversed()` happened to equal
        // `distinct().sorted()` for that input — so a perturbation replacing the sort with a
        // reversal changed nothing and the guard came back asleep. Two elements can only ever be in
        // one of two orders, and one of them is always the sorted one; it takes three to tell a
        // sort from a reversal.
        val d0 = day - 2 * 86_400_000L
        val d1 = day - 86_400_000L
        val staged = listOf(
            entry(100.0, on = day, name = "today"),
            entry(100.0, on = d0, name = "two back"),
            entry(100.0, on = d1, name = "one back"),
            entry(100.0, on = day, name = "today again"),
        )
        // Oldest first and de-duplicated, so a surface can say "this would go to three days" and
        // name them. An item staged while yesterday was on screen must land on yesterday — that is
        // what the person meant — and the only way that is not a silent surprise is to say it.
        assertEquals(listOf(d0, d1, day), MealDraft.daysCovered(staged))
        // Pinned explicitly: neither the order it was staged in nor its reverse is the answer.
        assertNotEquals(staged.map { it.dayStartMs }.distinct(), MealDraft.daysCovered(staged))
        assertNotEquals(
            staged.map { it.dayStartMs }.distinct().reversed(),
            MealDraft.daysCovered(staged),
        )
    }

    @Test
    fun `an empty plate covers no days`() {
        assertTrue(MealDraft.daysCovered(emptyList()).isEmpty())
    }

    @Test
    fun `the effect never looks at which day anything is stamped for`() {
        // ⚠️ Deliberate, and the division of labour this core rests on: the effect answers "what
        // does this do to the numbers", and which day a number belongs to is a calendar question.
        // Feeding it a plate spanning two days must produce the same totals as one that does not,
        // because the caller has already decided what `logged` means.
        val spread = listOf(entry(300.0, on = day), entry(300.0, on = day - 86_400_000L))
        val together = listOf(entry(300.0, on = day, name = "x"), entry(300.0, on = day, name = "y"))
        assertEquals(
            MealDraft.effect(spread, emptyList(), targets).after,
            MealDraft.effect(together, emptyList(), targets).after,
        )
    }

    // -------------------------------------------------------------------------------- robustness

    @Test
    fun `the four macros are read from the right fields`() {
        // A transposition here would be invisible on a balanced day and wrong on every other one,
        // so each is given a figure nothing else has.
        val e = MealDraft.effect(
            listOf(entry(kcal = 111.0, protein = 22.0, fat = 33.0, carb = 44.0)),
            emptyList(),
            targets,
        )
        assertEquals(111.0, e.after.kcal, 1e-9)
        assertEquals(22.0, lineFor(e, MacroTargets.Macro.PROTEIN).after, 1e-9)
        assertEquals(33.0, lineFor(e, MacroTargets.Macro.FAT).after, 1e-9)
        assertEquals(44.0, lineFor(e, MacroTargets.Macro.CARBS).after, 1e-9)
    }

    @Test
    fun `a line carries both ends so a surface can show the move`() {
        val e = MealDraft.effect(listOf(entry(600.0)), listOf(entry(800.0)), targets)
        val l = lineFor(e, MacroTargets.Macro.CALORIES)
        assertEquals(800.0, l.before, 1e-9)
        assertEquals(1400.0, l.after, 1e-9)
        assertEquals(2400, l.target)
        assertNotNull(l.sentence)
        assertNull(e.lines.firstOrNull { it.target <= 0 })
    }
}
