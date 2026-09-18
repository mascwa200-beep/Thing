package dev.mascwa.pulse.data.breaking

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.data.news.Article
import dev.mascwa.pulse.data.news.NewsRepository
import kotlinx.serialization.Serializable

/**
 * Aggregated coverage of a single breaking topic, drawn from many trusted, FREE sources at once — the
 * payload behind the full-screen BREAKING NEWS takeover. `articles` are de-duped and ordered
 * trusted-source-first then newest-first; `sources` is the distinct outlet list (for a "SOURCES" tab).
 */
@Serializable
data class BreakingCoverage(
    val query: String,
    val articles: List<Article> = emptyList(),
    val sources: List<String> = emptyList(),
    val fetchedAtMs: Long = 0L,
) {
    val isEmpty: Boolean get() = articles.isEmpty()
}

/**
 * Pulls together everything free-and-trusted that's being said about a breaking topic. It leans on the
 * keyless Google News RSS SEARCH (via [NewsRepository.search]) — which itself aggregates Reuters, AP, BBC,
 * the Guardian, NPR and the rest — then floats the most authoritative outlets to the top. No API keys, no
 * ads (we render the aggregated list ourselves), short-cached because breaking coverage is time-sensitive.
 */
class BreakingCoverageRepository(
    private val news: NewsRepository,
    private val cache: DiskCache,
) {

    /** [maxAgeMs] lets a non-time-critical caller (e.g. a lazily-loaded per-article strip in a normal
     *  scrolling news list) treat "which outlets covered this" as a longer-lived fact than the 90s default
     *  tuned for the time-critical BREAKING NEWS takeover — same cache, same fetch/dedupe, just a different
     *  freshness bar. */
    suspend fun coverage(query: String, force: Boolean = false, maxAgeMs: Long = TTL_MS): BreakingCoverage {
        val q = query.trim().ifBlank { return BreakingCoverage(query) }
        val key = "breaking_cov_${q.lowercase().hashCode()}"
        if (!force) {
            runCatching { cache.read(key, maxAgeMs, BreakingCoverage.serializer())?.value }.getOrNull()?.let { return it }
        }
        val raw = runCatching { news.search(q) }.getOrDefault(emptyList())
        if (raw.isEmpty()) {
            // Network failed / no results — serve any stale cache rather than a blank takeover.
            return runCatching { cache.readAny(key, BreakingCoverage.serializer())?.value }.getOrNull()
                ?: BreakingCoverage(q)
        }
        val sorted = raw.distinctBy { it.url }.sortedWith(
            compareByDescending<Article> { isTrusted(it.source) }.thenByDescending { it.publishedEpochMs },
        )
        val sources = sorted.map { it.source }.filter { it.isNotBlank() }.distinct()
        val result = BreakingCoverage(q, sorted, sources, System.currentTimeMillis())
        runCatching { cache.write(key, result, BreakingCoverage.serializer()) }
        return result
    }

    companion object {
        private const val TTL_MS = 90_000L // coverage is time-sensitive; a short cache only

        /**
         * True when an outlet name is one of the widely-trusted, free-to-read organisations.
         *
         * ⚠️ On the companion rather than the instance because callers that want the JUDGEMENT
         * should not have to construct a repository — which drags in the news repository and a
         * disk cache — to ask it. The home-screen widget needs exactly this and nothing else.
         *
         * It moved here because the rule had already been written twice: this one, and a private
         * copy in `BreakingNewsScreen` restating `TRUSTED.any { ... }` by hand. Two statements of
         * one rule is how they drift, and a third was about to be added.
         *
         * (Unqualified calls from this class's own instance methods still resolve here, so the
         * ordering at [coverage] is unchanged.)
         */
        fun isTrusted(source: String): Boolean {
            val s = source.lowercase()
            return TRUSTED.any { s.contains(it) }
        }

        /** Widely-trusted, free-to-read news organisations — floated to the top of the coverage. */
        val TRUSTED = listOf(
            "reuters", "associated press", "ap news", "bbc", "guardian", "npr", "al jazeera", "pbs",
            "new york times", "washington post", "bloomberg", "financial times", "the independent",
            "sky news", "abc news", "cbs news", "nbc news", "cnn", "afp", "france 24", "deutsche welle",
            "the times", "usa today", "politico", "axios", "the hill", "the economist", "propublica",
        )
    }
}
