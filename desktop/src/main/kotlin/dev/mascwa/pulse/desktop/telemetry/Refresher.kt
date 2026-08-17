// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Refresher.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import kotlin.math.roundToLong

/**
 * Coming back after time away.
 *
 * ⚠️ **The cap is the feature, not a limitation of it.** Plain spaced repetition has no opinion about
 * absence: stay away a fortnight and every card that fell due arrives at once, so the first thing you
 * see on returning is a backlog of two hundred. That pile is the single most common reason people
 * abandon a review habit — the schedule is technically correct and practically hostile. This rations it
 * instead: a short, ordered, finite way back in, and an honest note about what is being held aside.
 *
 * The order is a teaching decision, not a sort. After a long absence the plan **opens with something
 * you know**, because starting a cold return on your worst material is how a return becomes a last
 * visit. Then the weak material, then what has genuinely decayed, then the ordinary overdue.
 *
 * Pure and deterministic: every clock arrives as a parameter, so CI holds the whole thing.
 */
object Refresher {

    /** How long you have been gone, in the only bands that change what should happen. */
    enum class Layoff {
        /** Recently enough that the ordinary schedule is still the right answer. */
        NONE,

        /** A few days. A slightly longer sitting, nothing else. */
        SHORT,

        /** A week or more. Warm up first, and expect a backlog. */
        LONG,

        /** A month or more. The gentlest re-entry there is. */
        COLD,
    }

    /** Why a step is in the plan. Shown, because "do these eight" with no reason is just a chore. */
    enum class Reason {
        /** Something you are good at, first, to start on a win. */
        WARMUP,

        /** A guide you have been getting wrong. */
        WEAK,

        /** Left far longer than the gap it was scheduled for — likely genuinely forgotten. */
        DECAYED,

        /** Ordinary backlog: it fell due while you were away. */
        OVERDUE,
    }

    /** One card, and one guide, as the caller holds them — [Recall.Card] knows nothing about guides. */
    data class Item(val guideId: String, val guideTitle: String, val card: Recall.Card)

    /** One thing to do, and why. */
    data class Step(val item: Item, val reason: Reason) {
        fun note(): String = when (reason) {
            Reason.WARMUP -> "you know this one — start here"
            Reason.WEAK -> "this has been going badly"
            Reason.DECAYED -> "left much longer than it was meant to be"
            Reason.OVERDUE -> "fell due while you were away"
        }
    }

    /**
     * @param heldBack how many due cards are deliberately **not** in [steps]. Stated rather than hidden:
     *   a plan that silently omits a backlog is a plan you stop trusting the moment you notice.
     */
    data class Plan(
        val layoff: Layoff,
        val awayMs: Long,
        val steps: List<Step>,
        val dueTotal: Int,
        val heldBack: Int,
    ) {
        fun headline(): String = when (layoff) {
            Layoff.NONE -> "Back to it"
            Layoff.SHORT -> "${describeAway(awayMs)} away — an easy way back"
            Layoff.LONG -> "${describeAway(awayMs)} away — let's get back up to speed"
            Layoff.COLD -> "${describeAway(awayMs)} away — starting gently"
        }

        fun note(): String = when {
            heldBack <= 0 -> "${steps.size} to work through."
            else -> "$dueTotal came due while you were away. These ${steps.size} first; the rest can wait."
        }
    }

    /** Which band [nowMs] falls into, given when you last studied. A zero last-study means never. */
    fun layoff(lastStudiedAtMs: Long, nowMs: Long): Layoff {
        if (lastStudiedAtMs <= 0L) return Layoff.NONE
        val away = nowMs - lastStudiedAtMs
        return when {
            away < SHORT_FROM_MS -> Layoff.NONE
            away < LONG_FROM_MS -> Layoff.SHORT
            away < COLD_FROM_MS -> Layoff.LONG
            else -> Layoff.COLD
        }
    }

