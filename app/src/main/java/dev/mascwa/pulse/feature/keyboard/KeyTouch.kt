package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.KeyboardGeometry.Placed
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Key

/**
 * What one finger on the keyboard is doing, and what it types — the rules Gboard has trained
 * everybody's thumbs to expect, with no Android in them.
 *
 * The keyboard handles every touch itself, as one surface, rather than giving each key its own tap
 * detector. That is what makes these possible at all:
 *
 * - **A letter types on release, and it is the letter the finger is ON.** Land on the wrong letter,
 *   slide to the right one, lift: the right one is typed. The pop-up follows the finger, so what is
 *   about to be typed is always on screen.
 * - **Rolling over.** Fast typists put the next finger down before lifting the last. The moment a
 *   second finger lands, the first one's letter is typed — otherwise "the" typed quickly would come
 *   out in whatever order the thumbs happened to lift.
 * - **A key that DOES something must be released on itself.** Slide off shift, enter or the layer key
 *   before lifting and nothing happens: the way out of a press you did not mean.
 * - **Delete acts the instant it is touched**, and repeats while held (the keyboard times the
 *   repeats). Its release does nothing more.
 */
object KeyTouch {

    /**
     * How long a key is held before it counts as a long press — Gboard's own default. Shorter and a
     * deliberate but unhurried tap turns into a menu; longer and the menu feels as though it is not there.
     */
    const val LONG_PRESS_MS = 300L

    /**
     * One finger: the key it came down on, the key it is on now, and whether its key has already been
     * dealt with — typed on a rollover, or taken by a long press — so its release must do nothing.
     */
    data class Finger(val down: Placed, val current: Placed, val done: Boolean = false)

    fun down(key: Placed): Finger = Finger(down = key, current = key)

    /** The finger has moved onto [key]. */
    fun moveTo(f: Finger, key: Placed): Finger = if (key == f.current) f else f.copy(current = key)

    /** Keys that act the moment they are touched rather than when released. */
    fun actsOnDown(key: Key): Boolean = key == Key.Delete

    /**
     * The key the finger's release acts on, or null for none.
     *
     * - Already dealt with, or a key that acted when touched: nothing.
     * - Came down on a letter: whichever LETTER it is on now — sliding off the letters altogether (onto
     *   shift, say) is a way of changing one's mind, and types nothing.
     * - Anything else: only if it is lifted on the key it came down on.
     */
    fun release(f: Finger): Placed? = when {
        f.done || actsOnDown(f.down.key) -> null
        f.down.key is Key.Text -> f.current.takeIf { it.key is Key.Text }
        f.current == f.down -> f.down
        else -> null
    }

    /**
     * Another finger has come down while this one is still on the keyboard. A letter this finger is
     * on is typed NOW, so fast typing comes out in the order it was typed; anything else is left to
     * finish as it would have. Returns the finger as it now stands and the key to act on, if any.
     */
    fun rollOver(f: Finger): Pair<Finger, Placed?> {
        if (f.done || f.down.key !is Key.Text || f.current.key !is Key.Text) return f to null
        return f.copy(done = true) to f.current
    }

    /** The key to show the pop-up over, or null: only a letter or symbol a finger is still going to type. */
    fun preview(f: Finger): Placed? =
        f.current.takeIf { !f.done && f.down.key is Key.Text && it.key is Key.Text }
}
