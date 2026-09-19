package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.data.search.DeviceSearchIndex
import dev.mascwa.pulse.di.AppContainer

/**
 * One search across everything on this device, in a single call — the guides, what the user wrote,
 * what the assistant has learned, **and the app's own screens and settings**.
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
 * ## Why the corpus list is spelled out, and gated
 *
 * ⚠️ **This description named seven kinds while the index emitted ten**, and the three it left out
 * were screens, settings and ingested documents — two of which were added in the same session that
 * found this. [usage] is the model's whole basis for choosing a tool, so a corpus it does not
 * mention is a corpus the model has no reason to look in: *"where do I turn on sponsor skipping"* is
 * answerable from here and would never have been routed here.
 *
 * That is the owner's own twice-repeated complaint — "I can't find it" — one layer up, and it is the
 * two-independent-statements shape this repository keeps correcting: `DeviceSearchIndex` grew, and
 * nothing made this sentence grow with it. [AgentToolSurfaceTest] is what makes them move together.
 *
 * The grouped output needs no such fix and never had this problem: it is built from
 * [DeviceSearch.byKind], so each kind heads its own block and the model sees the split that
 * `SearchScreen` had to be taught. Only the prose was stale.
 *
 * Read-only, offline, no permission.
 */
class DeviceSearchTool(private val container: AppContainer) : JarvisTool {
    override val name = "search"
    override val usage =
        "search <query> — search EVERYTHING on this device at once (guides, notes, diary, memory, " +
            "tasks, profile, findings, ingested documents, and the app's own screens and settings), " +
            "ranked, offline. Use it whenever you don't know where the answer lives — including " +
            "\"where do I turn on X\", which finds the settings row. Then read the specific one with " +
            "`library read`, `note`, `diary` or `recall`, or go there with `open`"

    override suspend fun run(arg: String): String {
        val query = arg.trim()
        if (query.isBlank()) return "Search for what? e.g. `search passport renewal`."

        val records = runCatching { DeviceSearchIndex.records(container) }.getOrDefault(emptyList())
        if (records.isEmpty()) return "Nothing on this device is searchable right now."

        val hits = DeviceSearch.search(records, query, limit = LIMIT, perKind = PER_KIND)
        if (hits.isEmpty()) {
            val held = DeviceSearch.corpusSummary(records)
                .joinToString(", ") { (k, n) -> k.count(n) }
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
            // ⚠️ Names `open` as well as `library read`, because the corpus now holds places as well
            // as readings and a SCREEN or SETTING row's id IS its route — that is the invariant
            // `RecordKind.destination` carries. Telling the model only how to read would leave the
            // two kinds it most needs to ACT on looking like something to quote.
            append("\n\nRead a guide with `library read <id>`. A Feature or Setting row's id is its ")
            append("route — go there with `open`. Cite whatever you use by name.")
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
