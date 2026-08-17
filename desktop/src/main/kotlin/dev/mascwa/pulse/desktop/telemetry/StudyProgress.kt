// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/StudyProgress.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How your studying is actually going.
 *
 * [Recall] knows when each individual card is next due; nothing until now knew anything about **you** —
 * how long you have really spent, how many questions you have answered, how many of them you got right,
 * or how long it has been since you last sat down. That is what turns a queue of flashcards into
 * progress somebody can see, and it is the input the refresher needs to decide what to bring back.
 *
 * ⚠️ **Idle is not study, and this is the whole difficulty of measuring it.** A screen left open on the
 * bus, in a pocket, or overnight would otherwise report hours of diligent work. Every figure here is
 * therefore an *upper bound justified by evidence of activity*: a session credits wall-clock time only
 * up to an allowance that grows with what you actually did in it (see [creditedMs]). A flattering
 * number would be worse than no number, because the point of the figure is to be trusted.
 *
 * Pure and deterministic: no clock is read, and "today" arrives as a day index rather than being derived
 * here — a day boundary computed from UTC inside a shared module is a day out for half the planet, the
 * same reason [DailyLesson] takes one.
 */
object StudyProgress {

    /**
     * One question, answered, and whether it was right.
     *
     * Objective correctness is only knowable because questions became multiple choice ([QuizBuilder]);
     * a self-graded answer records how it *felt*, which is a different measurement and not one you can
     * compute a ratio from.
     *
     * @param elapsedMs how long that single question took, from being shown to being answered.
     */
    data class Attempt(
        val questionId: String,
        val guideId: String,
        val correct: Boolean,
        val atMs: Long,
        val elapsedMs: Long = 0L,
    )

    /** What a session was spent on. Reading a guide is studying; it is just paced differently. */
    enum class SessionKind {
        /** Being asked. Time is justified by answers. */
        QUESTIONS,

        /** Reading a guide. Slower, and legitimately quiet for long stretches. */
        READING,
    }

