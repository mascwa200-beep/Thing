package dev.mascwa.pulse.feature.keyboard

/**
 * The LCARS keyboard's logic, with no Android in it: which keys sit on which layer, what shift does,
 * and what a press sends to the text field. The service only carries the answers out.
 *
 * ## What it deliberately does not do
 *
 * **It learns nothing.** No dictionary, no prediction, no history of what was typed. Every decision
 * here is a function of the key pressed, the keyboard's own state and the field's declared type — so
 * there is nothing to store, and `KeyboardRecordsNothingTest` holds the package to that.
 *
 * ## Shift
 *
 * Three states, the way phone keyboards have trained everybody to expect:
 * - a tap turns it on for ONE letter ([ShiftState.ONCE]);
 * - two taps inside [DOUBLE_TAP_MS] lock it ([ShiftState.LOCKED]);
 * - a tap while locked, or on a ONCE nobody used, turns it off.
 *
 * ⚠️ **A ONCE the keyboard chose is not a ONCE the person chose.** At the start of a sentence the
 * keyboard turns shift on by itself ([autoCaps]), and it must be free to take that back when the
 * cursor moves somewhere that needs no capital. It must NOT take back one the person pressed. [State.auto]
 * is what tells the two apart.
 */
object KeyboardModel {

    enum class Layer { LETTERS, SYMBOLS, MORE }

    enum class ShiftState { OFF, ONCE, LOCKED }

    /**
     * What the keyboard is showing.
     *
     * [shiftTappedAtMs] is when shift was last tapped, so a second tap can be recognised as a double
     * tap; any other key clears it, so "shift, a letter, shift" is two separate taps and not a lock.
     */
    data class State(
        val layer: Layer = Layer.LETTERS,
        val shift: ShiftState = ShiftState.OFF,
        val auto: Boolean = false,
        val shiftTappedAtMs: Long? = null,
    )

    sealed interface Key {
        /** A key that types [value]: a letter, a digit or a symbol. */
        data class Text(val value: String) : Key
        data object Shift : Key
        data object Delete : Key
        data object Space : Key
        data object Enter : Key
        data class ToLayer(val layer: Layer, val label: String) : Key
        data object SwitchIme : Key

        /** Half a key of empty space, so the middle row of letters sits between the keys above it. */
        data object Gap : Key
    }

    /** One key and how much of its row it takes. Every row's weights sum to [ROW_WEIGHT]. */
    data class Slot(val key: Key, val weight: Float = 1f)

    sealed interface Output {
        data class Commit(val text: String) : Output
        data object Delete : Output
        data object Enter : Output
        data object SwitchIme : Output
        data object None : Output
    }

    data class Result(val state: State, val output: Output)

    /** Two taps on shift closer together than this lock it. */
    const val DOUBLE_TAP_MS = 400L

    /** Holding delete: the first repeat waits this long, then one every [REPEAT_INTERVAL_MS]. */
    const val REPEAT_DELAY_MS = 400L
    const val REPEAT_INTERVAL_MS = 50L

    /** The width of every row, in key widths. Rows that disagree would leave keys out of line. */
    const val ROW_WEIGHT = 10f

    /** What pressing [key] does, given the keyboard is in [state]. [nowMs] only times the shift key. */
    fun press(state: State, key: Key, nowMs: Long): Result {
        val settled = state.copy(shiftTappedAtMs = null)
        return when (key) {
            is Key.Text -> {
                val upper = state.layer == Layer.LETTERS && state.shift != ShiftState.OFF
                val text = if (upper) key.value.uppercase() else key.value
                // One letter uses up a ONCE; a lock stays until it is tapped off.
                val next = if (state.layer == Layer.LETTERS && state.shift == ShiftState.ONCE) {
                    settled.copy(shift = ShiftState.OFF, auto = false)
                } else {
                    settled
                }
                Result(next, Output.Commit(text))
            }
            Key.Shift -> {
                val quick = state.shiftTappedAtMs?.let { nowMs - it in 0..DOUBLE_TAP_MS } == true
                // A tap while locked always unlocks, and that is decided BEFORE the double-tap test:
                // a third quick tap undoes the lock rather than counting as half of another one.
                val next = when {
                    state.shift == ShiftState.LOCKED -> ShiftState.OFF
                    quick -> ShiftState.LOCKED
                    state.shift == ShiftState.OFF -> ShiftState.ONCE
                    else -> ShiftState.OFF
                }
                Result(state.copy(shift = next, auto = false, shiftTappedAtMs = nowMs), Output.None)
            }
            Key.Delete -> Result(settled, Output.Delete)
            // A space does not use up a ONCE: shift is for the next LETTER.
            Key.Space -> Result(settled, Output.Commit(" "))
            Key.Enter -> Result(settled, Output.Enter)
            is Key.ToLayer -> Result(settled.copy(layer = key.layer), Output.None)
            Key.SwitchIme -> Result(settled, Output.SwitchIme)
            Key.Gap -> Result(state, Output.None)
        }
    }

