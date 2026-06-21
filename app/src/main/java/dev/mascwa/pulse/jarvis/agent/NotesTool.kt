package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.notes.NotesStore

/**
 * Lets J.A.R.V.I.S. journal **into the LIBRARY (notes app)** on the user's behalf — write a note, list
 * them, or read one back. Notes are filed snippets/intel (categorised); for a dated personal journal,
 * use the `diary` tool instead.
 *
 * Usage:
 *  - `note <title> | <body>` — file a note (optionally lead with a category: `note [PERSONAL] <title> | <body>`).
 *  - `note <text>` — quick note (title derived from the text).
 *  - `note list` — list saved notes (title · category).
 *  - `note read <title>` — read a note's body back.
 */
class NotesTool(
    private val store: NotesStore,
) : JarvisTool {
    override val name = "note"
    override val usage =
        "note <title> | <body> | note list | note read <title> — write/read the user's notes (LIBRARY); " +
            "lead with a [CATEGORY] to file it (PERSONAL/INTEL/MISSION/IDEAS/GENERAL)"

    override suspend fun run(arg: String): String = runCatching {
        val a = arg.trim()
        when {
            a.isEmpty() || a.equals("list", true) || a.equals("ls", true) -> list()
            a.startsWith("read ", true) || a.startsWith("open ", true) || a.startsWith("show ", true) ->
                read(a.substringAfter(' ', "").trim())
            else -> add(a)
        }
    }.getOrElse { "Note action failed: ${it.message}" }

    private suspend fun add(raw: String): String {
        // Optional leading [CATEGORY].
        var rest = raw
        var category = "PERSONAL"
        val catMatch = Regex("^\\[([A-Za-z ]+)]\\s*").find(rest)
        if (catMatch != null) {
            category = catMatch.groupValues[1].trim().uppercase()
            rest = rest.removeRange(catMatch.range)
        }
        // title | body, or derive a title from the text.
        val (title, body) = if (rest.contains('|')) {
            rest.substringBefore('|').trim() to rest.substringAfter('|').trim()
        } else {
            deriveTitle(rest) to rest.trim()
        }
        val note = store.add(title, body, category)
            ?: return "Give the note something to say, sir."
        return "Filed in the library: \"${note.title}\" (${note.category}), sir."
    }

    private suspend fun read(query: String): String {
        if (query.isBlank()) return "Which note should I read, sir?"
        val notes = store.load()
        val hit = notes.firstOrNull { it.title.equals(query, true) }
            ?: notes.firstOrNull { it.title.contains(query, true) }
            ?: notes.firstOrNull { it.body.contains(query, true) }
            ?: return "No note matches \"$query\", sir."
        return "${hit.title} (${hit.category}):\n${hit.body}"
    }

    private suspend fun list(): String {
        val notes = store.load()
        if (notes.isEmpty()) return "The library is empty, sir."
        return "Library (${notes.size}):\n" + notes.take(30).joinToString("\n") { "• ${it.title} · ${it.category}" }
    }

    /** A short title from the first words of free text (so a quick note still reads well in the list). */
    private fun deriveTitle(text: String): String {
        val firstLine = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        val words = firstLine.split(' ').filter { it.isNotBlank() }
        return words.take(7).joinToString(" ").take(48).ifBlank { "Note" }
    }
}
