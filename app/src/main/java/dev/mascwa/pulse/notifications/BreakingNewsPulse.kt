package dev.mascwa.pulse.notifications

import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One breaking-news check, shared by the periodic [RefreshWorker] and the resident live poller so
 * dedup (`seenTopUrls`) and the notification stay consistent no matter which path fires it. It is
 * deliberately lightweight — only the TOP feed, force-fetched for freshness — so it is cheap enough
 * to run on a short interval. The first run only seeds the seen-set (no notification spam on launch).
 */
object BreakingNewsPulse {

    private const val STATE_KEY = "notify_state"

    suspend fun check(container: AppContainer) = withContext(Dispatchers.IO) {
        val state = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: NotifyState()
        val top = container.newsRepository.fetchCategory(NewsCategory.TOP, force = true).data
        // Dedup by TITLE, not URL: Google News RSS item URLs mutate between fetches (tracking/redirect
        // params), so URL-keyed dedup re-fired the same headlines every poll. Titles are stable.
        val seen = state.seenTopUrls.toSet() // field reused to hold seen titles
        val firstRun = seen.isEmpty()
        val fresh = top.filter { it.title.isNotBlank() && it.title !in seen }
        if (!firstRun && fresh.isNotEmpty()) {
            val lead = fresh.first()
            val extra = if (fresh.size > 1) " (+${fresh.size - 1} more)" else ""
            container.notifier.notifyBreaking(
                id = 1001,
                title = "Breaking" + (lead.source.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                body = lead.title + extra,
            )
        }
        // Accumulate seen titles (bounded) so an item briefly leaving the top list won't re-alert.
        val merged = (state.seenTopUrls + top.take(20).map { it.title }).filter { it.isNotBlank() }.distinct().takeLast(60)
        container.diskCache.write(STATE_KEY, state.copy(seenTopUrls = merged), NotifyState.serializer())
    }
}
