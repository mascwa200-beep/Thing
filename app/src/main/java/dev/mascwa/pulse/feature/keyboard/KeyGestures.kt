package dev.mascwa.pulse.feature.keyboard

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/**
 * The two gestures Gboard has taught everybody to make on keys, as arithmetic over how far a finger has
 * travelled — no Android, so every threshold is tested:
 *
 * - **Slide along the space bar** and the cursor follows, one character for every [CURSOR_STEP_KEYS] of
 *   a key's width. A slide never types a space.
 * - **Swipe left from delete** and whole words are marked, one more for every [WORD_STEP_KEYS] of a key's
 *   width; lifting erases them. Swiping back right unmarks them.
 *
 * Both need the finger to travel [SLOP_DP] first, so an ordinary tap that wobbles a little is a tap.
 */
object KeyGestures {

    /** How far a finger must travel before a press becomes a slide, in dp — a thumb's ordinary wobble. */
    const val SLOP_DP = 12f

    /** One character of cursor movement for every this much of a key's width. */
    const val CURSOR_STEP_KEYS = 0.35f

    /** One more word marked for erasing for every this much of a key's width. */
    const val WORD_STEP_KEYS = 0.5f

    /**
     * Where the cursor should now be, in characters from where the slide began: negative is left. Zero
     * until the finger has travelled [slop]; the first step is taken the moment it has, so the slide
     * visibly begins where the finger says it has.
     */
    fun cursorSteps(dx: Float, slop: Float, keyWidth: Float): Int {
        if (!(abs(dx) >= slop) || !(keyWidth > 0f)) return 0
        val step = keyWidth * CURSOR_STEP_KEYS
        return (sign(dx) * (1 + floor((abs(dx) - slop) / step))).toInt()
    }

    /**
     * How many words a swipe of [dx] from delete has marked: zero until the finger has moved [slop] to
     * the LEFT — a swipe right is not a gesture, and delete has already done its one character.
     */
    fun deleteWords(dx: Float, slop: Float, keyWidth: Float): Int {
        if (!(-dx >= slop) || !(keyWidth > 0f)) return 0
        val step = keyWidth * WORD_STEP_KEYS
        return 1 + floor((-dx - slop) / step).toInt()
    }
}

/** How many characters erasing whole words takes. */
object WordErase {

    /**
     * The number of characters at the end of [before] that [words] words take: each word is the spaces
     * after it and the run of non-spaces before those, so "hello world " erases "world " for one word and
     * everything for two. Punctuation stays with the word it touches, the way Gboard erases it. Never
     * more than there is.
     */
    fun chars(before: CharSequence, words: Int): Int {
        var i = before.length
        repeat(words.coerceAtLeast(0)) {
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
        }
        return before.length - i
    }
}

/**
 * Two taps on the space bar after a word type ". " — Gboard's full stop, and the one it has made
 * everybody's thumbs expect.
 *
 * ⚠️ **"The last key was the space bar" is a claim about the TEXT, not about the keyboard.** Somebody who
 * types a space, taps the text to put the cursor after a different space and taps space again has typed
 * two spaces in a row on this keyboard, and must not get a full stop somewhere else. So the keyboard
 * remembers where the cursor was left by its space ([Tracker.at]), and any cursor move it did not make
 * forgets it. [Tracker.pending] covers the moment between typing the space and the editor reporting
 * where the cursor went, which a fast double tap can fall inside.
 */
object DoubleSpace {

    data class Tracker(val pending: Boolean = false, val at: Int = -1) {
        val lastWasSpace: Boolean get() = pending || at >= 0
    }

    /** A space was just typed by the space bar. */
    fun typedSpace(): Tracker = Tracker(pending = true)

    /** Anything else was typed, or the full stop was just put in: the next space is only a space. */
    fun typedOther(): Tracker = Tracker()

    /** The editor reports the selection is now [start]..[end]. */
    fun selectionMoved(t: Tracker, start: Int, end: Int): Tracker = when {
        start != end -> Tracker()
        t.pending -> Tracker(at = start)
        t.at >= 0 && start == t.at -> t
        else -> Tracker()
    }

    /**
     * Whether a space typed now becomes ". " — the previous key was the space bar, and it followed a
     * letter or a digit with nothing between: "word " but not "word  " and not "word. ".
     */
    fun applies(t: Tracker, before: CharSequence?, allowedHere: Boolean): Boolean {
        if (!allowedHere || !t.lastWasSpace || before == null || before.length < 2) return false
        return before[before.length - 1] == ' ' && before[before.length - 2].isLetterOrDigit()
    }

    /**
     * Whether the field wants a full stop from two spaces at all: ordinary text does; a password, a web
     * address and an email address do not, where a space is not the end of a sentence. [inputType] is
     * `EditorInfo.inputType`.
     */
    fun allowed(inputType: Int): Boolean {
        if (inputType and KeyboardModel.TYPE_MASK_CLASS != KeyboardModel.TYPE_CLASS_TEXT) return false
        return when (inputType and KeyboardModel.TYPE_MASK_VARIATION) {
            TYPE_TEXT_VARIATION_PASSWORD,
            TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            TYPE_TEXT_VARIATION_WEB_PASSWORD,
            KeyboardModel.TYPE_TEXT_VARIATION_URI,
            KeyboardModel.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            KeyboardModel.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            -> false
            else -> true
        }
    }

    // android.text.InputType, read out of the platform-35 SDK stub with `javap -constants`.
    internal const val TYPE_TEXT_VARIATION_PASSWORD = 0x80
    internal const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90
    internal const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xE0
}
