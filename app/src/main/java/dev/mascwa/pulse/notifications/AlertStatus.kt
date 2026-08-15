package dev.mascwa.pulse.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ship's current alert condition, for the whole process.
 *
 * [BriefEngine] already computes one — it decides whether the single notification says RED ALERT,
 * YELLOW ALERT or nothing — and until now that judgement stopped at the notification tray. This
 * carries it into the app, so the console is in the same condition as the board rather than sitting
 * in calm orange while the tray says the opposite.
 *
 * A plain object because there is exactly one ship. The engine runs in a worker and the UI reads it
 * from a composable; both are the same process, so a `StateFlow` is the whole mechanism.
 *
 * ⚠️ **It is not decoration.** Going red recolours every screen's accents, so it must only happen
 * when something is genuinely wrong. The condition is [BriefEngine]'s to set and no one else's —
 * nothing here decides what counts as an emergency.
 */
object AlertStatus {

    private val _condition = MutableStateFlow(AlertCondition.ROUTINE)

    /** The live condition. ROUTINE until a brief says otherwise. */
    val condition: StateFlow<AlertCondition> = _condition.asStateFlow()

    private val _headline = MutableStateFlow("")

    /**
     * What raised it, in the board's own words.
     *
     * In memory only, deliberately: the condition is persisted so a cold launch opens in the right
     * one, but a headline restored from disk could be hours stale and stating a stale cause is worse
     * than stating none. Blank until the next publish, which the strip handles by showing the
     * condition alone.
     */
    val headline: StateFlow<String> = _headline.asStateFlow()

    /** Set by [BriefEngine] on every publish, including back down to ROUTINE when a situation clears. */
    fun set(condition: AlertCondition, headline: String = "") {
        _condition.value = condition
        _headline.value = if (condition == AlertCondition.ROUTINE) "" else headline
    }

    /** Parse a persisted condition, tolerating anything unrecognised as ROUTINE. */
    fun parse(name: String): AlertCondition =
        AlertCondition.entries.firstOrNull { it.name == name } ?: AlertCondition.ROUTINE
}
