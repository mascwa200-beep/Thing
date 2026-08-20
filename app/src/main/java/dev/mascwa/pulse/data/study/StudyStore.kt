package dev.mascwa.pulse.data.study

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.CourseMastery
import dev.mascwa.pulse.core.telemetry.Curriculum
import dev.mascwa.pulse.core.telemetry.DailyLesson
import dev.mascwa.pulse.core.telemetry.Hints
import dev.mascwa.pulse.core.telemetry.PracticeSet
import dev.mascwa.pulse.core.telemetry.QuizBuilder
import dev.mascwa.pulse.core.telemetry.Recall
import dev.mascwa.pulse.core.telemetry.Refresher
import dev.mascwa.pulse.core.telemetry.StudyProgress
import dev.mascwa.pulse.core.telemetry.StudyQuestions
import dev.mascwa.pulse.core.telemetry.CATEGORY_SUPERGROUP
import dev.mascwa.pulse.data.survival.SurvivalContentRepository
import dev.mascwa.pulse.core.telemetry.toSearchEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.studyDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_study")

/**
 * Whole days since the epoch **in the reader's own time zone**.
 *
 * [DailyLesson] takes this rather than a clock because a "today" derived from UTC inside a pure
 * module is a day out for half the planet. One definition here so the screen, the notification board
 * and the tool cannot disagree about which day it is.
 */
fun localDayIndex(nowMs: Long = System.currentTimeMillis()): Int {
    val zone = java.util.TimeZone.getDefault()
    return Math.floorDiv(nowMs + zone.getOffset(nowMs).toLong(), 86_400_000L).toInt()
}

/**
 * What you are learning, and when you are next due to be asked.
 *
 * The on-device half of the study cores: it holds an enrolled goal, which of its guides you have
 * read, which guides have already been taught, and one [Recall.Card] per question you have been
 * asked. Mirrors [dev.mascwa.pulse.data.profile.ProfileStore] — in-memory state is authoritative,
 * writes are debounced, a clear cancels a buffered flush so it cannot resurrect what was cleared.
 *
 * **The syllabus is not stored.** Only the goal, the pace and which guide ids are finished are;
 * [Curriculum.compose] is deterministic, so the path is recomposed from the current library each
 * time. Storing it would freeze a path against a library that grows every content wave, and progress
 * would start pointing at guides that had moved.
 *
 * Everything here is on the device. Nothing is fetched, and nothing is sent.
 */
