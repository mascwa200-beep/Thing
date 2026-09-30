package dev.mascwa.pulse.feature.keyboard

/**
 * The word being typed, kept as the field's COMPOSING text — underlined, as Gboard's is — until a space
 * or a mark of punctuation finishes it. Pure: it answers what to send to the field and the keyboard
 * sends it.
 *
 * - A letter adds to the word ([Edit.Compose]).
 * - A glided word goes in whole ([glide]), and a letter after it starts a new word with a space between
 *   ([Edit.CommitThenCompose]) — Gboard's rule: the space goes in when the next word starts, so a full
 *   stop straight after a glided word sits against it.
 * - A separator finishes it, as the autocorrection when there is one, followed by the separator
 *   ([Edit.Commit]). An autocorrection is remembered ([Undo]) until the next key.
 * - Delete takes a letter off the word; with no word, straight after an autocorrection, it puts back
 *   what was typed ([Edit.Revert]); otherwise it is an ordinary delete ([Edit.Backspace]).
 *
 * ⚠️ **The undo checks the text is still what was committed**, because the keyboard only remembers what
 * it did — anything could have happened to the text since. If the field no longer ends with the
 * corrected word and its separator, delete is only a delete.
 */
object Composer {

    /** The word being composed; how to undo the last correction; whether the word was glided. */
    data class State(val word: String = "", val undo: Undo? = null, val glided: Boolean = false)

    /** An autocorrection: what was [typed], and the [committed] text that replaced it, separator included. */
    data class Undo(val typed: String, val committed: String)

    sealed interface Edit {
        /** Show [text] as the composing word. Empty removes it. */
        data class Compose(val text: String) : Edit

        /** Put [text] into the field, finishing the composing word. */
        data class Commit(val text: String) : Edit

        /** Put [commit] into the field, finishing the composing word, then show [compose] as a new one. */
        data class CommitThenCompose(val commit: String, val compose: String) : Edit

        /** Delete [delete] characters before the cursor, then put in [text]. */
        data class Revert(val delete: Int, val text: String) : Edit

        /** An ordinary delete, as the field would do it for a hardware key. */
        data object Backspace : Edit
    }

    /** Whether [text] is part of a word: letters and the apostrophe of "don't". */
    fun isWordText(text: String): Boolean = text.isNotEmpty() && text.all { it.isLetter() || it == '\'' }

    /** Whether finishing a word with [separator] is a moment to autocorrect it. */
    fun correctsBefore(separator: String): Boolean = separator == " " || separator in SENTENCE_MARKS

    private val SENTENCE_MARKS = setOf(",", ".", "?", "!", ";", ":")

    fun letter(s: State, text: String): Pair<State, Edit> {
        // A LETTER after a glided word starts the next word. An apostrophe does not: "don" glided and
        // then "'" is the start of "don't", not a new word.
        if (s.glided && s.word.isNotEmpty() && text.first().isLetter()) {
            return State(word = text) to Edit.CommitThenCompose(s.word + " ", text)
        }
        val word = s.word + text
        return State(word = word) to Edit.Compose(word)
    }

    /**
     * A glided [word] goes in as the composing word, marked as glided. What was being composed before it
     * has already been finished by the keyboard, and any space it needs already put in ([spaceBeforeGlide]).
     */
    fun glide(word: String): Pair<State, Edit> = State(word = word, glided = true) to Edit.Compose(word)

    /**
     * Whether a glided word needs a space before it: when what is before the cursor ends a word or a
     * sentence. Not at the start of the field or of a line, not after a space, and not straight after a
     * mark that opens something or joins a word to the next — a bracket, a quote, a hyphen, a slash,
     * an @, a # — where the word belongs against it.
     */
    fun spaceBeforeGlide(before: CharSequence?): Boolean {
        val c = before?.lastOrNull() ?: return false
        return !c.isWhitespace() && c !in JOINS_NEXT
    }

    private const val JOINS_NEXT = "([{<\"'\u201C\u2018\u00BF\u00A1/@#-_"

    /** A glided word, capitalised as the shift key says: the first letter once, or every letter locked. */
    fun glideCase(word: String, shift: KeyboardModel.ShiftState): String = when (shift) {
        KeyboardModel.ShiftState.LOCKED -> word.uppercase()
        KeyboardModel.ShiftState.ONCE -> word.replaceFirstChar { it.uppercaseChar() }
        KeyboardModel.ShiftState.OFF -> word
    }

    /** [separator] typed after the word, which becomes [replacement] if that is not null. */
    fun separator(s: State, separator: String, replacement: String?): Pair<State, Edit> {
        if (s.word.isEmpty()) return State() to Edit.Commit(separator)
        val out = (replacement ?: s.word) + separator
        val undo = if (replacement != null && replacement != s.word) Undo(s.word, out) else null
        return State(undo = undo) to Edit.Commit(out)
    }

    /** The word is finished as it stands, with nothing after it: enter, or the field losing the cursor. */
    fun finish(s: State): Pair<State, Edit?> =
        if (s.word.isEmpty()) State() to null else State() to Edit.Commit(s.word)

    /** A suggestion was tapped: it replaces the word, followed by a space. */
    fun pick(word: String): Pair<State, Edit> = State() to Edit.Commit("$word ")

    fun backspace(s: State, before: CharSequence?): Pair<State, Edit> {
        val undo = s.undo
        return when {
            // A glided word went in whole, so it comes out whole.
            s.glided && s.word.isNotEmpty() -> State() to Edit.Compose("")
            s.word.isNotEmpty() -> {
                val word = s.word.dropLast(1)
                State(word = word) to Edit.Compose(word)
            }
            undo != null && before != null && before.endsWith(undo.committed) ->
                State() to Edit.Revert(undo.committed.length, undo.typed)
            else -> State() to Edit.Backspace
        }
    }
}

/**
 * What a field allows the keyboard to do with what is typed in it.
 *
 * ⚠️ **A password field gets nothing** — no underlined word, no suggestions, no corrections, and above
 * all nothing learned — and neither does a field that asks for no suggestions or no personalised
 * learning. A number, phone or date field is not text and gets none of it either. A web or email
 * address gets suggestions but is neither corrected nor learned from: in an address a "typo" is a
 * spelling, and a name typed there is somebody's address, not a word to offer back.
 */
object KeyboardPrivacy {

    fun maySuggest(inputType: Int): Boolean {
        if (inputType and KeyboardModel.TYPE_MASK_CLASS != KeyboardModel.TYPE_CLASS_TEXT) return false
        if (inputType and TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (inputType and KeyboardModel.TYPE_MASK_VARIATION) {
            DoubleSpace.TYPE_TEXT_VARIATION_PASSWORD,
            DoubleSpace.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            DoubleSpace.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            -> false
            else -> true
        }
    }

    /** Corrections are narrower still: never in a web or email address, where a "typo" is a spelling. */
    fun mayAutocorrect(inputType: Int): Boolean = maySuggest(inputType) && DoubleSpace.allowed(inputType)

    fun mayLearn(inputType: Int, imeOptions: Int): Boolean =
        mayAutocorrect(inputType) && imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING == 0

    // android.text.InputType and android.view.inputmethod.EditorInfo, read out of the platform-35 SDK
    // stub with `javap -constants`.
    internal const val TYPE_TEXT_FLAG_NO_SUGGESTIONS = 0x80000
    internal const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000
}
