package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.TaskStatus
import dev.mascwa.pulse.data.tasks.TaskStore

/**
 * Lets J.A.R.V.I.S. keep the user's task board current — the ongoing tasks and goals it tracks across
 * turns. The pending tasks are already injected into the system prompt every turn; this tool is how
 * they get added, advanced and completed.
 *
 * Usage:
 *  - `task <title>` — start tracking a task (optionally `task <title> | <note>`).
 *  - `task list` — show the whole board.
 *  - `task start|block|done|open <title>` — change its status (`block` accepts `| <reason>`).
 *  - `task drop <title>` — stop tracking it.
 */
class TaskTool(
    private val store: TaskStore,
) : JarvisTool {
    override val name = "task"
    override val usage =
        "task <title> | task list | task start <title> | task block <title> | task done <title> | " +
            "task drop <title> — track the user's ongoing tasks & goals (open tasks are shown to you each turn)"

    override suspend fun run(arg: String): String = runCatching {
        val a = arg.trim()
        if (a.isEmpty() || a.equals("list", ignoreCase = true) || a.equals("ls", ignoreCase = true)) {
            return@runCatching store.summary()
        }
        val (verb, rest) = a.splitFirstWord()
        when (verb.lowercase()) {
            "done", "complete", "completed", "finish", "finished" ->
                applyStatus(rest, TaskStatus.DONE, "Done")
            "start", "active", "doing", "begin", "wip" ->
                applyStatus(rest, TaskStatus.ACTIVE, "In progress")
            "block", "blocked", "stuck" ->
                applyStatus(rest, TaskStatus.BLOCKED, "Blocked")
            "open", "reopen", "todo" ->
                applyStatus(rest, TaskStatus.OPEN, "Open")
            "drop", "remove", "delete", "forget", "cancel" -> {
                if (rest.isBlank()) "Which task should I drop, sir?"
                else store.remove(rest)?.let { "Dropped: \"${it.title}\"." } ?: noMatch(rest)
            }
            else -> {
                // No status keyword — treat the whole argument as a new task title (optionally `| note`).
                val (title, note) = a.splitNote()
                store.add(title, note)?.let { "Tracking: \"${it.title}\", sir." }
                    ?: "Give the task a title, sir."
            }
        }
    }.getOrElse { "Task update failed: ${it.message}" }

    private suspend fun applyStatus(rest: String, status: TaskStatus, label: String): String {
        val (query, note) = rest.splitNote()
        if (query.isBlank()) return "Which task, sir?"
        return store.setStatus(query, status, note)?.let { "$label: \"${it.title}\", sir." } ?: noMatch(query)
    }

    private fun noMatch(query: String) = "No tracked task matches \"$query\", sir."

    private fun String.splitFirstWord(): Pair<String, String> {
        val i = indexOfFirst { it.isWhitespace() }
        return if (i < 0) this to "" else substring(0, i) to substring(i + 1).trim()
    }

    private fun String.splitNote(): Pair<String, String> {
        val p = split("|", limit = 2)
        return p[0].trim() to (p.getOrNull(1)?.trim() ?: "")
    }
}
