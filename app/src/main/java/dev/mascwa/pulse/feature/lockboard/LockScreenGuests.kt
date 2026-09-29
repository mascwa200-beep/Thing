package dev.mascwa.pulse.feature.lockboard

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many of this app's own lock-screen takeovers are alive — the condition-red alert and the
 * breaking-news page — so the lock-screen board never starts on top of one.
 *
 * ⚠️ **Alive, not visible.** A takeover is created and destroyed once, but it is stopped every time
 * the screen goes off — which is exactly the moment the board is started. Counting visibility would
 * read zero at the one instant it is asked, and put a weather readout over an unacknowledged
 * emergency. The count is kept from `onCreate` to `onDestroy`, and never goes below zero, so a
 * process that dies half-way cannot leave it stuck above.
 */
object LockScreenGuests {
    private val alive = AtomicInteger(0)

    fun enter() {
        alive.incrementAndGet()
    }

    fun leave() {
        alive.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    val any: Boolean get() = alive.get() > 0
}
