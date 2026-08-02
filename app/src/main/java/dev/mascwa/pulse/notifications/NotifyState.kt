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
    /** Last time (epoch ms) the full-screen BREAKING NEWS takeover fired — an audit timestamp only; the
     *  identity-dedup [breakingInterruptSeen] below is what stops the same story re-interrupting, not a
     *  time-based throttle (removed, owner's explicit choice: max notification frequency). */
    val breakingInterruptLastMs: Long = 0L,
    /** Titles already used for a breaking takeover, so the same major story can't re-interrupt. */
    val breakingInterruptSeen: List<String> = emptyList(),
)
