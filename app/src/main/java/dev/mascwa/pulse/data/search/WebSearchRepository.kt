package dev.mascwa.pulse.data.search

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.GuideSearch
import dev.mascwa.pulse.core.telemetry.SearchPlan
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.survival.LibraryLookup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/**
 * A real search, assembled out of the three things this app can actually reach.
 *
 * ⚠️ **The old `web` tool was an entity lookup wearing a search engine's name.** Measured over
 * fourteen queries against DuckDuckGo's Instant Answer API: eight of eight natural-language
 * questions returned nothing, six of six bare nouns returned an abstract. The model, told it had
 * "search the web", used it the way anyone would — as a search engine — and got silence, then
 * reported that silence as the web not knowing.
 *
 * So the tiers here are not a fallback chain bolted on for robustness. They are three genuinely
 * different capabilities, and [SearchPlan] decides which one the question belongs to:
 *
 *  1. **the bundled library** — offline, instant, free, and the best answer in the app to "how do I
 *     treat a burn". Reuses [GuideSearch] and [LibraryLookup], which are already tuned against the
 *     real 651-guide index; a second ranking here would be a duplicated definition, which this repo
 *     has had to converge four times.
 *  2. **Wikipedia** — keyless, enormous, and the right answer to "what is a caldera".
 *  3. **Brave Search** — the actual open web, when the user has supplied a key.
 *
 * ⚠️ **Every result says which tier it came from**, and every failure says which tier failed. A
 * model handed an offline guide, a keyless encyclopaedia summary and a live web result as
 * undifferentiated text will present a stale encyclopaedia sentence as the state of the world today.
 *
 * ⚠️ **Verified only as far as it can be from here.** This container reaches none of these hosts —
 * Wikipedia rate-limits the shared proxy IP, exactly as Commons does. Brave's endpoint was confirmed
 * live only to the extent of a 422 naming the missing subscription header. The pure decision layer
 * is CI-gated and locally tested; the fetching is owner-verify on the phone, which is the same
 * position as every other network feature in this app.
 */
