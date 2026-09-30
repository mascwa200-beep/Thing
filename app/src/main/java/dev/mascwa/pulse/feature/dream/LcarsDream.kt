package dev.mascwa.pulse.feature.dream

import android.service.dreams.DreamService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.feature.lcarsboard.LcarsBoardPanels
import dev.mascwa.pulse.feature.lcarsboard.legibleOn
import dev.mascwa.pulse.ui.LocalStardate
import dev.mascwa.pulse.ui.ProvideStardate
import dev.mascwa.pulse.ui.ServiceComposeOwner
import dev.mascwa.pulse.ui.theme.Antonio
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwireTheme
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.widget.LockBoard
import kotlinx.coroutines.delay
import java.util.Date

/**
 * The LCARS screensaver: the clock, the stardate and the widget's board, on black, while the phone
 * charges or sits in a dock. Chosen in Android's own screensaver page — an app cannot pick itself.
 *
 * ⚠️ **Mostly black, and never still.** It runs for hours on an OLED screen, so the picture holds no
 * large lit area and moves a few pixels every minute ([DreamDrift]); a static LCARS frame would print
 * itself into the glass.
 *
 * ⚠️ **Not interactive.** A touch ends it and wakes the phone, which is what somebody reaching for a
 * charging phone wants; a board that swallowed the first tap would be one more thing in the way.
 *
 * ## Compose inside a DreamService
 *
 * A dream is a `Service`, not an activity, so it is neither a `LifecycleOwner` nor a
 * `SavedStateRegistryOwner` — and a `ComposeView` without both on its view tree throws the moment it
 * attaches. [ServiceComposeOwner] supplies them, driven by the dream's own start and stop, which is
 * also what lets the board's flow collect only while the dream is showing.
 */
class LcarsDream : DreamService() {

    private val owner = ServiceComposeOwner()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        owner.create()
        window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(owner)
            decor.setViewTreeSavedStateRegistryOwner(owner)
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val defaults = AppSettings()
                NightwireTheme(accent = defaults.accentColor, amoledBlack = true) {
                    ProvideStardate { DreamConsole() }
                }
            }
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        owner.start()
        (application as? PulseApplication)?.let { app ->
            LockBoard.refreshIfStale(app, app.appScope, System.currentTimeMillis())
        }
    }

    override fun onDreamingStopped() {
        owner.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        owner.destroy()
        super.onDetachedFromWindow()
    }
}

@Composable
private fun DreamConsole() {
    val c = Pulse.colors
    val context = LocalContext.current
    val snapshot by LockBoard.latest.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var minute by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            delay(TICK_MS - System.currentTimeMillis() % TICK_MS)
            now = System.currentTimeMillis()
            minute = (now - start) / 60_000L
        }
    }
    val (dx, dy) = DreamDrift.offsetAt(minute)
    val time = remember(now / TICK_MS) {
        android.text.format.DateFormat.getTimeFormat(context).format(Date(now))
    }
    // Keyed on the minute, not on `now / DAY_MS`: that is a UTC day, and the date would stay on
    // yesterday for hours after local midnight everywhere west of Greenwich.
    val date = remember(now / 60_000L) {
        android.text.format.DateFormat.getLongDateFormat(context).format(Date(now))
    }
    Box(Modifier.fillMaxSize().background(c.void)) {
        Column(
            Modifier
                .fillMaxWidth()
                .offset(dx.dp, dy.dp)
                .padding(horizontal = 32.dp, vertical = 48.dp),
        ) {
            Text(time, fontFamily = Antonio, fontSize = 64.sp, color = legibleOn(c.accent, c.void))
            Text(
                "$date · ${LocalStardate.current}",
                fontFamily = JetBrainsMono,
                fontSize = 13.sp,
                color = legibleOn(c.muted, c.void),
            )
            snapshot?.board?.let { board ->
                LcarsBoardPanels(
                    board,
                    progress = { 1f },
                    onTap = {},
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    agendaLimit = DREAM_AGENDA_ROWS,
                )
            }
        }
    }
}

private const val TICK_MS = 1_000L
private const val DREAM_AGENDA_ROWS = 4
