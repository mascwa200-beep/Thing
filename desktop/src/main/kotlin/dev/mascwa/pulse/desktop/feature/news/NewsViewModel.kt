package dev.mascwa.pulse.desktop.feature.news

import dev.mascwa.pulse.desktop.news.Article
import dev.mascwa.pulse.desktop.news.NewsRepository
import dev.mascwa.pulse.desktop.news.NewsTab
import dev.mascwa.pulse.desktop.news.NewsTabs
import dev.mascwa.pulse.desktop.news.SocialSource
import dev.mascwa.pulse.desktop.reader.ReaderRepository
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.data.social.SocialItem
import dev.mascwa.pulse.data.social.SocialRepository
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.core.telemetry.Readability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class NewsUiState(
    val tab: NewsTab = NewsTabs.DEFAULT,
    val articles: List<Article> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /**
     * When the headlines on screen were actually obtained, and whether they came from storage after a
     * request that did not work. The repository has always known both; until now it discarded them at
     * the boundary, so serving day-old headlines looked exactly like serving live ones.
     */
    val lastUpdatedEpochMs: Long = 0L,
    val servingStored: Boolean = false,
    val refreshFailed: Boolean = false,
)

/**
 * Drives the News screen. A plain class holding a [StateFlow] — Compose Desktop has no AndroidX ViewModel,
 * and the Android `NewsViewModel` is bound to `Context`, so this is an adaptation rather than a port.
 *
 * Switching category cancels the in-flight fetch: without that, tabbing quickly through the rail leaves
 * several requests racing and the last one to *finish* wins, which is not necessarily the tab you are
 * looking at.
 */
