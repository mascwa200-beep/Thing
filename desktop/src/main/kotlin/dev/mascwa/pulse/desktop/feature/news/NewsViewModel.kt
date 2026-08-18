package dev.mascwa.pulse.desktop.feature.news

import dev.mascwa.pulse.desktop.news.Article
import dev.mascwa.pulse.desktop.news.NewsCategory
import dev.mascwa.pulse.desktop.news.NewsRepository
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
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
    val category: NewsCategory = NewsCategory.TOP,
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
) {
    private val _state = MutableStateFlow(NewsUiState())
    val state: StateFlow<NewsUiState> = _state.asStateFlow()

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
    private val live: Flow<NewsCategory?> =
        combine(onScreen, _state.map { it.category }.distinctUntilChanged()) { visible, category ->
            if (visible) category else null
        }

    init {
        scope.launch {
            val saved = runCatching { NewsCategory.valueOf(settings.current().newsCategory) }
                .getOrDefault(NewsCategory.TOP)
            _state.value = _state.value.copy(category = saved)
            load(saved)
        }
        scope.launch {
            // collectLatest, so leaving the screen or switching tab CANCELS the waiting loop rather
            // than leaving it to fire once more into a screen nobody is looking at. Off-screen the
            // collector body returns immediately and there is no timer running at all — which is the
            // whole reason this is a flow and not a `while (true) { delay(); if (visible) … }`.
            live.collectLatest { category ->
                if (category == null) return@collectLatest
                while (true) {
                    delay(nextTickDelayMs(_state.value.lastUpdatedEpochMs, System.currentTimeMillis()))
                    tick(category)
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

    fun select(category: NewsCategory) {
        if (category == _state.value.category && _state.value.articles.isNotEmpty()) return
        _state.value = _state.value.copy(category = category, error = null)
        scope.launch { settings.update { it.copy(newsCategory = category.name) } }
        load(category)
    }

    fun refresh() = load(_state.value.category, force = true)

    /**
     * One beat of the live feed.
     *
     * Forced, because the repository serves anything under ten minutes old from disk and a five-minute
     * tick would otherwise be a no-op every other time. Skipped entirely while a fetch is already in
     * flight — a tick landing on top of a tab switch is two requests for one answer.
     */
    private suspend fun tick(category: NewsCategory) {
        if (job?.isActive == true) return
        if (_state.value.category != category) return
        load(category, force = true, background = true)
    }

    /**
     * @param background a tick nobody asked for. It must not announce itself: no busy bar, and a
     *   failure leaves the headlines on screen with [NewsUiState.refreshFailed] set rather than
     *   replacing them with an error. Wiping a readable page because a background request timed out
     *   would be a worse outcome than the staleness it was trying to fix.
     */
    private fun load(category: NewsCategory, force: Boolean = false, background: Boolean = false) {
        job?.cancel()
        if (!background) _state.value = _state.value.copy(loading = true, error = null)
        job = scope.launch {
            repository.headlines(category, force)
                .onSuccess { fetched ->
                    // Guard against a stale response landing after the user moved on.
                    if (_state.value.category == category) {
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
                    if (_state.value.category != category) return@onFailure
                    _state.value = if (background) {
                        _state.value.copy(loading = false, refreshFailed = true)
                    } else {
                        _state.value.copy(
                            loading = false,
                            error = "Could not load headlines: ${e.message ?: "no connection"}",
                        )
                    }
                }
        }
    }

    companion object {
        /** How often the shown feed is brought up to date while it is being looked at. */
        const val LIVE_INTERVAL_MS = 5 * 60 * 1000L

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
        fun nextTickDelayMs(lastUpdatedEpochMs: Long, nowMs: Long): Long {
            if (lastUpdatedEpochMs <= 0L) return LIVE_INTERVAL_MS
            val age = nowMs - lastUpdatedEpochMs
            return (LIVE_INTERVAL_MS - age).coerceIn(0L, LIVE_INTERVAL_MS)
        }
    }
}
