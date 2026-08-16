package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.data.search.DeviceSearchIndex
import dev.mascwa.pulse.di.AppContainer

/**
 * One search across everything on this device — guides, notes, diary, memory, tasks, profile and
 * findings — in a single call.
 *
 * The pieces were each reachable already: `library` for the guides, `note` and `diary` for what the
 * user wrote, `recall` for the memory stream, `task`, `profile`, `finding`. But "what do I know
 * about X" is one question, and answering it meant guessing which store to open and then guessing
 * again when the guess was wrong. This is the question as asked.
 *
 * It does **not** replace those tools. Each of them writes as well as reads, and each returns the
 * full text of what it finds; this returns a ranked map of where the answer lives, so the right
 * follow-up is a targeted read rather than a second search.
 *
 * Read-only, offline, no permission.
 */
class DeviceSearchTool(private val container: AppContainer) : JarvisTool {
    override val name = "search"
    override val usage =
        "search <query> — search EVERYTHING on this device at once (guides, notes, diary, memory, " +
            "tasks, profile, findings), ranked, offline. Use it when you don't know which store holds " +
            "the answer; then read the specific one with `library read`, `note`, `diary` or `recall`"

    override suspend fun run(arg: String): String {
        val query = arg.trim()
        if (query.isBlank()) return "Search for what? e.g. `search passport renewal`."

        val records = runCatching { DeviceSearchIndex.records(container) }.getOrDefault(emptyList())
        if (records.isEmpty()) return "Nothing on this device is searchable right now."

        val hits = DeviceSearch.search(records, query, limit = LIMIT, perKind = PER_KIND)
        if (hits.isEmpty()) {
            val held = DeviceSearch.corpusSummary(records)
                .joinToString(", ") { (k, n) -> "$n ${k.label.lowercase()}${if (n == 1) "" else "s"}" }
            return "Nothing on this device matches \"$query\". Searched $held. " +
                "Say so plainly rather than guessing — then answer from what you know, or the web."
        }

        return buildString {
            append("On this device, for \"").append(query).append("\":")
            DeviceSearch.byKind(hits).forEach { (kind, group) ->
                append("\n\n").append(kind.label.uppercase()).append(":")
                group.forEach { r ->
                    append("\n  • ").append(r.title.oneLine(TITLE_CHARS))
                    append("\n    id: ").append(r.id)
                    r.record.entry.summary.trim().takeIf { it.isNotBlank() }?.let {
                        append("\n    ").append(it.oneLine(SUMMARY_CHARS))
                    }
                }
            }
            append("\n\nRead a guide with `library read <id>`. Cite whatever you use by name.")
        }
    }

    private companion object {
        const val LIMIT = 12
        const val PER_KIND = 4
        const val TITLE_CHARS = 120
        const val SUMMARY_CHARS = 220
    }
}

/** Collapse whitespace and cap, so one entry stays one readable line in a tool result. */
private fun String.oneLine(max: Int): String {
    val flat = trim().replace(Regex("\\s+"), " ")
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}