class NewsViewModel(
    private val scope: CoroutineScope,
    private val repository: NewsRepository,
    private val settings: DesktopSettingsStore,
    private val reader: ReaderRepository? = null,
    private val social: SocialRepository? = null,
) {
    private val _state = MutableStateFlow(NewsUiState())
    val state: StateFlow<NewsUiState> = _state.asStateFlow()

    // ---- The reader --------------------------------------------------------------------------
    //
    // Kept here rather than in a screen `remember` for the reason the settings store is hoisted out
    // of the composition: state that survives a recomposition and outlives the widget that started
    // it belongs to something that is not the widget. Switching to another screen and back should
    // not silently re-fetch a page that is already read.

    private val _reading = MutableStateFlow<Article?>(null)

    /** The story being read, or null for the list. */
    val reading: StateFlow<Article?> = _reading.asStateFlow()

    private val _extraction = MutableStateFlow<Readability.Extraction?>(null)
    val extraction: StateFlow<Readability.Extraction?> = _extraction.asStateFlow()

    private val _readerBusy = MutableStateFlow(false)
    val readerBusy: StateFlow<Boolean> = _readerBusy.asStateFlow()

    private var readJob: Job? = null

    /**
     * Can this story be read here at all?
     *
     * ⚠️ The same rule the phone routes taps with, from the same mirrored core — most of this feed
     * is Google News links, which are redirect stubs no decimator can read. A READ button that is
     * always offered and usually apologises is worse than one that appears when it will work.
     */
    fun canRead(article: Article): Boolean = reader != null && Readability.canRead(article.url)

    fun read(article: Article) {
        if (!canRead(article)) return
        val repo = reader ?: return
        readJob?.cancel()
        _reading.value = article
        _extraction.value = null
        readJob = scope.launch {
            _readerBusy.value = true
            _extraction.value = runCatching { repo.read(article.url) }.getOrNull()
            _readerBusy.value = false
        }
    }

    fun closeReader() {
        readJob?.cancel()
        _reading.value = null
        _extraction.value = null
        _readerBusy.value = false
    }

    private var job: Job? = null

    /** Whether the News screen is the one being shown. Set by the screen; drives the live feed. */
    private val onScreen = MutableStateFlow(false)

    /**
     * What the live feed should be keeping current, or null when it should be doing nothing at all.
     *
     * Both inputs matter. Visibility, obviously — but also the category, because switching tabs may
     * land on a copy that was cached minutes ago, and the countdown to the next refresh has to restart
     * from *that* copy's age rather than from when the tab was tapped.
     */
    private val live: Flow<NewsTab?> =
        combine(onScreen, _state.map { it.tab }.distinctUntilChanged()) { visible, tab ->
            if (visible) tab else null
        }

    /** The rail. Discussion tabs are dropped when there is no repository behind them, rather than
     *  offered and then apologised for. */
    val tabs: List<NewsTab> =
        if (social != null) NewsTabs.ALL else NewsTabs.ALL.filter { it.social == null }

    init {
        scope.launch {
            val saved = NewsTabs.byKey(settings.current().newsCategory)
                ?.takeIf { it in tabs }
                ?: NewsTabs.DEFAULT
            _state.value = _state.value.copy(tab = saved)
            load(saved)
        }
        scope.launch {
            // collectLatest, so leaving the screen or switching tab CANCELS the waiting loop rather
            // than leaving it to fire once more into a screen nobody is looking at. Off-screen the
            // collector body returns immediately and there is no timer running at all — which is the
            // whole reason this is a flow and not a `while (true) { delay(); if (visible) … }`.
            live.collectLatest { tab ->
                if (tab == null) return@collectLatest
                while (true) {
                    // ⚠️ Re-read every pass rather than captured once: changing the interval in
                    // Settings should take effect on the next tick, not on the next app launch.
                    val every = intervalMs(runCatching { settings.current().refreshMinutes }.getOrDefault(5))
                    // `refreshOnOpen` off means "show what you have until the timer comes round" —
                    // so a cached copy older than the interval waits a full one instead of firing
                    // the moment the tab is opened.
                    val onOpen = runCatching { settings.current().refreshOnOpen }.getOrDefault(true)
                    val since = _state.value.lastUpdatedEpochMs
                    delay(
                        if (onOpen) nextTickDelayMs(since, System.currentTimeMillis(), every)
                        else every,
                    )
                    tick(tab)
                }
            }
        }
    }

    /**
     * The News screen came into or went out of view.
     *
     * ⚠️ Deliberately **not** also gated on the window being minimised. A minimised window parked on
     * News is exactly the "keep it live in the background" case this feature exists for, and one small
     * RSS request every five minutes is nothing. The saving worth having is the other one: sitting on
     * LIBRARY for an hour while News quietly refetches twelve times for nobody.
     */
    fun setOnScreen(visible: Boolean) {
        onScreen.value = visible
    }

    fun select(tab: NewsTab) {
        if (tab == _state.value.tab && _state.value.articles.isNotEmpty()) return
        _state.value = _state.value.copy(tab = tab, error = null)
        scope.launch { settings.update { it.copy(newsCategory = tab.key) } }
        load(tab)
    }

    fun refresh() = load(_state.value.tab, force = true)

    /**
     * One beat of the live feed.
     *
     * Forced, because the repository serves anything under ten minutes old from disk and a five-minute
     * tick would otherwise be a no-op every other time. Skipped entirely while a fetch is already in
     * flight — a tick landing on top of a tab switch is two requests for one answer.
     */
    private suspend fun tick(tab: NewsTab) {
        if (job?.isActive == true) return
        if (_state.value.tab != tab) return
        load(tab, force = true, background = true)
    }

    /**
     * A tab's articles, whichever kind of tab it is.
     *
     * A discussion post has a title, a link, a source and a time, which is everything the list draws —
     * so the feeds are adapted into [Article] rather than given a screen of their own. That is exactly
     * what the phone does, and it is why Lemmy and Hacker News get the reader, the topic tags and the
     * market line for free.
     */
    private suspend fun fetch(tab: NewsTab, force: Boolean): Fetched<List<Article>> {
        val src = tab.social
        if (src == null) return repository.headlines(tab.category!!, force).getOrThrow()
        val repo = social ?: error("no discussion feeds on this build")
        val items = when (src) {
            SocialSource.LEMMY -> repo.lemmy(force).let { it.data.items to it }
            SocialSource.HN -> repo.hackerNews(force).let { it.data.items to it }
            SocialSource.MASTODON -> repo.mastodon(force).let { it.data.statuses to it }
        }
        val (posts, fetched) = items
        return Fetched(
            posts.map { it.asArticle(tab.title) },
            fromCache = fetched.fromCache,
            refreshFailed = fetched.refreshFailed,
            timestampEpochMs = fetched.timestampEpochMs,
        )
    }

    /**
     * ⚠️ The summary is the post's own text where there is one. Using the vote count unconditionally —
     * which is what the phone did until it was fixed — turns an Ask HN thread, which is nothing BUT its
     * text, into a title and "▲ 412 · 88 comments" with the actual question discarded.
     */
    private fun SocialItem.asArticle(category: String) = Article(
        title = title,
        url = url,
        summary = body ?: meta,
        source = source,
        publishedEpochMs = publishedEpochMs,
        category = category,
    )

    /**
     * @param background a tick nobody asked for. It must not announce itself: no busy bar, and a
     *   failure leaves the headlines on screen with [NewsUiState.refreshFailed] set rather than
     *   replacing them with an error. Wiping a readable page because a background request timed out
     *   would be a worse outcome than the staleness it was trying to fix.
     */
    private fun load(tab: NewsTab, force: Boolean = false, background: Boolean = false) {
        job?.cancel()
        if (!background) _state.value = _state.value.copy(loading = true, error = null)
        job = scope.launch {
            runCatching { fetch(tab, force) }
                .onSuccess { fetched ->
                    // Guard against a stale response landing after the user moved on.
                    if (_state.value.tab == tab) {
                        _state.value = _state.value.copy(
                            articles = fetched.data,
                            loading = false,
                            error = null,
                            lastUpdatedEpochMs = fetched.timestampEpochMs,
                            servingStored = fetched.fromCache,
                            refreshFailed = fetched.refreshFailed,
                        )
                    }
                }
                .onFailure { e ->
                    if (_state.value.tab != tab) return@onFailure
                    _state.value = if (background) {
                        _state.value.copy(loading = false, refreshFailed = true)
                    } else {
                        _state.value.copy(
                            loading = false,
                            error = "Could not load ${tab.title}: ${e.message ?: "no connection"}",
                        )
                    }
                }
        }
    }

    companion object {
        /** How often the shown feed is brought up to date while it is being looked at. */
        /** The default, when nothing has said otherwise. The reader's own setting overrides it. */
        const val LIVE_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * Whatever the reader asked for, in milliseconds.
         *
         * ⚠️ The bounds MATCH `SettingsViewModel.setRefreshMinutes`, deliberately. Clamping tighter
         * here than the setter allows would silently honour a stored 120 as 60 — the switch would
         * appear to accept a value and then not use it, which is the defect this whole change
         * exists to remove. One minute is the floor because a stored 0 means a request every
         * millisecond; the ceiling is the setter's.
         */
        fun intervalMs(refreshMinutes: Int): Long =
            (refreshMinutes.coerceIn(1, 240)) * 60_000L

        /**
         * How long to wait before the next live refresh, given how old what is on screen already is.
         *
         * ⚠️ Two edges, and both are the reason this is a named function rather than a bare `delay`.
         * **Nothing loaded yet** (`lastUpdatedEpochMs == 0`) waits a full interval: the opening fetch
         * is already in flight, and treating "no timestamp" as "infinitely old" would fire a second
         * request against the same feed within milliseconds of the first. **Already stale** waits
         * zero, so arriving at a tab whose cached copy is nine minutes old refreshes on arrival
         * instead of showing old news for another five.
         */
        fun nextTickDelayMs(
            lastUpdatedEpochMs: Long,
            nowMs: Long,
            intervalMs: Long = LIVE_INTERVAL_MS,
        ): Long {
            if (lastUpdatedEpochMs <= 0L) return intervalMs
            val age = nowMs - lastUpdatedEpochMs
            return (intervalMs - age).coerceIn(0L, intervalMs)
        }
    }
}
