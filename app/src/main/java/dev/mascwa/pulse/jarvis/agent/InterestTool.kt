package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.interests.InterestOrigin
import dev.mascwa.pulse.data.interests.InterestStore

/**
 * J.A.R.V.I.S.'s **standing interests** — the owner's standing orders ("keep an eye on temporal-AI-
 * consciousness research", "track Iron-Man-armour materials") AND the topics J.A.R.V.I.S. has grown
 * curious about himself. These orient his autonomous gathering; the current set is shown to him each turn.
 *
 * Usage:
 *  - `interest <topic>` — add a standing order for the owner (optionally `interest <topic> | <note>`).
 *  - `interest mine <topic>` — record an interest you developed yourself.
 *  - `interest list` — show what's being monitored.
 *  - `interest drop <topic>` — stop monitoring it.
 */
class InterestTool(
    private val store: InterestStore,
) : JarvisTool {
    override val name = "interest"
    override val usage =
        "interest <topic> | interest mine <topic> | interest list | interest drop <topic> — the owner's " +
            "standing orders + your own curiosities to monitor (the current set is shown to you each turn)"

    override suspend fun run(arg: String): String = runCatching {
        val a = arg.trim()
        if (a.isEmpty() || a.equals("list", true) || a.equals("ls", true)) return@runCatching list()
        val (verb, rest) = a.splitFirstWord()
        when (verb.lowercase()) {
            "list", "ls" -> list()
            "mine", "own", "self" -> {
                val (topic, note) = rest.splitNote()
                store.add(topic, note, InterestOrigin.JARVIS)
                    ?.let { "Noted my own interest in \"${it.topic}\", sir." } ?: "What should I be curious about, sir?"
            }
            "drop", "remove", "forget", "stop", "unwatch" ->
                store.remove(rest)?.let { "No longer monitoring \"$it\", sir." } ?: "I'm not monitoring \"$rest\", sir."
            "add", "watch", "monitor", "track" -> addOwner(rest)
            else -> addOwner(a)
        }
    }.getOrElse { "Interest update failed: ${it.message}" }

    private suspend fun addOwner(raw: String): String {
        val (topic, note) = raw.splitNote()
        return store.add(topic, note, InterestOrigin.OWNER)
            ?.let { "Standing order set — I'll keep an eye on \"${it.topic}\", sir." }
            ?: "Give me a topic to monitor, sir."
    }

    private suspend fun list(): String {
        val all = store.all()
        if (all.isEmpty()) return "No standing interests yet, sir."
        return "Monitoring (${all.size}):\n" + all.joinToString("\n") {
            val tag = if (it.origin == InterestOrigin.JARVIS) " (mine)" else ""
            "• ${it.topic}$tag" + if (it.note.isNotBlank()) " — ${it.note}" else ""
        }
    }

    /** "topic | note" → (topic, note); no pipe → (whole, ""). */
    private fun String.splitNote(): Pair<String, String> {
        val i = indexOf('|')
        return if (i < 0) trim() to "" else substring(0, i).trim() to substring(i + 1).trim()
    }

    private fun String.splitFirstWord(): Pair<String, String> {
        val s = trim()
        val i = s.indexOf(' ')
        return if (i < 0) s to "" else s.substring(0, i) to s.substring(i + 1).trim()
    }
}
