package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected value below is computed from the shipped formula with the arithmetic left in the
 * comment, not recalled. This repository has recorded roughly seventeen occasions where an
 * expectation of mine was wrong and the code was right; the arithmetic in a comment is what makes the
 * difference visible when a constant later moves.
 *
 * The base plan is chosen so its macros sum EXACTLY to its calories — 150 g protein (600 kcal) +
 * 60 g fat (540) + 215 g carbohydrate (860) = 2000 — so a macro assertion that fails is a real
 * failure and not a rounding artefact carried in from the fixture.
 */
class WeeklyPlanTest {

    private val base = MacroTargets.Targets(kcal = 2000, proteinG = 150, fatG = 60, carbG = 215)

    @Test
    fun `the base fixture sums to its own calories, so later assertions mean something`() {
        assertEquals(2000, base.proteinKcal + base.fatKcal + base.carbKcal)
    }

    @Test
    fun `a coached week is flat, and says so by having nothing to report`() {
        val week = WeeklyPlan.build(base, WeeklyPlan.Mode.COACHED)
        assertEquals(7, week.days.size)
        assertEquals(14_000, week.budgetKcal)
        assertTrue(week.days.all { it.kcal == 2000 })
        assertTrue(week.days.all { it.kind == WeeklyPlan.DayKind.EVEN })
        assertEquals(0.0, week.shift, 1e-9)
        assertTrue(week.limits.isEmpty())
    }

    @Test
    fun `manual ignores nominated heavy days rather than acting on them`() {
        // ⚠️ In manual mode the person owns the number; redistributing it would be the app taking
        // back the very thing that mode hands over. No limit is reported either — nothing was
        // refused, the mode simply does not ask the question.
        val week = WeeklyPlan.build(base, WeeklyPlan.Mode.MANUAL, heavy = setOf(1, 3))
        assertTrue(week.days.all { it.kcal == 2000 })
        assertTrue(week.limits.isEmpty())
    }

    @Test
    fun `two heavy days against five light ones is held by the heavy cap`() {
        // h=2, l=5.
        //   fromFloor = 1 - 1200/2000                = 0.40
        //   fromCap   = (1.35 - 1) * 2 / 5           = 0.14   <- the smaller, so it binds
        //   light     = 2000 * (1 - 0.14)            = 1720
        //   heavy     = 2000 * (1 + 0.14 * 5 / 2)    = 2700   ( = 1.35 x 2000, the cap exactly)
        //   sum       = 5*1720 + 2*2700 = 8600 + 5400 = 14000
        val week = WeeklyPlan.build(base, WeeklyPlan.Mode.COLLABORATIVE, heavy = setOf(1, 3))
        assertEquals(0.14, week.shift, 1e-9)
        assertEquals(2700, week.heaviestKcal)
        assertEquals(1720, week.lightestKcal)
        assertEquals(14_000, week.distributedKcal)
        assertEquals(listOf(1, 3), week.heavyDays)
        assertEquals(
            listOf(WeeklyPlan.LimitKind.HEAVY_DAY_CAPPED),
            week.limits.map { it.kind },
        )
    }

    @Test
    fun `a high floor holds the light days and is reported as the reason`() {
        // floor = 1900 against a flat 2000, h=3, l=4.
        //   fromFloor = 1 - 1900/2000            = 0.05    <- binds
        //   fromCap   = 0.35 * 3 / 4             = 0.2625
        //   light     = 2000 * 0.95              = 1900    ( = the floor exactly)
        //   heavy     = 2000 * (1 + 0.05 * 4/3)  = 2133.33
        //   sum       = 4*1900 + 3*2133.33 = 7600 + 6400 = 14000
        val week = WeeklyPlan.build(
            base,
            WeeklyPlan.Mode.COLLABORATIVE,
            heavy = setOf(0, 2, 4),
            floorKcal = 1900.0,
        )
        assertEquals(0.05, week.shift, 1e-9)
        assertEquals(1900, week.lightestKcal)
        assertEquals(14_000, week.distributedKcal)
        assertEquals(
            listOf(WeeklyPlan.LimitKind.FLOOR_HELD_THE_LIGHT_DAYS),
            week.limits.map { it.kind },
        )
    }

    @Test
    fun `no day may fall below the floor, whatever the shape of the week`() {
        // ⚠️ This is the property the whole file exists for, so it is asserted over every shape
        // rather than on one example: a floor honoured on six days and missed on the seventh is
        // exactly the failure a weekly-average floor produces.
        for (h in 1..6) {
            val heavy = (0 until h).toSet()
            val week = WeeklyPlan.build(
                base,
                WeeklyPlan.Mode.COLLABORATIVE,
                heavy = heavy,
                floorKcal = 1500.0,
                requestedShift = 0.9,
            )
            assertTrue(
                "h=$h lightest=${week.lightestKcal}",
                week.days.all { it.kcal >= 1500 },
            )
        }
    }

    @Test
    fun `the week always sums to its own stated budget`() {
        // ⚠️ Seven independent roundings drift by up to three kcal, so this is asserted across every
        // shape and both binding constraints rather than on the one case that happens to divide.
        for (h in 0..7) {
            for (floor in listOf(1200.0, 1700.0, 1900.0)) {
                val week = WeeklyPlan.build(
                    base,
                    WeeklyPlan.Mode.COLLABORATIVE,
                    heavy = (0 until h).toSet(),
                    floorKcal = floor,
                )
                assertEquals("h=$h floor=$floor", week.budgetKcal, week.distributedKcal)
            }
        }
    }

