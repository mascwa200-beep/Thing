package dev.mascwa.pulse.feature.settings

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mascwa.pulse.feature.dream.LcarsDream
import dev.mascwa.pulse.feature.wallpaper.LcarsWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether each takeover is in place, read together off the main thread. */
private data class TakeoverState(val wallpaper: Boolean, val dream: Chosen, val assistant: Boolean)

/** Whether LCARS is the chosen screensaver — or whether Android would not say. */
private enum class Chosen { YES, NO, UNKNOWN }

/**
 * The parts of Android that LCARS can stand behind or in for but cannot take by itself: the
 * wallpaper, the screensaver and the assistant.
 *
 * ⚠️ **Each row reads the truth rather than a stored preference**, again every time the screen comes
 * back, because each is changed somewhere else too — the wallpaper in the phone's own settings, the
 * screensaver and the assistant only there.
 *
 * ⚠️ **The wallpaper is the only one this switches directly**, and it says the cost before the tap:
 * Android does not let an app read the current wallpaper to save it, so "off" can only return
 * Android's default. The screensaver and the assistant are chosen in Android's own pages; these rows
 * open them and say afterwards whether LCARS was picked.
 */
@Composable
internal fun TakeoverRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var openNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { refresh++ }
    val state by produceState<TakeoverState?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            TakeoverState(
                wallpaper = LcarsWallpaper.isOurs(context),
                dream = dreamChosen(context),
                assistant = holdsAssistant(context),
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
    openNote?.let { PrefInfo("Could not open", subtitle = it) }
}

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

private fun holdsAssistant(context: Context): Boolean = runCatching {
    context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
}.getOrDefault(false)

private fun open(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

/** Android's own name for the setting that holds the chosen screensaver; not a public constant. */
private const val SCREENSAVER_COMPONENTS = "screensaver_components"
