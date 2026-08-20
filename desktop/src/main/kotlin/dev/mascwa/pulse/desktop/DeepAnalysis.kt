package dev.mascwa.pulse.desktop

/**
 * What a panel's deep-analysis switch is doing.
 *
 * [readyFor] and [failedFor] hold the KEY the outcome belongs to — the symbol, the coordinate, the
 * query — rather than a bare flag, because "we already did this" is only true of the thing that was
 * asked about. A flag would keep showing a deep answer about the last subject after the subject
 * changed, which is worse than showing nothing.
 */
data class DeepState(
    val running: Boolean = false,
    val readyFor: String? = null,
    val failedFor: String? = null,
)

/**
 * The rule behind every DEEP switch on this console.
 *
 * ⚠️ **The contract is the feature, and it is the reason this is a tested function rather than a
 * comment.** Deep analysis means asking a source for far more than a page needs — a whole intraday
 * series, sixteen days of forecast, a scan of every page in the library — and the owner's instruction
 * was exact: a switch inside the panel, **default off, never on open, never on a timer**. Each of
 * those is a property something can be wrong about, so each is stated here where it can be asserted:
 *
 * * off means it never runs, whatever else is true — so opening a panel costs nothing;
 * * it runs once per subject, so leaving the switch on does not re-fetch as you look at it;
 * * a subject that failed is not retried on its own, or a dead endpoint becomes a fetch per frame;
 * * nothing starts while something is running.
 *
 * There is no clock anywhere in this file, and that absence is what makes "never on a timer" true
 * rather than merely intended.
 */
object DeepAnalysis {

    /**
     * Whether the expensive work should start now.
     *
     * [key] identifies what is being asked about. A blank key means the panel has nothing to analyse
     * yet — an empty search box, a coordinate this machine has not established — and asking a source
     * about nothing is a wasted round trip that returns an answer to no question.
     */
    fun shouldRun(on: Boolean, key: String, state: DeepState): Boolean {
        if (!on) return false
        if (key.isBlank()) return false
        if (state.running) return false
        if (state.readyFor == key) return false
        if (state.failedFor == key) return false
        return true
    }

    /** Entering the run. */
    fun started(state: DeepState): DeepState = state.copy(running = true)

    /** It worked, and the answer belongs to [key]. */
    fun succeeded(key: String): DeepState = DeepState(running = false, readyFor = key)

    /**
     * It did not work.
     *
     * ⚠️ Recorded against the key rather than forgotten, so the same failing subject is not asked
     * about again and again. Turning the switch off and on is the retry, which is a deliberate act
     * and exactly the right amount of friction for something that just failed.
     */
    fun failed(key: String): DeepState = DeepState(running = false, failedFor = key)

    /**
     * The switch went off.
     *
     * ⚠️ Everything is dropped, including a held result. Keeping it would mean flicking the switch
     * back on shows a stale answer instantly and silently — with no way to tell it apart from a fresh
     * one — and for a market series or a forecast that is the difference between useful and wrong.
     */
    fun cleared(): DeepState = DeepState()

    /** Whether there is an answer on hand for [key] right now. */
    fun isReady(key: String, state: DeepState): Boolean = state.readyFor == key

    /** What to put on screen while nothing has been asked for yet. */
    const val OFF_HINT = "Off. Turning this on asks the source for far more than this page needs."
}