    /**
     * One continuous span with a study surface open.
     *
     * @param attempts how many questions were answered inside it — the evidence that justifies crediting
     *   the span as study rather than as an open screen.
     */
    data class Session(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val attempts: Int = 0,
        val kind: SessionKind = SessionKind.QUESTIONS,
    ) {
        /** Wall-clock span. Not the credited figure — see [creditedMs]. */
        val openMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)
    }

    /** How well a single guide is known. Ordered, so comparisons read naturally. */
    enum class Level {
        /** Never taught, never asked. */
        UNSEEN,

        /** Taught, but not yet asked enough times to say anything honest about it. */
        INTRODUCED,

        /** Being got wrong more often than is comfortable. */
        SHAKY,

        /** Going fine, but the schedule has not stretched out yet. */
        LEARNING,

        /** Answered well and the gaps have widened. */
        SOLID,

        /** Every card at a long interval, and answered right almost every time. */
        MASTERED,
    }

    /** A guide's record: what was asked of it and how it went. */
    data class GuideAccuracy(val guideId: String, val answered: Int, val correct: Int) {
        val incorrect: Int get() = answered - correct

        /** 0..1. Zero when nothing was asked — callers should check [answered] before reading it. */
        val accuracy: Double get() = if (answered == 0) 0.0 else correct.toDouble() / answered
    }

    /** One guide, judged. */
    data class Mastery(
        val guideId: String,
        val level: Level,
        val answered: Int,
        val correct: Int,
        val learnedCards: Int,
        val totalCards: Int,
    ) {
        val accuracy: Double get() = if (answered == 0) 0.0 else correct.toDouble() / answered

        /** Plain English, because "0.73" is a number and not an answer to "how am I doing". */
        fun describe(): String = when (level) {
            Level.UNSEEN -> "Not started"
            Level.INTRODUCED -> "Just introduced — $answered answered so far"
            Level.SHAKY -> "Shaky — $correct of $answered right"
            Level.LEARNING -> "Learning — $correct of $answered right"
            Level.SOLID -> "Solid — $correct of $answered right"
            Level.MASTERED -> "Mastered — $correct of $answered right"
        }
    }

    /** Everything a progress panel needs, computed once. */
    data class Snapshot(
        val answered: Int,
        val correct: Int,
        val studiedMs: Long,
        val activeDays: Int,
        val streakDays: Int,
        val lastStudiedAtMs: Long,
        val recentAnswered: Int,
        val recentCorrect: Int,
    ) {
        val incorrect: Int get() = answered - correct
        val accuracy: Double get() = if (answered == 0) 0.0 else correct.toDouble() / answered

        /** Over the last [RECENT_WINDOW] answers — how it is going *now*, not how it averages out. */
        val recentAccuracy: Double
            get() = if (recentAnswered == 0) 0.0 else recentCorrect.toDouble() / recentAnswered

        val hasHistory: Boolean get() = answered > 0 || studiedMs > 0L

        /** The correct-to-incorrect record, said the way a person would read it. */
        fun describeRatio(): String =
            if (answered == 0) "nothing answered yet" else "$correct right · $incorrect wrong (${percent(accuracy)}%)"

        /**
         * Whether recent answers are running better or worse than the lifetime average.
         *
         * Null until there is enough recent evidence to say — a two-question sample swinging the
         * verdict would make the line noise rather than information.
         */
        fun trend(): String? {
            if (recentAnswered < MIN_JUDGEABLE || answered <= recentAnswered) return null
            val delta = recentAccuracy - accuracy
            return when {
                delta >= TREND_DELTA -> "improving"
                delta <= -TREND_DELTA -> "slipping"
                else -> "steady"
            }
        }
    }

    // ---- time -----------------------------------------------------------------------------------------

    /**
     * How much of a session's wall-clock span counts as study.
     *
     * The allowance is a base — you can plausibly sit and think for a few minutes without touching
     * anything — plus a per-answer share. So an open screen with nothing happening tops out at the base,
     * a busy quarter of an hour is credited in full, and an app left open all night credits the same as
     * the work done in it. Reading gets a much larger base and no per-answer term, because reading a long
     * guide is genuinely quiet.
     */
    fun creditedMs(session: Session): Long {
        val allowance = when (session.kind) {
            SessionKind.READING -> READING_ALLOWANCE_MS
            SessionKind.QUESTIONS -> OPEN_ALLOWANCE_MS + session.attempts.coerceAtLeast(0) * PER_ANSWER_MS
        }
        return min(session.openMs, allowance)
    }

    /** Total credited study time across [sessions]. */
    fun studiedMs(sessions: List<Session>): Long = sessions.sumOf { creditedMs(it) }

    /**
     * A duration, compactly. Not "ago" — [ElapsedPhrase] says that, and this is a total, not an age.
     *
     * Built by concatenation rather than a format string: these are whole numbers, and a locale-aware
     * format is how a decimal comma ends up in a figure that has no decimals.
     */
    fun describeStudied(ms: Long): String {
        if (ms < MINUTE_MS) return "under a minute"
        val minutes = ms / MINUTE_MS
        if (minutes < 60) return "${minutes}m"
        return "${minutes / 60}h ${minutes % 60}m"
    }

    // ---- days -----------------------------------------------------------------------------------------

    /**
     * Consecutive days of study ending now.
     *
     * ⚠️ Anchored on today **or yesterday**. Requiring today would report every streak as broken from
     * midnight until you next opened the app — punishing you for the day not having happened yet, which
     * is exactly the kind of dishonest zero that makes people stop looking at the number.
     */
    fun streak(activeDays: Set<Int>, todayIndex: Int): Int {
        var day = when {
            todayIndex in activeDays -> todayIndex
            (todayIndex - 1) in activeDays -> todayIndex - 1
            else -> return 0
        }
        var run = 0
        while (day in activeDays) {
            run++
            day--
        }
        return run
    }

    // ---- the whole picture ------------------------------------------------------------------------------

    /**
     * @param dayOf maps an instant to a whole local day index. Supplied by the caller for the reason in
     *   the class note.
     */
    fun summarise(
        attempts: List<Attempt>,
        sessions: List<Session>,
        todayIndex: Int,
        dayOf: (Long) -> Int,
    ): Snapshot {
        val ordered = attempts.sortedBy { it.atMs }
        val recent = ordered.takeLast(RECENT_WINDOW)
        val days = HashSet<Int>()
        for (a in ordered) days += dayOf(a.atMs)
        // A session that was only ever read in still marks the day as studied.
        for (s in sessions) if (creditedMs(s) > 0L) days += dayOf(s.startedAtMs)

        val lastAttempt = ordered.lastOrNull()?.atMs ?: 0L
        val lastSession = sessions.maxOfOrNull { it.endedAtMs } ?: 0L

        return Snapshot(
            answered = ordered.size,
            correct = ordered.count { it.correct },
            studiedMs = studiedMs(sessions),
            activeDays = days.size,
            streakDays = streak(days, todayIndex),
            lastStudiedAtMs = maxOf(lastAttempt, lastSession),
            recentAnswered = recent.size,
            recentCorrect = recent.count { it.correct },
        )
    }

    /** Per-guide records, most-answered first. */
    fun byGuide(attempts: List<Attempt>): List<GuideAccuracy> = attempts
        .groupBy { it.guideId }
        .map { (id, list) -> GuideAccuracy(id, list.size, list.count { it.correct }) }
        .sortedByDescending { it.answered }

    /**
     * The guides going worst, weakest first — what a refresher should reach for.
     *
     * @param minAnswers evidence bar. Without it a single wrong answer on something barely touched tops
     *   the list forever, which would send the refresher after noise instead of after weakness.
     */
    fun weakest(
        attempts: List<Attempt>,
        limit: Int = DEFAULT_WEAKEST,
        minAnswers: Int = MIN_JUDGEABLE,
    ): List<GuideAccuracy> = byGuide(attempts)
        .filter { it.answered >= minAnswers && it.incorrect > 0 }
        // Ties broken by evidence: two guides at 50% are not equally well established.
        .sortedWith(compareBy<GuideAccuracy> { it.accuracy }.thenByDescending { it.answered })
        .take(limit)

    /**
     * How well one guide is known, from what was answered **and** how far its schedule has stretched.
     *
     * Accuracy alone would call a guide mastered the day it was taught; intervals alone would call it
     * mastered for having been answered "GOOD" four times without ever checking whether the answers were
     * right. Both together is the only honest reading, which is the point of this arc.
     *
     * @param cards that guide's cards, already filtered by the caller — [Recall.Card] carries a question
     *   id, not a guide id, and only the store knows which is which.
     */
    fun mastery(guideId: String, attempts: List<Attempt>, cards: List<Recall.Card>): Mastery {
        val mine = attempts.filter { it.guideId == guideId }
        val answered = mine.size
        val correct = mine.count { it.correct }
        val learned = cards.count { Recall.isLearned(it) }
        val accuracy = if (answered == 0) 0.0 else correct.toDouble() / answered

        val level = when {
            answered == 0 && cards.isEmpty() -> Level.UNSEEN
            answered < MIN_JUDGEABLE -> Level.INTRODUCED
            accuracy < SHAKY_BELOW -> Level.SHAKY
            cards.isNotEmpty() && learned == cards.size && accuracy >= MASTERED_FROM -> Level.MASTERED
            accuracy >= SOLID_FROM && cards.isNotEmpty() && learned * 2 >= cards.size -> Level.SOLID
            else -> Level.LEARNING
        }
        return Mastery(guideId, level, answered, correct, learned, cards.size)
    }

    /** 0..1 as a whole percent, for display. Rounded, so 0.999 does not read as 99. */
    fun percent(fraction: Double): Int = (fraction.coerceIn(0.0, 1.0) * 100).roundToInt()

    private const val MINUTE_MS = 60_000L

    /** Thinking time an open study screen is allowed without any answers to show for it. */
    const val OPEN_ALLOWANCE_MS = 4 * MINUTE_MS

    /** What each answer buys. Generous enough to cover reading the question and its explanation. */
    const val PER_ANSWER_MS = 90_000L

    /** A long guide is a genuinely long read; beyond this it is a screen someone walked away from. */
    const val READING_ALLOWANCE_MS = 25 * MINUTE_MS

    /** How many recent answers "how it is going now" looks at. */
    const val RECENT_WINDOW = 20

    /** Below this many answers, no verdict is offered — a small sample is not a weakness. */
    const val MIN_JUDGEABLE = 4

    /** How far recent accuracy must diverge from lifetime before the trend is worth stating. */
    const val TREND_DELTA = 0.1

    const val SHAKY_BELOW = 0.6
    const val SOLID_FROM = 0.8
    const val MASTERED_FROM = 0.9

    const val DEFAULT_WEAKEST = 5
}
