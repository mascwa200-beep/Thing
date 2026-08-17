package dev.mascwa.pulse.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.CourseMastery
import dev.mascwa.pulse.core.telemetry.Curriculum
import dev.mascwa.pulse.core.telemetry.DailyLesson
import dev.mascwa.pulse.core.telemetry.Hints
import dev.mascwa.pulse.core.telemetry.PracticeSet
import dev.mascwa.pulse.core.telemetry.Recall
import dev.mascwa.pulse.core.telemetry.Refresher
import dev.mascwa.pulse.core.telemetry.StudyProgress
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
 * The study surface: what to learn now, what is due to be asked again, how it is going, and a way back
 * in after time away.
 *
 * Holds one question at a time rather than a list, because a session is answered one question at a
 * time and a screen showing the next four prompts is a screen showing you four answers.
 */
class StudyViewModel(private val container: AppContainer) : ViewModel() {

    private val study: StudyStore get() = container.studyStore

    data class UiState(
        val loading: Boolean = true,
        val lesson: DailyLesson.Lesson? = null,
        val dueCount: Int = 0,
        /** The question on screen, with its multiple choice when one could be built fairly. */
        val ask: StudyStore.Ask? = null,
        /** Which option was chosen. Non-null means this question has been answered and marked. */
        val picked: Int? = null,
        val wasCorrect: Boolean? = null,
        /** Free-recall path only: whether the answer is showing. Self-grading needs a commitment first. */
        val revealed: Boolean = false,
        /** "in 3 days" — what the last answer did to the schedule. Cleared on the next question. */
        val scheduled: String? = null,
        val syllabus: Curriculum.Syllabus? = null,
        val completed: Set<String> = emptySet(),
        val suggestions: List<String> = emptyList(),
        val held: Int = 0,
        val learned: Int = 0,
        /** Time studied, answered, accuracy, streak. Null until the first read completes. */
        val progress: StudyProgress.Snapshot? = null,
        /** Offered only after a real absence with something waiting. */
        val refresher: Refresher.Plan? = null,
        /** The enrolled course with every skill's standing. Null when nothing is enrolled. */
        val course: CourseMastery.Course? = null,
        /** A bounded set in progress — practice, unit test or challenge. Null = the ordinary queue. */
        val session: PracticeSet.Session? = null,
        val sessionAt: Int = 0,
        val sessionCorrect: Int = 0,
        /** Set once a session finishes; the screen shows the verdict until it is dismissed. */
        val sessionResult: PracticeSet.Result? = null,
        /** The hint ladder for the question on screen, and how far up it has been climbed. */
        val hints: List<Hints.Hint> = emptyList(),
        val hintsTaken: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * When the question on screen was put there.
     *
     * How long an answer took is the only signal an objectively-marked question leaves about how
     * comfortably it was known, so it is measured rather than guessed at.
     */
    private var shownAtMs: Long = 0L

    init { refresh() }

    /** The screen came into view: a sitting starts, and the time it accrues starts being measured. */
    fun enter() {
        study.openSession()
        refresh()
    }

    /**
     * And left: bank it. Time is credited from what was actually done, not from the span.
     *
     * ⚠️ On the STORE's scope, not `viewModelScope`. This is called from the screen's disposal, which
     * is almost exactly when this view model is cleared — a launch into a scope cancelled a moment
     * later never runs, so navigating back out of STUDY would silently lose the sitting every time.
     */
    fun leave() {
        study.endSitting()
    }

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
            val progress = runCatching { study.progress() }.getOrNull()
            val refresher = runCatching { study.refresher() }.getOrNull()
            val course = runCatching { study.course() }.getOrNull()
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
                    progress = progress,
                    refresher = refresher,
                    course = course,
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

    /** Begin the way back after an absence, at its first step. */
    fun startRefresher() {
        val first = _state.value.refresher?.steps?.firstOrNull() ?: return
        viewModelScope.launch {
            val ask = runCatching { study.askFor(first.item.card.id) }.getOrNull()
            if (ask == null) {
                nextQuestion()
            } else {
                shownAtMs = System.currentTimeMillis()
                _state.update { it.copy(ask = ask, picked = null, wasCorrect = null, revealed = false, scheduled = null) }
            }
        }
    }

    fun reveal() {
        _state.update { it.copy(revealed = true) }
    }

    /** Work at one skill until it holds — the practice loop, not the scheduling queue. */
    fun practiceSkill(guideId: String, title: String) {
        viewModelScope.launch {
            val set = runCatching { study.practice(guideId, title) }.getOrNull() ?: return@launch
            beginSession(set)
        }
    }

    fun startUnitTest(category: String) {
        viewModelScope.launch { runCatching { study.unitTest(category) }.getOrNull()?.let { beginSession(it) } }
    }

    fun startChallenge() {
        viewModelScope.launch { runCatching { study.challenge() }.getOrNull()?.let { beginSession(it) } }
    }

    private suspend fun beginSession(set: PracticeSet.Session) {
        _state.update {
            it.copy(session = set, sessionAt = 0, sessionCorrect = 0, sessionResult = null, scheduled = null)
        }
        askSessionQuestion(0)
    }

    /**
     * Climb one rung of the hint ladder.
     *
     * The count is what the grade and the pass mark read, so it has to be recorded before the answer,
     * not inferred afterwards.
     */
    fun takeHint() {
        _state.update { if (it.hintsTaken < it.hints.size) it.copy(hintsTaken = it.hintsTaken + 1) else it }
    }

    private suspend fun askSessionQuestion(index: Int) {
        val set = _state.value.session ?: return
        val id = set.questionIds.getOrNull(index)
        if (id == null) { finishSession(); return }
        val ask = runCatching { study.askFor(id) }.getOrNull()
        if (ask == null) {
            // A card that vanished under us (eviction, a cleared deck) must not strand the session.
            if (index + 1 < set.size) askSessionQuestion(index + 1) else finishSession()
            return
        }
        shownAtMs = System.currentTimeMillis()
        _state.update {
            it.copy(
                ask = ask, picked = null, wasCorrect = null, revealed = false, sessionAt = index,
                hints = ask.quiz?.let { q -> Hints.forQuiz(q) }.orEmpty(), hintsTaken = 0,
            )
        }
    }

    private fun finishSession() {
        val set = _state.value.session ?: return
        _state.update {
            it.copy(
                ask = null, picked = null, wasCorrect = null, hints = emptyList(), hintsTaken = 0,
                sessionResult = PracticeSet.Result(set.kind, it.sessionCorrect, set.size, set.passMark),
            )
        }
    }

    /** Clear a finished set's verdict and return to the ordinary screen. */
    fun dismissResult() {
        _state.update { it.copy(session = null, sessionResult = null, sessionAt = 0, sessionCorrect = 0) }
    }

    /**
     * Commit to one of the options.
     *
     * Marked immediately and irrevocably: a choice you can take back measures nothing, and the whole
     * value of the accuracy figure downstream is that it was not negotiated after the fact.
     */
    fun choose(index: Int) {
        val current = _state.value
        val ask = current.ask ?: return
        val quiz = ask.quiz ?: return
        if (current.picked != null) return
        val correct = index == quiz.correctIndex
        val elapsed = if (shownAtMs > 0L) (System.currentTimeMillis() - shownAtMs).coerceAtLeast(0L) else 0L
        _state.update { it.copy(picked = index, wasCorrect = correct) }
        viewModelScope.launch {
            // ⚠️ The CARD's id, never the quiz's. A comprehension item mints its own identifier from
            // the guide and heading it was assembled from, so grading by the quiz's id would find no
            // card and the answer would vanish without a trace. The card under review is the item.
            val next = runCatching {
                study.answer(ask.item.question.id, correct, elapsed, hintsTaken = current.hintsTaken)
            }.getOrNull()
            // Inside a bounded set, an answer only counts toward the pass mark if it was not read off
            // the last hint — otherwise a unit test clears with three taps per question.
            val counts = Hints.countsTowardPass(correct, current.hints, current.hintsTaken)
            _state.update {
                it.copy(
                    scheduled = next?.let { c -> Recall.describeInterval(c.intervalDays) },
                    sessionCorrect = if (it.session != null && counts) it.sessionCorrect + 1 else it.sessionCorrect,
                )
            }
            refresh()
        }
    }

    /**
     * Record a self-graded answer and move on. The open-recall path only.
     *
     * No attempt is recorded here, and that is deliberate: a self-grade says how it felt, and turning
     * that into an objective right-or-wrong would put a number on the progress panel that nothing
     * actually measured.
     */
    fun answer(grade: Recall.Grade) {
        val id = _state.value.ask?.item?.question?.id ?: return
        viewModelScope.launch {
            val next = runCatching { study.grade(id, grade) }.getOrNull()
            _state.update { it.copy(scheduled = next?.let { c -> Recall.describeInterval(c.intervalDays) }) }
            nextQuestion(keepScheduled = true)
            refresh()
        }
    }

    /** Move past a marked multiple choice to whatever is next. */
    fun next() {
        viewModelScope.launch {
            val st = _state.value
            // A bounded set walks its own list to its own finish line; only the open-ended review path
            // falls through to "whatever is due next".
            if (st.session != null) askSessionQuestion(st.sessionAt + 1) else nextQuestion(keepScheduled = true)
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
        _state.update {
            it.copy(
                ask = null, picked = null, wasCorrect = null, revealed = false, scheduled = null,
                session = null, sessionAt = 0, sessionCorrect = 0, sessionResult = null,
                hints = emptyList(), hintsTaken = 0,
            )
        }
    }

    private suspend fun nextQuestion(keepScheduled: Boolean = false) {
        val next = runCatching { study.nextAsk() }.getOrNull()
        shownAtMs = if (next != null) System.currentTimeMillis() else 0L
        _state.update {
            it.copy(
                ask = next,
                picked = null,
                wasCorrect = null,
                revealed = false,
                scheduled = if (keepScheduled) it.scheduled else null,
                hints = next?.quiz?.let { q -> Hints.forQuiz(q) }.orEmpty(),
                hintsTaken = 0,
            )
        }
    }
}
