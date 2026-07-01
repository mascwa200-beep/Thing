package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestLogTest {

    private fun quest(id: String, goal: QuestGoal, target: Int, source: String = "", kind: QuestKind = QuestKind.SIDE) =
        Quest(id, "T", "brief", goal, target, kind, source, rewardCaps = 10, rewardXp = 12)

    @Test fun walkProgressIsADeltaFromBaseline() {
        val a = ActiveQuest(quest("q", QuestGoal.WALK_DISTANCE, 1000), baseDistanceM = 500)
        assertEquals(700, QuestLog.progress(a, QuestMetrics(distanceM = 1200)))
        assertFalse(QuestLog.isComplete(a, QuestMetrics(distanceM = 1200)))
        assertTrue(QuestLog.isComplete(a, QuestMetrics(distanceM = 1500)))
    }

    @Test fun winsProgressIsADelta() {
        val a = ActiveQuest(quest("q", QuestGoal.WIN_ENCOUNTERS, 2), baseWins = 3)
        assertEquals(2, QuestLog.progress(a, QuestMetrics(wins = 5)))
        assertTrue(QuestLog.isComplete(a, QuestMetrics(wins = 5)))
    }

    @Test fun surviveDaysIsAbsolute() {
        val a = ActiveQuest(quest("q", QuestGoal.SURVIVE_DAYS, 8))
        assertFalse(QuestLog.isComplete(a, QuestMetrics(day = 5)))
        assertTrue(QuestLog.isComplete(a, QuestMetrics(day = 8)))
    }

    @Test fun completeTaskChecksPendingSet() {
        val a = ActiveQuest(quest("q", QuestGoal.COMPLETE_TASK, 1, source = "Your task: Call the bank"))
        assertEquals(0, QuestLog.progress(a, QuestMetrics(pendingTasks = setOf("Call the bank"))))
        assertEquals(1, QuestLog.progress(a, QuestMetrics(pendingTasks = emptySet())))
        assertTrue(QuestLog.isComplete(a, QuestMetrics(pendingTasks = emptySet())))
    }

    @Test fun syncRetiresCompletedAndReturnsReward() {
        val a = ActiveQuest(quest("q_walk", QuestGoal.WALK_DISTANCE, 1000), baseDistanceM = 0)
        val r = QuestLog.sync(QuestLogState(active = listOf(a)), emptyList(), QuestMetrics(distanceM = 1200))
        assertEquals(listOf("q_walk"), r.newlyCompleted.map { it.id })
        assertTrue(r.state.active.isEmpty())
        assertTrue("q_walk" in r.state.completedIds)
    }

    @Test fun syncTopsUpAndFreezesBaseline() {
        val composed = listOf(quest("q_walk", QuestGoal.WALK_DISTANCE, 1000))
        val r = QuestLog.sync(QuestLogState(), composed, QuestMetrics(distanceM = 500))
        val issued = r.state.active.single()
        assertEquals(500, issued.baseDistanceM)
        // Baseline frozen: progress is 0 right after issue even though 500 m is already on the odometer.
        assertEquals(0, QuestLog.progress(issued, QuestMetrics(distanceM = 500)))
    }

    @Test fun syncNeverReAddsACompletedQuest() {
        val state = QuestLogState(completedIds = setOf("q_daily_5"))
        val composed = listOf(quest("q_daily_5", QuestGoal.WIN_ENCOUNTERS, 2, kind = QuestKind.DAILY))
        val r = QuestLog.sync(state, composed, QuestMetrics())
        assertTrue(r.state.active.isEmpty())
    }

    @Test fun dailyRefreshesByDayId() {
        val state = QuestLogState(completedIds = setOf("q_daily_5"))
        val composed = listOf(quest("q_daily_6", QuestGoal.WIN_ENCOUNTERS, 2, kind = QuestKind.DAILY))
        val r = QuestLog.sync(state, composed, QuestMetrics(day = 6))
        assertEquals(listOf("q_daily_6"), r.state.active.map { it.quest.id })
    }

    @Test fun incompleteQuestsStayActiveAcrossSyncs() {
        val composed = listOf(quest("q_walk", QuestGoal.WALK_DISTANCE, 1000))
        val first = QuestLog.sync(QuestLogState(), composed, QuestMetrics(distanceM = 0))
        val second = QuestLog.sync(first.state, composed, QuestMetrics(distanceM = 300))
        assertEquals(1, second.state.active.size)
        assertTrue(second.newlyCompleted.isEmpty())
        assertEquals(300, QuestLog.progress(second.state.active.single(), QuestMetrics(distanceM = 300)))
    }

    @Test fun noDoubleRewardOnRepeatSync() {
        val a = ActiveQuest(quest("q_walk", QuestGoal.WALK_DISTANCE, 1000), baseDistanceM = 0)
        val m = QuestMetrics(distanceM = 1200)
        val r1 = QuestLog.sync(QuestLogState(active = listOf(a)), emptyList(), m)
        val r2 = QuestLog.sync(r1.state, emptyList(), m)
        assertTrue(r2.newlyCompleted.isEmpty())
    }

    @Test fun viewReportsProgressAndCompletion() {
        val a = ActiveQuest(quest("q", QuestGoal.VENTURE, 3), baseVentures = 1)
        val views = QuestLog.view(QuestLogState(active = listOf(a)), QuestMetrics(ventures = 4))
        assertEquals(1, views.size)
        assertEquals(3, views.single().done)
        assertTrue(views.single().complete)
    }
}
