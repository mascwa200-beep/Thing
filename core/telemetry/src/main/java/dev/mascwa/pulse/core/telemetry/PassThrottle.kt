package dev.mascwa.pulse.core.telemetry

/**
 * Is a periodic pass due, given the wall-clock time it last ran?
 *
 * This exists for the background worker's **persisted** throttles — the `last…Ms` stamps that live in
 * the settings blob and survive the process. Three of those stamps were being written and read by
 * nothing at all, so the passes they were meant to pace ran on every tick: a full cloud agent loop
 * with web search, a StrongBox keypair minted to read its own attestation, and a call to a free
 * public timestamping service. A throttle whose timestamp is never read does not throttle.
 *
 * The rule is four lines and it is here rather than at the three call sites because the interesting
 * case is not the arithmetic — it is what to do when the arithmetic cannot be trusted, and a rule
 * written three times is a rule that will differ in three ways. This project has corrected a
 * duplicated definition seven times.
 *
 * ⚠️ **The in-memory gates elsewhere in the app deliberately do NOT need this, so do not "unify"
 * them.** [SensorFusionController]'s pressure gate, the camera sampler's frame gap and the routing
 * cache all hold their stamp in a `var` on a live object: it starts at zero every process, so the
 * never-run case is free, and a clock that jumps backwards costs one interval and then self-corrects
 * when the process next dies. A **persisted** stamp has neither escape, which is the whole reason
 * this is worth a file.
 */
object PassThrottle {

    /**
     * True when a pass whose last run was stamped [lastMs] should run again at [nowMs], given a
     * minimum gap of [floorMs].
     *
     * Three rules, in order, and the first two are the reason this is not written inline:
     *
     * 1. **Never run is due.** A zero (or negative) stamp is the field's default — a fresh install,
     *    a settings reset, or a pass that has simply never fired. `nowMs - 0` clears any sane floor
     *    on its own, so this is belt-and-braces rather than load-bearing; it is stated so that a
     *    reader does not have to work out that it holds, and so it keeps holding if the epoch ever
     *    stops being 1970.
     *
     * 2. ⚠️ **A backwards clock is due.** This is the load-bearing one, and it only bites a persisted
     *    stamp. A phone whose clock is briefly wrong — a bad time sync, a manual change, a dead
     *    coin cell — stamps a time in the future; when the clock is corrected, `nowMs - lastMs` is
     *    negative and stays negative for as long as the error was large. A plain `>= floorMs` would
     *    silently switch the pass off for that entire span, potentially years, from a transient
     *    mistake the user has already fixed. Treating it as due costs at most one extra run.
     *
     * 3. Otherwise the gap must have elapsed.
     *
     * A [floorMs] of zero means "always due", which is what an unthrottled pass wants and lets a
     * caller disable the gate without a second code path. A negative floor also reads as always due;
     * that is the safe direction (a pass that runs too often is a cost, a pass that never runs again
     * is a silent feature loss), so it is documented rather than rejected.
     */
    fun due(lastMs: Long, nowMs: Long, floorMs: Long): Boolean {
        if (lastMs <= 0L) return true
        if (nowMs < lastMs) return true
        return nowMs - lastMs >= floorMs
    }
}
