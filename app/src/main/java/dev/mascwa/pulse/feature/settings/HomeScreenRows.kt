package dev.mascwa.pulse.feature.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mascwa.pulse.feature.launcher.HomeStore
import dev.mascwa.pulse.feature.launcher.HomeSwitch
import dev.mascwa.pulse.security.DevicePolicyController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** What the rows below need to know, read together off the main thread. */
private data class HomeRowState(val on: Boolean, val isDefault: Boolean, val owner: Boolean, val stoodDownAtMs: Long?)

/**
 * The switch that makes the LCARS console the phone's home screen, and whatever it needs to say.
 *
 * ⚠️ **It reads the truth rather than a stored preference.** The switch shows whether the Home alias
 * is enabled and the line under it whether pressing Home would open LCARS — so a stand-down by the
 * crash-loop guard, or a Home changed in Android's own settings, shows here as it is. Both are read
 * again every time the screen comes back, because the place they change is somewhere else.
 */
@Composable
internal fun HomeScreenRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { refresh++ }
    val state by produceState<HomeRowState?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            HomeRowState(
                on = HomeSwitch.isOn(context),
                isDefault = HomeSwitch.isDefault(context),
                owner = DevicePolicyController.isDeviceOwner(context),
                stoodDownAtMs = HomeStore(context).standDownAtMs(),
            )
        }
    }
    val st = state

    PrefSwitch(
        "LCARS home screen",
        subtitle = describe(st),
        checked = st?.on == true,
        enabled = st != null,
    ) { on ->
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (on) HomeSwitch.turnOn(context) else HomeSwitch.turnOff(context)
            }
            // Without device-owner rights Android will not let an app choose the Home for anyone;
            // the one honest thing to do is take them to the place they choose it.
            if (result == HomeSwitch.Result.CHOOSE_YOURSELF) openHomeSettings(context)
            refresh++
        }
    }
    if (st != null && st.on && !st.isDefault) {
        PrefClickable(
            "Choose LCARS as Home",
            subtitle = "Switched on, but Android is still opening another home screen. Pick LCARS in " +
                "Android's Default apps ▸ Home app.",
            onClick = { openHomeSettings(context) },
        )
    }
}

private fun describe(st: HomeRowState?): String = when {
    st == null -> "Checking…"
    !st.on && st.stoodDownAtMs != null ->
        "Stood down at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(st.stoodDownAtMs))}: the " +
            "LCARS home screen crashed as it started, three times in two minutes, so your previous " +
            "launcher is back. The crash console has what went wrong."
    !st.on ->
        "Makes the LCARS console your home screen: the dock, a search box, the board, and every app " +
            "A to Z. Your other launcher stays installed, and switching this off brings it back." +
            if (st.owner) "" else " You pick LCARS once in Android's Home app setting."
    st.isDefault ->
        "LCARS is your home screen" + (if (st.owner) ", set as the phone's Home by device owner." else ".") +
            " Switching this off hands Home back to your other launcher, and it stays off."
    else -> "Switched on, but not yet chosen as Home."
}

private fun openHomeSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
