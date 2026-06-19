package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.memory.MemoryStreamStore

/**
 * J.A.R.V.I.S.'s window into its own **episodic memory on a relative timeline** — the time-aware half
 * of the Mnemosyne stack. Backed by [MemoryStreamStore] (recency·importance·relevance recall) +
 * `core:telemetry/TemporalReasoner` (calendar-free "yesterday" / "3 hours ago" stamping).
 *
 * Usage:
 *  - `history` — the most recent events, newest first, each stamped with how long ago it was.
 *  - `history <topic>` — recall when <topic> last came up, recall-ranked and time-stamped.
 *
 * Read-only and best-effort: never throws, returns an honest empty note when there's nothing yet.
 */
class HistoryTool(
    private val store: MemoryStreamStore,
) : JarvisTool {
    override val name = "history"
    override val usage =
        "history [<topic>] — your episodic memory on a relative timeline; blank = recent events, or pass a topic to recall when it came up"

    override suspend fun run(arg: String): String = runCatching {
        val q = arg.trim()
        val out = if (q.isEmpty()) store.timeline() else store.digest(q)
        out.ifBlank {
            if (q.isEmpty()) "No episodic memories recorded yet, sir."
            else "Nothing in my memory about \"$q\" yet, sir."
        }
    }.getOrElse { "History read failed: ${it.message}" }
}
