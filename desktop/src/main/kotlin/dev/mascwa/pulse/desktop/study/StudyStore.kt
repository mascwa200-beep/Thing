package dev.mascwa.pulse.desktop.study

import dev.mascwa.pulse.desktop.AppPaths
import dev.mascwa.pulse.desktop.library.CATEGORY_SUPERGROUP
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.library.toSearchEntry
import dev.mascwa.pulse.desktop.telemetry.Curriculum
import dev.mascwa.pulse.desktop.telemetry.DailyLesson
import dev.mascwa.pulse.desktop.telemetry.Recall
import dev.mascwa.pulse.desktop.telemetry.StudyQuestions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.TimeZone

/**
 * Whole days since the epoch **in this machine's own time zone**.
 *
 * [DailyLesson] takes this rather than a clock, because a "today" derived from UTC inside a pure module
 * is a day out for half the planet. One definition here so every desktop caller agrees which day it is.
 */
fun localDayIndex(nowMs: Long = System.currentTimeMillis()): Int {
    val zone = TimeZone.getDefault()
    return Math.floorDiv(nowMs + zone.getOffset(nowMs).toLong(), 86_400_000L).toInt()
}

/**
 * What this desktop is learning, and when it is next due to be asked.
 *
 * The desktop counterpart of the Android `StudyStore`, with the same public shape so the two behave
 * alike, and the same persistence discipline as [dev.mascwa.pulse.desktop.settings.DesktopSettingsStore]:
 * in-memory state is authoritative, writes are debounced, disk IO runs inside the lock so a debounced
 * and a forced flush can never race on the same temp file, and an unreadable file on disk is left alone
 * rather than overwritten with defaults.
 *
 * **The syllabus is not stored.** Only the goal, the pace and which guide ids are finished are;
 * [Curriculum.compose] is deterministic, so the path is recomposed from the current library each time.
 * Storing it would freeze a path against a library that grows with every content wave.
 *
 * ⚠️ **This schedule is the desktop's own and does not follow the phone.** That was a deliberate
 * decision — the alternative was pushing review state across the LAN control link, widening a
 * deliberately tiny command allowlist to carry bulk state — but it means a card answered here still
 * comes back on the phone. The screen says so rather than leaving it to be discovered.
 */
