package dev.mascwa.pulse.core.util

/**
 * When something LCARS put in charge of a piece of the phone must stand down and hand it back to
 * Android: the home screen, and the call screen.
 *
 * ⚠️ **Why this exists at all.** As device owner, LCARS can make itself the persistent default
 * Home, and once the owner switches it on, the phone app. Each has one failure worse than any other
 * in the app: a default Home that crashes on start is a phone whose Home button leads nowhere,
 * reopened by Android the moment it dies; a call screen that crashes on every call is a phone that
 * cannot be answered. So every start is counted, and a start that never lived long enough to count
 * as healthy is evidence. Enough of them close together and the part switches itself off rather
 * than waiting to be rescued. Each caller keeps its own [State] in its own store.
 *
 * ## The rule
 *
 * - A start is **pending** until it has been alive for [HEALTHY_AFTER_MS] (or ended cleanly); it is
 *   then healthy and clears the record, because a start that just worked is not a crash loop.
 * - When a new start finds the previous one still pending, the previous one failed.
 * - [FAILURES_TO_STAND_DOWN] failures within [WINDOW_MS] of now stand it down.
 *
 * A failure outside the window is forgotten, so an occasional death on a busy phone — the system
 * reclaiming memory, say — never adds up to a stand-down over a week. A failure stamped in the
 * FUTURE is forgotten too, for the reason `PassThrottle` gives: a clock that was briefly fast must not
 * leave evidence that never ages out.
 *
 * Pure — times in, verdict out — so the threshold and the window are tested rather than remembered.
 */
object CrashLoopGuard {

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
