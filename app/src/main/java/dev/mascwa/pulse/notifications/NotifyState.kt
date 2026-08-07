package dev.mascwa.pulse.notifications

import kotlinx.serialization.Serializable

/** Persisted dedup bookkeeping shared by the board/takeover writers (worker, resident poller, BriefEngine).
 *  The retired per-category day-stamp fields were pruned after the one-notification cutover
 *  (`ignoreUnknownKeys` makes old blobs load cleanly without them). */
@Serializable
data class NotifyState(
    val seenTopUrls: List<String> = emptyList(),
    val safetyAlertedIds: List<String> = emptyList(),
    /** Last time (epoch ms) the full-screen BREAKING NEWS takeover fired — an audit timestamp only; the
     *  identity-dedup [breakingInterruptSeen] below is what stops the same story re-interrupting, not a
     *  time-based throttle (removed, owner's explicit choice: max notification frequency). */
    val breakingInterruptLastMs: Long = 0L,
    /** Titles already used for a breaking takeover, so the same major story can't re-interrupt. */
    val breakingInterruptSeen: List<String> = emptyList(),
    /** The one-notification board: the last urgencyKey that buzzed the alert channel, so the same urgent
     *  item alerts exactly once and every later refresh stays silent. Owned by BriefEngine. */
    val lastUrgentKey: String = "",
)
