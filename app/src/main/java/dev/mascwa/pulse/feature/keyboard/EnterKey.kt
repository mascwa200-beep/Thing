package dev.mascwa.pulse.feature.keyboard

/**
 * What the keyboard's enter key does in a given field, and what it says on it.
 *
 * A field declares the action it wants (search, send, go, next, done) in `EditorInfo.imeOptions`;
 * the key performs that action and is labelled for it. Otherwise it is a RETURN.
 *
 * ## When the answer is RETURN
 *
 * - **The field asked for no enter action** (`IME_FLAG_NO_ENTER_ACTION`). A multi-line text box sets
 *   this for itself — Android's own `TextView` adds it for every multi-line field — so a note gets a
 *   new line rather than being "sent".
 * - **The field named no action at all**, or one this keyboard does not know. RETURN is then sent as
 *   an Enter KEY EVENT rather than a typed line break, because that is the one thing every kind of
 *   field understands: a multi-line field inserts a line, and a single-line field treats it as
 *   "submit", which is what somebody pressing enter in one wants.
 *
 * ⚠️ A multi-line field that DOES name an action and does not set the flag — a chat box whose app
 * wants enter to send — gets the action. That is the app saying what it wants, and it is what every
 * other keyboard does there too.
 */
object EnterKey {

    /** [action] is the `EditorInfo` action to perform; null means send an Enter key event. */
    data class Spec(val action: Int?, val label: String)

    /** The enter key for a field whose `EditorInfo.imeOptions` are [imeOptions]. */
    fun of(imeOptions: Int): Spec {
        if (imeOptions and IME_FLAG_NO_ENTER_ACTION != 0) return RETURN
        return when (val action = imeOptions and IME_MASK_ACTION) {
            IME_ACTION_GO -> Spec(action, "GO")
            IME_ACTION_SEARCH -> Spec(action, "SEARCH")
            IME_ACTION_SEND -> Spec(action, "SEND")
            IME_ACTION_NEXT -> Spec(action, "NEXT")
            IME_ACTION_DONE -> Spec(action, "DONE")
            IME_ACTION_PREVIOUS -> Spec(action, "PREV")
            else -> RETURN
        }
    }

    val RETURN = Spec(null, "RETURN")

    // android.view.inputmethod.EditorInfo, restated so this file needs no Android to test. Each value
    // was read out of the platform-35 SDK stub with `javap -constants`.
    internal const val IME_MASK_ACTION = 0xFF
    internal const val IME_ACTION_UNSPECIFIED = 0
    internal const val IME_ACTION_NONE = 1
    internal const val IME_ACTION_GO = 2
    internal const val IME_ACTION_SEARCH = 3
    internal const val IME_ACTION_SEND = 4
    internal const val IME_ACTION_NEXT = 5
    internal const val IME_ACTION_DONE = 6
    internal const val IME_ACTION_PREVIOUS = 7
    internal const val IME_FLAG_NO_ENTER_ACTION = 0x40000000
}
