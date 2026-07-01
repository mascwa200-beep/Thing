package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the [Achievements] core — thresholds, progress, evaluate-once, and reward application. */
class AchievementsTest {

    private fun char(caps: Int = 25): Character =
        Character(stats = Special.entries.associateWith { 4 }, caps = caps)

    @Test fun catalogIsStableAndLookupWorks() {
        assertTrue(Achievements.ALL.isNotEmpty())
        assertEquals(Achievements.ALL.size, Achievements.ALL.map { it.id }.toSet().size) // unique ids
        assertNotNull(Achievements.byId("first_blood"))
        assertEquals(null, Achievements.byId("nope"))
    }

    @Test fun everyRewardItemIdResolves() {
        // A typo'd reward id would silently grant nothing on-device.
        Achievements.ALL.mapNotNull { it.rewardItemId }.forEach {
            assertNotNull("Unknown reward item id '$it'", Items.byId(it))
        }
    }

    @Test fun unlockAtThreshold() {
        val a = Achievements.byId("survivor")!! // WINS >= 10
        assertFalse(Achievements.isUnlocked(a, GameMetrics(wins = 9)))
        assertTrue(Achievements.isUnlocked(a, GameMetrics(wins = 10)))
        assertTrue(Achievements.isUnlocked(a, GameMetrics(wins = 99)))
    }

    @Test fun progressClamps() {
        val a = Achievements.byId("survivor")!! // threshold 10
        assertEquals(0f, Achievements.progress(a, GameMetrics(wins = 0)), 0.0001f)
        assertEquals(0.5f, Achievements.progress(a, GameMetrics(wins = 5)), 0.0001f)
        assertEquals(1f, Achievements.progress(a, GameMetrics(wins = 25)), 0.0001f) // capped at 1
    }

    @Test fun evaluateReturnsOnlyNewlyCleared() {
        val m = GameMetrics(wins = 12, level = 6) // clears first_blood, survivor, seasoned
        val fresh = Achievements.evaluate(m, unlocked = setOf("first_blood")).map { it.id }.toSet()
        assertTrue("survivor" in fresh)
        assertTrue("seasoned" in fresh)
        assertFalse("first_blood" in fresh) // already unlocked → not re-reported
        assertFalse("veteran" in fresh)     // not yet cleared (needs 50 wins)
    }

    @Test fun evaluateEmptyWhenNothingNew() {
        val m = GameMetrics(wins = 1)
        val once = Achievements.evaluate(m, emptySet()).map { it.id }
        assertTrue("first_blood" in once)
        // Feeding the same metrics with it already unlocked yields nothing new.
        assertTrue(Achievements.evaluate(m, once.toSet()).isEmpty())
    }

    @Test fun applyRewardGrantsCapsItemAndXp() {
        val a = Achievement("t", "T", "d", AchMetric.WINS, 1, rewardXp = 40, rewardCaps = 20, rewardItemId = "medkit")
        val before = char(caps = 10)
        val after = Achievements.applyReward(before, a)
        assertEquals(30, after.caps)                     // +20 caps
        assertEquals(1, after.inventory["medkit"])       // +1 item
        assertEquals(40, after.xp)                        // +40 xp (no level-up at 40 < 100)
    }

    @Test fun applyRewardXpCanLevelUp() {
        val a = Achievement("t", "T", "d", AchMetric.LEVEL, 1, rewardXp = 100)
        val leveled = Achievements.applyReward(char(), a)
        assertEquals(2, leveled.level) // 100 xp = one level at XP_PER_LEVEL
    }

    @Test fun usageAndTravelMetricsProject() {
        val m = GameMetrics(appVisits = 200, distinctFeatures = 15, distanceM = 10_000, placesVisited = 5)
        assertTrue(Achievements.isUnlocked(Achievements.byId("power_user")!!, m))
        assertTrue(Achievements.isUnlocked(Achievements.byId("cartographer")!!, m))
        assertTrue(Achievements.isUnlocked(Achievements.byId("trailblazer")!!, m))
        assertTrue(Achievements.isUnlocked(Achievements.byId("tourist")!!, m))
    }
}
