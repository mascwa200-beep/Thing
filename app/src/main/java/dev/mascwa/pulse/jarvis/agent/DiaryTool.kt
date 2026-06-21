package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.diary.DiaryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lets J.A.R.V.I.S. keep the user's **DIARY** — a dated personal journal, written on their behalf. This
 * is distinct from `note` (the LIBRARY): the diary is chronological and personal; notes are filed
 * reference snippets. Use this when the user wants to journal, reflect, or record how a day went.
 *
 * Usage:
 *  - `diary <entry>` — add today's entry (title is derived from the text).
 *  - `diary <title> | <entry>` — add with an explicit title. Lead with a `[mood]` to tag it, e.g.
 *    `diary [calm] Quiet day | …`.
 *  - `diary list` — list recent entries (date · title).
 *  - `diary read <query>` — read an entry back (by title/text/date word).
 */
class DiaryTool(
    private val store: DiaryStore,
) : JarvisTool {
    override val name = "diary"
    override val usage =
        "diary <entry> | diary <title> | <entry> | diary list | diary read <query> — keep the user's " +
            "dated personal journal (lead an entry with a [mood] to tag it)"

    private val dateFmt = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())

    override suspend fun run(arg: String): String = runCatching {
        val a = arg.trim()
        when {
            a.isEmpty() || a.equals("list", true) || a.equals("ls", true) -> list()
            a.startsWith("read ", true) || a.startsWith("open ", true) || a.startsWith("show ", true) ->
                read(a.substringAfter(' ', "").trim())
            else -> add(a)
        }
    }.getOrElse { "Diary action failed: ${it.message}" }

    private suspend fun add(raw: String): String {
        var rest = raw
        var mood = ""
        val moodMatch = Regex("^\\[([^]]+)]\\s*").find(rest)
        if (moodMatch != null) {
            mood = moodMatch.groupValues[1].trim()
            rest = rest.removeRange(moodMatch.range)
        }
        val (title, body) = if (rest.contains('|')) {
            rest.substringBefore('|').trim() to rest.substringAfter('|').trim()
        } else {
            deriveTitle(rest) to rest.trim()
        }
        val entry = store.add(title, body, mood)
            ?: return "Give the entry something to record, sir."
        val moodTag = if (entry.mood.isNotBlank()) " [${entry.mood}]" else ""
        return "Journaled for ${dateFmt.format(Date(entry.createdMs))}: \"${entry.title}\"$moodTag, sir."
    }

    private suspend fun read(query: String): String {
        if (query.isBlank()) return "Which entry should I read, sir?"
        val all = store.load()
        val hit = all.firstOrNull { it.title.equals(query, true) }
            ?: all.firstOrNull { it.title.contains(query, true) }
            ?: all.firstOrNull { dateFmt.format(Date(it.createdMs)).contains(query, true) }
            ?: all.firstOrNull { it.body.contains(query, true) }
            ?: return "No diary entry matches \"$query\", sir."
        val moodTag = if (hit.mood.isNotBlank()) " · ${hit.mood}" else ""
        return "${dateFmt.format(Date(hit.createdMs))}$moodTag — ${hit.title}:\n${hit.body}"
    }

    private suspend fun list(): String {
        val all = store.load()
        if (all.isEmpty()) return "The diary is empty, sir."
        return "Diary (${all.size}):\n" + all.take(30).joinToString("\n") {
            "• ${dateFmt.format(Date(it.createdMs))} · ${it.title}" + if (it.mood.isNotBlank()) " (${it.mood})" else ""
        }
    }

    private fun deriveTitle(text: String): String {
        val firstLine = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        val words = firstLine.split(' ').filter { it.isNotBlank() }
        return words.take(7).joinToString(" ").take(48).ifBlank { "Entry" }
    }
}