    /**
     * Shift as the field would like it: on at the start of a sentence, off again when the cursor moves
     * somewhere that needs no capital.
     *
     * [capsMode] is what the field reports for the cursor (`InputConnection.getCursorCapsMode`);
     * anything but zero asks for a capital. A password or an address field never asks, because its
     * type carries none of the capitalisation flags.
     *
     * Never overrides a person. Only an OFF shift is ever turned on — so a lock stays locked — and
     * only a ONCE this function chose is ever taken back.
     */
    fun autoCaps(state: State, capsMode: Int): State = when {
        capsMode != 0 && state.shift == ShiftState.OFF -> state.copy(shift = ShiftState.ONCE, auto = true)
        capsMode == 0 && state.shift == ShiftState.ONCE && state.auto -> state.copy(shift = ShiftState.OFF, auto = false)
        else -> state
    }

    /**
     * The layer a field opens on: its digits for a number, a phone number or a date, letters for
     * everything else. [inputType] is `EditorInfo.inputType`.
     */
    fun startLayer(inputType: Int): Layer = when (inputType and TYPE_MASK_CLASS) {
        TYPE_CLASS_NUMBER, TYPE_CLASS_PHONE, TYPE_CLASS_DATETIME -> Layer.SYMBOLS
        else -> Layer.LETTERS
    }

    /**
     * The key beside the space bar on the letters layer: `@` in an email field, `/` in a web address,
     * a comma everywhere else. The character a field of that kind most needs, one tap away.
     */
    fun punctuation(inputType: Int): String {
        if (inputType and TYPE_MASK_CLASS != TYPE_CLASS_TEXT) return ","
        return when (inputType and TYPE_MASK_VARIATION) {
            TYPE_TEXT_VARIATION_EMAIL_ADDRESS, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "@"
            TYPE_TEXT_VARIATION_URI -> "/"
            else -> ","
        }
    }

    /**
     * The rows of [layer], top to bottom.
     *
     * [offerSwitch] adds a key that moves to the next keyboard. Android says whether there is one to
     * move to, and a switch key that goes nowhere would be a dead control, so it is not drawn then.
     */
    fun rows(layer: Layer, punctuation: String, offerSwitch: Boolean): List<List<Slot>> {
        val bottom = buildList {
            add(Slot(if (layer == Layer.LETTERS) TO_SYMBOLS else TO_LETTERS, 1.5f))
            if (offerSwitch) add(Slot(Key.SwitchIme))
            // The symbols layers carry their own `@` and `/`, so the letters layer's field-specific
            // character would only be a duplicate there.
            add(Slot(Key.Text(if (layer == Layer.LETTERS) punctuation else ",")))
            add(Slot(Key.Space, if (offerSwitch) 4f else 5f))
            add(Slot(Key.Text(".")))
            add(Slot(Key.Enter, 1.5f))
        }
        val body = when (layer) {
            Layer.LETTERS -> listOf(
                texts("qwertyuiop"),
                listOf(Slot(Key.Gap, 0.5f)) + texts("asdfghjkl") + Slot(Key.Gap, 0.5f),
                listOf(Slot(Key.Shift, 1.5f)) + texts("zxcvbnm") + Slot(Key.Delete, 1.5f),
            )
            Layer.SYMBOLS -> listOf(
                texts("1234567890"),
                symbols("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
                listOf(Slot(TO_MORE, 1.5f)) + texts("*\"':;!?") + Slot(Key.Delete, 1.5f),
            )
            Layer.MORE -> listOf(
                symbols("~", "`", "|", "•", "√", "π", "÷", "×", "<", ">"),
                symbols("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\"),
                listOf(Slot(TO_SYMBOLS, 1.5f)) + symbols("%", "©", "®", "™", "✓", "[", "]") + Slot(Key.Delete, 1.5f),
            )
        }
        return body + listOf(bottom)
    }

    /** What a key says. Letters follow shift; everything else says what it does. */
    fun label(key: Key, state: State): String = when (key) {
        is Key.Text -> if (state.layer == Layer.LETTERS && state.shift != ShiftState.OFF) key.value.uppercase() else key.value
        Key.Shift -> if (state.shift == ShiftState.LOCKED) "CAPS" else "SHIFT"
        Key.Delete -> "DEL"
        Key.Space -> "SPACE"
        Key.Enter -> ""
        is Key.ToLayer -> key.label
        Key.SwitchIme -> "🌐"
        Key.Gap -> ""
    }

    private fun texts(chars: String): List<Slot> = chars.map { Slot(Key.Text(it.toString())) }
    private fun symbols(vararg values: String): List<Slot> = values.map { Slot(Key.Text(it)) }

    private val TO_SYMBOLS = Key.ToLayer(Layer.SYMBOLS, "?123")
    private val TO_LETTERS = Key.ToLayer(Layer.LETTERS, "ABC")
    private val TO_MORE = Key.ToLayer(Layer.MORE, "=\\<")

    // android.text.InputType, restated so this file needs no Android to test. Each value was read
    // out of the platform-35 SDK stub with `javap -constants`; InputType has never renumbered one.
    internal const val TYPE_MASK_CLASS = 0x0F
    internal const val TYPE_CLASS_TEXT = 1
    internal const val TYPE_CLASS_NUMBER = 2
    internal const val TYPE_CLASS_PHONE = 3
    internal const val TYPE_CLASS_DATETIME = 4
    internal const val TYPE_MASK_VARIATION = 0xFF0
    internal const val TYPE_TEXT_VARIATION_URI = 0x10
    internal const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20
    internal const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0xD0
}
