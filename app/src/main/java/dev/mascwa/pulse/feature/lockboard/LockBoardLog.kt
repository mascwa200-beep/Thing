package dev.mascwa.pulse.feature.lockboard

import android.content.Context
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.feature.lockboard.LockBoardPolicy.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What became of the last screen-off: shown, skipped and why, or refused by Android.
 *
 * ⚠️ **This exists because the first report of the board "not working" could not be told apart from
 * the board never having been installed.** Every way it can fail to appear is silent: a background
 * start Android refuses is dropped rather than thrown, a process that was not running hears no
 * screen-off at all, and a skip leaves the stock lock screen exactly as a failure would. So each
 * outcome is kept here, shown under the Settings switch, and — when it changes — written to the
 * activity log that travels with a debug report.
 *
 * Persisted in a small preferences file so the answer survives the process: the moment somebody
 * looks is usually long after the screen-off it describes. The rules — what is worth recording,
 * what counts as refused, what reaches the log — are [LockBoardPolicy]'s, where they are tested.
 */
object LockBoardLog {

    data class Entry(val outcome: Outcome, val reason: String?, val atMs: Long)

    private val _latest = MutableStateFlow<Entry?>(null)

    /** The last recorded outcome, or null before the first one (or before [load]). */
    val latest: StateFlow<Entry?> = _latest.asStateFlow()

    /** The last outcome written to the activity log, so only changes reach it. Per process. */
    @Volatile
    private var lastLogged: Pair<Outcome, String?>? = null

    @Volatile
    private var loaded = false

    /**
     * Read the persisted outcome into [latest]. Call off the main thread: the first read of a
     * preferences file parses it on the calling thread.
     */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        val downAt = prefs.getLong(KEY_POWER_DOWN_AT, 0L)
        if (downAt > 0L && _powerDown.value == null) {
            _powerDown.value = PowerDown(prefs.getLong(KEY_POWER_DOWN_MS, 0L), downAt)
        }
        val outcome = prefs.getString(KEY_OUTCOME, null)
            ?.let { name -> Outcome.entries.firstOrNull { it.name == name } } ?: return
        // A newer outcome recorded while this was loading wins.
        if (_latest.value == null) {
            _latest.value = Entry(outcome, prefs.getString(KEY_REASON, null), prefs.getLong(KEY_AT, 0L))
        }
    }

    fun record(context: Context, outcome: Outcome, reason: String? = null, nowMs: Long = System.currentTimeMillis()) {
        _latest.value = Entry(outcome, reason, nowMs)
        runCatching {
            prefs(context).edit()
                .putString(KEY_OUTCOME, outcome.name)
                .putString(KEY_REASON, reason)
                .putLong(KEY_AT, nowMs)
                .apply()
        }
        if (LockBoardPolicy.worthLogging(outcome, reason, lastLogged)) {
            lastLogged = outcome to reason
            runCatching {
                (context.applicationContext as? PulseApplication)?.container?.usageRepository
                    ?.log(LOG_CATEGORY, "lock-screen board: ${LockBoardPolicy.describe(outcome, reason)}")
            }
        }
    }

    /** The last power-down's measured window: how long the console had before the screen went dark. */
    data class PowerDown(val windowMs: Long, val atMs: Long)

    private val _powerDown = MutableStateFlow<PowerDown?>(null)
    val powerDown: StateFlow<PowerDown?> = _powerDown.asStateFlow()

    /** The last window written to the activity log, so only a change of band reaches it. Per process. */
    @Volatile
    private var lastLoggedPowerDownMs: Long? = null

    /** Record how long the power-down had before the screen went dark — see `LockBoardPolicy.powerDownSeen`. */
    fun recordPowerDown(context: Context, windowMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val window = windowMs.coerceAtLeast(0L)
        _powerDown.value = PowerDown(window, nowMs)
        runCatching {
            prefs(context).edit().putLong(KEY_POWER_DOWN_MS, window).putLong(KEY_POWER_DOWN_AT, nowMs).apply()
        }
        if (LockBoardPolicy.powerDownWorthLogging(window, lastLoggedPowerDownMs)) {
            lastLoggedPowerDownMs = window
            runCatching {
                (context.applicationContext as? PulseApplication)?.container?.usageRepository
                    ?.log(LOG_CATEGORY, "lock-screen board: ${LockBoardPolicy.describePowerDown(window)}")
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "lock_board_log"
    private const val KEY_OUTCOME = "outcome"
    private const val KEY_REASON = "reason"
    private const val KEY_AT = "at_ms"
    private const val KEY_POWER_DOWN_MS = "power_down_ms"
    private const val KEY_POWER_DOWN_AT = "power_down_at"
    private const val LOG_CATEGORY = "lockboard"
}
