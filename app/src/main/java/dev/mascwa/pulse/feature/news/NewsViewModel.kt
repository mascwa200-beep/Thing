package dev.mascwa.pulse.feature.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.core.util.toUserMessage
import dev.mascwa.pulse.data.news.Article
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
) : ViewModel() {

    private val _state = MutableStateFlow(NewsUiState())
    val state: StateFlow<NewsUiState> = _state.asStateFlow()

    // In-memory cache so switching tabs is instant.
    private val cache = mutableMapOf<String, Async<List<Article>>>()

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
}
