package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [DailyObjectives] — deterministic daily picks, progress, and rewards. */
class DailyObjectivesTest {

    private fun char(caps: Int = 25): Character =
        Character(stats = Special.entries.associateWith { 4 }, caps = caps)

    @Test fun catalogIsValid() {
        assertEquals(DailyObjectives.CATALOG.size, DailyObjectives.CATALOG.map { it.id }.toSet().size)
        DailyObjectives.CATALOG.mapNotNull { it.rewardItemId }.forEach {
            assertNotNull("Unknown reward item '$it'", Items.byId(it))
        }
    }

    @Test fun forDayIsDeterministicAndPicksThreeDistinct() {
        val a = DailyObjectives.forDay(20_000)
        val b = DailyObjectives.forDay(20_000)
        assertEquals(a.map { it.id }, b.map { it.id }) // same day → identical set
        assertEquals(3, a.size)
        assertEquals(3, a.map { it.id }.toSet().size)  // distinct
    }

    @Test fun differentDaysVaryTheSet() {
        // Over a week, the daily set should not be identical every single day (deterministic but rotating).
        val sets = (20_000L..20_006L).map { DailyObjectives.forDay(it).map { o -> o.id }.toSet() }
        assertTrue(sets.toSet().size > 1)
    }

    @Test fun progressAndCompletion() {
        val o = DailyObjectives.byId("d_win3")!! // WINS >= 3
        assertEquals(2, DailyObjectives.progress(o, TodayMetrics(wins = 2)))
        assertEquals(3, DailyObjectives.progress(o, TodayMetrics(wins = 5))) // clamped to target
        assertFalse(DailyObjectives.isComplete(o, TodayMetrics(wins = 2)))
        assertTrue(DailyObjectives.isComplete(o, TodayMetrics(wins = 3)))
    }

    @Test fun travelAndPlacesGoalsProject() {
        assertTrue(DailyObjectives.isComplete(DailyObjectives.byId("d_travel1500")!!, TodayMetrics(travelM = 1500)))
        assertTrue(DailyObjectives.isComplete(DailyObjectives.byId("d_places2")!!, TodayMetrics(places = 2)))
        assertFalse(DailyObjectives.isComplete(DailyObjectives.byId("d_travel1500")!!, TodayMetrics(travelM = 1499)))
    }

    @Test fun applyRewardGrantsCapsItemXp() {
        val o = DailyObjective("t", "d", DailyGoal.WINS, 1, rewardXp = 20, rewardCaps = 30, rewardItemId = "medkit")
        val after = DailyObjectives.applyReward(char(caps = 10), o)
        assertEquals(40, after.caps)
        assertEquals(1, after.inventory["medkit"])
        assertEquals(20, after.xp)
    }
}
