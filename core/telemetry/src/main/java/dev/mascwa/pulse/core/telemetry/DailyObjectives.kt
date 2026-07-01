package dev.mascwa.pulse.core.telemetry

/**
 * Daily objectives for the S.P.E.C.I.A.L. game — a rotating set of three goals per day that pay out on
 * completion, plus the basis for a play streak. The retention/grind layer. Pure + CI-tested: the day's
 * three objectives are picked deterministically from a day index (same day → same set, reproducible), and
 * progress is measured against "today's" metric deltas that the on-device store supplies.
 */
enum class DailyGoal { WINS, VENTURES, CRITS, TRAVEL_M, PLACES }

data class DailyObjective(
    val id: String,
    val description: String,
    val goal: DailyGoal,
    val target: Int,
    val rewardXp: Int = 0,
    val rewardCaps: Int = 0,
    val rewardItemId: String? = null,
)

/** How much the player has done *today* (current lifetime counters minus the day's baseline). */
data class TodayMetrics(
    val wins: Int = 0,
    val ventures: Int = 0,
    val crits: Int = 0,
    val travelM: Int = 0,
    val places: Int = 0,
) {
    fun value(g: DailyGoal): Int = when (g) {
        DailyGoal.WINS -> wins
        DailyGoal.VENTURES -> ventures
        DailyGoal.CRITS -> crits
        DailyGoal.TRAVEL_M -> travelM
        DailyGoal.PLACES -> places
    }
}

object DailyObjectives {
    /** The pool the daily three are drawn from. Reward item ids resolve in [Items]. */
    val CATALOG: List<DailyObjective> = listOf(
        DailyObjective("d_win3", "Win 3 encounters", DailyGoal.WINS, 3, rewardXp = 25),
        DailyObjective("d_win6", "Win 6 encounters", DailyGoal.WINS, 6, rewardCaps = 40),
        DailyObjective("d_venture5", "Venture out 5 times", DailyGoal.VENTURES, 5, rewardXp = 20),
        DailyObjective("d_venture10", "Venture out 10 times", DailyGoal.VENTURES, 10, rewardCaps = 35),
        DailyObjective("d_crit2", "Land 2 critical successes", DailyGoal.CRITS, 2, rewardItemId = "rabbit_foot"),
        DailyObjective("d_travel500", "Travel 500 m on foot", DailyGoal.TRAVEL_M, 500, rewardCaps = 25),
        DailyObjective("d_travel1500", "Travel 1.5 km on foot", DailyGoal.TRAVEL_M, 1500, rewardItemId = "medkit"),
        DailyObjective("d_places2", "Reach 2 real-world locations", DailyGoal.PLACES, 2, rewardCaps = 30),
    )

    private val byId: Map<String, DailyObjective> = CATALOG.associateBy { it.id }
    fun byId(id: String): DailyObjective? = byId[id]

    /** The three objectives for [dayIndex] (e.g. `LocalDate.toEpochDay()`) — deterministic + distinct. */
    fun forDay(dayIndex: Long): List<DailyObjective> =
        CATALOG.indices.sortedBy { hash(dayIndex, it) }.take(3).map { CATALOG[it] }

    /** A stable per-(day, index) hash so each day gets its own pseudo-random ordering of the catalog. */
    private fun hash(dayIndex: Long, idx: Int): Long {
        var h = dayIndex * 1_000_003L + idx
        h = h xor (h ushr 16)
        h *= -0x7ee3623a03d3c65fL
        h = h xor (h ushr 13)
        return h
    }

    /** Progress toward [o], clamped to its target. */
    fun progress(o: DailyObjective, m: TodayMetrics): Int = m.value(o.goal).coerceAtMost(o.target)

    /** Whether [o] is cleared by today's [m]. */
    fun isComplete(o: DailyObjective, m: TodayMetrics): Boolean = m.value(o.goal) >= o.target

    /** Grant [o]'s reward to [c] (caps, then item, then XP so a level-up heals last). */
    fun applyReward(c: Character, o: DailyObjective): Character {
        var updated = c
        if (o.rewardCaps != 0) updated = updated.copy(caps = (updated.caps + o.rewardCaps).coerceAtLeast(0))
        o.rewardItemId?.let { updated = SpecialGame.addItem(updated, it, 1) }
        if (o.rewardXp > 0) updated = SpecialGame.gainXp(updated, o.rewardXp)
        return updated
    }
}
