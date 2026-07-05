package dev.mascwa.pulse.core.telemetry

/**
 * A durable **task board** for J.A.R.V.I.S. — the user's ongoing tasks and goals, with status, kept
 * across turns and sessions. It is the *deliberate-goal* layer of the assistant's on-device cognitive
 * stack: where [UserProfile] is descriptive (who the user is) and [Cerebellum] is procedural (what
 * actions reliably work), the task board is intentional (what the user is trying to get done) — so
 * J.A.R.V.I.S. can stay oriented and follow up proactively rather than forgetting between turns.
 *
 * This is the pure, CI-tested core: add/dedupe tasks, change their status, match them by title, order
 * the pending ones, render a compact digest for the system prompt, and conservatively detect when a
 * message is the user assigning themselves a task. Storage and wiring live in the app layer.
 *
 * Content note: like the profile, this is on-device only and the user can clear it. It holds task
 * titles the user (or J.A.R.V.I.S. on their behalf) chooses to track — nothing else.
 */
enum class TaskStatus { OPEN, ACTIVE, BLOCKED, DONE }

/** One thing the user is working toward. [note] is an optional detail (e.g. a due date or blocker). */
data class Task(
    val title: String,
    val status: TaskStatus,
    val note: String,
    val createdMs: Long,
    val updatedMs: Long,
)

object TaskBoard {
    const val MAX_TASKS = 60
    const val MAX_TITLE_LEN = 120
    const val MAX_NOTE_LEN = 200
    const val DEFAULT_DIGEST_ITEMS = 8
    const val DEFAULT_DIGEST_CHARS = 600

    /** The result of a status change / removal: the new list and the task that matched (null = none). */
    data class Outcome(val tasks: List<Task>, val matched: Task?)

    /** Strict cues that mark a message as the user assigning *themselves* a task (start-of-message). */
    private val DETECT_CUES = listOf(
        "todo:", "to-do:", "task:", "i need to ", "i have to ", "i've got to ", "ive got to ",
        "i gotta ", "i must ",
    )
    /** Predicates that read as a question/mental-state rather than an actionable task ("i need to know …"). */
    private val DETECT_STOP_VERBS = setOf("know", "understand", "see", "hear", "think")

    // Compiled once — normalize runs inside add/setStatus/remove's O(N) title-match loops, and detect
    // runs on every user message. Regex is immutable/thread-safe, so object-level sharing is safe.
    private val WHITESPACE = Regex("\\s+")
    private val LEADING_FILLER = Regex("(?i)^(ok(ay)?|so|well|right|alright|also|and|um+|uh+)[\\s,]+")
    private val SENTENCE_SPLIT = Regex("[.!?\\n]")

    /** Normalized key for dedupe/matching: lowercase, collapsed whitespace, no surrounding punctuation. */
    fun normalize(text: String): String =
        text.lowercase().trim().trim('.', '!', ',', ';', ':', '"', '\'').replace(WHITESPACE, " ")

    /**
     * Add a task, or reaffirm an existing one with the same title (re-adding a completed task reopens
     * it). [note] overwrites the stored note only when non-blank. Caps the board, evicting a completed
     * task first (oldest), else the stalest task.
     */
    fun add(
        tasks: List<Task>,
        title: String,
        nowMs: Long,
        note: String = "",
        cap: Int = MAX_TASKS,
    ): List<Task> {
        val clean = title.trim().take(MAX_TITLE_LEN)
        if (clean.isBlank()) return tasks
        val cleanNote = note.trim().take(MAX_NOTE_LEN)
        val key = normalize(clean)
        val existing = tasks.firstOrNull { normalize(it.title) == key }
        val updated = if (existing != null) {
            tasks.map {
                if (it === existing) it.copy(
                    status = if (it.status == TaskStatus.DONE) TaskStatus.OPEN else it.status,
                    note = if (cleanNote.isNotBlank()) cleanNote else it.note,
                    updatedMs = nowMs,
                ) else it
            }
        } else {
            tasks + Task(clean, TaskStatus.OPEN, cleanNote, createdMs = nowMs, updatedMs = nowMs)
        }
        if (updated.size <= cap) return updated
        val victim = updated.filter { it.status == TaskStatus.DONE }.minByOrNull { it.updatedMs }
            ?: updated.minByOrNull { it.updatedMs }
        return if (victim != null) updated.filterNot { it === victim } else updated
    }

    /** Set the status of the best title match. [note] updates the matched task's note when non-blank. */
    fun setStatus(
        tasks: List<Task>,
        query: String,
        status: TaskStatus,
        nowMs: Long,
        note: String = "",
    ): Outcome {
        val match = match(tasks, query) ?: return Outcome(tasks, null)
        val cleanNote = note.trim().take(MAX_NOTE_LEN)
        val newTask = match.copy(
            status = status,
            note = if (cleanNote.isNotBlank()) cleanNote else match.note,
            updatedMs = nowMs,
        )
        return Outcome(tasks.map { if (it === match) newTask else it }, newTask)
    }

