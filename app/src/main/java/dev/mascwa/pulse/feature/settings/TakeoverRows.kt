package dev.mascwa.pulse.feature.settings

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.data.keyboard.LearnedWords
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.feature.dream.LcarsDream
import dev.mascwa.pulse.feature.keyboard.LcarsKeyboard
import dev.mascwa.pulse.feature.wallpaper.LcarsWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether each takeover is in place, read together off the main thread. */
private data class TakeoverState(
    val wallpaper: Boolean,
    val dream: Chosen,
    val assistant: Boolean,
    val keyboard: KeyboardStatus,
)

/** Where the LCARS keyboard stands: not switched on, switched on but not in use, or the keyboard. */
private enum class KeyboardStatus { OFF, ENABLED, CHOSEN }

/** Whether LCARS is the chosen screensaver — or whether Android would not say. */
private enum class Chosen { YES, NO, UNKNOWN }

/**
 * The parts of Android that LCARS can stand behind or in for but cannot take by itself: the
 * wallpaper, the screensaver, the assistant and the keyboard.
 *
 * ⚠️ **Each row reads the truth rather than a stored preference**, again every time the screen comes
 * back, because each is changed somewhere else too — the wallpaper in the phone's own settings, the
 * screensaver, the assistant and the keyboard only there.
 *
 * ⚠️ **The wallpaper is the only one this switches directly**, and it says the cost before the tap:
 * Android does not let an app read the current wallpaper to save it, so "off" can only return
 * Android's default. The screensaver and the assistant are chosen in Android's own pages; these rows
 * open them and say afterwards whether LCARS was picked.
 */
@Composable
internal fun TakeoverRows(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var openNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { refresh++ }
    // Android's keyboard picker is a system dialog over this screen, so closing it does not restart
    // the screen — getting the focus back is the only sign that something may have been picked.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) refresh++ }
    val state by produceState<TakeoverState?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            TakeoverState(
                wallpaper = LcarsWallpaper.isOurs(context),
                dream = dreamChosen(context),
                assistant = holdsAssistant(context),
                keyboard = keyboardStatus(context),
            )
        }
    }
    val st = state

    PrefSwitch(
        "LCARS wallpaper",
        subtitle = note ?: when {
            st == null || busy -> "Working…"
            st.wallpaper -> "The LCARS frame is behind Android's own screens, home and lock. Switching this " +
                "off returns Android's default wallpaper — not the one you had before."
            else -> "Puts the LCARS frame behind the PIN pad, the app switcher and the shade, on the home " +
                "and lock screens. It replaces your current wallpaper, which Android does not let an app " +
                "save first; switching it off returns Android's default."
        },
        checked = st?.wallpaper == true,
        enabled = st != null && !busy,
    ) { on ->
        busy = true
        note = null
        scope.launch {
            note = withContext(Dispatchers.IO) {
                if (on) {
                    when (LcarsWallpaper.apply(context)) {
                        LcarsWallpaper.Result.SET -> null
                        LcarsWallpaper.Result.REFUSED -> "Android does not allow the wallpaper to be changed on this phone."
                        LcarsWallpaper.Result.FAILED -> "Android would not take the wallpaper. Nothing was changed."
                    }
                } else {
                    if (LcarsWallpaper.clear(context)) null else "Android would not reset the wallpaper."
                }
            }
            busy = false
            refresh++
        }
    }

    PrefClickable(
        "LCARS screensaver",
        subtitle = when (st?.dream) {
            null -> "Checking…"
            Chosen.YES -> "LCARS is the screensaver: the clock, the stardate and the board while the " +
                "phone charges. Tap to change it in Android's screensaver settings."
            Chosen.NO -> "Another screensaver is chosen. Tap to pick LCARS in Android's screensaver settings."
            Chosen.UNKNOWN -> "The clock, the stardate and the board while the phone charges. Tap to pick " +
                "LCARS in Android's screensaver settings."
        },
        onClick = {
            if (!open(context, Intent(Settings.ACTION_DREAM_SETTINGS))) {
                openNote = "Android would not open its screensaver settings."
            }
        },
    )

    PrefClickable(
        "LCARS as the assistant",
        subtitle = when (st?.assistant) {
            null -> "Checking…"
            true -> "LCARS is the digital assistant: the assistant gesture opens the Computer. For the " +
                "power button, set \"Press and hold power\" to the assistant in Android's gestures."
            false -> "Makes the assistant gesture open the Computer. Tap, then pick LCARS as the digital " +
                "assistant app — Android does not let an app choose itself."
        },
        onClick = {
            val opened = open(context, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) ||
                open(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            if (!opened) openNote = "Android would not open its default-apps settings."
        },
    )
    PrefClickable(
        "LCARS keyboard",
        subtitle = when (st?.keyboard) {
            null -> "Checking…"
            KeyboardStatus.CHOSEN -> "LCARS is the keyboard. It sends nothing you type anywhere; the only " +
                "thing it keeps is the words it learns, below. Tap to pick another."
            KeyboardStatus.ENABLED -> "Switched on but not in use. Tap to pick it."
            KeyboardStatus.OFF -> "A full keyboard in the console's own look, for every app. Tap to switch it " +
                "on in Android's keyboard list, then come back here to pick it. It sends nothing you type " +
                "anywhere; your other keyboards stay on."
        },
        onClick = {
            val picked = st?.keyboard != KeyboardStatus.OFF && showKeyboardPicker(context)
            if (!picked && !open(context, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))) {
                openNote = "Android would not open its keyboard settings."
            }
        },
    )
    KeyboardWordRows(settings, onUpdate, refresh)
    openNote?.let { PrefInfo("Could not open", subtitle = it) }
}

