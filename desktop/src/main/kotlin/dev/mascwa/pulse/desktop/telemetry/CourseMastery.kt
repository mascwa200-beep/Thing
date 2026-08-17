// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/CourseMastery.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import kotlin.math.roundToInt

/**
 * A course you can see the whole of, one skill at a time.
 *
 * [Curriculum] lays out an ordered path and [StudyProgress] judges a single guide; neither answers the
 * question a learner actually has, which is *"where am I in this, what have I got, and what should I do
 * next?"*. This joins them into a map: every step of a path carried alongside how well it is known, what
 * is unlocked, and a single honest percentage for the whole course.
 *
 * The model is mastery learning as Khan Academy popularised it — you do not move on because a video
 * ended, you move on because a skill is demonstrably known, and the ladder is visible the whole time.
 * Two departures, both deliberate:
 *
 * - **Nothing is locked.** Khan gates later units behind earlier ones. Here a skill can be *suggested*
 *   and never *forbidden*: this is a reference library somebody may open in an emergency, and refusing
 *   to show a page because a prerequisite is unfinished would be indefensible.
 * - **Mastery decays.** A skill that has not been asked in a long time is not still "mastered" simply
 *   because it once was — [Recall] already schedules the re-asking, so the map reflects it.
 *
 * Pure and deterministic; every clock arrives as a parameter.
 */
object CourseMastery {

    /** One skill on the course map: a guide, where it sits, and how well it is known. */
    data class Skill(
        val guideId: String,
        val title: String,
        val category: String,
        val position: Int,
        val level: StudyProgress.Level,
        /** Cards held for this guide, and how many are ready to be asked right now. */
        val cards: Int = 0,
        val due: Int = 0,
        /** True when the guide has been read/taught but never asked — the "begin here" state. */
        val taught: Boolean = false,
    ) {
        val points: Int get() = POINTS[level] ?: 0

        /** What to do about this skill, in the imperative. */
        fun callToAction(): String = when {
            !taught && cards == 0 -> "Start"
            due > 0 -> "Practise"
            level == StudyProgress.Level.SHAKY -> "Shore up"
            level == StudyProgress.Level.MASTERED -> "Keep sharp"
            else -> "Practise"
        }
    }

    /**
     * The whole course, seen at once.
     *
     * @param mastered/[proficient]/[started] skill counts by band, so a dashboard need not re-derive them.
     */
    data class Course(
        val goal: String,
        val skills: List<Skill>,
        val mastered: Int,
        val proficient: Int,
        val started: Int,
    ) {
        val total: Int get() = skills.size
        val isEmpty: Boolean get() = skills.isEmpty()

        /**
         * 0..100. Points-weighted rather than a count of finished skills, so real progress inside a
         * skill shows up. Counting only "mastered" leaves the bar at zero for weeks, which is the
         * fastest way to make somebody stop.
         */
        val percent: Int
            get() {
                if (skills.isEmpty()) return 0
                val got = skills.sumOf { it.points }
                val max = skills.size * MAX_POINTS
                return ((got.toDouble() / max) * 100).roundToInt().coerceIn(0, 100)
            }

        /** Skills with something ready to be asked, most overdue first. */
        fun dueNow(): List<Skill> = skills.filter { it.due > 0 }.sortedByDescending { it.due }

        /**
         * What to do next, and why — one skill, chosen the way a tutor would.
         *
         * Order of preference: something ready for review, then something shaky, then the first thing
         * not yet started. Reviews come first because a skill slipping away costs more than a skill not
         * yet begun.
         */
        fun nextUp(): Skill? = dueNow().firstOrNull()
            ?: skills.firstOrNull { it.level == StudyProgress.Level.SHAKY }
            ?: skills.firstOrNull { it.level == StudyProgress.Level.UNSEEN }
            ?: skills.firstOrNull { it.level != StudyProgress.Level.MASTERED }

        fun describe(): String = when {
            skills.isEmpty() -> "Nothing on this course yet."
            mastered == skills.size -> "Every skill mastered. Reviews will keep it that way."
            else -> "$percent% · $mastered of ${skills.size} mastered, $started started"
        }
    }

    /**
     * Build the map for a composed path.
     *
     * @param levels how each guide is doing, by guide id — the caller looks these up because only it
     *   knows which cards belong to which guide.
     * @param cardsPerGuide/[duePerGuide] counts by guide id; absent means zero.
     * @param taughtIds guides that have been read or turned into questions at least once.
     */
    fun course(
        syllabus: Curriculum.Syllabus,
        levels: Map<String, StudyProgress.Level>,
        cardsPerGuide: Map<String, Int> = emptyMap(),
        duePerGuide: Map<String, Int> = emptyMap(),
        taughtIds: Set<String> = emptySet(),
    ): Course {
        val skills = syllabus.steps.map { step ->
            Skill(
                guideId = step.guideId,
                title = step.title,
                category = step.category,
                position = step.position,
                level = levels[step.guideId] ?: StudyProgress.Level.UNSEEN,
                cards = cardsPerGuide[step.guideId] ?: 0,
                due = duePerGuide[step.guideId] ?: 0,
                taught = step.guideId in taughtIds,
            )
        }
        return Course(
            goal = syllabus.goal,
            skills = skills,
            mastered = skills.count { it.level == StudyProgress.Level.MASTERED },
            proficient = skills.count { it.level >= StudyProgress.Level.SOLID },
            started = skills.count { it.level != StudyProgress.Level.UNSEEN },
        )
    }

    /**
     * Points per mastery band — the ladder the percentage is weighted by.
     *
     * ⚠️ INTRODUCED is worth something on purpose. A learner who has been taught a skill and answered
     * once has done real work, and a scale that pays nothing until "solid" makes the first week look
     * identical to doing nothing at all.
     */
    private val POINTS: Map<StudyProgress.Level, Int> = mapOf(
        StudyProgress.Level.UNSEEN to 0,
        StudyProgress.Level.INTRODUCED to 1,
        StudyProgress.Level.SHAKY to 1,
        StudyProgress.Level.LEARNING to 2,
        StudyProgress.Level.SOLID to 3,
        StudyProgress.Level.MASTERED to 4,
    )

    const val MAX_POINTS = 4
}
