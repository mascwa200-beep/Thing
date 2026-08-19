package dev.mascwa.pulse.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Readability
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The reader's state.
 *
 * One fetch, one extraction, one of four outcomes. Deliberately not built on `AsyncLoader`: that
 * models "loaded / failed / stale", and the interesting thing here is a page that loaded perfectly
 * and is still not an article. Collapsing "blocked by a paywall" and "this is a redirect stub" into
 * a generic error is exactly what [Readability] exists to avoid.
 */
class ReaderViewModel(private val c: AppContainer) : ViewModel() {

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _result = MutableStateFlow<Readability.Extraction?>(null)
    val result: StateFlow<Readability.Extraction?> = _result.asStateFlow()

    private var lastUrl: String? = null

    /**
     * Read [url].
     *
     * Re-entrant by design: the screen calls this from a `LaunchedEffect` keyed on the URL, and a
     * configuration change re-runs it. A second call for the same URL while one is in flight is
     * dropped rather than duplicated.
     */
    fun load(url: String) {
        if (url.isBlank()) return
        if (_loading.value && lastUrl == url) return
        lastUrl = url
        viewModelScope.launch {
            _loading.value = true
            _result.value = runCatching { c.readerRepository.read(url) }.getOrElse {
                Readability.Extraction(
                    outcome = Readability.Outcome.NOT_ARTICLE,
                    strategy = Readability.Strategy.NONE,
                    meta = Readability.Meta(),
                    blocks = emptyList(),
                    wordCount = 0,
                    note = "Could not read that page. ${it.message ?: ""}".trim(),
                )
            }
            _loading.value = false
        }
    }

    fun retry() {
        val url = lastUrl ?: return
        _result.value = null
        _loading.value = false
        lastUrl = null
        load(url)
    }

    companion object {
        /**
         * Roughly how long this will take to read, in minutes.
         *
         * 220 words a minute is the middle of the range usually quoted for adult reading of
         * ordinary prose. Rounded up, and never zero — "0 min read" is worse than saying nothing.
         */
        fun minutesToRead(words: Int): Int = ((words + 219) / 220).coerceAtLeast(1)
    }
}
