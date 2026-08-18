package dev.mascwa.pulse.feature.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.social.MastodonTrends
import dev.mascwa.pulse.data.social.SocialFeed
import dev.mascwa.pulse.data.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SocialTab(val label: String) { LEMMY("Lemmy"), MASTODON("Mastodon"), HN("Hacker News") }

class SocialViewModel(
    private val repo: SocialRepository,
) : ViewModel() {

    private val _tab = MutableStateFlow(SocialTab.LEMMY)
    val tab: StateFlow<SocialTab> = _tab.asStateFlow()

    private val _lemmy = MutableStateFlow<Async<SocialFeed>>(Async())
    val lemmy: StateFlow<Async<SocialFeed>> = _lemmy.asStateFlow()

    private val _mastodon = MutableStateFlow<Async<MastodonTrends>>(Async())
    val mastodon: StateFlow<Async<MastodonTrends>> = _mastodon.asStateFlow()

    private val _hn = MutableStateFlow<Async<SocialFeed>>(Async())
    val hn: StateFlow<Async<SocialFeed>> = _hn.asStateFlow()

    init { ensureLoaded(SocialTab.LEMMY, force = false) }

    fun select(tab: SocialTab) {
        _tab.value = tab
        ensureLoaded(tab, force = false)
    }

    fun refresh() = ensureLoaded(_tab.value, force = true)

    /**
     * Load a tab unless it already holds something worth showing.
     *
     * ⚠️ "Worth showing" used to mean non-null, and an empty feed is non-null. So once a tab had
     * settled on nothing — which a swallowed outage could produce — leaving the tab and coming back
     * never reloaded it, and the only other way in was a pull gesture that a non-scrollable empty
     * state cannot deliver. The feed stayed empty for the life of the view model.
     *
     * Re-asking is cheap: the repository serves from a ten-minute disk cache, so a genuinely quiet
     * feed costs a disk read per visit rather than a request.
     */
    private fun ensureLoaded(tab: SocialTab, force: Boolean) {
        when (tab) {
            SocialTab.LEMMY -> if (force || _lemmy.value.data?.items.isNullOrEmpty())
                viewModelScope.launch { _lemmy.load(force) { repo.lemmy(it) } }
            SocialTab.MASTODON -> if (force || _mastodon.value.data.let { it == null || (it.tags.isEmpty() && it.statuses.isEmpty()) })
                viewModelScope.launch { _mastodon.load(force) { repo.mastodon(it) } }
            SocialTab.HN -> if (force || _hn.value.data?.items.isNullOrEmpty())
                viewModelScope.launch { _hn.load(force) { repo.hackerNews(it) } }
        }
    }
}
