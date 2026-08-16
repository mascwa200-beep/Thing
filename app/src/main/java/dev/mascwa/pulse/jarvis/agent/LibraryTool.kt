package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.EmergencyTriage
import dev.mascwa.pulse.core.telemetry.GuideSearch
import dev.mascwa.pulse.data.survival.Guide
import dev.mascwa.pulse.data.survival.GuideIndexEntry
import dev.mascwa.pulse.data.survival.SurvivalContentRepository
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/**
 * The bundled library — hundreds of written guides across dozens of categories — read from the console.
 *
 * The app is a Library Computer Access and Retrieval System and until now the library was the one
 * thing it could not access: the corpus was reachable only from the Guides screen. This is the
 * retrieval half.
 *
 * Two properties make it worth reaching for over a web search: it is **curated**, so an answer can
 * name the guide it came from and be checked; and it is **bundled**, so it works with no signal, no
 * key and no account — in exactly the conditions much of its content is written for.
 *
 * Read-only.
 */
class LibraryTool(private val content: SurvivalContentRepository) : JarvisTool {
    override val name = "library"
    override val usage =
        "library <question> — search the bundled offline guide library (works with no signal). " +
            "`library read <id>` lists a guide's sections; `library read <id> <section words>` reads one " +
            "section in full; `library categories` lists what the library covers"

    override suspend fun run(arg: String): String {
        val a = arg.trim()
        return when {
            a.isBlank() -> "Ask the library a question, e.g. `library how do I purify water`."
            a.equals("categories", true) -> categories()
            // substring, not removePrefix: the match above is case-insensitive and removePrefix is not,
            // so "Read foo" would otherwise fall through with the verb still attached.
            a.startsWith("read ", true) -> read(a.substring(5).trim())
            else -> search(a)
        }
    }

    // ---- search ----------------------------------------------------------------------------------

    /** How many guides to name. Enough to choose between, few enough to read. */
    private val maxHits = 5

    /**
     * A body scan reads every shard, so it is only run when the index came back thin — and then only
     * for the query's rarest word, and only for as many shards as it takes to fill the list.
     */
    private val scanShards = 4

    private suspend fun search(query: String): String {
        // Before ranking, never after. The scorer is good at the library and dangerous at an
        // emergency -- it answers "stroke symptoms" with an article on two-stroke engines -- so a
        // recognised emergency is routed by hand and the ranked list becomes the footnote.
        EmergencyTriage.match(query)?.let { return emergency(it) }

        val index = runCatching { content.index() }.getOrDefault(emptyList())
        if (index.isEmpty()) return "The library isn't available right now."

        val entries = index.map { it.toSearchEntry() }
        val hits = GuideSearch.rank(entries, query, limit = maxHits)

        // The index knows a title, a summary and the section headings. When that is not enough to
        // place the question, the words are somewhere in the bodies or nowhere at all.
        val extra: List<GuideIndexEntry> = if (hits.size < 3) {
            val token = GuideSearch.distinctiveToken(entries, query)
            if (token == null) emptyList() else {
                val ids = runCatching {
                    content.searchBodies(token).take(scanShards).toList().flatten()
                }.getOrDefault(emptyList()).toSet()
                val already = hits.map { it.entry.id }.toSet()
                index.filter { it.id in ids && it.id !in already }.take(maxHits - hits.size)
            }
        } else emptyList()

        if (hits.isEmpty() && extra.isEmpty()) {
            return "Nothing in the library covers \"$query\". Say so rather than guessing — " +
                "it holds ${index.size} guides but it does not hold everything."
        }

        return buildString {
            append("Library matches for \"").append(query).append("\":\n")
            hits.forEach { h -> appendEntry(h.entry.id, h.entry.title, h.entry.category, h.entry.summary) }
            extra.forEach { e -> appendEntry(e.id, e.title, e.category, e.summary, viaBody = true) }
            append("\nRead one with `library read <id>`, then cite it by title in your answer.")
        }
    }

