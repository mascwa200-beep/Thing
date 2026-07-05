package dev.mascwa.pulse.core.telemetry

/**
 * The quest lifecycle — how the personalised missions from [StoryDirector] actually get tracked, completed
 * and rewarded against your real-world/game progress. Pure + CI-tested; the on-device [QuestStore] persists
 * the state, supplies the live metric snapshot, and grants the rewards this returns.
 *
 * The trick that makes completion work with derived quests: an issued quest is FROZEN into an [ActiveQuest]
 * with a metric baseline, so "walk 1 km" means 1 km *from when the quest appeared*, not lifetime. Absolute
 * goals (survive to day N, finish a real task) need no baseline. A completed quest's id goes into
 * `completedIds` so it's never re-issued (no farming); daily quests dodge this because their id carries the
 * day, so a fresh one appears each day.
 */

/** A live snapshot of the counters quests are measured against (from the game + world stores + clock). */
data class QuestMetrics(
    val distanceM: Int = 0,               // cumulative metres walked
    val wins: Int = 0,                    // lifetime encounter wins
    val ventures: Int = 0,                // lifetime ventures faced
    val places: Int = 0,                  // distinct real places reached
    val day: Int = 1,                     // wasteland day (absolute)
    val pendingTasks: Set<String> = emptySet(), // titles of tasks still open
    val visitedKinds: Set<String> = emptySet(), // LocationKind.names of place-kinds physically reached
)

/** An issued quest, frozen with the metric baseline captured when it appeared. */
data class ActiveQuest(
    val quest: Quest,
    val baseDistanceM: Int = 0,
    val baseWins: Int = 0,
    val baseVentures: Int = 0,
    val basePlaces: Int = 0,
    val issuedDay: Int = 1,
)

/** The persisted quest-log state: what's active, and what's been completed (never to be re-issued). */
data class QuestLogState(
    val active: List<ActiveQuest> = emptyList(),
    val completedIds: Set<String> = emptySet(),
)

/** A quest rendered for the UI: the quest, how much is done, and whether it's complete. */
data class QuestView(val quest: Quest, val done: Int, val complete: Boolean)

/** The outcome of a [QuestLog.sync]: the new state, and the quests that JUST completed (grant their rewards). */
data class QuestSync(val state: QuestLogState, val newlyCompleted: List<Quest>)

object QuestLog {
    private const val TASK_PREFIX = "Your task: "

    /** How much of [a] is done given the live [m] — a delta from the baseline, or absolute where it makes sense. */
    fun progress(a: ActiveQuest, m: QuestMetrics): Int = when (a.quest.goal) {
        QuestGoal.WALK_DISTANCE -> (m.distanceM - a.baseDistanceM).coerceAtLeast(0)
        QuestGoal.WIN_ENCOUNTERS -> (m.wins - a.baseWins).coerceAtLeast(0)
        QuestGoal.VENTURE -> (m.ventures - a.baseVentures).coerceAtLeast(0)
        QuestGoal.VISIT_KIND -> {
            // Complete only when the quest's SPECIFIC kind of place has been physically reached — not any
            // place. A legacy quest with no targetKey (persisted before this field) keeps the old any-place
            // delta so it can still finish. Per-kind ids mean each kind can only ever pay out once.
            val key = a.quest.targetKey
            if (key == null) (m.places - a.basePlaces).coerceAtLeast(0)
            else if (key in m.visitedKinds) 1 else 0
        }
        QuestGoal.SURVIVE_DAYS -> m.day
        QuestGoal.COMPLETE_TASK -> {
            val title = a.quest.source.removePrefix(TASK_PREFIX)
            if (title.isNotBlank() && title !in m.pendingTasks) 1 else 0
        }
    }

    fun isComplete(a: ActiveQuest, m: QuestMetrics): Boolean = progress(a, m) >= a.quest.target

    fun fraction(a: ActiveQuest, m: QuestMetrics): Float = StoryDirector.fraction(progress(a, m), a.quest.target)

    /** Render the active quests for the UI at the current metric snapshot. */
    fun view(state: QuestLogState, m: QuestMetrics): List<QuestView> =
        state.active.map { QuestView(it.quest, progress(it, m), isComplete(it, m)) }

    /**
     * Advance the log against the live [m]: retire any active quest that's now complete (its reward is
     * returned in [QuestSync.newlyCompleted]), then top up the active set from the freshly-[composed] quests —
     * adding only ids that aren't already active and were never completed — freezing each new one's baseline
     * from [m]. Deterministic; the store just persists the result and grants the rewards.
     */
    fun sync(state: QuestLogState, composed: List<Quest>, m: QuestMetrics): QuestSync {
        val done = state.active.filter { isComplete(it, m) }
        val doneIds = done.map { it.quest.id }.toSet()
        val completedIds = state.completedIds + doneIds
        val remaining = state.active.filterNot { it.quest.id in doneIds }
        val activeIds = remaining.map { it.quest.id }.toSet()
        val additions = composed
            .filter { it.id !in activeIds && it.id !in completedIds }
            .map {
                ActiveQuest(
                    quest = it,
                    baseDistanceM = m.distanceM, baseWins = m.wins,
                    baseVentures = m.ventures, basePlaces = m.places, issuedDay = m.day,
                )
            }
        return QuestSync(QuestLogState(remaining + additions, completedIds), done.map { it.quest })
    }
}