    /**
     * The way back in, or null when there is nothing to come back to.
     *
     * Null in two cases, both of which mean the ordinary screen is the right answer: you have not been
     * away, or nothing is due. Deliberately does **not** invent something to re-read in the second case
     * — [DailyLesson] already decides what to offer when there is no queue, and two things choosing that
     * would eventually disagree.
     */
    fun plan(
        items: List<Item>,
        attempts: List<StudyProgress.Attempt>,
        lastStudiedAtMs: Long,
        nowMs: Long,
    ): Plan? {
        val layoff = layoff(lastStudiedAtMs, nowMs)
        if (layoff == Layoff.NONE) return null

        val due = items.filter { it.card.dueAtMs <= nowMs }
        if (due.isEmpty()) return null

        val limit = when (layoff) {
            Layoff.NONE -> 0
            Layoff.SHORT -> SHORT_STEPS
            Layoff.LONG -> LONG_STEPS
            // ⚠️ Fewer than LONG, on purpose. A month out, the plan has to look doable more than it has
            // to be thorough — the backlog is not going anywhere, and the person might.
            Layoff.COLD -> COLD_STEPS
        }

        val chosen = LinkedHashMap<String, Step>()
        val perGuide = HashMap<String, Int>()

        fun take(item: Item, reason: Reason): Boolean {
            if (chosen.size >= limit) return false
            if (item.card.id in chosen) return false
            // Interleaving, and a guard against a plan that is eight cards from one guide — which reads
            // as being made to redo a chapter rather than being brought back up to speed.
            val used = perGuide[item.guideId] ?: 0
            if (used >= MAX_PER_GUIDE) return false
            chosen[item.card.id] = Step(item, reason)
            perGuide[item.guideId] = used + 1
            return true
        }

        // 1. A win first, but only when the absence was long enough to need one.
        if (layoff == Layoff.LONG || layoff == Layoff.COLD) {
            warmUp(due, attempts)?.let { take(it, Reason.WARMUP) }
        }

        // 2. What has been going badly, weakest guide first.
        val weakOrder = StudyProgress.weakest(attempts, limit = Int.MAX_VALUE).map { it.guideId }
        for (guideId in weakOrder) {
            due.filter { it.guideId == guideId }
                .sortedBy { it.card.dueAtMs }
                .forEach { take(it, Reason.WEAK) }
        }

        // 3. What the absence itself is likely to have cost: left far longer than its own gap.
        due.filter { decayed(it.card, nowMs) }
            .sortedByDescending { overdueRatio(it.card, nowMs) }
            .forEach { take(it, Reason.DECAYED) }

        // 4. Ordinary backlog, most overdue first — the same order the normal queue uses.
        due.sortedBy { it.card.dueAtMs }.forEach { take(it, Reason.OVERDUE) }

        val steps = chosen.values.toList()
        return Plan(
            layoff = layoff,
            awayMs = (nowMs - lastStudiedAtMs).coerceAtLeast(0L),
            steps = steps,
            dueTotal = due.size,
            heldBack = (due.size - steps.size).coerceAtLeast(0),
        )
    }

    /**
     * The due card most likely to go well: from the guide with the best record, and the most established
     * card in it. Null when nothing due comes from a guide with enough answers behind it to be confident
     * about — an invented warm-up that turns out to be hard is worse than no warm-up.
     */
    internal fun warmUp(due: List<Item>, attempts: List<StudyProgress.Attempt>): Item? {
        val strong = StudyProgress.byGuide(attempts)
            .filter { it.answered >= StudyProgress.MIN_JUDGEABLE && it.accuracy >= WARMUP_ACCURACY }
            .sortedByDescending { it.accuracy }
        for (guide in strong) {
            val best = due.filter { it.guideId == guide.guideId }.maxByOrNull { it.card.intervalDays }
            if (best != null) return best
        }
        return null
    }

    /** Left longer than the gap it was scheduled for — the memory has had time to actually go. */
    internal fun decayed(card: Recall.Card, nowMs: Long): Boolean {
        if (card.intervalDays <= 0.0) return false
        val overdueMs = nowMs - card.dueAtMs
        return overdueMs > (card.intervalDays * Recall.DAY_MS * DECAY_FACTOR).roundToLong()
    }

    /** How far past due, as a multiple of its own interval — comparable across fast and slow cards. */
    internal fun overdueRatio(card: Recall.Card, nowMs: Long): Double {
        val interval = (card.intervalDays * Recall.DAY_MS).coerceAtLeast(Recall.DAY_MS)
        return (nowMs - card.dueAtMs) / interval
    }

    /**
     * "4 days", "3 weeks", "2 months". A span, not an age — [ElapsedPhrase] says "ago", which reads
     * wrongly in "3 weeks ago away". Built by concatenation: whole numbers, so no locale is involved.
     */
    fun describeAway(ms: Long): String {
        val days = (ms / DAY_MS).toInt()
        return when {
            days < 1 -> "Hardly any time"
            days == 1 -> "A day"
            days < 14 -> "$days days"
            days < 60 -> "${(days / 7.0).roundToLong()} weeks"
            days < 365 -> "${(days / 30.0).roundToLong()} months"
            else -> "Over a year"
        }
    }

    private const val DAY_MS = 86_400_000L

    /** Two days is a weekend, not an absence. */
    const val SHORT_FROM_MS = 2 * DAY_MS
    const val LONG_FROM_MS = 7 * DAY_MS
    const val COLD_FROM_MS = 30 * DAY_MS

    const val SHORT_STEPS = 5
    const val LONG_STEPS = 8
    const val COLD_STEPS = 6

    /** No more than this many from any one guide, so a plan interleaves rather than re-runs a chapter. */
    const val MAX_PER_GUIDE = 3

    /** How well a guide must have gone for one of its cards to be trusted as the opening win. */
    const val WARMUP_ACCURACY = 0.75

    /** How far past its own interval a card must sit before the absence counts as having cost it. */
    const val DECAY_FACTOR = 1.0
}
