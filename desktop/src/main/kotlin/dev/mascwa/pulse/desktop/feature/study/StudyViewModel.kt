package dev.mascwa.pulse.desktop.feature.study

import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.desktop.telemetry.Curriculum
import dev.mascwa.pulse.desktop.telemetry.DailyLesson
import dev.mascwa.pulse.desktop.telemetry.Recall
import dev.mascwa.pulse.desktop.telemetry.StudyQuestions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StudyUiState(
    val loading: Boolean = true,
    val lesson: DailyLesson.Lesson? = null,
    val dueCount: Int = 0,
    /** The question on screen, or null when the session is finished or not started. */
    val asking: StudyQuestions.Question? = null,
    /** Whether the answer is showing — self-grading only works after you have committed. */
    val revealed: Boolean = false,
    /** "in 3 days" — what the last answer did to the schedule. */
    val scheduled: String? = null,
    val syllabus: Curriculum.Syllabus? = null,
    val completed: Set<String> = emptySet(),
    val suggestions: List<String> = emptyList(),
    val held: Int = 0,
    val learned: Int = 0,
)

/**
 * Drives the Study screen.
 *
 * A plain class holding a [StateFlow] — the module's convention, since Compose Desktop has no AndroidX
 * ViewModel. Holds one question at a time rather than a list: a session is answered one at a time, and
 * a screen showing the next four prompts is a screen showing you four answers.
 */
class StudyViewModel(
    private val scope: CoroutineScope,
    private val store: StudyStore,
) {
    private val _state = MutableStateFlow(StudyUiState())
    val state: StateFlow<StudyUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        scope.launch {
            val lesson = runCatching { store.today() }.getOrNull()
            val syllabus = runCatching { store.syllabus() }.getOrNull()
            val completed = runCatching { store.completedIds() }.getOrDefault(emptySet())
            val suggestions = runCatching { store.suggestedGoals() }.getOrDefault(emptyList())
            val items = store.items.value
            _state.value = _state.value.copy(
                loading = false,
                lesson = lesson,
                dueCount = runCatching { store.dueCount() }.getOrDefault(0),
                syllabus = syllabus,
                completed = completed,
                suggestions = suggestions,
                held = items.size,
                learned = items.count { Recall.isLearned(it.card) },
            )
        }
    }

    /** Turn today's guide into questions and go straight into answering them. */
    fun teachToday() {
        val guideId = _state.value.lesson?.guideId?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            runCatching { store.teach(guideId) }
            nextQuestion()
            refresh()
        }
    }

    fun startReview() {
        scope.launch { nextQuestion() }
    }

    fun reveal() {
        _state.value = _state.value.copy(revealed = true)
    }

    /** Record an answer and move on. The schedule it produces is shown, not hidden. */
    fun answer(grade: Recall.Grade) {
        val id = _state.value.asking?.id ?: return
        scope.launch {
            val next = runCatching { store.grade(id, grade) }.getOrNull()
            _state.value = _state.value.copy(
                scheduled = next?.let { Recall.describeInterval(it.intervalDays) },
            )
            nextQuestion(keepScheduled = true)
            refresh()
        }
    }

    fun enroll(goal: String) {
        scope.launch {
            runCatching { store.enroll(goal) }
            refresh()
        }
    }

    fun abandonGoal() {
        scope.launch {
            runCatching { store.abandonGoal() }
            refresh()
        }
    }

    /** Mark a guide read without being asked about it. */
    fun markRead(guideId: String) {
        scope.launch {
            runCatching { store.markRead(guideId) }
            refresh()
        }
    }

    fun endSession() {
        _state.value = _state.value.copy(asking = null, revealed = false, scheduled = null)
    }

    private suspend fun nextQuestion(keepScheduled: Boolean = false) {
        val next = runCatching { store.due(limit = 1) }.getOrDefault(emptyList()).firstOrNull()
        _state.value = _state.value.copy(
            asking = next?.question,
            revealed = false,
            scheduled = if (keepScheduled) _state.value.scheduled else null,
        )
    }
}
