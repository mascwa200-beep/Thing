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

    @Test fun catalogHasAtLeastAThousand() {
        assertTrue("expected 1000+ achievements, got ${Achievements.ALL.size}", Achievements.ALL.size >= 1000)
        // Every generated track's ladder is monotonic + all thresholds positive.
        Achievements.ALL.forEach { assertTrue("bad threshold ${it.id}", it.threshold > 0) }
    }

    @Test fun nextUpSurfacesAShortChaseList() {
        // With a blank profile, "what's next" is a short, capped, most-progressed-first list.
        val next = Achievements.nextUp(GameMetrics(wins = 3, distanceM = 400), emptySet())
        assertTrue(next.size <= 6)
        assertTrue(next.none { Achievements.isUnlocked(it, GameMetrics(wins = 3, distanceM = 400)) })
    }

    @Test fun newTrackingMetricsProject() {
        val m = GameMetrics(walkM = 1500, runM = 500, driveM = 8000, elevationM = 300, stepsTotal = 12_000,
            cellsExplored = 7, talksWon = 4, scavenges = 6, questsDone = 3, crafted = 5, trades = 9,
            renown = 40, daysSurvived = 8)
        assertEquals(2000, m.value(AchMetric.ONFOOT_M)) // walk + run
        assertEquals(1500, m.value(AchMetric.WALK_M))
        assertEquals(8000, m.value(AchMetric.DRIVE_M))
        assertEquals(300, m.value(AchMetric.ELEVATION_M))
        assertEquals(12_000, m.value(AchMetric.STEPS_TOTAL))
        assertEquals(40, m.value(AchMetric.RENOWN))
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

    @Test fun codexDiscoveryMilestonesProjectAndClear() {
        assertEquals(0, GameMetrics().value(AchMetric.ITEMS_DISCOVERED))
        assertEquals(15, GameMetrics(itemsDiscovered = 15).value(AchMetric.ITEMS_DISCOVERED))
        val ten = Achievements.byId("codex_10")!!
        assertFalse(Achievements.isUnlocked(ten, GameMetrics(itemsDiscovered = 9)))
        assertTrue(Achievements.isUnlocked(ten, GameMetrics(itemsDiscovered = 10)))
        // "Archivist" clears exactly at a full catalog and its threshold tracks the catalog size.
        val all = Achievements.byId("codex_all")!!
        assertEquals(Items.ALL.size, all.threshold)
        assertFalse(Achievements.isUnlocked(all, GameMetrics(itemsDiscovered = Items.ALL.size - 1)))
        assertTrue(Achievements.isUnlocked(all, GameMetrics(itemsDiscovered = Items.ALL.size)))
    }
}
