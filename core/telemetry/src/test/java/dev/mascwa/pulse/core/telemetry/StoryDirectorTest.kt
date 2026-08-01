package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryDirectorTest {

    private fun List<Quest>.byGoal(g: QuestGoal) = firstOrNull { it.goal == g }

    @Test fun aRealTaskBecomesTheMainQuest() {
        val q = StoryDirector.compose(LifeContext(pendingTasks = listOf("Finish the report")), seed = 1)
        val main = q.first { it.kind == QuestKind.MAIN }
        assertEquals(QuestGoal.COMPLETE_TASK, main.goal)
        assertTrue(main.brief.contains("Finish the report"))
        assertTrue(main.source.contains("Finish the report"))
    }

    @Test fun noTaskFallsBackToASurvivalArc() {
        val q = StoryDirector.compose(LifeContext(day = 5), seed = 1)
        val main = q.first { it.kind == QuestKind.MAIN }
        assertEquals(QuestGoal.SURVIVE_DAYS, main.goal)
        assertEquals(8, main.target) // day + 3
    }

    @Test fun interestOutdoorsBecomesAWalk() {
        val q = StoryDirector.compose(
            LifeContext(interests = listOf("hiking"), scene = SceneContext(setting = Setting.OUTDOOR)), seed = 1,
        )
        val walk = q.byGoal(QuestGoal.WALK_DISTANCE)
        assertTrue(walk != null && walk.kind == QuestKind.SIDE)
        assertTrue(walk!!.source.contains("hiking"))
    }

    @Test fun interestWhileStillIndoorsBecomesAVenture() {
        val q = StoryDirector.compose(LifeContext(interests = listOf("chess")), seed = 1) // default scene = still/unknown
        assertTrue(q.byGoal(QuestGoal.VENTURE) != null)
    }

    @Test fun nearbyPlaceBecomesAVisitQuest() {
        val q = StoryDirector.compose(LifeContext(nearbyKinds = setOf(LocationKind.MEDIC)), seed = 1)
        val visit = q.byGoal(QuestGoal.VISIT_KIND)
        assertTrue(visit != null && visit.brief.lowercase().contains("medic"))
    }

    @Test fun thereIsAlwaysADailyEncounterQuest() {
        val q = StoryDirector.compose(LifeContext(), seed = 1)
        assertTrue(q.byGoal(QuestGoal.WIN_ENCOUNTERS)?.kind == QuestKind.DAILY)
    }

    @Test fun compositionIsDeterministic() {
        val life = LifeContext(pendingTasks = listOf("Call the bank"), interests = listOf("music"), day = 3, level = 2)
        assertEquals(StoryDirector.compose(life, 42), StoryDirector.compose(life, 42))
    }

    @Test fun neverExceedsMaxQuests() {
        val q = StoryDirector.compose(
            LifeContext(
                pendingTasks = listOf("t"), interests = listOf("i"),
                scene = SceneContext(setting = Setting.OUTDOOR), nearbyKinds = setOf(LocationKind.TRADER),
                day = 2, level = 4,
            ),
            seed = 1,
        )
        assertTrue(q.size <= StoryDirector.MAX_QUESTS)
    }

    @Test fun rewardsScaleWithDayAndLevel() {
        val early = StoryDirector.compose(LifeContext(pendingTasks = listOf("x"), day = 1, level = 1), 1)
            .first { it.goal == QuestGoal.COMPLETE_TASK }
        val late = StoryDirector.compose(LifeContext(pendingTasks = listOf("x"), day = 10, level = 10), 1)
            .first { it.goal == QuestGoal.COMPLETE_TASK }
        assertTrue(late.rewardCaps > early.rewardCaps)
        assertTrue(late.rewardXp > early.rewardXp)
    }

    @Test fun dailyBriefIsFlavouredByTheScene() {
        val q = StoryDirector.compose(LifeContext(scene = SceneContext(setting = Setting.OUTDOOR)), seed = 1)
        val daily = q.first { it.kind == QuestKind.DAILY }
        val flavor = Perception.strategy(SceneContext(setting = Setting.OUTDOOR)).flavor
        assertTrue(daily.brief.startsWith(flavor))
    }

    @Test fun anEnergeticSceneRaisesTheDailyTarget() {
        // The scene the game PERCEIVES around you moves the objective, not just the flavour text: an active
        // scene (tempoNudge +1) asks for more daily wins than a calm one. This is what makes the ambient
        // camera/mic sensing functional gameplay and not decoration.
        val active = SceneContext(activity = Activity.MOVING) // tempoNudge +1
        val calmTarget = StoryDirector.compose(LifeContext(level = 4), seed = 1)
            .first { it.kind == QuestKind.DAILY }.target
        val activeTarget = StoryDirector.compose(LifeContext(level = 4, scene = active), seed = 1)
            .first { it.kind == QuestKind.DAILY }.target
        assertTrue(activeTarget > calmTarget)
        assertEquals(calmTarget + Perception.strategy(active).tempoNudge, activeTarget)
    }

    @Test fun dailyTargetNeverDropsBelowOne() {
        val daily = StoryDirector.compose(LifeContext(level = 0), seed = 1).first { it.kind == QuestKind.DAILY }
        assertTrue(daily.target >= 1)
    }

    @Test fun fractionClamps() {
        assertEquals(0f, StoryDirector.fraction(0, 2), 0.0001f)
        assertEquals(0.5f, StoryDirector.fraction(1, 2), 0.0001f)
        assertEquals(1f, StoryDirector.fraction(2, 2), 0.0001f)
        assertEquals(1f, StoryDirector.fraction(3, 2), 0.0001f) // clamped
        assertEquals(1f, StoryDirector.fraction(1, 0), 0.0001f) // no target
    }
}