    @Test
    fun `a heavy day never exceeds the cap`() {
        for (h in 1..6) {
            val week = WeeklyPlan.build(
                base,
                WeeklyPlan.Mode.COLLABORATIVE,
                heavy = (0 until h).toSet(),
                requestedShift = 0.9,
            )
            // 1.35 x 2000 = 2700. The +1 is the largest-remainder reconciliation, which can hand a
            // single calorie to one day and cannot hand it more than that.
            assertTrue("h=$h heaviest=${week.heaviestKcal}", week.heaviestKcal <= 2701)
        }
    }

    @Test
    fun `no heavy days under collaborative is an even week with a reason, not a silent one`() {
        val week = WeeklyPlan.build(base, WeeklyPlan.Mode.COLLABORATIVE, heavy = emptySet())
        assertTrue(week.days.all { it.kcal == 2000 })
        assertEquals(listOf(WeeklyPlan.LimitKind.NOTHING_TO_SHIFT), week.limits.map { it.kind })
    }

    @Test
    fun `every day heavy is the same nothing-to-shift case`() {
        val week = WeeklyPlan.build(base, WeeklyPlan.Mode.COLLABORATIVE, heavy = (0..6).toSet())
        assertTrue(week.days.all { it.kcal == 2000 })
        assertEquals(listOf(WeeklyPlan.LimitKind.NOTHING_TO_SHIFT), week.limits.map { it.kind })
    }

    @Test
    fun `out-of-range day indices are dropped rather than refused`() {
        // A stale index from a set of toggles is a caller bug; giving somebody no plan over it is worse.
        val week = WeeklyPlan.build(base, WeeklyPlan.Mode.COLLABORATIVE, heavy = setOf(1, 9, -2))
        assertEquals(listOf(1), week.heavyDays)
    }

    @Test
    fun `carbohydrate carries the difference and protein does not move`() {
        // heavy 2700: delta +700 -> carbs 215 + 700/4 = 390.  600 + 540 + 1560 = 2700 exactly.
        val up = WeeklyPlan.macrosFor(base, 2700)
        assertEquals(150, up.proteinG)
        assertEquals(60, up.fatG)
        assertEquals(390, up.carbG)
        assertEquals(2700, up.proteinKcal + up.fatKcal + up.carbKcal)

        // light 1720: delta -280 -> carbs 215 - 70 = 145.     600 + 540 + 580 = 1720 exactly.
        val down = WeeklyPlan.macrosFor(base, 1720)
        assertEquals(150, down.proteinG)
        assertEquals(60, down.fatG)
        assertEquals(145, down.carbG)
        assertEquals(1720, down.proteinKcal + down.fatKcal + down.carbKcal)
    }

    @Test
    fun `fat gives way only after carbohydrate has run out, and protein last of all`() {
        // ⚠️ Unreachable through `build`, which the floor prevents — asserted directly because the
        // ORDER is the contract: it has to match the order MacroTargets cuts in, or a light day and
        // a daily plan would disagree about which macro is sacrificed last.
        // 1040: delta -960 -> carbs 215 - 240 = -25, so carbs go to 0 and 100 kcal comes off fat:
        //   fat 60 - 100/9 = 60 - 11 = 49  (integer division, deliberately)
        val deep = WeeklyPlan.macrosFor(base, 1040)
        assertEquals(0, deep.carbG)
        assertEquals(49, deep.fatG)
        assertEquals(150, deep.proteinG)

        // Deeper still: fat reaches zero and protein is the last thing to move.
        val extreme = WeeklyPlan.macrosFor(base, 300)
        assertEquals(0, extreme.carbG)
        assertEquals(0, extreme.fatG)
        assertTrue("protein=${extreme.proteinG}", extreme.proteinG < 150)
    }

    @Test
    fun `an unchanged day returns the base untouched`() {
        assertEquals(base, WeeklyPlan.macrosFor(base, base.kcal))
    }

    @Test
    fun `largest-remainder rounding spreads the shortfall rather than favouring the first day`() {
        // Three days at 10.9 and four at 10.0 floor to 10 and 10, summing to 70 against a budget of
        // 73 — so three calories are owed, and they go to the three days that were rounded down
        // hardest, not to whichever day happens to be first.
        val raw = listOf(10.9, 10.0, 10.9, 10.0, 10.9, 10.0, 10.0)
        val out = WeeklyPlan.reconcile(raw, 73)
        assertEquals(73, out.sum())
        assertEquals(listOf(11, 10, 11, 10, 11, 10, 10), out)
    }

    @Test
    fun `the sentence names the shape rather than reciting the days`() {
        val flat = WeeklyPlan.sentence(WeeklyPlan.build(base, WeeklyPlan.Mode.COACHED))
        assertTrue(flat, flat.contains("2000") && flat.contains("14000"))

        val cycled = WeeklyPlan.sentence(
            WeeklyPlan.build(base, WeeklyPlan.Mode.COLLABORATIVE, heavy = setOf(1, 3)),
        )
        assertTrue(cycled, cycled.contains("2700") && cycled.contains("1720"))
    }
}
