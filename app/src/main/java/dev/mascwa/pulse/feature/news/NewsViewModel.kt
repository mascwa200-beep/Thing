package dev.mascwa.pulse.feature.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.EmergencyNews
import dev.mascwa.pulse.core.telemetry.NewsMarketLink
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.core.util.toUserMessage
import dev.mascwa.pulse.data.breaking.BreakingCoverage
import dev.mascwa.pulse.data.breaking.BreakingCoverageRepository
import dev.mascwa.pulse.data.news.Article
import dev.mascwa.pulse.data.news.NewsAnalysis
import dev.mascwa.pulse.data.news.NewsAnalysisEngine
import dev.mascwa.pulse.data.news.NewsAnalysisStore
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.news.NewsRepository
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.social.SocialItem
import dev.mascwa.pulse.data.social.SocialRepository
import dev.mascwa.pulse.feature.social.SocialTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections

data class NewsTab(
    val key: String,
    val title: String,
    val category: NewsCategory?,
    val custom: Boolean,
    val breaking: Boolean = false,
    /** A community source (Lemmy / Hacker News / Mastodon) surfaced as its own News tab, or null. */
    val social: SocialTab? = null,
)

data class NewsUiState(
    val tabs: List<NewsTab> = emptyList(),
    val selectedIndex: Int = 0,
    val content: Async<List<Article>> = Async(loading = true),
    /** market name (from NewsMarketLink) -> today's % change, for the article market strips. */
    val marketPulse: Map<String, Double> = emptyMap(),
    val searchMode: Boolean = false,
    val query: String = "",
)

