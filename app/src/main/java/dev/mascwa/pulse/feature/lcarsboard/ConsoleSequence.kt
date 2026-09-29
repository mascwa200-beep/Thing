package dev.mascwa.pulse.feature.lcarsboard

/**
 * The console's power-up, and — the same thing run backwards — its power-down.
 *
 * The owner asked for the lock screen to go off with the same animation it comes on with. The way
 * to guarantee "the same" is not to write two animations and keep them in step, but to have ONE:
 * everything visible is a function of a single [progress] between 0 (dark) and 1 (fully up), and
 * powering down is simply that progress travelling back toward 0. Every intermediate frame of the
 * way down is a frame of the way up, by construction.
 *
 * Each element — the corner, the header bar, each rail block, each panel — gets its own window of
 * the timeline, staggered in drawing order, so the console assembles itself piece by piece the way
 * an LCARS panel comes up, and comes apart in exactly the reverse order.
 *
 * Pure: no clock, no Android. The screen supplies elapsed time; this says what that time looks like.
 */
object ConsoleSequence {

    /** One full sweep, dark to up (or up to dark). */
    const val DURATION_MS = 650L

    /**
     * How much of the whole timeline one element takes to arrive. The rest is the stagger: the last
     * element starts at `1 - ELEMENT_SPAN` and lands exactly at 1.
     */
    const val ELEMENT_SPAN = 0.45f

    /**
     * Where the console stands [elapsedMs] after a sweep began at [fromProgress].
     *
     * ⚠️ **A reversal starts where the console IS, not from an end.** Press the power button half a
     * second into a power-up and the power-down must continue from that half-assembled console; a
     * sweep that jumped to 1 first would flash the whole board up just as the screen goes dark.
     */
    fun progressAt(elapsedMs: Long, rising: Boolean, fromProgress: Float, durationMs: Long = DURATION_MS): Float {
        if (durationMs <= 0L) return if (rising) 1f else 0f
        val moved = elapsedMs.coerceAtLeast(0L).toFloat() / durationMs
        val p = if (rising) fromProgress + moved else fromProgress - moved
        return p.coerceIn(0f, 1f)
    }

    /**
     * How far element [index] of [count] has arrived at overall [progress]: 0 absent, 1 in place.
     *
     * Earlier elements always lead later ones, and every element is monotone in [progress] — which is
     * what makes the reverse read as the forward sequence played backwards rather than as a different
     * animation.
     */
    fun element(progress: Float, index: Int, count: Int): Float {
        val p = progress.coerceIn(0f, 1f)
        if (count <= 1) return ease(p)
        val start = index.coerceIn(0, count - 1).toFloat() / (count - 1) * (1f - ELEMENT_SPAN)
        val local = ((p - start) / ELEMENT_SPAN).coerceIn(0f, 1f)
        return ease(local)
    }

    /**
     * How long a sweep takes under the system's animator scale.
     *
     * Zero — "remove animations" in Accessibility — means no sweep at all: the console is simply
     * there, and simply gone. Anything else scales the sweep as the rest of the phone scales its own.
     */
    fun durationFor(animatorScale: Float): Long =
        if (animatorScale <= 0f || animatorScale.isNaN()) 0L else (DURATION_MS * animatorScale).toLong()

    /** Ease-out cubic: fast off the mark, settling into place, as a block sliding home. */
    private fun ease(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }
}
