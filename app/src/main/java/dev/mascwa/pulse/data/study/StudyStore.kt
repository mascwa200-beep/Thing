package dev.mascwa.pulse.data.study

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Curriculum
import dev.mascwa.pulse.core.telemetry.DailyLesson
import dev.mascwa.pulse.core.telemetry.Recall
import dev.mascwa.pulse.core.telemetry.StudyQuestions
import dev.mascwa.pulse.data.survival.CATEGORY_SUPERGROUP
import dev.mascwa.pulse.data.survival.SurvivalContentRepository
import dev.mascwa.pulse.data.survival.toSearchEntry
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
    )

    @Serializable
    private data class Stored(
        val goal: String = "",
        val perDay: Int = Curriculum.DEFAULT_PER_DAY,
        val completed: List<String> = emptyList(),
        val taught: List<String> = emptyList(),
        val cards: List<StoredCard> = emptyList(),
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
        for (section in guide.sections.take(LESSON_SECTIONS)) {
            for (q in StudyQuestions.forSection(guide.id, guide.title, section.heading, section.body)) {
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
            publish(s.copy(cards = cards))
        }
        if (updated != null) scheduleFlush()
        return updated
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
    }
}
