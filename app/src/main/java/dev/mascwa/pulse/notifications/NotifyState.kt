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
    val neoAlertDay: String = "",
    val safetyAlertedIds: List<String> = emptyList(),
    val flightAlertedIds: List<String> = emptyList(),
)
