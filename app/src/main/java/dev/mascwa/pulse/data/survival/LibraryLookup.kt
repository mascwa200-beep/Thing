package dev.mascwa.pulse.data.survival

import dev.mascwa.pulse.core.telemetry.Guide
import dev.mascwa.pulse.core.telemetry.GuideSearch
import dev.mascwa.pulse.core.telemetry.LibraryConsult

/**
 * Asks the bundled library whether it has anything to say about a question.
 *
 * The one place that decides. The voice service and the chat console both consult the library before
 * asking a model, and each having its own copy of "which guide, which section, how much of it" is how
 * two surfaces quietly start giving different answers to the same question.
 *
 * Cheap enough to call on any turn: the guide index is resident, and a shard is opened only after a
 * guide has already cleared the relevance bar. Both repository calls dispatch their own IO, so this
 * is safe to call from the main thread.
 */
class LibraryLookup(private val content: SurvivalContentRepository) {

    /**
     * What the library found.
     *
     * @param title the guide, for citing.
     * @param where "Guide ▸ Section", for telling a model exactly what it is looking at.
     * @param spoken a couple of sentences plus the citation — an answer a person can hear or read.
     * @param grounding the passage as prompt context, with instructions to ignore it if it misses.
     * @param body the section in full — an emergency protocol is read whole, not in two sentences.
     * @param safety the guide's own warning, already folded into [spoken] and [grounding]. Exposed
     *   separately so a caller that shows the [body] itself can set the warning apart from it, the way
     *   the reader does. Null when the guide carries none.
     */
    data class Found(
        val title: String,
        val where: String,
        val spoken: String,
        val grounding: String,
        val body: String,
        val safety: String? = null,
    )

    /**
     * A named guide and section, fetched exactly rather than searched for.
     *
     * For the emergency table, whose routes are curated precisely so that symptom-to-protocol is
     * never left to a scorer. Ranking is the right tool for a question and the wrong one for
     * "someone is not breathing".
     */
    suspend fun exact(guideId: String, heading: String): Found? {
        val guide = runCatching { content.guide(guideId) }.getOrNull() ?: return null
        val section = guide.sections.firstOrNull { it.heading == heading } ?: return null
        val body = section.body.trim()
        if (body.isBlank()) return null
        val where = guide.title + " ▸ " + section.heading
        return Found(
            title = guide.title,
            where = where,
            spoken = LibraryConsult.spokenAnswer(body, guide.safetyNote, guide.title),
            grounding = LibraryConsult.groundingBlock(where, body, guide.safetyNote),
            body = body,
            safety = guide.safetyNote?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The guides most related to [query], best first — a list of candidates, not an answer.
     *
     * ⚠️ **Deliberately a much lower bar than [consult], and the difference is the point.** `consult`
     * has to decide whether the library can *answer*, so it refuses anything that does not clear the
     * distinctive-word test: a confident paragraph about the wrong subject is worse than admitting
     * the library has nothing. A search result list is a different promise — three related guides
     * under a heading that says "from the offline library" invites the reader to judge, and applying
     * the answer bar there would show an empty list next to genuinely relevant pages.
     *
     * Only the resident index is read; no shard is opened, which is what the sharded loader exists
     * for. The caller fetches prose for whichever hit it actually shows.
     */
    suspend fun rank(query: String, limit: Int): List<GuideSearch.Hit> {
        val index = runCatching { content.index() }.getOrNull().orEmpty()
        if (index.isEmpty()) return emptyList()
        return GuideSearch.rank(index.map { it.toSearchEntry() }, query, limit)
    }

    /** The library's best answer to [query], or null when it genuinely has none. */
    suspend fun consult(query: String): Found? {
        val index = runCatching { content.index() }.getOrNull().orEmpty()
        if (index.isEmpty()) return null

        val entries = index.map { it.toSearchEntry() }
        // The question's rarest word — the bar a guide has to clear to claim it is about the question.
        val key = GuideSearch.distinctiveToken(entries, query) ?: return null
        val entry = GuideSearch.rank(entries, query, limit = 1).firstOrNull()?.entry ?: return null
        if (!LibraryConsult.isTopical(entry, key)) return null

        // Only now is a shard opened — the index is resident, the prose is not.
        val guide = runCatching { content.guide(entry.id) }.getOrNull() ?: return null
        val at = LibraryConsult.bestSection(guide.sections.map { it.heading }, query)
        val section = at?.let { guide.sections.getOrNull(it) }
        val body = (section?.body ?: guide.summary).trim()
        if (body.isBlank()) return null

        val where = if (section == null) guide.title else guide.title + " ▸ " + section.heading
        return Found(
            title = guide.title,
            where = where,
            spoken = LibraryConsult.spokenAnswer(body, guide.safetyNote, guide.title),
            grounding = LibraryConsult.groundingBlock(where, body, guide.safetyNote),
            body = body,
            safety = guide.safetyNote?.trim()?.takeIf { it.isNotBlank() },
        )
    }
}
