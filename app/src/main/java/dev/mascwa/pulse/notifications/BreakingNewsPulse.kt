package dev.mascwa.pulse.notifications

import dev.mascwa.pulse.core.telemetry.EmergencyNews
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The breaking-news TAKEOVER check, shared by the periodic [RefreshWorker] and the resident live poller.
 * Since the one-notification consolidation this fires exactly ONE thing: on a fresh MAJOR event
 * ([EmergencyNews.isMajor] — a death, a disaster) it launches the full-screen takeover via
 * [TakeoverLauncher] (direct activity start when "display over other apps" is granted; the full-screen
 * intent otherwise). Every lesser headline reaches the user through the one LCARS board instead
 * ([BriefEngine] renders the top/emergency story as the board's NEWS row) — the retired per-headline
 * breaking/"this just in" notifications are gone by the owner's explicit instruction.
 *
 * The force-fetches here double as the cache warm-up [BriefEngine] reads from. First run only seeds the
 * seen-set (no takeover on launch); per-title dedup (`seenTopUrls` reused for titles + `breakingInterruptSeen`)
 * stops the same story repeating — no timing throttle, per the owner's standing max-frequency choice.
 */
object BreakingNewsPulse {

    private const val STATE_KEY = "notify_state"

    suspend fun check(container: AppContainer) = withContext(Dispatchers.IO) {
        val prefs = container.settingsRepository.current().notifications
        val state = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: NotifyState()
        val top = container.newsRepository.fetchCategory(NewsCategory.TOP, force = true).data
        // Emergencies break ANYWHERE — also scan the global WORLD feed, not just the region-focused TOP.
        val world = runCatching {
            container.newsRepository.fetchCategory(NewsCategory.WORLD, force = true).data
        }.getOrDefault(emptyList())
        // Dedup by TITLE, not URL: Google News RSS item URLs mutate between fetches (tracking/redirect
        // params), so URL-keyed dedup re-fired the same headlines every poll. Titles are stable.
        val seen = state.seenTopUrls.toSet() // field reused to hold seen titles
        val firstRun = seen.isEmpty()
        val fresh = top.filter { it.title.isNotBlank() && it.title !in seen }
        val emergencyPool = (fresh + world.filter { it.title.isNotBlank() && it.title !in seen }).distinctBy { it.title }
        var firedInterrupt: String? = null
        var firedInterruptMs = 0L
        if (!firstRun && prefs.breakingInterrupt) {
            val major = emergencyPool.firstOrNull {
                it.title !in state.breakingInterruptSeen && EmergencyNews.isMajor(it.title, it.summary)
            }
            if (major != null) {
                TakeoverLauncher.launch(
                    context = container.applicationContext,
                    notifier = container.notifier,
                    headline = major.title,
                    query = EmergencyNews.topicQuery(major.title),
                )
                firedInterrupt = major.title
                firedInterruptMs = System.currentTimeMillis()
            }
        }
        // Accumulate seen titles (bounded) so an item briefly leaving a feed won't re-fire. Re-read the
        // LATEST state immediately before writing and touch ONLY our fields — this poller shares
        // `notify_state` with RefreshWorker/BriefEngine, and the slow news fetch above means our snapshot
        // could be stale by now.
        val latest = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: state
        val merged = (latest.seenTopUrls + top.take(20).map { it.title } + world.take(20).map { it.title })
            .filter { it.isNotBlank() }.distinct().takeLast(100)
        var updated = latest.copy(seenTopUrls = merged)
        if (firedInterrupt != null) {
            updated = updated.copy(
                breakingInterruptLastMs = firedInterruptMs,
                breakingInterruptSeen = (latest.breakingInterruptSeen + firedInterrupt).distinct().takeLast(50),
            )
        }
        container.diskCache.write(STATE_KEY, updated, NotifyState.serializer())
    }
}