class StudyStore(
    private val library: LibraryRepository,
    private val path: Path = AppPaths.dataDir.resolve("study.json"),
    private val json: Json = defaultJson,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** One question and its schedule. What a review session is made of. */
    data class Item(val question: StudyQuestions.Question, val card: Recall.Card)

    @Serializable
    data class StoredCard(
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
    data class Stored(
        val goal: String = "",
        val perDay: Int = Curriculum.DEFAULT_PER_DAY,
        val completed: List<String> = emptyList(),
        val taught: List<String> = emptyList(),
        val cards: List<StoredCard> = emptyList(),
    )

    private val mutex = Mutex()
    private var cached: Stored? = null
    private var flushJob: Job? = null

    private val _goal = MutableStateFlow("")
    /** The enrolled goal, or blank. Drives whether the screen offers a path or a picker. */
    val goal: StateFlow<String> = _goal.asStateFlow()

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** Every card held, so a screen can show progress without asking. */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    // ---- loading and persistence -------------------------------------------------------------------

    private suspend fun ensureLoaded(): Stored = mutex.withLock { loadLocked() }

    /** Caller must hold [mutex]. */
    private suspend fun loadLocked(): Stored = cached ?: (readFromDisk() ?: Stored()).also { publish(it) }

    /** Caller must hold [mutex]. */
    private fun publish(s: Stored) {
        cached = s
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

    // ---- the enrolled path ---------------------------------------------------------------------------

    private suspend fun entries() = library.index().map { it.toSearchEntry() }

    /** The composed path for the enrolled goal, or null when nothing is enrolled. */
    suspend fun syllabus(): Curriculum.Syllabus? {
        val s = ensureLoaded()
        if (s.goal.isBlank()) return null
        val e = entries()
        if (e.isEmpty()) return null
        return Curriculum.compose(s.goal, e, CATEGORY_SUPERGROUP, perDay = s.perDay)
    }

    /** Which guides are recorded as finished. */
    suspend fun completedIds(): Set<String> = ensureLoaded().completed.toSet()

    /** Every guide ever offered as a lesson. */
    suspend fun taughtIds(): Set<String> = ensureLoaded().taught.toSet()

    /** Goals worth offering, filtered to the ones this library can genuinely support. */
    suspend fun suggestedGoals(): List<String> = Curriculum.suggestions(entries())

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

    // ---- being taught -----------------------------------------------------------------------------------

    /**
     * Turn a guide into questions and schedule them, returning what was created.
     *
     * Draws from the opening sections rather than the whole guide: a guide here runs to a dozen
     * sections, and asking every one at once turns a sitting into a worksheet and buries the queue.
     */
    suspend fun teach(guideId: String, nowMs: Long = System.currentTimeMillis()): List<StudyQuestions.Question> {
        val guide = runCatching { library.guide(guideId) }.getOrNull() ?: return emptyList()
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

    // ---- being asked --------------------------------------------------------------------------------------

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

    // ---- today ---------------------------------------------------------------------------------------------

    /**
     * Today's item, or null when there is nothing to offer.
     *
     * @param interests what the reader has said they care about. The desktop has no profile store, so
     *   this comes from the enrolled goal's own words unless a caller has something better — stated
     *   honestly rather than pretending the phone's profile is available here.
     */
    suspend fun today(
        interests: List<String> = emptyList(),
        pendingTasks: List<String> = emptyList(),
        dayIndex: Int = localDayIndex(),
        nowMs: Long = System.currentTimeMillis(),
    ): DailyLesson.Lesson? {
        val s = ensureLoaded()
        val e = entries()
        return DailyLesson.pick(
            DailyLesson.StudyContext(
                dayIndex = dayIndex,
                dueCount = Recall.dueCount(s.cards.map { it.item().card }, nowMs),
                syllabus = if (s.goal.isBlank() || e.isEmpty()) {
                    null
                } else {
                    Curriculum.compose(s.goal, e, CATEGORY_SUPERGROUP, perDay = s.perDay)
                },
                completed = s.completed.toSet(),
                pendingTasks = pendingTasks,
                interests = interests,
                library = e,
                taught = s.taught.toSet(),
            ),
        )
    }

    // ---- housekeeping ---------------------------------------------------------------------------------------

    suspend fun clear() {
        flushJob?.cancel()
        mutex.withLock { publish(Stored()) }
        withContext(Dispatchers.IO) { runCatching { Files.deleteIfExists(path) } }
    }

    /** Force buffered changes to disk now (e.g. on window close). */
    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    /**
     * Keep the deck bounded.
     *
     * Sorted so the ones to lose come first, then dropped from the front: learned cards go before
     * unlearned ones (they have earned the longest gaps and cost the least to lose), and within each
     * group whatever is furthest from being due. Nothing recently forgotten is ever evicted, because
     * that is precisely the card still worth asking.
     */
    private fun capped(cards: List<StoredCard>): List<StoredCard> {
        if (cards.size <= MAX_CARDS) return cards
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

    /** Disk IO inside the lock — see the note on `DesktopSettingsStore.flush`. */
    private suspend fun flush() {
        mutex.withLock {
            val snapshot = cached ?: return@withLock
            withContext(Dispatchers.IO) {
                runCatching {
                    path.parent?.let(Files::createDirectories)
                    val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                    Files.writeString(tmp, json.encodeToString(Stored.serializer(), snapshot))
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /** A missing or malformed file is left untouched rather than overwritten with defaults. */
    private suspend fun readFromDisk(): Stored? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Files.exists(path)) return@runCatching null
            json.decodeFromString(Stored.serializer(), Files.readString(path))
        }.getOrNull()
    }

    companion object {
        private val defaultJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
