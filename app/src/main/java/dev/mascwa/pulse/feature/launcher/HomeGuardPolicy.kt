package dev.mascwa.pulse.feature.launcher

/**
 * When the LCARS home screen must stand down and hand the phone back to the previous launcher.
 *
 * ⚠️ **Why this exists at all.** As device owner, LCARS can make itself the persistent default
 * Home. That is exactly what was asked for, and it has one failure worse than any other in the app:
 * a default Home that crashes on start is a phone whose Home button leads nowhere, reopened by
 * Android the moment it dies. So every start is counted, and a start that never lived long enough
 * to count as healthy is evidence. Enough of them close together and the home screen switches
 * itself off rather than waiting to be rescued.
 *
 * ## The rule
 *
 * - A start is **pending** until it has been alive for [HEALTHY_AFTER_MS]; it is then healthy and
 *   clears the record, because a home screen that just worked is not in a crash loop.
 * - When a new start finds the previous one still pending, the previous one failed.
 * - [FAILURES_TO_STAND_DOWN] failures within [WINDOW_MS] of now stand the home screen down.
 *
 * A failure outside the window is forgotten, so an occasional death on a busy phone — the system
 * reclaiming memory, say — never adds up to a stand-down over a week. A failure stamped in the
 * FUTURE is forgotten too, for the reason `PassThrottle` gives: a clock that was briefly fast must not
 * leave evidence that never ages out.
 *
 * Pure — times in, verdict out — so the threshold and the window are tested rather than remembered.
 */
object HomeGuardPolicy {

    const val FAILURES_TO_STAND_DOWN = 3
    const val WINDOW_MS = 2 * 60_000L
    const val HEALTHY_AFTER_MS = 5_000L

    /** What survives between starts: the start not yet proven healthy, and recent failures. */
    data class State(val pendingStartMs: Long? = null, val failuresMs: List<Long> = emptyList())

    data class Verdict(val state: State, val standDown: Boolean)

    /** A start is beginning at [nowMs]. */
    fun onStart(state: State, nowMs: Long): Verdict {
        val failures = (state.failuresMs + listOfNotNull(state.pendingStartMs))
            .filter { nowMs - it in 0..WINDOW_MS }
        val standDown = failures.size >= FAILURES_TO_STAND_DOWN
        return Verdict(State(pendingStartMs = nowMs, failuresMs = failures), standDown)
    }

    /** The current start has been alive for [HEALTHY_AFTER_MS]: it worked, and the record clears. */
    fun onHealthy(): State = State()
}
