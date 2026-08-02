package dev.mascwa.pulse.notifications

import dev.mascwa.pulse.core.telemetry.EmergencyNews
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One breaking-news check, shared by the periodic [RefreshWorker] and the resident live poller so
 * dedup (`seenTopUrls`) and the notification stay consistent no matter which path fires it. It is
 * deliberately lightweight — the TOP feed for the general breaking lead (plus the WORLD feed, only when
 * emergency alerts are on, so a major emergency ANYWHERE is caught, not just in the region-focused TOP feed)
 * — so it is cheap enough to run on a short interval. The first run only seeds the seen-set (no spam on launch).
 *
 * The three tiers (INTERRUPT > EMERGENCY > BREAKING) fire in priority order and share a per-run `claimed`
 * set keyed by title: once a story has fired at its highest-appropriate tier, the lower tiers skip it — one
 * distinct story produces exactly one notification, not up to three for the same underlying headline. No
 * artificial timing cooldown between checks; the identity-dedup sets ([NotifyState.seenTopUrls],
 * `breakingInterruptSeen`) are what stop a story repeating, not a wait timer.
 */
object BreakingNewsPulse {

    private const val STATE_KEY = "notify_state"

    suspend fun check(container: AppContainer) = withContext(Dispatchers.IO) {
        val prefs = container.settingsRepository.current().notifications
        val state = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: NotifyState()
        val top = container.newsRepository.fetchCategory(NewsCategory.TOP, force = true).data
        // Emergencies break ANYWHERE — also scan the global WORLD feed for them (only when the emergency
        // alert is on, an extra RSS fetch otherwise skipped). The general breaking lead stays TOP-only.
        val world = if (prefs.emergencyAlerts) {
            runCatching { container.newsRepository.fetchCategory(NewsCategory.WORLD, force = true).data }.getOrDefault(emptyList())
        } else emptyList()
        // Dedup by TITLE, not URL: Google News RSS item URLs mutate between fetches (tracking/redirect
        // params), so URL-keyed dedup re-fired the same headlines every poll. Titles are stable.
        val seen = state.seenTopUrls.toSet() // field reused to hold seen titles
        val firstRun = seen.isEmpty()
        val fresh = top.filter { it.title.isNotBlank() && it.title !in seen }
        // Emergencies are drawn from TOP + WORLD (deduped); the general breaking lead is TOP-only.
        val emergencyPool = (fresh + world.filter { it.title.isNotBlank() && it.title !in seen }).distinctBy { it.title }
        var firedInterrupt: String? = null
        var firedInterruptMs = 0L
        // Titles already claimed by a higher tier this run — the lower tiers below check this before firing.
        val claimed = mutableSetOf<String>()
        if (!firstRun) {
            // Tier 1 (highest): BREAKING INTERRUPT — the full-screen cinematic takeover. Only for a MAJOR
            // event (EmergencyNews.isMajor — a death, a disaster; a much higher bar than the emergency
            // notification), opt-out-gated, per-title deduped so the SAME major story can't re-interrupt.
            if (prefs.breakingInterrupt) {
                val major = emergencyPool.firstOrNull {
                    it.title !in state.breakingInterruptSeen && EmergencyNews.isMajor(it.title, it.summary)
                }
                if (major != null) {
                    val now = System.currentTimeMillis()
                    container.notifier.notifyBreakingInterrupt(
                        headline = major.title,
                        query = EmergencyNews.topicQuery(major.title),
                    )
                    firedInterrupt = major.title
                    firedInterruptMs = now
                    claimed += major.title
                }
            }
            // Tier 2: "This just in" — a SEPARATE, most-urgent alert for the most-severe fresh headline
            // (from TOP or WORLD) that reads as a major emergency, on its own channel + id. Skips any title
            // already claimed by the interrupt above, so the identical story doesn't double-alert.
            if (prefs.emergencyAlerts) {
                val emergency = emergencyPool
                    .filter { it.title !in claimed }
                    .map { it to EmergencyNews.severity(it.title, it.summary) }
                    .filter { it.second > 0 }
                    .maxByOrNull { it.second }
                    ?.first
                if (emergency != null) {
                    container.notifier.notifyEmergency(
                        id = 1002,
                        title = "🚨 THIS JUST IN" + (emergency.source.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        body = emergency.title,
                    )
                    claimed += emergency.title
                }
            }
            // Tier 3: the general breaking feed — the lead fresh TOP headline not already claimed above.
            if (prefs.breakingNews) {
                val candidates = fresh.filter { it.title !in claimed }
                val lead = candidates.firstOrNull()
                if (lead != null) {
                    val more = candidates.size - 1
                    val extra = if (more > 0) " (+$more more)" else ""
                    container.notifier.notifyBreaking(
                        id = 1001,
                        title = "Breaking" + (lead.source.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        body = lead.title + extra,
                    )
                }
            }
        }
        // Accumulate seen titles (bounded) so an item briefly leaving a feed won't re-alert. Include WORLD
        // titles so a world emergency won't re-fire, and widen the window for the two feeds.
        // Re-read the LATEST state immediately before writing and touch ONLY our field (seenTopUrls): the
        // news fetch above is slow, and this poller shares `notify_state` with RefreshWorker. If we wrote
        // our pre-fetch snapshot back we'd clobber whatever the worker advanced meanwhile — notably the
        // survival-tip index/timestamp, which stuck the tip rotation on the first tip ("Rule of Threes").
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