    /** Drop the best title match. */
    fun remove(tasks: List<Task>, query: String): Outcome {
        val match = match(tasks, query) ?: return Outcome(tasks, null)
        return Outcome(tasks.filterNot { it === match }, match)
    }

    /** The best task for [query]: exact normalized title, else a substring match (pending before done). */
    fun find(tasks: List<Task>, query: String): Task? = match(tasks, query)

    private fun match(tasks: List<Task>, query: String): Task? {
        val key = normalize(query)
        if (key.isBlank()) return null
        tasks.firstOrNull { normalize(it.title) == key }?.let { return it }
        return tasks.filter { normalize(it.title).contains(key) }
            .minWithOrNull(
                compareBy<Task> { if (it.status == TaskStatus.DONE) 1 else 0 }
                    .thenByDescending { it.updatedMs },
            )
    }

    /** Pending tasks (not done), ordered active → blocked → open, most-recently-touched first. */
    fun pending(tasks: List<Task>): List<Task> =
        tasks.filter { it.status != TaskStatus.DONE }
            .sortedWith(compareBy<Task> { order(it.status) }.thenByDescending { it.updatedMs })

    /** Completed tasks, most-recently-completed first. */
    fun completed(tasks: List<Task>): List<Task> =
        tasks.filter { it.status == TaskStatus.DONE }.sortedByDescending { it.updatedMs }

    private fun order(status: TaskStatus): Int = when (status) {
        TaskStatus.ACTIVE -> 0
        TaskStatus.BLOCKED -> 1
        TaskStatus.OPEN -> 2
        TaskStatus.DONE -> 3
    }

    /**
     * Compact digest of the *pending* tasks for the system prompt — one line each, capped by
     * [maxItems] and [maxChars]. Empty when nothing is pending (so no header is added upstream).
     */
    fun digest(
        tasks: List<Task>,
        maxItems: Int = DEFAULT_DIGEST_ITEMS,
        maxChars: Int = DEFAULT_DIGEST_CHARS,
    ): String {
        val open = pending(tasks)
        if (open.isEmpty()) return ""
        val body = open.take(maxItems).joinToString("\n") { line(it) }
        return if (body.length <= maxChars) body else body.take(maxChars - 1).trimEnd() + "…"
    }

    private fun line(task: Task): String {
        val tag = when (task.status) {
            TaskStatus.ACTIVE -> " [in progress]"
            TaskStatus.BLOCKED -> " [blocked]"
            else -> ""
        }
        val note = if (task.note.isNotBlank()) " — ${task.note}" else ""
        return "• ${task.title}$tag$note"
    }

    /** A full human-readable list (pending first, then completed) for the `task` tool's `list`. */
    fun summary(tasks: List<Task>): String {
        if (tasks.isEmpty()) return "No tasks tracked yet."
        val open = pending(tasks)
        val header = "Tracking ${tasks.size} task${if (tasks.size == 1) "" else "s"} (${open.size} open):"
        val lines = (open + completed(tasks)).joinToString("\n") {
            val note = if (it.note.isNotBlank()) " — ${it.note}" else ""
            "• [${label(it.status)}] ${it.title}$note"
        }
        return "$header\n$lines"
    }

    private fun label(status: TaskStatus): String = when (status) {
        TaskStatus.OPEN -> "open"
        TaskStatus.ACTIVE -> "in progress"
        TaskStatus.BLOCKED -> "blocked"
        TaskStatus.DONE -> "done"
    }

    /**
     * A single glanceable nudge for a surface like the home "for you" card: the top pending task,
     * labelled by status, with a count of the rest. Null when nothing is pending.
     */
    fun focus(tasks: List<Task>): String? {
        val open = pending(tasks)
        val top = open.firstOrNull() ?: return null
        val prefix = when (top.status) {
            TaskStatus.ACTIVE -> "In progress: "
            TaskStatus.BLOCKED -> "Blocked: "
            else -> "To do: "
        }
        val more = if (open.size > 1) " (+${open.size - 1} more)" else ""
        return prefix + top.title + more
    }

    /**
     * Return a cleaned task title only when [text] clearly reads as the user assigning *themselves* a
     * task (so questions and passing remarks are ignored). Conservative by design — precision over
     * recall — for always-on auto-capture; the model and the user can always add/drop tasks explicitly.
     */
    fun detect(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.endsWith("?")) return null
        // Strip a short leading filler so "ok, i need to …" still matches.
        val stripped = t.replaceFirst(LEADING_FILLER, "").trim()
        val lower = stripped.lowercase()
        val cue = DETECT_CUES.firstOrNull { lower.startsWith(it) } ?: return null
        var rest = stripped.substring(cue.length).trim()
        rest = rest.split(SENTENCE_SPLIT, limit = 2).first().trim().trimEnd(',', ';', ':')
        if (rest.length < 3) return null
        val firstWord = rest.substringBefore(' ').lowercase()
        if (firstWord in DETECT_STOP_VERBS) return null
        return rest.take(MAX_TITLE_LEN)
    }
}
