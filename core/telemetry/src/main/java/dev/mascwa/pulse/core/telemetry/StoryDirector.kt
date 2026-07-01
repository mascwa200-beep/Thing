package dev.mascwa.pulse.core.telemetry

/**
 * The story/quest director for the Fallout life-sim — the "knows about you" payoff. It composes
 * personalised missions from your real life: the tasks you told J.A.R.V.I.S. about, your profiled
 * interests, the scene it perceives around you ([SceneContext]), the kinds of real places nearby, and the
 * wasteland day. Each quest's goal is a REAL-WORLD signal the game already tracks (finish a task, walk a
 * distance, physically visit a place, win encounters, survive to a day), so playing the game nudges your
 * actual life — The Sims, but you're the Sim.
 *
 * Pure + CI-tested: generation is deterministic given the [LifeContext] + a seed (the on-device layer
 * supplies the seed and can layer LLM-written flavour on top later). No profile/task text leaves the
 * device — the director only weaves it into local quest briefs.
 */
enum class QuestKind(val label: String) { MAIN("MAIN"), SIDE("SIDE"), DAILY("DAILY") }

/** What real-world signal advances a quest — checkable against the game's live metrics. */
enum class QuestGoal {
    COMPLETE_TASK,   // finish a real to-do you recorded
    VISIT_KIND,      // physically reach a kind of place nearby
    WALK_DISTANCE,   // walk N metres in the real world
    WIN_ENCOUNTERS,  // win N encounters
    SURVIVE_DAYS,    // reach wasteland day N
    VENTURE,         // face N encounters
}

/** A generated, personalised mission. [source] is the human-readable real-life origin (for transparency). */
data class Quest(
    val id: String,
    val title: String,
    val brief: String,
    val goal: QuestGoal,
    val target: Int,
    val kind: QuestKind,
    val source: String,
    val rewardCaps: Int,
    val rewardXp: Int,
)

/** Everything the director knows about you + your world right now. */
data class LifeContext(
    val interests: List<String> = emptyList(),
    val pendingTasks: List<String> = emptyList(),
    val scene: SceneContext = SceneContext(),
    val nearbyKinds: Set<LocationKind> = emptySet(),
    val day: Int = 1,
    val level: Int = 1,
)

object StoryDirector {
    const val MAX_QUESTS = 4

    private val TASK_TITLES = listOf("The Overseer's Orders", "A Debt Unpaid", "Unfinished Business", "The Standing Order")
    private val TASK_BRIEFS = listOf(
        "A job weighs on you — \"%s\". The wasteland doesn't wait, but neither does this. See it done.",
        "\"%s\" hangs over you like radstorm clouds. Clear it and breathe easier.",
        "The Overseer's ledger has your name on it: \"%s\". Settle up.",
    )

    private fun <T> List<T>.pick(seed: Long): T = this[(((seed % size) + size) % size).toInt()]
    private fun stableHash(s: String): Long = (s.hashCode().toLong() and 0xffffffL)

    /**
     * Compose up to [MAX_QUESTS] personalised quests from [life]. Deterministic given ([life], [seed]).
     * Always yields at least a MAIN (a task if you have one, else a survival arc) + a DAILY.
     */
    fun compose(life: LifeContext, seed: Long): List<Quest> {
        val quests = mutableListOf<Quest>()

        // 1 · MAIN — your top pending real task, wrapped as the through-line. Else a survival arc.
        val topTask = life.pendingTasks.firstOrNull()
        if (topTask != null) {
            quests += Quest(
                id = "q_task_${stableHash(topTask)}",
                title = TASK_TITLES.pick(seed),
                brief = TASK_BRIEFS.pick(seed + 7).format(topTask),
                goal = QuestGoal.COMPLETE_TASK, target = 1, kind = QuestKind.MAIN,
                source = "Your task: $topTask",
                rewardCaps = 30 + life.day * 2, rewardXp = 40 + life.level * 5,
            )
        } else {
            val goalDay = life.day + 3
            quests += Quest(
                id = "q_survive_$goalDay",
                title = "Endure",
                brief = "No orders today — just the wasteland, and the will to see day $goalDay.",
                goal = QuestGoal.SURVIVE_DAYS, target = goalDay, kind = QuestKind.MAIN,
                source = "The long road",
                rewardCaps = 25 + goalDay, rewardXp = 50 + life.level * 4,
            )
        }

        // 2 · SIDE — an interest, themed by what you're actually doing right now.
        val interest = life.interests.firstOrNull()
        if (interest != null) {
            val moving = life.scene.setting == Setting.OUTDOOR || life.scene.activity != Activity.STILL
            quests += if (moving) Quest(
                id = "q_interest_walk",
                title = "Trailblazer",
                brief = "You've a thing for $interest — and the wastes reward wanderers. Cover some ground on foot.",
                goal = QuestGoal.WALK_DISTANCE, target = 1000 + life.day * 100, kind = QuestKind.SIDE,
                source = "Your interest: $interest", rewardCaps = 18 + life.day, rewardXp = 22,
            ) else Quest(
                id = "q_interest_venture",
                title = "Restless",
                brief = "$interest is on your mind, but the wasteland's calling louder. Go find some trouble.",
                goal = QuestGoal.VENTURE, target = 3, kind = QuestKind.SIDE,
                source = "Your interest: $interest", rewardCaps = 15, rewardXp = 20,
            )
        }

        // 3 · SIDE — a real place nearby, reachable only in person (ties to the geofenced map).
        val kind = life.nearbyKinds.firstOrNull()
        if (kind != null && quests.size < MAX_QUESTS) {
            quests += Quest(
                id = "q_visit_${kind.name.lowercase()}",
                title = "Supply Run",
                brief = "A ${kind.title.lowercase()} (${kind.role}) is within reach. Get there in person and deal.",
                goal = QuestGoal.VISIT_KIND, target = 1, kind = QuestKind.SIDE,
                source = "Nearby: ${kind.title}", rewardCaps = 20, rewardXp = 25,
            )
        }

        // 4 · DAILY — scaled to your level, flavoured by the scene the game perceives around you.
        val strat = Perception.strategy(life.scene)
        val dailyTarget = 2 + life.level / 2
        quests += Quest(
            id = "q_daily_${life.day}",
            title = "Prove Your Worth",
            brief = "${strat.flavor} Win $dailyTarget and come back standing.",
            goal = QuestGoal.WIN_ENCOUNTERS, target = dailyTarget, kind = QuestKind.DAILY,
            source = "Day ${life.day}", rewardCaps = 15 + life.day, rewardXp = 20 + life.level * 3,
        )

        return quests.take(MAX_QUESTS)
    }

    /** Completion fraction of a quest given how much of its [Quest.target] you've achieved. */
    fun fraction(done: Int, target: Int): Float =
        if (target <= 0) 1f else (done.toFloat() / target).coerceIn(0f, 1f)
}
