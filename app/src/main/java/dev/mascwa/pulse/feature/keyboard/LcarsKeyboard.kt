package dev.mascwa.pulse.feature.keyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.view.inputmethod.InputConnection
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.feature.keyboard.Composer.Edit
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Output
import dev.mascwa.pulse.ui.ServiceComposeOwner
import dev.mascwa.pulse.ui.theme.NightwireTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The LCARS keyboard: a full on-screen keyboard in the console's own look, for every app on the phone.
 *
 * Android lets anybody's keyboard be picked in its own keyboard settings; an app cannot pick itself,
 * so Settings ▸ Android takeover opens that page and then the picker. Every other keyboard stays
 * switched on, so the switch key — or Android's own picker — is always a way back.
 *
 * ## It sends nothing, and keeps only the words it learns
 *
 * A keyboard sees every word typed on the phone, passwords included. This one sends none of it
 * anywhere and logs none of it. The one thing it keeps, because the owner asked for it, is the list of
 * words it has learned — through [WordMemory], sealed on the phone by `LearnedWords`, never in a
 * password field or one that asks for no learning ([KeyboardPrivacy]), and only when "Learn words I
 * type" is on. `KeyboardRecordsNothingTest` fails the build if this package reaches for storage,
 * logging or the network itself, or for anything in the app but its settings and that list.
 *
 * ## The word being typed
 *
 * Typed letters are the field's COMPOSING text — underlined, as Gboard's are — until a space or a mark
 * of punctuation finishes them ([Composer]). Suggestions are worked out off the main thread as each
 * letter lands ([Suggest], over the bundled [Dictionary]) and shown in the strip ([StripWords]); the
 * separator then puts in the correction [Autocorrect] allows, if any, and delete straight afterwards
 * puts back what was typed. A password field gets none of this — its letters go straight in.
 *
 * ## Glide typing
 *
 * A finger drawn across the letters is read as a word ([GlideDecoder]) off the main thread, and the word
 * goes in as the composing word, with a space before it when the text before it ends a word — Gboard's
 * rule, which puts the space in when the next word starts, so a full stop straight after sits against
 * it. The other readings go in the strip. Only where corrections are allowed: not in a password, and not
 * in a web or email address, where a space put in for you would break it.
 *
 * ⚠️ **Nothing overtakes a word being read.** Anything done on the keyboard while a glide is still being
 * decoded — a key, a tap in the strip, the next glide — waits for it and then runs in the order it was
 * done ([whenIdle]); otherwise a fast thumb could put the letter it tapped next before the word it drew.
 *
 * ## Compose inside an input method
 *
 * An input method is a service, so — like the screensaver — its `ComposeView` needs owners supplied
 * ([ServiceComposeOwner]), set on the keyboard window's own decor view as well as on the view. The
 * owner is STARTED only while the keyboard is on screen, which is also what pauses Compose's frame
 * clock while it is hidden.
 */
class LcarsKeyboard : InputMethodService() {

    private val owner = ServiceComposeOwner()

    private var state by mutableStateOf(KeyboardModel.State())
    private var enter by mutableStateOf(EnterKey.RETURN)
    private var punctuation by mutableStateOf(",")
    private var offerSwitch by mutableStateOf(false)

    /** Whether the last key was the space bar, and where it left the cursor — see [DoubleSpace]. */
    private var spaces = DoubleSpace.Tracker()
    private var doubleSpaceHere = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The word being typed, and the words the strip offers for it. */
    private var composer = Composer.State()
    private var strip by mutableStateOf(NO_WORDS)
    private var candidates: List<Suggest.Candidate> = emptyList()
    private var candidatesFor = ""
    private var suggesting: Job? = null

    /** Where the last correction left the cursor, once the field has said; -1 until then. */
    private var undoAt = -1

    /** Null until the bundled list has loaded; until then the keyboard types and does nothing else. */
    private var dictionary: Dictionary? = null
    private var memory: WordMemory? = null

    // What the field allows (KeyboardPrivacy), and what the owner has switched on in Settings.
    private var suggestHere = false
    private var correctHere = false
    private var learnHere = false
    @Volatile private var autocorrectOn = true
    @Volatile private var learnOn = true
    private var glideOn by mutableStateOf(true)
    private var glideHere by mutableStateOf(false)
    private var dictionaryReady by mutableStateOf(false)