class WebSearchRepository(
    private val http: HttpClient,
    private val settings: SettingsRepository,
    private val library: LibraryLookup,
) {

    /**
     * What a search produced.
     *
     * [verdict] is filled in only when [answers] is empty, and it is the interesting half — it names
     * what was searched and what could not be, so a caller can tell "I looked and there is nothing"
     * from "I could not look".
     */
    data class Outcome(
        val plan: SearchPlan.Plan,
        val answers: List<SearchPlan.Answer>,
        val verdict: String?,
        /** Tiers that were tried and threw, with the reason. Empty when everything behaved. */
        val failures: Map<SearchPlan.Tier, String> = emptyMap(),
    )

    /**
     * Search for [query].
     *
     * Runs the tiers in the plan's order and stops as soon as one has answered — the order already
     * encodes which shelf this question belongs on, so continuing past a good answer would spend a
     * network round trip to add worse results below a better one.
     *
     * ⚠️ Except when [gather] is set, which collects across tiers instead. That is for a screen
     * showing a list; the assistant wants the best answer, not a page of them.
     */
    suspend fun search(query: String, gather: Boolean = false): Outcome {
        val q = query.trim()
        // ⚠️ Settings are read ONCE and the key carried down, rather than read again inside the web
        // tier. Two reads is two snapshots: clear the key between them and the plan would promise a
        // web search that the fetch then declines to make, reporting "nothing found" for a tier that
        // was never actually tried. Harmless today and a genuinely confusing bug the day it happens.
        val braveKey = runCatching { settings.current().apiKeys.brave }.getOrDefault("")
        val plan = SearchPlan.plan(q, SearchPlan.Availability(web = braveKey.isNotBlank()))
        if (!plan.canSearch) {
            return Outcome(plan, emptyList(), SearchPlan.emptyVerdict(q, plan.order, plan.unavailable))
        }

        val found = ArrayList<SearchPlan.Answer>()
        val failures = LinkedHashMap<SearchPlan.Tier, String>()
        for (tier in plan.order) {
            val result = runCatching { fetch(tier, plan, braveKey) }
            result.exceptionOrNull()?.let { failures[tier] = it.message ?: it::class.java.simpleName }
            found += result.getOrDefault(emptyList())
            if (found.isNotEmpty() && !gather) break
        }

        val merged = SearchPlan.merge(found, plan.order)
        return Outcome(
            plan = plan,
            answers = merged,
            // ⚠️ The tiers that FAILED are reported alongside the ones that were never available.
            // "Wikipedia was unreachable" and "Wikipedia is not configured" are different problems,
            // and both are more useful than a bare "nothing found".
            verdict = if (merged.isNotEmpty()) null
            else SearchPlan.emptyVerdict(q, plan.order - failures.keys, plan.unavailable + failures.keys),
            failures = failures,
        )
    }

    private suspend fun fetch(
        tier: SearchPlan.Tier,
        plan: SearchPlan.Plan,
        braveKey: String,
    ): List<SearchPlan.Answer> = when (tier) {
        SearchPlan.Tier.LIBRARY -> fromLibrary(plan.term)
        SearchPlan.Tier.ENCYCLOPAEDIA -> fromWikipedia(plan.term)
        SearchPlan.Tier.WEB -> fromBrave(plan.term, braveKey)
    }

    // ---- the library -----------------------------------------------------------------------------

    /**
     * The bundled guides.
     *
     * ⚠️ Ranks the resident index only — no shard is opened unless a guide is actually chosen. The
     * whole point of the sharded loader is that answering a query does not mean parsing 651 guides.
     */
    private suspend fun fromLibrary(term: String): List<SearchPlan.Answer> {
        val hits = library.rank(term, LIBRARY_HITS)
        if (hits.isEmpty()) return emptyList()
        // ⚠️ `consult` is asked ONCE, for the lead. It applies the strict answer bar and may well
        // return null while `rank` returned three perfectly reasonable candidates — that is the two
        // methods disagreeing correctly, not a bug, and the fallback to the guide's own summary is
        // what makes the disagreement harmless.
        val lead = library.consult(term)?.spoken
        return hits.mapIndexed { i, hit ->
            SearchPlan.Answer(
                tier = SearchPlan.Tier.LIBRARY,
                title = hit.entry.title,
                // The top hit can carry real prose out of the guide; the rest carry their summary,
                // because opening a shard per result would defeat the lazy loader for a list nobody
                // may read past the first line of.
                snippet = (if (i == 0) lead else null) ?: hit.entry.summary,
                url = null,
            )
        }
    }

    // ---- Wikipedia -------------------------------------------------------------------------------

    @Serializable
    private data class WikiSearchResponse(val query: WikiQuery? = null)

    @Serializable
    private data class WikiQuery(val search: List<WikiHit> = emptyList())

    @Serializable
    private data class WikiHit(
        val title: String = "",
        /** HTML with <span class="searchmatch"> around the hit words — stripped before use. */
        val snippet: String = "",
    )

    @Serializable
    private data class WikiSummary(
        val title: String = "",
        val extract: String = "",
        @SerialName("content_urls") val contentUrls: WikiUrls? = null,
    )

    @Serializable
    private data class WikiUrls(val desktop: WikiUrl? = null)

    @Serializable
    private data class WikiUrl(val page: String = "")

    /**
     * Wikipedia, in two steps because one endpoint does each half well.
     *
     * `list=search` finds the right articles for a phrase; the REST summary gives a clean opening
     * paragraph for one of them. The search endpoint's own snippet is HTML fragments around the
     * matched words and reads like a concordance, which is why the top hit is fetched properly and
     * the rest keep the fragment.
     */
    private suspend fun fromWikipedia(term: String): List<SearchPlan.Answer> {
        if (term.isBlank()) return emptyList()
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json" +
            "&srlimit=$WIKI_HITS&utf8=1&srsearch=${enc(term)}"
        val hits = http.getJson(url, WikiSearchResponse.serializer(), UA)
            .query?.search.orEmpty().filter { it.title.isNotBlank() }
        if (hits.isEmpty()) return emptyList()

        val lead = runCatching { summary(hits.first().title) }.getOrNull()
        return hits.mapIndexed { i, hit ->
            SearchPlan.Answer(
                tier = SearchPlan.Tier.ENCYCLOPAEDIA,
                title = hit.title,
                snippet = (if (i == 0) lead?.extract?.takeIf { it.isNotBlank() } else null)
                    ?: stripHtml(hit.snippet),
                url = (if (i == 0) lead?.contentUrls?.desktop?.page else null) ?: articleUrl(hit.title),
            )
        }
    }

    private suspend fun summary(title: String): WikiSummary =
        http.getJson(
            "https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_'))}",
            WikiSummary.serializer(), UA,
        )

    private fun articleUrl(title: String) =
        "https://en.wikipedia.org/wiki/${enc(title.replace(' ', '_'))}"

    // ---- Brave -----------------------------------------------------------------------------------

    @Serializable
    private data class BraveResponse(val web: BraveWeb? = null)

    @Serializable
    private data class BraveWeb(val results: List<BraveResult> = emptyList())

    @Serializable
    private data class BraveResult(
        val title: String = "",
        val url: String = "",
        val description: String = "",
    )

    /**
     * The open web, when a key is configured.
     *
     * ⚠️ The key rides a header, not the query string, so it cannot end up in a log line or a cached
     * URL — which matters because [HttpClient] keeps a shared disk cache keyed on the URL. It is also
     * covered by `allSecretValues()`, so the debug-report scrubber redacts it if it ever appears in a
     * bundle.
     */
    private suspend fun fromBrave(term: String, key: String): List<SearchPlan.Answer> {
        if (key.isBlank() || term.isBlank()) return emptyList()
        val url = "https://api.search.brave.com/res/v1/web/search?count=$WEB_HITS&q=${enc(term)}"
        val headers = mapOf(
            "Accept" to "application/json",
            "Accept-Encoding" to "gzip",
            "X-Subscription-Token" to key,
        )
        return http.getJson(url, BraveResponse.serializer(), headers)
            .web?.results.orEmpty()
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .map {
                SearchPlan.Answer(
                    tier = SearchPlan.Tier.WEB,
                    title = it.title,
                    snippet = stripHtml(it.description),
                    url = it.url,
                )
            }
    }

    // ---- odds and ends ---------------------------------------------------------------------------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Both Wikipedia's search snippets and Brave's descriptions carry `<strong>`-style highlight
     * markup. It is not a document, so this is a tag strip rather than the DOM decimator — reaching
     * for jsoup to clean one sentence would be the wrong tool at a hundred times the cost.
     */
    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]*>"), "")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        const val LIBRARY_HITS = 3
        const val WIKI_HITS = 4
        const val WEB_HITS = 5

        /**
         * ⚠️ Wikipedia's API policy asks for an identifying User-Agent and answers 403 to some
         * default clients. Naming the app and a contact point is the documented requirement, not
         * politeness.
         */
        private val UA = mapOf(
            "User-Agent" to "LCARS/1.0 (personal sideloaded app; https://github.com/mascwa200-beep)",
            "Accept" to "application/json",
        )
    }
}
