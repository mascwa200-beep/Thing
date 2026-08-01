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
    /**
     * Last time (epoch ms) each survival need+band was alerted (key `NEED|BAND` → ms), so a nag is
     * throttled per severity and an escalation (LOW → URGENT → CRITICAL) fires promptly on its own key.
     */
    val survivalFiredMs: Map<String, Long> = emptyMap(),
    /** Needs currently in a real concern band (LOW or worse) that we've alerted — so we can fire a single
     *  recovery confirmation when one is brought back up to healthy. Need names. */
    val survivalNeedActive: List<String> = emptyList(),
    /** Afflictions we've already announced as active — so a disease pushes once when it takes hold and once
     *  when it clears. Affliction names. */
    val afflictedNotified: List<String> = emptyList(),
    /** Calendar event ids already reminded about while imminent, so an appointment nudges once. */
    val agendaNotifiedIds: List<String> = emptyList(),
    /** Rotating survival-tip cursor (walks the whole catalog before repeating) + when one last fired. */
    val survivalTipIndex: Int = 0,
    val survivalTipLastMs: Long = 0L,
    /** Local epoch-day we last fired a "don't break your streak" reminder, so it nudges at most once a day. */
    val streakReminderDay: Int = -1,
    /** Last time (epoch ms) the full-screen BREAKING NEWS takeover fired — a hard throttle so it can't spam. */
    val breakingInterruptLastMs: Long = 0L,
    /** Titles already used for a breaking takeover, so the same major story can't re-interrupt. */
    val breakingInterruptSeen: List<String> = emptyList(),
)
