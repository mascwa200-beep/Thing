package dev.mascwa.pulse.desktop.feature.study

import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.desktop.telemetry.Curriculum
import dev.mascwa.pulse.desktop.telemetry.DailyLesson
import dev.mascwa.pulse.desktop.telemetry.Recall
import dev.mascwa.pulse.desktop.telemetry.Refresher
import dev.mascwa.pulse.desktop.telemetry.StudyProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StudyUiState(
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
    /** "in 3 days" — what the last answer did to the schedule. */
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

    /**
     * When the question on screen was put there.
     *
     * How long an answer took is the only signal an objectively-marked question leaves about how
     * comfortably it was known, so it is measured rather than guessed at.
     */
    private var shownAtMs: Long = 0L

    init {
        // The window being open is the sitting. Banked by the shell on close, which already flushes
        // this store for the same reason.
        store.openSession()
        refresh()
    }

    fun refresh() {
        scope.launch {
            val lesson = runCatching { store.today() }.getOrNull()
            val syllabus = runCatching { store.syllabus() }.getOrNull()
            val completed = runCatching { store.completedIds() }.getOrDefault(emptySet())
            val suggestions = runCatching { store.suggestedGoals() }.getOrDefault(emptyList())
            val progress = runCatching { store.progress() }.getOrNull()
            val refresher = runCatching { store.refresher() }.getOrNull()
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
                progress = progress,
                refresher = refresher,
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

    /** Teach a named guide — the reader's "STUDY THIS" — and go straight into answering it. */
    fun teach(guideId: String) {
        if (guideId.isBlank()) return
        scope.launch {
            runCatching { store.teach(guideId) }
            nextQuestion()
            refresh()
        }
    }

    fun startReview() {
        scope.launch { nextQuestion() }
    }

    /** Begin the way back after an absence, at its first step. */
    fun startRefresher() {
        val first = _state.value.refresher?.steps?.firstOrNull() ?: return
        scope.launch {
            val ask = runCatching { store.askFor(first.item.card.id) }.getOrNull()
            if (ask == null) {
                nextQuestion()
            } else {
                shownAtMs = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    ask = ask, picked = null, wasCorrect = null, revealed = false, scheduled = null,
                )
            }
        }
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
        _state.value = current.copy(picked = index, wasCorrect = correct)
        scope.launch {
            // ⚠️ The CARD's id, never the quiz's. A comprehension item mints its own identifier from
            // the guide and heading it was assembled from, so grading by the quiz's id would find no
            // card and the answer would vanish without a trace. The card under review is the item.
            val next = runCatching { store.answer(ask.item.question.id, correct, elapsed) }.getOrNull()
            _state.value = _state.value.copy(
                scheduled = next?.let { Recall.describeInterval(it.intervalDays) },
            )
            refresh()
        }
    }

    /** Move past a marked multiple choice to whatever is next. */
    fun next() {
        scope.launch {
            nextQuestion(keepScheduled = true)
            refresh()
        }
    }

    fun reveal() {
        _state.value = _state.value.copy(revealed = true)
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
        _state.value = _state.value.copy(
            ask = null, picked = null, wasCorrect = null, revealed = false, scheduled = null,
        )
    }

    private suspend fun nextQuestion(keepScheduled: Boolean = false) {
        val next = runCatching { store.nextAsk() }.getOrNull()
        shownAtMs = if (next != null) System.currentTimeMillis() else 0L
        _state.value = _state.value.copy(
            ask = next,
            picked = null,
            wasCorrect = null,
            revealed = false,
            scheduled = if (keepScheduled) _state.value.scheduled else null,
        )
    }
}
