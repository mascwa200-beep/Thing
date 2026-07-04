package dev.mascwa.pulse.notifications

import kotlinx.serialization.Serializable

/** Persisted bookkeeping so the worker doesn't repeat the same alert. */
@Serializable
data class NotifyState(
    val seenTopUrls: List<String> = emptyList(),
    val marketAlertDay: String = "",
    val marketAlertedSymbols: List<String> = emptyList(),
    val weatherAlertDay: String = "",
    val lastDigestDay: String = "",
    val spaceAlertDay: String = "",
    val auroraAlertDay: String = "",
    val neoAlertDay: String = "",
    val safetyAlertedIds: List<String> = emptyList(),
    val flightAlertedIds: List<String> = emptyList(),
    /** Last time (epoch ms) each survival need was alerted (need name → ms), so a nag is throttled. */
    val survivalFiredMs: Map<String, Long> = emptyMap(),
    /** Calendar event ids already reminded about while imminent, so an appointment nudges once. */
    val agendaNotifiedIds: List<String> = emptyList(),
    /** Rotating survival-tip cursor (walks the whole catalog before repeating) + when one last fired. */
    val survivalTipIndex: Int = 0,
    val survivalTipLastMs: Long = 0L,
    /** Local epoch-day we last fired a "don't break your streak" reminder, so it nudges at most once a day. */
    val streakReminderDay: Int = -1,
)