class StudyStore(
    private val context: Context,
    private val json: Json,
    private val content: SurvivalContentRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** One question and its schedule. What a review session is made of. */
    data class Item(val question: StudyQuestions.Question, val card: Recall.Card)

    /**
     * A question ready to be asked, and — when one could be built fairly — the multiple choice for it.
     *
     * A null [quiz] is not a failure. Only numeric cloze questions can be turned into a defensible set
     * of options, so open recall remains the fallback: it is the strongest form of retrieval practice,
     * and refusing to ask it at all in order to make everything multiple choice would cost real
     * learning. What it cannot do is mark itself, which is why those answers stay self-graded.
     */
    data class Ask(val item: Item, val quiz: QuizBuilder.QuizItem?)

    @Serializable
    private data class StoredCard(
        val id: String,
        val kind: String,
        val prompt: String,
        val answer: String,
        val guideId: String,
        val guideTitle: String,
        val heading: String,
        val dueAtMs: Long,
        val intervalDays: Double = 0.0,
        val ease: Double = Recall.START_EASE,
        val reps: Int = 0,
        val lapses: Int = 0,
        /** ⚠️ An ORDER card's sibling steps. Without these it survives a restart as a card
         *  that can never be turned back into a question — see StudyQuestions.Question.options.
         *  Defaulted, so saves written before procedures existed still load. */
        val options: List<String> = emptyList(),
    )

    @Serializable
    private data class StoredAttempt(
        val questionId: String,
        val guideId: String,
        val correct: Boolean,
        val atMs: Long,
        val elapsedMs: Long = 0L,
    )

    @Serializable
    private data class StoredSession(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val attempts: Int = 0,
        val reading: Boolean = false,
    )

    // Every field added for the progress layer is defaulted, so a save written before it existed still
    // decodes — the same discipline every prior store change has kept.
    @Serializable
    private data class Stored(
        val goal: String = "",
        val perDay: Int = Curriculum.DEFAULT_PER_DAY,
        val completed: List<String> = emptyList(),
        val taught: List<String> = emptyList(),
        val cards: List<StoredCard> = emptyList(),
        val attempts: List<StoredAttempt> = emptyList(),
        val sessions: List<StoredSession> = emptyList(),
        /**
         * Held separately rather than derived from the logs above, because both are capped: evicting
         * old history must never make it look as though you studied longer ago than you did, which
         * would have the refresher offer a cold return to somebody who was here yesterday.
         */
        val lastStudiedAtMs: Long = 0L,
    )

    private val prefsKey = stringPreferencesKey("study_json")
    private val mutex = Mutex()
    private var state: Stored? = null
    private var flushJob: Job? = null

    private val _goal = MutableStateFlow("")
    /** The enrolled goal, or blank. Drives whether the screen offers a path or a picker. */
    val goal: StateFlow<String> = _goal.asStateFlow()

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** Every card held, newest schedule included, so a screen can show progress without a query. */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    /**
     * The sitting currently open, if any.
     *
     * ⚠️ In memory only, and deliberately. A session is recorded when it *ends*; if the process is
     * killed mid-sitting the time is simply lost. Persisting an open start would mean an app killed in
     * the background reopened a week later credits a week of study, which is exactly the flattering
     * figure [StudyProgress] exists to refuse.
     */
    // ⚠️ Volatile and atomic because these are genuinely cross-thread: opened and closed from the UI
    // lifecycle, incremented from the IO dispatcher inside `answer`. A lost increment would only
    // under-credit time, but an unsynchronised Long read is worse than that.
    @Volatile private var openStartMs: Long = 0L
    @Volatile private var openReading: Boolean = false
    private val openAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    // ---- loading and persistence -----------------------------------------------------------------

    private suspend fun ensureLoaded(): Stored = mutex.withLock { loadLocked() }

    /** Caller must hold [mutex]. */
    private suspend fun loadLocked(): Stored = state ?: run {
        val raw = context.studyDataStore.data.first()[prefsKey]
        val loaded = raw
            ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            ?: Stored()
        loaded.also { publish(it) }
    }

    /** Caller must hold [mutex]. */
    private fun publish(s: Stored) {
        state = s
        _goal.value = s.goal
        _items.value = s.cards.map { it.item() }
    }

    private fun StoredCard.item() = Item(
        question = StudyQuestions.Question(
            id = id,
            kind = runCatching { StudyQuestions.QuestionKind.valueOf(kind) }
                .getOrDefault(StudyQuestions.QuestionKind.RECALL),
            prompt = prompt,
            answer = answer,
            guideId = guideId,
            guideTitle = guideTitle,
            heading = heading,
            options = options,
        ),
        card = Recall.Card(id, dueAtMs, intervalDays, ease, reps, lapses),
    )

    private fun Item.stored() = StoredCard(
        id = question.id,
        kind = question.kind.name,
        prompt = question.prompt,
        answer = question.answer,
        guideId = question.guideId,
        guideTitle = question.guideTitle,
        heading = question.heading,
        dueAtMs = card.dueAtMs,
        intervalDays = card.intervalDays,
        ease = card.ease,
        reps = card.reps,
        lapses = card.lapses,
        options = question.options,
    )

    // ---- the enrolled path ------------------------------------------------------------------------

    /**
     * The composed path for the enrolled goal, or null when nothing is enrolled.
     *
     * Recomposed on every call rather than cached: the index is resident and [Curriculum.compose] is
     * a rank over it, so this costs one pass over ~600 rows and always reflects the current library.
     */
    suspend fun syllabus(): Curriculum.Syllabus? {
        val s = ensureLoaded()
        if (s.goal.isBlank()) return null
        val entries = runCatching { content.index() }.getOrNull().orEmpty().map { it.toSearchEntry() }
        if (entries.isEmpty()) return null
        return Curriculum.compose(s.goal, entries, CATEGORY_SUPERGROUP, perDay = s.perDay)
    }

    /** Which guides are recorded as finished. Empty when nothing is enrolled. */
    suspend fun completedIds(): Set<String> = ensureLoaded().completed.toSet()

    /** Every guide ever offered as a lesson, so a screen can say how far through the library you are. */
    suspend fun taughtIds(): Set<String> = ensureLoaded().taught.toSet()

    /** Goals worth offering, filtered to the ones this library can genuinely support. */
    suspend fun suggestedGoals(): List<String> {
        val entries = runCatching { content.index() }.getOrNull().orEmpty().map { it.toSearchEntry() }
        return Curriculum.suggestions(entries)
    }

    suspend fun enroll(goal: String, perDay: Int = Curriculum.DEFAULT_PER_DAY) {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return
        mutex.withLock {
            val s = loadLocked()
            // Enrolling in something new clears the old path's progress: the ids belonged to it.
            val fresh = if (trimmed.equals(s.goal, ignoreCase = true)) s.completed else emptyList()
            publish(s.copy(goal = trimmed, perDay = perDay.coerceIn(1, Curriculum.MAX_PER_DAY), completed = fresh))
        }
        scheduleFlush()
    }

    suspend fun abandonGoal() {
        mutex.withLock {
            val s = loadLocked()
            publish(s.copy(goal = "", completed = emptyList()))
        }
        scheduleFlush()
    }

    // ---- being taught -------------------------------------------------------------------------------

    /**
     * Turn a guide into questions and schedule them, returning what was created.
     *
     * The material itself is not returned: the app already has a reader, and the lesson deep-links to
     * it. This is the part the reader cannot do — making the guide askable.
     *
     * Draws from the opening sections rather than the whole guide. A guide in this library runs to a
     * dozen sections; asking every one of them at once turns a lesson into a worksheet and buries the
     * review queue for a week.
     */
    suspend fun teach(guideId: String, nowMs: Long = System.currentTimeMillis()): List<StudyQuestions.Question> {
        val guide = runCatching { content.guide(guideId) }.getOrNull() ?: return emptyList()
        val made = ArrayList<StudyQuestions.Question>(MAX_QUESTIONS_PER_LESSON)
        // ⚠️ First, so the cap below can never be the reason a guide's warning goes untaught. 184 of
        // the bundled guides carry one and none of them was ever asked about.
        StudyQuestions.safety(guide.id, guide.title, guide.safetyNote)?.let { made += it }
        for (section in guide.sections.take(LESSON_SECTIONS)) {
            for (q in StudyQuestions.forSection(
                guide.id, guide.title, section.heading, section.body,
                // The section has carried these all along; nobody passed them, so 3,298 steps
                // across the corpus produced no questions at all.
                steps = section.steps.orEmpty(),
                ingredients = section.ingredients.orEmpty(),
            )) {
                if (made.size >= MAX_QUESTIONS_PER_LESSON) break
                made += q
            }
            if (made.size >= MAX_QUESTIONS_PER_LESSON) break
        }
        if (made.isEmpty()) return emptyList()

        mutex.withLock {
            val s = loadLocked()
            val existing = s.cards.associateBy { it.id }
            // A question already carrying a schedule keeps it — re-teaching must not reset progress.
            val added = made.filter { it.id !in existing }
                .map { Item(it, Recall.newCard(it.id, nowMs)).stored() }
            publish(
                s.copy(
                    cards = capped(s.cards + added),
                    taught = (s.taught + guide.id).distinct().takeLast(MAX_TAUGHT),
                    completed = if (s.goal.isBlank()) s.completed else (s.completed + guide.id).distinct(),
                ),
            )
        }
        scheduleFlush()
        return made
    }

    /** Mark a guide read without generating questions — the reader's own "done" action. */
    suspend fun markRead(guideId: String) {
        if (guideId.isBlank()) return
        mutex.withLock {
            val s = loadLocked()
            publish(
                s.copy(
                    taught = (s.taught + guideId).distinct().takeLast(MAX_TAUGHT),
                    completed = if (s.goal.isBlank()) s.completed else (s.completed + guideId).distinct(),
                ),
            )
        }
        scheduleFlush()
    }

    // ---- being asked ---------------------------------------------------------------------------------

    /** What is due now, most overdue first. */
    suspend fun due(nowMs: Long = System.currentTimeMillis(), limit: Int = Recall.DEFAULT_DUE_LIMIT): List<Item> {
        val s = ensureLoaded()
        val byId = s.cards.associateBy { it.id }
        return Recall.due(s.cards.map { it.item().card }, nowMs, limit)
            .mapNotNull { c -> byId[c.id]?.item()?.copy(card = c) }
    }

    suspend fun dueCount(nowMs: Long = System.currentTimeMillis()): Int =
        Recall.dueCount(ensureLoaded().cards.map { it.item().card }, nowMs)

    /**
     * The next thing to be asked, with a multiple choice built for it where one can be built fairly.
     *
     * The distractor pool is every numeric term the same guide uses anywhere — near misses from the
     * material itself, which is what makes an option impossible to eliminate without having read it.
     * That costs one shard read, which the content repository already caches.
     */
    suspend fun nextAsk(nowMs: Long = System.currentTimeMillis()): Ask? {
        val item = due(nowMs, limit = 1).firstOrNull() ?: return null
        val pool = poolFor(item.question.guideId)
        // The seed advances with the card's own history, so re-meeting a question rotates its format
        // and shuffles its options rather than replaying the same screen.
        val seed = item.question.id.hashCode() + item.card.reps * PRIME_STRIDE + item.card.lapses
        return Ask(item, quizFor(item, pool, seed))
    }

    /**
     * The best multiple choice this question can carry, or null when neither form can be built fairly.
     *
     * A numeric gap becomes a near-miss value pick; anything else becomes a which-statement-belongs
     * item. Refusing both is a real outcome, not a bug — the open-recall path then asks it as written
     * and it stays self-graded, which is honest about what was actually measured.
     */
    private suspend fun quizFor(item: Item, pool: List<String>, seed: Int): QuizBuilder.QuizItem? {
        // A minority of reviews are asked with no options at all — see QuizBuilder.asksOpenRecall.
        if (QuizBuilder.asksOpenRecall(seed)) return null
        return runCatching { QuizBuilder.build(item.question, pool, seed) }.getOrNull()
            ?: statementQuiz(item, seed)
    }

    /** Every numeric term the guide uses — the near misses a fair option set is drawn from. */
    private suspend fun poolFor(guideId: String): List<String> {
        val guide = runCatching { content.guide(guideId) }.getOrNull() ?: return emptyList()
        return guide.sections
            .flatMap { StudyQuestions.sentences(it.body) }
            .flatMap { StudyQuestions.blankableTerms(it) }
            .distinct()
    }

    /**
     * A multiple choice for a question that has no number in it: which statement this section actually
     * makes.
     *
     * This is the half that checks **understanding** rather than a remembered figure, and it is the
     * only reason an open-recall card can be marked objectively at all. The wrong answers are real
     * sentences from a guide in a **different category** — perfectly sensible in their own context and
     * simply not what this section says. Telling them apart requires having understood it.
     */
    private suspend fun statementQuiz(item: Item, seed: Int): QuizBuilder.QuizItem? {
        // ⚠️ A safety warning is answered as written, never marked against distractors — see
        // StudyQuestions.safety. Its heading is synthetic, so without this a guide that happened to
        // title a section the same way would have its warning quietly replaced by a comprehension
        // item about that section.
        if (StudyQuestions.isSafety(item.question)) return null
        val guide = runCatching { content.guide(item.question.guideId) }.getOrNull() ?: return null
        val section = guide.sections.firstOrNull { it.heading == item.question.heading } ?: return null
        val truths = StudyQuestions.sentences(section.body)
        if (truths.size < MIN_TRUE_STATEMENTS) return null
        // Rotated, so re-meeting the card asks about a different sentence of the same section rather
        // than replaying one it has already taught you the answer to.
        val rotated = Math.floorMod(seed, truths.size).let { truths.drop(it) + truths.take(it) }
        val others = foreignSentences(guide.category, seed)
        if (others.isEmpty()) return null
        return runCatching {
            QuizBuilder.statementItem(guide.id, guide.title, section.heading, rotated, others, seed)
        }.getOrNull()
    }

    /**
     * Sentences from somewhere genuinely unrelated.
     *
     * ⚠️ A different **category**, not merely a different guide. A sentence from a neighbouring guide
     * on the same subject can easily also be true of this section, which is the two-defensible-answers
     * failure the whole quiz layer is built to avoid. Category distance is the cheap, reliable proxy.
     */
    private suspend fun foreignSentences(category: String, seed: Int): List<String> {
        val index = runCatching { content.index() }.getOrNull().orEmpty()
        val candidates = index.filter { it.category != category }
        if (candidates.isEmpty()) return emptyList()
        val pick = candidates[Math.floorMod(seed, candidates.size)]
        val other = runCatching { content.guide(pick.id) }.getOrNull() ?: return emptyList()
        return other.sections.flatMap { StudyQuestions.sentences(it.body) }.take(FOREIGN_SENTENCES)
    }

    /** Record an answer. Returns the new schedule, so the screen can say when it will come back. */
    suspend fun grade(
        questionId: String,
        grade: Recall.Grade,
        nowMs: Long = System.currentTimeMillis(),
    ): Recall.Card? {
        var updated: Recall.Card? = null
        mutex.withLock {
            val s = loadLocked()
            val at = s.cards.indexOfFirst { it.id == questionId }
            if (at < 0) return@withLock
            val item = s.cards[at].item()
            val next = Recall.review(item.card, grade, nowMs)
            updated = next
            val cards = s.cards.toMutableList()
            cards[at] = item.copy(card = next).stored()
            publish(s.copy(cards = cards, lastStudiedAtMs = maxOf(s.lastStudiedAtMs, nowMs)))
            // No attempt is recorded — a self-graded answer says how it FELT, and inventing an
            // objective right-or-wrong from that is exactly the flattering number to avoid. The
            // sitting's count still moves, because the time allowance is about evidence of activity
            // and answering something is activity whether or not it can be scored.
            openAttempts.incrementAndGet()
        }
        if (updated != null) scheduleFlush()
        return updated
    }

    /**
     * Record an objectively-marked answer: schedule it **and** keep it as evidence.
     *
     * This is the pairing the whole progress layer rests on. [grade] alone tells the schedule how it
     * went; only an attempt tells the reader — and the refresher — whether it was actually right.
     */
    suspend fun answer(
        questionId: String,
        correct: Boolean,
        elapsedMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
        hintsTaken: Int = 0,
    ): Recall.Card? {
        var updated: Recall.Card? = null
        mutex.withLock {
            val s = loadLocked()
            val at = s.cards.indexOfFirst { it.id == questionId }
            if (at < 0) return@withLock
            val item = s.cards[at].item()
            // Hints cap the grade but never the correctness — see Hints.gradeFor.
            val next = Recall.review(item.card, Hints.gradeFor(correct, elapsedMs, hintsTaken), nowMs)
            updated = next
            val cards = s.cards.toMutableList()
            cards[at] = item.copy(card = next).stored()
            val attempt = StoredAttempt(
                questionId = questionId,
                guideId = item.question.guideId,
                correct = correct,
                atMs = nowMs,
                elapsedMs = elapsedMs.coerceAtLeast(0L),
            )
            publish(
                s.copy(
                    cards = cards,
                    attempts = (s.attempts + attempt).takeLast(MAX_ATTEMPTS),
                    lastStudiedAtMs = maxOf(s.lastStudiedAtMs, nowMs),
                ),
            )
            openAttempts.incrementAndGet()
        }
        if (updated != null) scheduleFlush()
        return updated
    }

    // ---- sittings ---------------------------------------------------------------------------------------

    /** Note that a study surface has opened. Idempotent — reopening without closing keeps the earlier start. */
    fun openSession(reading: Boolean = false, nowMs: Long = System.currentTimeMillis()) {
        if (openStartMs > 0L) return
        openStartMs = nowMs
        openAttempts.set(0)
        openReading = reading
    }

    /**
     * Close the sitting from a caller that may be going away, on the store's own scope.
     *
     * ⚠️ The screen's natural place to do this is disposal, and a view model's scope is cancelled at
     * almost exactly that moment. Launching the close on `viewModelScope` therefore races its own
     * cancellation and would silently lose the sitting every time you navigated back out of STUDY —
     * silently, because a launch into a cancelled scope simply never runs. This store's scope lives as
     * long as the container, so it cannot be cancelled out from under the caller.
     */
    fun endSitting(nowMs: Long = System.currentTimeMillis()) {
        if (openStartMs <= 0L) return
        scope.launch { closeSession(nowMs) }
    }

    /**
     * Note that it has closed, and bank the credited time.
     *
     * Zero-length sittings are dropped rather than stored: a screen opened and immediately left is not
     * evidence of anything, and a log full of them would push real history out of the cap.
     */
    suspend fun closeSession(nowMs: Long = System.currentTimeMillis()) {
        val started = openStartMs
        if (started <= 0L) return
        val attempts = openAttempts.getAndSet(0)
        val reading = openReading
        openStartMs = 0L
        openReading = false
        val session = StoredSession(started, nowMs, attempts, reading)
        if (StudyProgress.creditedMs(session.session()) <= 0L) return
        mutex.withLock {
            val s = loadLocked()
            publish(
                s.copy(
                    sessions = (s.sessions + session).takeLast(MAX_SESSIONS),
                    lastStudiedAtMs = maxOf(s.lastStudiedAtMs, nowMs),
                ),
            )
        }
        scheduleFlush()
    }


    // ---- the course, seen at once -------------------------------------------------------------------

    /**
     * The enrolled path with how well each skill on it is known, or null when nothing is enrolled.
     *
     * ⚠️ Attempts are grouped by guide ONCE rather than re-scanned per step. The obvious shape is a
     * `mastery(step.guideId)` call inside the loop, which re-walks the whole attempt log for every step
     * — forty steps against a thousand attempts is forty thousand comparisons to draw one screen.
     */
    suspend fun course(): CourseMastery.Course? {
        val syllabus = syllabus() ?: return null
        val s = ensureLoaded()
        val attemptsByGuide = s.attempts.map { it.attempt() }.groupBy { it.guideId }
        val cardsByGuide = s.cards.groupBy { it.guideId }
        val now = System.currentTimeMillis()
        val levels = HashMap<String, StudyProgress.Level>()
        val cardCount = HashMap<String, Int>()
        val dueCount = HashMap<String, Int>()
        for (step in syllabus.steps) {
            val cards = cardsByGuide[step.guideId].orEmpty()
            cardCount[step.guideId] = cards.size
            dueCount[step.guideId] = cards.count { it.dueAtMs <= now }
            levels[step.guideId] = StudyProgress.mastery(
                step.guideId,
                attemptsByGuide[step.guideId].orEmpty(),
                cards.map { it.item().card },
            ).level
        }
        return CourseMastery.course(syllabus, levels, cardCount, dueCount, s.taught.toSet())
    }

    // ---- practice -----------------------------------------------------------------------------------

    /**
     * A short set on one skill, teaching it first if it has never been asked.
     *
     * Teaching-on-demand matters: "practise this" on a guide you have only read should just work rather
     * than telling you to go and do something else first.
     */
    suspend fun practice(guideId: String, title: String): PracticeSet.Session? {
        if (ensureLoaded().cards.none { it.guideId == guideId }) teach(guideId)
        val ids = ensureLoaded().cards.filter { it.guideId == guideId }.map { it.id }
        return PracticeSet.practice(title, ids)
    }

    /** A mixed set across one category of the enrolled path. */
    suspend fun unitTest(category: String): PracticeSet.Session? {
        val course = course() ?: return null
        val wanted = course.skills.filter { it.category.equals(category, ignoreCase = true) }.map { it.guideId }.toSet()
        if (wanted.isEmpty()) return null
        return PracticeSet.mixed(PracticeSet.Kind.UNIT_TEST, category, questionsByGuide(wanted))
    }

    /** A mixed set across the whole course, weighted toward what is weakest. */
    suspend fun challenge(): PracticeSet.Session? {
        val course = course() ?: return null
        val order = PracticeSet.challengeOrder(course.skills).map { it.guideId }
        if (order.isEmpty()) return null
        // LinkedHashMap so the weakest-first ordering survives into the round-robin.
        val byGuide = LinkedHashMap<String, List<String>>()
        val all = questionsByGuide(order.toSet())
        for (id in order) all[id]?.let { byGuide[id] = it }
        return PracticeSet.mixed(PracticeSet.Kind.CHALLENGE, course.goal, byGuide)
    }

    private suspend fun questionsByGuide(guideIds: Set<String>): Map<String, List<String>> =
        ensureLoaded().cards.filter { it.guideId in guideIds }.groupBy({ it.guideId }, { it.id })

    // ---- how it is going ---------------------------------------------------------------------------------

    private fun StoredAttempt.attempt() =
        StudyProgress.Attempt(questionId, guideId, correct, atMs, elapsedMs)

    private fun StoredSession.session() = StudyProgress.Session(
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        attempts = attempts,
        kind = if (reading) StudyProgress.SessionKind.READING else StudyProgress.SessionKind.QUESTIONS,
    )

    /**
     * Time studied, answered, accuracy, streak — the record as it stands.
     *
     * @param dayIndex whole local days since the epoch, for the same reason [today] takes one.
     */
    suspend fun progress(
        dayIndex: Int = localDayIndex(),
        nowMs: Long = System.currentTimeMillis(),
    ): StudyProgress.Snapshot {
        val s = ensureLoaded()
        val snapshot = StudyProgress.summarise(
            attempts = s.attempts.map { it.attempt() },
            sessions = s.sessions.map { it.session() },
            todayIndex = dayIndex,
            dayOf = { localDayIndex(it) },
        )
        // The persisted stamp wins: the logs are capped, and eviction must not make the last sitting
        // look older than it was.
        return snapshot.copy(lastStudiedAtMs = maxOf(snapshot.lastStudiedAtMs, s.lastStudiedAtMs))
    }

    /** How well one guide is known, from its answers and its schedule together. */
    suspend fun mastery(guideId: String): StudyProgress.Mastery {
        val s = ensureLoaded()
        val cards = s.cards.filter { it.guideId == guideId }.map { it.item().card }
        return StudyProgress.mastery(guideId, s.attempts.map { it.attempt() }, cards)
    }

    /**
     * The subject going worst, as a title and a plain-English line — or null when nothing has enough
     * behind it to say so.
     *
     * The evidence bar lives in [StudyProgress.weakest], so anything returned here has already earned
     * the claim; that is what stops one wrong answer on something barely touched from being named.
     *
     * ⚠️ Null when the title cannot be resolved. Attempts outlive their cards — the deck is capped and
     * evicts — and naming a raw guide id at somebody would be worse than saying nothing.
     *
     * Android-only on purpose: the Oracle is the only caller, and the desktop has no Oracle. Mirroring
     * it there would add a second method with no callers, which is the exact defect this arc exists to
     * fix rather than repeat.
     */
    suspend fun weakestGuide(): Pair<String, String>? {
        val s = ensureLoaded()
        val worst = StudyProgress.weakest(s.attempts.map { it.attempt() }, limit = 1).firstOrNull()
            ?: return null
        val title = s.cards.firstOrNull { it.guideId == worst.guideId }?.guideTitle ?: return null
        return title to "${worst.correct} of ${worst.answered} right so far."
    }

    /** The capped, ordered way back after time away, or null when the ordinary screen is right. */
    suspend fun refresher(nowMs: Long = System.currentTimeMillis()): Refresher.Plan? {
        val s = ensureLoaded()
        val items = s.cards.map { stored ->
            val item = stored.item()
            Refresher.Item(stored.guideId, stored.guideTitle, item.card)
        }
        return Refresher.plan(
            items = items,
            attempts = s.attempts.map { it.attempt() },
            lastStudiedAtMs = s.lastStudiedAtMs,
            nowMs = nowMs,
        )
    }

    /** Ask a specific question next — how a refresher step is entered. */
    suspend fun askFor(questionId: String): Ask? {
        val s = ensureLoaded()
        val stored = s.cards.firstOrNull { it.id == questionId } ?: return null
        val item = stored.item()
        val pool = poolFor(item.question.guideId)
        val seed = item.question.id.hashCode() + item.card.reps * PRIME_STRIDE + item.card.lapses
        return Ask(item, quizFor(item, pool, seed))
    }

    // ---- today ----------------------------------------------------------------------------------------

    /**
     * Today's item, or null when there is nothing to offer.
     *
     * @param dayIndex whole local days since the epoch. Supplied by the caller because this store has
     *   no business deciding what "today" means in the reader's own time zone.
     */
    suspend fun today(
        interests: List<String>,
        pendingTasks: List<String>,
        dayIndex: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): DailyLesson.Lesson? {
        val s = ensureLoaded()
        val entries = runCatching { content.index() }.getOrNull().orEmpty().map { it.toSearchEntry() }
        return DailyLesson.pick(
            DailyLesson.StudyContext(
                dayIndex = dayIndex,
                dueCount = Recall.dueCount(s.cards.map { it.item().card }, nowMs),
                syllabus = if (s.goal.isBlank() || entries.isEmpty()) {
                    null
                } else {
                    Curriculum.compose(s.goal, entries, CATEGORY_SUPERGROUP, perDay = s.perDay)
                },
                completed = s.completed.toSet(),
                pendingTasks = pendingTasks,
                interests = interests,
                library = entries,
                taught = s.taught.toSet(),
            ),
        )
    }

    // ---- housekeeping -----------------------------------------------------------------------------------

    suspend fun clear() {
        flushJob?.cancel()
        // An open sitting belongs to the history being erased; leaving it running would bank time
        // against a deck that no longer exists.
        openStartMs = 0L
        openAttempts.set(0)
        openReading = false
        mutex.withLock { publish(Stored()) }
        runCatching { context.studyDataStore.edit { it.remove(prefsKey) } }
    }

    /** Force buffered changes to disk now (e.g. on app stop). */
    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    /**
     * Keep the deck bounded.
     *
     * Learned cards go first — they are the ones that have earned the longest gaps and cost the least
     * to lose — then whatever is furthest from being due. Nothing recently forgotten is ever evicted,
     * because that is precisely the card still worth asking.
     */
    private fun capped(cards: List<StoredCard>): List<StoredCard> {
        if (cards.size <= MAX_CARDS) return cards
        // Sorted so the ones to lose come FIRST, then dropped from the front.
        return cards.sortedWith(
            compareByDescending<StoredCard> { if (Recall.isLearned(it.item().card)) 1 else 0 }
                .thenByDescending { it.dueAtMs },
        ).drop(cards.size - MAX_CARDS)
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { state } ?: return
        runCatching {
            context.studyDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L

        /** How many of a guide's sections one lesson draws from. */
        const val LESSON_SECTIONS = 3

        /** A lesson is a sitting, not a worksheet. */
        const val MAX_QUESTIONS_PER_LESSON = 5

        /** The deck ceiling. Well past a year of daily lessons. */
        const val MAX_CARDS = 600

        /** Enough history that the daily pick keeps finding something new for years. */
        const val MAX_TAUGHT = 2_000

        /** Answer history. Well past a year of daily sittings, and five small fields each. */
        const val MAX_ATTEMPTS = 1_000

        /** Sittings. Several a day for a year. */
        const val MAX_SESSIONS = 500

        /** Advances the quiz seed per review so a re-met question is not the same screen again. */
        const val PRIME_STRIDE = 7

        /** A statement item needs the section to make more than one statement to choose between. */
        const val MIN_TRUE_STATEMENTS = 2

        /** Enough unrelated prose to find well-shaped distractors in, without holding a whole guide. */
        const val FOREIGN_SENTENCES = 80
    }
}