    /**
     * A recognised emergency: the action, then the protocol, then nothing else.
     *
     * The section text is inlined rather than named, because telling someone mid-emergency to make a
     * second tool call to read the thing they need is a design that only works when nothing is wrong.
     */
    private suspend fun emergency(e: EmergencyTriage.Emergency): String = buildString {
        append(EmergencyTriage.brief(e))
        val gid = e.guideId
        val heading = e.section
        if (gid != null && heading != null) {
            val guide = runCatching { content.guide(gid) }.getOrNull()
            val section = guide?.sections?.firstOrNull { it.heading == heading }
            if (guide != null && section != null) {
                append("\n\n— from \"").append(guide.title).append("\" ▸ ").append(section.heading)
                append(" (bundled library; cite it) —\n\n")
                append(section.body.trim())
            } else {
                // The CI guard should make this unreachable; if content moved anyway, the first
                // action above still stands and is the part that matters.
                append("\n\nThe protocol page could not be opened. The action above still applies.")
            }
        }
        append("\n\nThis is written guidance, not training and not medical advice.")
    }

    private fun StringBuilder.appendEntry(
        id: String, title: String, category: String, summary: String, viaBody: Boolean = false,
    ) {
        append("\n• ").append(title).append("  [").append(category).append("]")
        if (viaBody) append("  (mentioned in the text)")
        append("\n  id: ").append(id)
        summary.trim().takeIf { it.isNotBlank() }?.let { append("\n  ").append(it.oneLine(200)) }
    }

    // ---- read ------------------------------------------------------------------------------------

    private suspend fun read(rest: String): String {
        if (rest.isBlank()) return "Which guide? Use the id from a `library` search."
        // "<id> <section words>" — the id never contains a space, so the split is unambiguous.
        val id = rest.substringBefore(' ').trim()
        val wanted = rest.substringAfter(' ', "").trim()
        val guide = runCatching { content.guide(id) }.getOrNull()
            ?: return "No guide with id \"$id\". Search first with `library <question>`."
        return if (wanted.isBlank()) outline(guide) else section(guide, wanted)
    }

    /**
     * The guide's shape rather than its text.
     *
     * A guide runs to a dozen or more sections of several hundred words each — far past what a single
     * tool result may carry — so reading one means choosing a section first. The safety note is the
     * exception and is always shown: it is the part that matters before any of the rest.
     */
    private fun outline(g: Guide): String = buildString {
        append(g.title).append("  [").append(g.category).append("]\n")
        append(g.summary.oneLine(400)).append("\n")
        g.safetyNote?.trim()?.takeIf { it.isNotBlank() }?.let {
            append("\nSAFETY — ").append(it.oneLine(400)).append("\n")
        }
        append("\nSections (read one with `library read ").append(g.id).append(" <words from a heading>`):")
        g.sections.forEach { append("\n  · ").append(it.heading) }
    }

    private fun section(g: Guide, wanted: String): String {
        val s = g.sections.firstOrNull { it.heading.equals(wanted, true) }
            ?: g.sections.firstOrNull { it.heading.contains(wanted, true) }
            ?: g.sections.firstOrNull { h -> wanted.split(' ').any { it.length > 3 && h.heading.contains(it, true) } }
            ?: return "\"$wanted\" isn't a section of ${g.title}. Its sections are:" +
                g.sections.joinToString("") { "\n  · " + it.heading }
        return buildString {
            append(g.title).append(" — ").append(s.heading).append("\n\n")
            append(s.body.trim())
            s.ingredients?.takeIf { it.isNotEmpty() }?.let { list ->
                append("\n\nYou need:"); list.forEach { append("\n  - ").append(it) }
            }
            s.steps?.takeIf { it.isNotEmpty() }?.let { list ->
                append("\n\nSteps:"); list.forEachIndexed { i, st -> append("\n  ").append(i + 1).append(". ").append(st) }
            }
            append("\n\n(from \"").append(g.title).append("\" in the bundled library — cite it.)")
        }
    }

    // ---- categories ------------------------------------------------------------------------------

    private suspend fun categories(): String {
        val index = runCatching { content.index() }.getOrDefault(emptyList())
        if (index.isEmpty()) return "The library isn't available right now."
        val byCat = index.groupingBy { it.category }.eachCount().toList().sortedBy { it.first }
        return buildString {
            append("The library holds ").append(index.size).append(" guides across ")
                .append(byCat.size).append(" categories:")
            byCat.forEach { (cat, n) -> append("\n  · ").append(cat).append(" (").append(n).append(")") }
        }
    }
}

private fun GuideIndexEntry.toSearchEntry() =
    GuideSearch.Entry(id = id, title = title, category = category, summary = summary, headings = headings)

/** Collapse whitespace and cap, so one entry stays one readable line in a tool result. */
private fun String.oneLine(max: Int): String {
    val flat = trim().replace(Regex("\\s+"), " ")
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}
