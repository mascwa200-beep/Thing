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
        val currentUrls = top.take(20).map { it.url }
        val firstRun = state.seenTopUrls.isEmpty()
        val fresh = top.filter { it.url !in state.seenTopUrls }
        if (!firstRun && fresh.isNotEmpty()) {
            val lead = fresh.first()
            val extra = if (fresh.size > 1) " (+${fresh.size - 1} more)" else ""
            container.notifier.notifyBreaking(
                id = 1001,
                title = "Breaking" + (lead.source.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                body = lead.title + extra,
            )
        }
        container.diskCache.write(STATE_KEY, state.copy(seenTopUrls = currentUrls), NotifyState.serializer())
    }
}
