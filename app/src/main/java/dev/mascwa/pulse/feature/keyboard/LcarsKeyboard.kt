package dev.mascwa.pulse.feature.keyboard

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
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Output
import dev.mascwa.pulse.ui.ServiceComposeOwner
import dev.mascwa.pulse.ui.theme.NightwireTheme
import kotlin.math.abs

/**
 * The LCARS keyboard: a full on-screen keyboard in the console's own look, for every app on the phone.
 *
 * Android lets anybody's keyboard be picked in its own keyboard settings; an app cannot pick itself,
 * so Settings ▸ Android takeover opens that page and then the picker. Every other keyboard stays
 * switched on, so the switch key — or Android's own picker — is always a way back.
 *
 * ## It records nothing, and sends nothing
 *
 * A keyboard sees every word typed on the phone, passwords included. This one keeps none of it: no
 * dictionary, no prediction, no history, no network, no log. What a key does is decided by
 * [KeyboardModel] from the key, the keyboard's own state and the field's declared type, and the answer
 * goes straight to the field. `KeyboardRecordsNothingTest` fails the build if this package ever reaches
 * for storage, logging or the network.
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

    override fun onCreate() {
        super.onCreate()
        owner.create()
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(owner)
            decor.setViewTreeSavedStateRegistryOwner(owner)
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
                        onKey = ::onKey,
                        onPicker = ::showPicker,
                        onCursor = ::moveCursor,
                        onEraseWords = ::eraseWords,
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
        refreshCaps()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        owner.stop()
        super.onFinishInputView(finishingInput)
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
        refreshCaps()
    }

    /**
     * A landscape keyboard would otherwise switch to Android's full-screen text box, which is not LCARS
     * and hides the app being typed into.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
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
                return
            }
            spaces = DoubleSpace.typedSpace()
        } else {
            spaces = DoubleSpace.typedOther()
        }
        when (val out = result.output) {
            is Output.Commit -> ic.commitText(out.text, 1)
            // A key event rather than deleting a character ourselves: the field then removes a whole
            // selection, or a whole emoji, exactly as it would for a hardware keyboard.
            Output.Delete -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            Output.Enter -> {
                val action = enter.action
                if (action != null) ic.performEditorAction(action) else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            Output.SwitchIme -> if (!switchToNextInputMethod(false)) showPicker()
            Output.None -> Unit
        }
    }

    /**
     * The space bar slid [steps] characters (negative is left). Arrow-key events rather than setting the
     * selection ourselves: the field then moves across an emoji or a line break exactly as it would for
     * a hardware keyboard, and the keyboard needs no idea where in the text the cursor is.
     */
    private fun moveCursor(steps: Int) {
        spaces = DoubleSpace.typedOther()
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
    }
}
