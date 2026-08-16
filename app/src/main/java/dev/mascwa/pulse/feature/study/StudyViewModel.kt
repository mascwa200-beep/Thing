package dev.mascwa.pulse.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Curriculum
import dev.mascwa.pulse.core.telemetry.DailyLesson
import dev.mascwa.pulse.core.telemetry.Recall
import dev.mascwa.pulse.core.telemetry.StudyQuestions
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.data.study.StudyStore
import dev.mascwa.pulse.data.study.localDayIndex
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The study surface: what to learn now, what is due to be asked again, and an enrolled path.
 *
 * Holds one review at a time rather than a list, because a session is answered one question at a
 * time and a screen showing the next four prompts is a screen showing you four answers.
 */
class StudyViewModel(private val container: AppContainer) : ViewModel() {

    private val study: StudyStore get() = container.studyStore

    data class UiState(
        val loading: Boolean = true,
        val lesson: DailyLesson.Lesson? = null,
        val dueCount: Int = 0,
        /** The question on screen, or null when the session is finished or not started. */
        val asking: StudyQuestions.Question? = null,
        /** Whether the answer is showing — self-grading only works after you have committed. */
        val revealed: Boolean = false,
        /** "in 3 days" — what the last answer did to the schedule. Cleared on the next question. */
        val scheduled: String? = null,
        val syllabus: Curriculum.Syllabus? = null,
        val completed: Set<String> = emptySet(),
        val suggestions: List<String> = emptyList(),
        /** Total questions held and how many are learned — the only honest progress number here. */
        val held: Int = 0,
        val learned: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val interests = runCatching {
                container.profileStore.all().sortedByDescending { it.weight }.map { it.text }
            }.getOrDefault(emptyList())
            val tasks = runCatching {
                TaskBoard.pending(container.taskStore.all()).map { it.title }
            }.getOrDefault(emptyList())

            val lesson = runCatching { study.today(interests, tasks, localDayIndex()) }.getOrNull()
            val syllabus = runCatching { study.syllabus() }.getOrNull()
            val completed = runCatching { study.completedIds() }.getOrDefault(emptySet())
            val suggestions = runCatching { study.suggestedGoals() }.getOrDefault(emptyList())
            val items = study.items.value
            _state.update {
                it.copy(
                    loading = false,
                    lesson = lesson,
                    dueCount = runCatching { study.dueCount() }.getOrDefault(0),
                    syllabus = syllabus,
                    completed = completed,
                    suggestions = suggestions,
                    held = items.size,
                    learned = items.count { i -> Recall.isLearned(i.card) },
                )
            }
        }
    }

    /** Turn today's guide into questions and go straight into answering them. */
    fun teachToday() {
        val guideId = _state.value.lesson?.guideId?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            runCatching { study.teach(guideId) }
            nextQuestion()
            refresh()
        }
    }

    fun startReview() {
        viewModelScope.launch { nextQuestion() }
    }

    fun reveal() {
        _state.update { it.copy(revealed = true) }
    }

    /**
     * Record an answer and move on.
     *
     * The schedule it produces is shown rather than hidden: "in 3 days" is the feedback that makes
     * grading yourself honestly worth doing.
     */
    fun answer(grade: Recall.Grade) {
        val id = _state.value.asking?.id ?: return
        viewModelScope.launch {
            val next = runCatching { study.grade(id, grade) }.getOrNull()
            _state.update { it.copy(scheduled = next?.let { c -> Recall.describeInterval(c.intervalDays) }) }
            nextQuestion(keepScheduled = true)
            refresh()
        }
    }

    fun enroll(goal: String) {
        viewModelScope.launch {
            runCatching { study.enroll(goal) }
            refresh()
        }
    }

    fun abandonGoal() {
        viewModelScope.launch {
            runCatching { study.abandonGoal() }
            refresh()
        }
    }

    /** Mark a guide read without being asked about it — the "I have read this" action. */
    fun markRead(guideId: String) {
        viewModelScope.launch {
            runCatching { study.markRead(guideId) }
            refresh()
        }
    }

    fun endSession() {
        _state.update { it.copy(asking = null, revealed = false, scheduled = null) }
    }

    private suspend fun nextQuestion(keepScheduled: Boolean = false) {
        val next = runCatching { study.due(limit = 1) }.getOrDefault(emptyList()).firstOrNull()
        _state.update {
            it.copy(
                asking = next?.question,
                revealed = false,
                scheduled = if (keepScheduled) it.scheduled else null,
            )
        }
    }
}