/**
 * The keyboard's corrections and the words it learns. ⚠️ **Forgetting asks twice**: it cannot be undone,
 * and one tap on a long list of rows is easy to make by accident.
 */
@Composable
private fun KeyboardWordRows(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit, refresh: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val learned = remember {
        runCatching { (context.applicationContext as PulseApplication).container.learnedWords }.getOrNull()
    }
    var forgotten by remember { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    val summary by produceState<LearnedWords.Summary?>(null, refresh, forgotten) {
        value = learned?.let { l -> withContext(Dispatchers.IO) { l.load(); l.summary() } }
    }

    PrefSwitch(
        "Autocorrect",
        subtitle = "Fixes a slip as a word ends — \"teh\" becomes \"the\" — and delete straight after puts back " +
            "what you typed. Never in a password, a web address or an email.",
        checked = settings.keyboardAutocorrect,
    ) { v -> onUpdate { it.copy(keyboardAutocorrect = v) } }

    PrefSwitch(
        "Learn words I type",
        subtitle = "A word the keyboard does not know, typed twice, stops being corrected. Kept on this phone " +
            "only, sealed with its hardware key; never learned in a password field or an address.",
        checked = settings.keyboardLearn,
    ) { v -> onUpdate { it.copy(keyboardLearn = v) } }

    val s = summary
    PrefClickable(
        "Forget learned words",
        subtitle = when {
            learned == null -> "Not available."
            s == null -> "Checking…"
            s.unreadable -> "The learned words on this phone could not be opened — its key may have changed. " +
                "Tap to forget them and start again."
            s.words == 0 -> "Nothing learned yet."
            confirming -> "Tap again to forget ${words(s.words)} for good."
            else -> "${words(s.known)} learned, ${s.words - s.known} more seen once. Tap to forget them all."
        },
        onClick = {
            val l = learned ?: return@PrefClickable
            if (s == null || (s.words == 0 && !s.unreadable)) return@PrefClickable
            if (!confirming && !s.unreadable) {
                confirming = true
                return@PrefClickable
            }
            confirming = false
            scope.launch {
                l.forget()
                forgotten++
            }
        },
    )
}

private fun words(n: Int): String = if (n == 1) "1 word" else "$n words"

/**
 * Whether LCARS is the chosen screensaver. [Chosen.UNKNOWN] when Android will not say — the setting
 * that holds it is not one every build lets an app read, and a guess would be worse than silence.
 */
private fun dreamChosen(context: Context): Chosen {
    val value = runCatching { Settings.Secure.getString(context.contentResolver, SCREENSAVER_COMPONENTS) }
        .getOrElse { return Chosen.UNKNOWN }
        ?: return Chosen.UNKNOWN
    val ours = ComponentName(context, LcarsDream::class.java)
    val chosen = value.split(',').mapNotNull { ComponentName.unflattenFromString(it.trim()) }
    return if (ours in chosen) Chosen.YES else Chosen.NO
}

/**
 * Whether the LCARS keyboard is switched on in Android's list, and whether it is the one in use.
 *
 * The keyboard in use is the component Android names in `DEFAULT_INPUT_METHOD`, which it may write in
 * the short `package/.Class` form; `unflattenFromString` reads either, so comparing component names
 * rather than strings is what makes the answer right.
 */
private fun keyboardStatus(context: Context): KeyboardStatus {
    val ours = ComponentName(context, LcarsKeyboard::class.java)
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return KeyboardStatus.OFF
    val enabled = runCatching { imm.enabledInputMethodList.any { it.component == ours } }.getOrDefault(false)
    if (!enabled) return KeyboardStatus.OFF
    val current = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }.getOrNull()
    val inUse = current?.let { ComponentName.unflattenFromString(it) }
    return if (inUse == ours) KeyboardStatus.CHOSEN else KeyboardStatus.ENABLED
}

private fun showKeyboardPicker(context: Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
    return runCatching { imm.showInputMethodPicker() }.isSuccess
}

private fun holdsAssistant(context: Context): Boolean = runCatching {
    context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
}.getOrDefault(false)

private fun open(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

/** Android's own name for the setting that holds the chosen screensaver; not a public constant. */
private const val SCREENSAVER_COMPONENTS = "screensaver_components"
