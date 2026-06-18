package dev.mascwa.pulse.feature.hud

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import dev.mascwa.pulse.data.jarvis.db.Speaker
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the glasses HUD: when [AppContainer.settingsRepository]'s `glassesHud` is on AND an external
 * "presentation" display is connected, it shows a [HudPresentation] on it and keeps it updated with the
 * clock, the daily brief (weather / objectives / top story / markets) and J.A.R.V.I.S.'s latest reply.
 *
 * Tied to the host Activity's started lifecycle — [start] from `onStart`, [stop] from `onStop` — so the
 * Presentation always has a valid window token. (A HUD that persists while the app is backgrounded would
 * need a foreground service; that's a deliberate follow-up.) Everything is wrapped so a HUD failure can
 * never take down the app.
 */
class HudController(
    private val activityContext: Context,
    private val container: AppContainer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager: DisplayManager?
        get() = activityContext.getSystemService(DisplayManager::class.java)

    private var presentation: HudPresentation? = null
    private var enabled = false
    private val dataJobs = mutableListOf<Job>()

    @Volatile private var brief: String = "J.A.R.V.I.S. online, sir."
    @Volatile private var latestReply: String? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncDisplay()
        override fun onDisplayRemoved(displayId: Int) = syncDisplay()
        override fun onDisplayChanged(displayId: Int) {}
    }

    fun start() {
        scope.launch {
            container.settingsRepository.settings
                .map { it.jarvis.glassesHud }
                .distinctUntilChanged()
                .collect { on -> if (on) enable() else disable() }
        }
    }

    fun stop() {
        disable()
        runCatching { scope.cancel() }
    }

    private fun enable() {
        if (enabled) return
        enabled = true
        runCatching { displayManager?.registerDisplayListener(displayListener, handler) }
        // Latest J.A.R.V.I.S. reply (live).
        dataJobs += scope.launch {
            runCatching {
                container.jarvisMemory.history.collect { rows ->
                    latestReply = rows.lastOrNull { it.speaker != Speaker.USER }?.messageText
                    push()
                }
            }
        }
        // Periodic brief (weather / objectives / news / markets).
        dataJobs += scope.launch {
            while (true) {
                brief = runCatching { container.briefingBuilder.build() }.getOrNull() ?: brief
                push()
                delay(BRIEF_REFRESH_MS)
            }
        }
        // Clock tick.
        dataJobs += scope.launch {
            while (true) { push(); delay(1_000) }
        }
        syncDisplay()
    }

    private fun disable() {
        if (!enabled) return
        enabled = false
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        dataJobs.forEach { it.cancel() }
        dataJobs.clear()
        presentation?.let { p -> runCatching { p.dismiss() } }
        presentation = null
    }

    /** Show the HUD on the first connected presentation display, or dismiss it when none is present. */
    private fun syncDisplay() {
        if (!enabled) return
        val display = runCatching {
            displayManager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull()
        }.getOrNull()
        if (display == null) {
            presentation?.let { p -> runCatching { p.dismiss() } }
            presentation = null
            return
        }
        if (presentation?.display?.displayId != display.displayId) {
            presentation?.let { p -> runCatching { p.dismiss() } }
            presentation = runCatching {
                HudPresentation(activityContext, display).also { it.show() }
            }.getOrNull()
            push()
        }
    }

    private fun push() {
        val p = presentation ?: return
        val now = runCatching { CLOCK_FMT.format(Date()) }.getOrDefault("")
        runCatching { p.render(now, brief, latestReply) }
    }

    private companion object {
        const val BRIEF_REFRESH_MS = 60_000L
        val CLOCK_FMT = SimpleDateFormat("h:mm a · EEE d MMM", Locale.getDefault())
    }
}