    /** A glide being read, and what was done on the keyboard since, waiting for it in order. */
    private var decoding: Job? = null
    private val waiting = ArrayList<() -> Unit>()

    override fun onCreate() {
        super.onCreate()
        owner.create()
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(owner)
            decor.setViewTreeSavedStateRegistryOwner(owner)
        }
        // The only two things the keyboard takes from the app: `KeyboardRecordsNothingTest` holds it to them.
        val container = runCatching { (application as PulseApplication).container }.getOrNull()
        memory = container?.learnedWords
        val settings = container?.settingsRepository?.settings
        if (settings != null) {
            scope.launch {
                settings.collect {
                    autocorrectOn = it.keyboardAutocorrect
                    learnOn = it.keyboardLearn
                    glideOn = it.keyboardGlide
                }
            }
        }
        scope.launch { withContext(Dispatchers.IO) { memory?.load() } }
        scope.launch {
            dictionary = WordList.get(applicationContext)
            dictionaryReady = dictionary != null
        }
    }

    override fun onCreateInputView(): View {
        val defaults = AppSettings()
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                NightwireTheme(accent = defaults.accentColor, amoledBlack = true) {
                    KeyboardPanel(
                        state = state,
                        enter = enter,
                        punctuation = punctuation,
                        offerSwitch = offerSwitch,
                        onKey = { key -> whenIdle { onKey(key) } },
                        onPicker = ::showPicker,
                        onCursor = { steps -> whenIdle { moveCursor(steps) } },
                        onEraseWords = { words -> whenIdle { eraseWords(words) } },
                        strip = strip,
                        onPick = { word -> whenIdle { pick(word) } },
                        glide = glideHere && glideOn && dictionaryReady,
                        onGlide = { stroke -> whenIdle { glide(stroke) } },
                    )
                }
            }
        }
    }

    /**
     * A new field — or the same one restarted, as a chat app does after sending. Either way the keyboard
     * opens as the field asks: on its digits for a number, with the enter key it named, and with shift
     * on if the cursor starts a sentence.
     */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        owner.start()
        val inputType = info?.inputType ?: 0
        enter = EnterKey.of(info?.imeOptions ?: 0)
        punctuation = KeyboardModel.punctuation(inputType)
        offerSwitch = runCatching { shouldOfferSwitchingToNextInputMethod() }.getOrDefault(false)
        state = KeyboardModel.State(layer = KeyboardModel.startLayer(inputType))
        spaces = DoubleSpace.Tracker()
        doubleSpaceHere = DoubleSpace.allowed(inputType)
        suggestHere = KeyboardPrivacy.maySuggest(inputType)
        correctHere = KeyboardPrivacy.mayAutocorrect(inputType)
        learnHere = KeyboardPrivacy.mayLearn(inputType, info?.imeOptions ?: 0)
        glideHere = correctHere
        // A new field, or the same one restarted: whatever was being composed belonged to the last one.
        dropWaiting()
        composer = Composer.State()
        clearStrip()
        refreshCaps()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        owner.stop()
        super.onFinishInputView(finishingInput)
    }

    /** Leaving a field mid-word leaves the word as typed, not underlined; it is not learned. */
    override fun onFinishInput() {
        dropWaiting()
        runCatching { currentInputConnection?.finishComposingText() }
        composer = Composer.State()
        clearStrip()
        super.onFinishInput()
    }

    /**
     * The cursor moved — because a key was typed, or because somebody tapped elsewhere in the text.
     * Either way shift should follow where it now is.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        spaces = DoubleSpace.selectionMoved(spaces, newSelStart, newSelEnd)
        if (composer.word.isNotEmpty() && !stillComposing(newSelStart, newSelEnd, candidatesEnd)) {
            // Somebody tapped elsewhere in the text: the word stays as typed, and the next letter
            // starts a new one where the cursor now is.
            runCatching { currentInputConnection?.finishComposingText() }
            composer = Composer.State()
            clearStrip()
        } else if (composer.word.isEmpty() && composer.undo != null) {
            // Delete puts back what was corrected only straight after the correction. The first update
            // with no composing word is the one the correction made, and says where the cursor was
            // left; any later move, or a selection, and delete is only a delete.
            when {
                newSelStart != newSelEnd -> composer = Composer.State()
                candidatesEnd >= 0 -> Unit // a late update from before the word was finished
                undoAt < 0 -> undoAt = newSelEnd
                newSelEnd != undoAt -> composer = Composer.State()
            }
        }
        refreshCaps()
    }

    /**
     * Whether the cursor is still at the end of the word being composed. ⚠️ Not just the numbers: an
     * update can arrive late, from before the last few keys, and report no composing word at all while
     * one is plainly there. So a missing range is settled by what the field says is before the cursor.
     */
    private fun stillComposing(selStart: Int, selEnd: Int, composingEnd: Int): Boolean {
        if (selStart != selEnd) return false
        if (composingEnd >= 0) return composingEnd == selEnd
        val before = runCatching { currentInputConnection?.getTextBeforeCursor(composer.word.length, 0) }.getOrNull()
        return before?.toString() == composer.word
    }

    /**
     * A landscape keyboard would otherwise switch to Android's full-screen text box, which is not LCARS
     * and hides the app being typed into.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        // A save may still be waiting; it must outlive this service, so it is not run on its scope.
        memory?.let { m -> (application as? PulseApplication)?.appScope?.launch { m.flushNow() } }
        scope.cancel()
        WordList.release()
        owner.destroy()
        super.onDestroy()
    }

    private fun onKey(key: KeyboardModel.Key) {
        val result = KeyboardModel.press(state, key, SystemClock.uptimeMillis())
        state = result.state
        val ic = currentInputConnection ?: return
        if (key == KeyboardModel.Key.Space) {
            // The second of two spaces after a word types ". " in place of the first — Gboard's full stop.
            val before = runCatching { ic.getTextBeforeCursor(2, 0) }.getOrNull()
            if (DoubleSpace.applies(spaces, before, doubleSpaceHere)) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                spaces = DoubleSpace.typedOther()
                composer = Composer.State()
                return
            }
            spaces = DoubleSpace.typedSpace()
        } else {
            spaces = DoubleSpace.typedOther()
        }
        when (val out = result.output) {
            is Output.Commit -> type(ic, out.text)
            Output.Delete -> delete(ic)
            Output.Enter -> {
                finishWord(ic, learn = true)
                val action = enter.action
                if (action != null) ic.performEditorAction(action) else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            Output.SwitchIme -> {
                finishWord(ic, learn = false)
                if (!switchToNextInputMethod(false)) showPicker()
            }
            Output.None -> Unit
        }
    }

    /** A letter adds to the word; anything else finishes it — corrected, when that is allowed. */
    private fun type(ic: InputConnection, text: String) {
        if (suggestHere && Composer.isWordText(text)) {
            val (s, edit) = Composer.letter(composer, text)
            composer = s
            apply(ic, edit)
            suggest()
            return
        }
        val word = composer.word
        if (word.isEmpty()) {
            composer = Composer.State()
            ic.commitText(text, 1)
            return
        }
        val replacement = if (Composer.correctsBefore(text)) correctionFor(word) else null
        val (s, edit) = Composer.separator(composer, text, replacement)
        composer = s
        undoAt = -1
        apply(ic, edit)
        // Only a word ended as words end is learned: "mp" is not a word because "mp3" was typed, and
        // the name before an "@" is an address, not something to offer back.
        if (replacement == null && Composer.correctsBefore(text)) learnTyped(word)
        clearStrip()
    }

    /**
     * Delete takes a letter off the word; straight after a correction it puts back what was typed, and
     * that word is learned — it was kept on purpose. Otherwise a key event rather than deleting a
     * character ourselves: the field then removes a whole selection, or a whole emoji, exactly as it
     * would for a hardware keyboard.
     */
    private fun delete(ic: InputConnection) {
        if (!suggestHere) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        val undo = composer.undo
        val before = if (composer.word.isEmpty() && undo != null) {
            runCatching { ic.getTextBeforeCursor(undo.committed.length, 0) }.getOrNull()
        } else {
            null
        }
        val (s, edit) = Composer.backspace(composer, before)
        composer = s
        when (edit) {
            is Edit.Compose -> {
                apply(ic, edit)
                if (s.word.isEmpty()) clearStrip() else suggest()
            }
            is Edit.Revert -> {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(edit.delete, 0)
                ic.commitText(edit.text, 1)
                ic.endBatchEdit()
                if (mayLearn()) memory?.accept(edit.text)
                clearStrip()
            }
            is Edit.Commit, is Edit.CommitThenCompose -> apply(ic, edit)
            Edit.Backspace -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    /** A word in the strip was tapped: it goes in with a space. The typed word, kept, is learned. */
    private fun pick(choice: StripWord) {
        val ic = currentInputConnection ?: return
        if (composer.word.isEmpty()) return
        val (s, edit) = Composer.pick(choice.word)
        composer = s
        apply(ic, edit)
        if (choice.typed && mayLearn()) memory?.accept(choice.word)
        spaces = DoubleSpace.typedOther()
        clearStrip()
    }

    /** The word is done as it stands — enter, the switch key, a slide of the cursor. */
    private fun finishWord(ic: InputConnection, learn: Boolean) {
        val word = composer.word
        composer = Composer.State()
        clearStrip()
        if (word.isEmpty()) return
        ic.finishComposingText()
        if (learn) learnTyped(word)
    }

    /**
     * Runs [action] now, or — while a glide is still being read — after it, in the order things were
     * done. See the class's note.
     */
    private fun whenIdle(action: () -> Unit) {
        if (decoding?.isActive == true) waiting += action else action()
    }

    /** What was waiting on a glide, run in order until one of them starts another glide. */
    private fun runWaiting() {
        while (waiting.isNotEmpty() && decoding?.isActive != true) waiting.removeAt(0)()
    }

    /** A new field: a glide still being read, and what waited on it, belonged to the last one. */
    private fun dropWaiting() {
        decoding?.cancel()
        decoding = null
        waiting.clear()
    }

    /**
     * A glide has lifted: its path is read off the main thread, and the word it spells goes in — or, if it
     * spells nothing, the letter the finger lifted on, as a tap would have typed it.
     */
    private fun glide(stroke: GlideDecoder.Stroke) {
        val dict = dictionary
        if (dict == null) {
            stroke.fallback?.let(::onKey)
            return
        }
        val learned = learnedWords()
        decoding = scope.launch {
            val found = withContext(Dispatchers.Default) {
                GlideDecoder.decode(stroke.path, stroke.centres, stroke.unit, dict, learned)
            }
            decoding = null
            if (found.isEmpty()) stroke.fallback?.let(::onKey) else glided(found.map { it.word })
            runWaiting()
        }
    }

    /**
     * [words] were read from a glide, best first. What was being typed is finished as it stands; a space
     * goes in first if the text before ends a word; and the best word goes in as the composing word,
     * capitalised as shift says, with the rest in the strip.
     */
    private fun glided(words: List<String>) {
        val ic = currentInputConnection ?: return
        spaces = DoubleSpace.typedOther()
        undoAt = -1
        ic.beginBatchEdit()
        val previous = composer
        composer = Composer.State()
        if (previous.word.isNotEmpty()) {
            ic.finishComposingText()
            // A glided word was already a word; one typed by hand is kept as typed, and learned as one.
            if (!previous.glided) learnTyped(previous.word)
        }
        val before = runCatching { ic.getTextBeforeCursor(1, 0) }.getOrNull()
        if (Composer.spaceBeforeGlide(before)) {
            ic.commitText(" ", 1)
            // Shift follows the space just put in: after ". " the word starts a sentence.
            refreshCaps()
        }
        val shift = state.shift
        val cased = words.map { Composer.glideCase(it, shift) }
        if (shift == KeyboardModel.ShiftState.ONCE) state = state.copy(shift = KeyboardModel.ShiftState.OFF, auto = false)
        val (s, edit) = Composer.glide(cased.first())
        composer = s
        apply(ic, edit)
        ic.endBatchEdit()
        clearStrip()
        strip = StripWords.glided(cased)
    }

    private fun apply(ic: InputConnection, edit: Edit) {
        when (edit) {
            is Edit.Compose -> ic.setComposingText(edit.text, 1)
            is Edit.Commit -> ic.commitText(edit.text, 1)
            is Edit.CommitThenCompose -> {
                ic.beginBatchEdit()
                ic.commitText(edit.commit, 1)
                ic.setComposingText(edit.compose, 1)
                ic.endBatchEdit()
            }
            is Edit.Revert, Edit.Backspace -> Unit
        }
    }

    /** Suggestions for the word as it now stands, worked out off the main thread; a newer letter wins. */
    private fun suggest() {
        val word = composer.word
        val dict = dictionary ?: return
        val learned = learnedWords()
        suggesting?.cancel()
        suggesting = scope.launch {
            val found = withContext(Dispatchers.Default) { Suggest.candidates(word, dict, learned) }
            if (composer.word != word) return@launch
            candidates = found
            candidatesFor = word
            val correction = if (correctHere && autocorrectOn) {
                Autocorrect.decide(word, found, ::isKnown, allowed = true)
            } else {
                null
            }
            strip = StripWords.arrange(word, found, correction)
        }
    }

    /**
     * What the separator should put in place of [word], or null. Uses the suggestions already worked
     * out for it; only when the last letter was faster than they were is it worked out here.
     */
    private fun correctionFor(word: String): String? {
        if (!correctHere || !autocorrectOn) return null
        val dict = dictionary ?: return null
        val found = if (candidatesFor == word) candidates else Suggest.candidates(word, dict, learnedWords())
        return Autocorrect.decide(word, found, ::isKnown, allowed = true)
    }

    /**
     * [word] was kept as typed. Learned only where the field allows it and the owner has it on, and only
     * when it is not already a word — the list holds what the dictionary lacks, and nothing else.
     */
    private fun learnTyped(word: String) {
        if (!mayLearn()) return
        val dict = dictionary ?: return
        if (dict.contains(word) || !LearnedCounts.isLearnable(word)) return
        memory?.saw(word)
    }

    private fun mayLearn(): Boolean = learnHere && learnOn

    private fun learnedWords(): Collection<String> = if (learnOn) memory?.known().orEmpty() else emptyList()

    private fun isKnown(word: String): Boolean {
        if (dictionary?.contains(word) == true) return true
        val k = Dictionary.key(word)
        return learnedWords().any { Dictionary.key(it) == k }
    }

    private fun clearStrip() {
        suggesting?.cancel()
        suggesting = null
        candidates = emptyList()
        candidatesFor = ""
        if (strip != NO_WORDS) strip = NO_WORDS
    }

    /**
     * The space bar slid [steps] characters (negative is left). Arrow-key events rather than setting the
     * selection ourselves: the field then moves across an emoji or a line break exactly as it would for
     * a hardware keyboard, and the keyboard needs no idea where in the text the cursor is.
     */
    private fun moveCursor(steps: Int) {
        spaces = DoubleSpace.typedOther()
        currentInputConnection?.let { finishWord(it, learn = false) }
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps).coerceAtMost(MAX_CURSOR_STEPS)) { sendDownUpKeyEvents(code) }
    }

    /**
     * A swipe from delete marked [words] words. A selection is deleted as delete would delete it;
     * otherwise the words before the cursor go, counted by [WordErase] over what the field says is
     * there — never more than it says.
     */
    private fun eraseWords(words: Int) {
        spaces = DoubleSpace.typedOther()
        val ic = currentInputConnection ?: return
        finishWord(ic, learn = false)
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        val before = runCatching { ic.getTextBeforeCursor(ERASE_LOOKBACK, 0) }.getOrNull() ?: return
        val chars = WordErase.chars(before, words)
        if (chars > 0) ic.deleteSurroundingText(chars, 0)
    }

    private fun refreshCaps() {
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: return
        val caps = runCatching { ic.getCursorCapsMode(type) }.getOrDefault(0)
        state = KeyboardModel.autoCaps(state, caps)
    }

    private fun showPicker() {
        runCatching { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
    }

    private companion object {
        /** How much text before the cursor a word erase reads — far more than any swipe marks. */
        const val ERASE_LOOKBACK = 256

        /** A bound on one slide's arrow keys, so a runaway gesture cannot queue thousands of events. */
        const val MAX_CURSOR_STEPS = 64

        val NO_WORDS: List<StripWord?> = listOf(null, null, null)
    }
}

/**
 * The bundled word list, read once and shared by every keyboard the service creates. About six
 * megabytes held, so it is let go when the keyboard service is.
 */
private object WordList {

    const val ASSET = "keyboard/en_US.tsv"

    @Volatile private var held: Dictionary? = null
    private val lock = Mutex()

    suspend fun get(context: Context): Dictionary? = held ?: lock.withLock {
        held ?: withContext(Dispatchers.IO) {
            runCatching { context.assets.open(ASSET).bufferedReader().useLines { Dictionary.parse(it) } }.getOrNull()
        }?.also { held = it }
    }

    fun release() {
        held = null
    }
}
