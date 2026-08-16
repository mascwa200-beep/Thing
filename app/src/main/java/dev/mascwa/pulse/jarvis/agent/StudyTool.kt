package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.DailyLesson
import dev.mascwa.pulse.core.telemetry.Recall
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.data.profile.ProfileStore
import dev.mascwa.pulse.data.study.StudyStore
import dev.mascwa.pulse.data.study.localDayIndex
import dev.mascwa.pulse.data.tasks.TaskStore

/**
 * Lets the computer teach from the bundled library, and be asked about it aloud.
 *
 * Every other study surface is a screen. This is the one that works by voice and in the console, so
 * "what should I learn today", "how far through am I", "start teaching me first aid" and "what am I
 * due to review" are answerable without opening anything.
 *
 * ⚠️ **It never invents a question.** Everything it returns is extracted from written guide prose by
 * [dev.mascwa.pulse.core.telemetry.StudyQuestions] or is the schedule [Recall] computed. A model
 * fluent enough to make up a plausible flashcard is exactly what must not be teaching you facts.
 */
class StudyTool(
    private val study: StudyStore,
    private val profile: ProfileStore,
    private val tasks: TaskStore,
) : JarvisTool {

    override val name = "study"

    override val usage =
        "study [today|due|path|goals|enroll <goal>|teach <guide-id>] — what to learn now from the " +
            "581-guide offline library, what is due for review, an enrolled path and its progress. " +
            "'today' is the default; 'enroll' starts a path; 'teach' turns a guide into questions."

    override suspend fun run(arg: String): String = runCatching {
        val input = arg.trim()
        val verb = input.substringBefore(' ').lowercase()
        val rest = input.substringAfter(' ', "").trim()
        when {
            input.isEmpty() || verb == "today" -> today()
            verb == "due" -> due()
            verb == "path" -> path()
            verb == "goals" -> goals()
            verb == "enroll" || verb == "enrol" -> enroll(rest)
            verb == "teach" -> teach(rest)
            // An unrecognised word is far more likely to be a goal than a typo of a verb.
            else -> today()
        }
    }.getOrElse { "Couldn't reach the study log right now." }

    private suspend fun today(): String {
        val lesson = study.today(interests(), pending(), localDayIndex()) ?: return NOTHING
        return buildString {
            append(lesson.headline)
            append(" — ").append(lesson.reason)
            if (lesson.kind == DailyLesson.Kind.REVIEW) {
                append("\nAsk 'study due' for the questions.")
            } else {
                append("\nGuide id: ").append(lesson.guideId)
                if (lesson.category.isNotBlank()) append(" (").append(lesson.category).append(")")
                append("\nAsk 'study teach ").append(lesson.guideId).append("' to turn it into questions.")
            }
        }
    }

    private suspend fun due(): String {
        val items = study.due()
        if (items.isEmpty()) return "Nothing is due for review."
        return buildString {
            append(items.size).append(" due:")
            items.forEach { item ->
                append("\n- ").append(item.question.prompt)
                append("\n  answer: ").append(item.question.answer.take(ANSWER_CHARS))
                append("  [").append(item.question.guideTitle).append(" ▸ ").append(item.question.heading).append("]")
            }
        }
    }

    private suspend fun path(): String {
        val s = study.syllabus() ?: return "No study path yet. Ask 'study goals' for what the library can teach."
        if (s.isEmpty) return "Nothing in the library matched \"${s.goal}\"."
        val completed = study.completedIds()
        return buildString {
            append("Path to ").append(s.goal).append(" — ").append(s.describeProgress(completed))
            append(" (").append(s.days).append(" sittings)")
            s.next(completed, count = LISTED).forEach { step ->
                append("\n- ").append(step.position).append(". ").append(step.title)
                append(" — ").append(step.why).append(" [id: ").append(step.guideId).append("]")
            }
            append("\n").append(s.note)
        }
    }

    private suspend fun goals(): String {
        val g = study.suggestedGoals()
        if (g.isEmpty()) return "The library isn't loaded yet."
        return "Goals this library can genuinely teach:\n- " + g.joinToString("\n- ") +
            "\nOr any subject — 'study enroll <goal>'."
    }

    private suspend fun enroll(goal: String): String {
        if (goal.isBlank()) return "Say what to learn, e.g. 'study enroll first aid and emergencies'."
        study.enroll(goal)
        val s = study.syllabus()
        if (s == null || s.isEmpty) return "Nothing in the library matched \"$goal\"."
        return "Enrolled: ${s.goal} — ${s.steps.size} guides, ${s.days} sittings. First up: " +
            "${s.steps.first().title} [id: ${s.steps.first().guideId}]"
    }

    private suspend fun teach(guideId: String): String {
        if (guideId.isBlank()) return "Say which guide, e.g. 'study teach first-aid'."
        val made = study.teach(guideId)
        if (made.isEmpty()) return "No guide with id \"$guideId\", or nothing in it could be turned into a question."
        return buildString {
            append("Ready — ").append(made.size).append(" question")
            if (made.size != 1) append("s")
            append(" from ").append(made.first().guideTitle).append(", due now:")
            made.forEach { append("\n- ").append(it.prompt) }
        }
    }

    private suspend fun interests(): List<String> =
        runCatching { profile.all().sortedByDescending { it.weight }.map { it.text } }.getOrDefault(emptyList())

    private suspend fun pending(): List<String> =
        runCatching { TaskBoard.pending(tasks.all()).map { it.title } }.getOrDefault(emptyList())

    private companion object {
        const val NOTHING = "Nothing to study — the library hasn't loaded, or everything in it has been offered."
        const val ANSWER_CHARS = 240
        const val LISTED = 3
    }
}