class NewsViewModel(
    private val repo: NewsRepository,
    private val settings: SettingsRepository,
    private val markets: dev.mascwa.pulse.data.markets.MarketsRepository,
    private val socialRepo: SocialRepository,
    private val analysisEngine: NewsAnalysisEngine,
    private val analysisStore: NewsAnalysisStore,
    private val breakingCoverageRepo: BreakingCoverageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NewsUiState())
    val state: StateFlow<NewsUiState> = _state.asStateFlow()

    // In-memory cache so switching tabs is instant.
    private val cache = mutableMapOf<String, Async<List<Article>>>()

    /** The cloud-LLM "what's really going on" read per article, keyed by url — see [ensureAnalyzed]. Falls
     *  back to the existing heuristic copy (NewsMarketLink/NewsInsights) wherever an entry is missing. */
    val analyses: StateFlow<Map<String, NewsAnalysis>> = analysisStore.analysesFlow

    // Urls currently being analyzed, so rapid recomposition can't fire the same article twice concurrently.
    private val analyzing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    // Caps concurrent cloud calls — a fast scroll through many new cards shouldn't burst-fire requests.
    private val analysisSemaphore = Semaphore(2)

    /** Multi-outlet coverage per article, keyed by url — see [ensureCoverage]. Session-scoped in memory
     *  (persistence-across-restarts already comes free from [BreakingCoverageRepository]'s own disk cache,
     *  keyed by search query) so a "who else is covering this" bias read doesn't refetch every recomposition. */
    private val _coverageByUrl = MutableStateFlow<Map<String, BreakingCoverage>>(emptyMap())
    val coverageByUrl: StateFlow<Map<String, BreakingCoverage>> = _coverageByUrl.asStateFlow()

    private val coverageInFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    // A separate cap from analysisSemaphore — this is a plain network fetch, not a cloud LLM call.
    private val coverageSemaphore = Semaphore(2)

    init {
        viewModelScope.launch {
            // Rebuild tabs whenever the set of custom feeds changes.
            settings.settings
                .map { it.customFeeds.isNotEmpty() }
                .distinctUntilChanged()
                .collect { hasCustom ->
                    val tabs = buildList {
                        // Breaking leads: the freshest stories across topics, "just reported on".
                        add(NewsTab("BREAKING", "Breaking", null, custom = false, breaking = true))
                        NewsCategory.entries.forEach { add(NewsTab(it.name, it.title, it, false)) }
                        // Community sources, each its own tab (Lemmy · Mastodon · Hacker News).
                        SocialTab.entries.forEach { add(NewsTab("SOCIAL_${it.name}", it.label, null, custom = false, social = it)) }
                        if (hasCustom) add(NewsTab("MYFEEDS", "My Feeds", null, true))
                    }
                    _state.update { it.copy(tabs = tabs) }
                    if (_state.value.content.data == null) selectTab(_state.value.selectedIndex)
                }
        }
        viewModelScope.launch {
            // Locale change invalidates the in-memory cache.
            settings.settings
                .map { Triple(it.newsCountry, it.newsLanguage, it.mutedKeywords) }
                .distinctUntilChanged()
                .collect {
                    cache.clear()
                    if (!_state.value.searchMode) selectTab(_state.value.selectedIndex, force = false)
                }
        }
        // The market pulse: fetch the fixed basket once so each article's strip can show its linked
        // markets' live ±% today. Best-effort — a throttled/failed quote just omits its %.
        viewModelScope.launch {
            val pulse = runCatching {
                dev.mascwa.pulse.data.news.NewsMarketPulse.fetch(markets)
            }.getOrDefault(emptyMap())
            if (pulse.isNotEmpty()) _state.update { it.copy(marketPulse = pulse) }
        }
        // Warm the on-device analysis cache from disk once, so a previously-analyzed article's LLM copy
        // is available immediately instead of only after a redundant re-analysis.
        viewModelScope.launch { analysisStore.preload() }
    }

    /**
     * Ask the "what's really going on" cloud analysis for [article], if it doesn't already have one. A
     * no-op when already cached, already failed this session, or already in flight. Fire-and-forget — the
     * result (or lack of one) surfaces reactively via [analyses]; callers keep showing the existing
     * heuristic copy until/unless an entry appears. Never throws (NewsAnalysisEngine is fully defensive).
     */
    fun ensureAnalyzed(article: Article) {
        val url = article.url
        if (analysisStore.get(url) != null) return
        if (analysisStore.hasFailed(url)) return
        if (!analyzing.add(url)) return
        viewModelScope.launch {
            try {
                analysisSemaphore.withPermit {
                    val links = NewsMarketLink.linksFor(article.title, article.summary, article.category)
                    val pulse = _state.value.marketPulse
                    val result = analysisEngine.analyze(
                        title = article.title,
                        summary = article.summary,
                        source = article.source,
                        category = article.category,
                        links = links,
                        livePulse = pulse,
                    )
                    if (result != null) analysisStore.record(url, result) else analysisStore.markFailed(url)
                }
            } finally {
                analyzing.remove(url)
            }
        }
    }

    /**
     * Ask how many outlets — and which — are covering [article]'s story, for the bias-distribution
     * ("COVERAGE") strip. A no-op when already cached this session, already in flight, or when the owner has
     * disabled the strip in Settings. Fire-and-forget, mirrors [ensureAnalyzed]'s shape; results surface
     * reactively via [coverageByUrl]. A genuinely empty result (no cross-outlet matches) is simply not
     * stored, so a later app session can retry rather than caching a permanent "nothing found" — matches
     * [BreakingCoverageRepository]'s own defensive fallback-on-empty behavior.
     */
    fun ensureCoverage(article: Article) {
        val url = article.url
        if (_coverageByUrl.value.containsKey(url)) return
        if (!coverageInFlight.add(url)) return
        viewModelScope.launch {
            try {
                if (!settings.current().showNewsCoverageStrip) return@launch
                coverageSemaphore.withPermit {
                    val query = EmergencyNews.topicQuery(article.title)
                    val result = runCatching {
                        breakingCoverageRepo.coverage(query, maxAgeMs = COVERAGE_MAX_AGE_MS)
                    }.getOrNull()
                    if (result != null && result.sources.isNotEmpty()) {
                        _coverageByUrl.update { it + (url to result) }
                    }
                }
            } finally {
                coverageInFlight.remove(url)
            }
        }
    }

    fun selectTab(index: Int, force: Boolean = false) {
        val tabs = _state.value.tabs
        if (tabs.isEmpty()) return
        val i = index.coerceIn(0, tabs.lastIndex)
        val tab = tabs[i]
        _state.update { it.copy(selectedIndex = i, searchMode = false, query = "") }

        cache[tab.key]?.takeIf { !force && it.data != null }?.let { cached ->
            _state.update { it.copy(content = cached) }
            if (!cached.stale) return
        }

        viewModelScope.launch {
            val flow = MutableStateFlow(cache[tab.key] ?: Async(loading = true))
            _state.update { it.copy(content = flow.value.copy(loading = true)) }
            flow.load(force) { f -> fetchTab(tab, f) }
            cache[tab.key] = flow.value
            // Only commit if still on this tab and not searching.
            if (_state.value.tabs.getOrNull(_state.value.selectedIndex)?.key == tab.key &&
                !_state.value.searchMode
            ) {
                _state.update { it.copy(content = flow.value) }
            }
        }
    }

    private suspend fun fetchTab(tab: NewsTab, force: Boolean): Fetched<List<Article>> = when {
        tab.breaking -> repo.fetchBreaking(force)
        tab.custom -> repo.fetchCustomFeeds(force)
        tab.social != null -> loadSocial(tab.social, force)
        else -> repo.fetchCategory(tab.category!!, force)
    }

    /** Fetch a community source and adapt its items into the news [Article] shape so it renders in the
     *  same list (title · upvotes/comments as the summary · source · thumbnail). */
    private suspend fun loadSocial(src: SocialTab, force: Boolean): Fetched<List<Article>> = when (src) {
        SocialTab.LEMMY -> socialRepo.lemmy(force).let { Fetched(it.data.items.map { i -> i.toArticle(src.label) }, it.fromCache, it.timestampEpochMs) }
        SocialTab.HN -> socialRepo.hackerNews(force).let { Fetched(it.data.items.map { i -> i.toArticle(src.label) }, it.fromCache, it.timestampEpochMs) }
        SocialTab.MASTODON -> socialRepo.mastodon(force).let { Fetched(it.data.statuses.map { i -> i.toArticle(src.label) }, it.fromCache, it.timestampEpochMs) }
    }

    private fun SocialItem.toArticle(cat: String): Article = Article(
        title = title, url = url, summary = meta, source = source,
        publishedEpochMs = publishedEpochMs, imageUrl = thumbnail, category = cat,
    )

    fun refresh() {
        if (_state.value.searchMode) search(_state.value.query)
        else selectTab(_state.value.selectedIndex, force = true)
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _state.update { it.copy(searchMode = false, query = "") }
            selectTab(_state.value.selectedIndex)
            return
        }
        _state.update { it.copy(searchMode = true, query = query, content = it.content.copy(loading = true, error = null)) }
        viewModelScope.launch {
            try {
                val results = repo.search(query)
                _state.update { it.copy(content = Async(data = results, loading = false)) }
            } catch (e: Throwable) {
                _state.update { it.copy(content = Async(loading = false, error = e.toUserMessage())) }
            }
        }
    }

    fun clearSearch() {
        _state.update { it.copy(searchMode = false, query = "") }
        selectTab(_state.value.selectedIndex)
    }

    companion object {
        // "Which outlets covered this headline" is treated as a stable-once-fetched fact, not a
        // freshness-driven read (unlike the 90s default tuned for the time-critical BREAKING NEWS
        // takeover) — a day-long cache avoids re-searching the same story on every app open.
        private const val COVERAGE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
