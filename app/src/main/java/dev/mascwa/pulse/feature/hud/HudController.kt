package dev.mascwa.pulse.feature.hud

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import dev.mascwa.pulse.core.telemetry.NavGuidance
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.jarvis.db.Speaker
import dev.mascwa.pulse.data.objectives.Waypoint
import dev.mascwa.pulse.data.settings.WindUnit
import dev.mascwa.pulse.data.weather.DeviceLocation
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

    /** Heading source for the waypoint nav card; started/stopped with the HUD. */
    private val compass = container.newCompassController()

    @Volatile private var brief: String = "Computer online."
    @Volatile private var latestReply: String? = null
    // Active-waypoint guidance state (all touched on the Main dispatcher).
    @Volatile private var navLine: String? = null
    @Volatile private var activeWaypoint: Waypoint? = null
    @Volatile private var lastLocation: DeviceLocation? = null
    @Volatile private var headingTrue: Float? = null
    @Volatile private var metric: Boolean = true

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
        // Clock tick — also recomputes the heading-driven nav arrow so it tracks as you turn.
        dataJobs += scope.launch {
            while (true) { recomputeNav(); push(); delay(1_000) }
        }
        // ---- Active-waypoint guidance (turn arrow + distance + bearing) ----
        runCatching { compass.start() }
        // Distance units (metric vs imperial), mirrored from the wind-speed unit.
        dataJobs += scope.launch {
            container.settingsRepository.settings
                .map { it.windUnit != WindUnit.MPH }
                .distinctUntilChanged()
                .collect { metric = it; recomputeNav(); push() }
        }
        // The tracked waypoint (null when none is active → the nav line is hidden).
        dataJobs += scope.launch {
            runCatching {
                container.waypointStore.active.collect { wp -> activeWaypoint = wp; recomputeNav(); push() }
            }
        }
        // Live true-north heading; null until the first sample / when the device has no sensor. The 1s
        // clock tick (above) is what re-renders, so we don't push on every high-rate sensor sample.
        dataJobs += scope.launch {
            runCatching {
                compass.reading.collect { r -> headingTrue = if (r.hasSensor) r.trueAzimuth else null }
            }
        }
        // Polled GPS fix — only while a waypoint is tracked (no waypoint → no GPS drain). Feeds the
        // compass its declination so heading and bearing are both true-north.
        dataJobs += scope.launch {
            while (true) {
                if (activeWaypoint != null && container.locationProvider.hasPermission()) {
                    runCatching { container.locationProvider.current() }.getOrNull()?.let { loc ->
                        lastLocation = loc
                        runCatching { compass.setLocation(loc.latitude, loc.longitude, 0.0) }
                        recomputeNav(); push()
                    }
                }
                delay(NAV_REFRESH_MS)
            }
        }
        syncDisplay()
    }

    /** Recompute the active-waypoint nav line from the latest fix/heading. Null (line hidden) when there's
     *  no tracked waypoint or no location fix yet. Pure formatting — the great-circle maths is [Geo], the
     *  relative-turn maths is [NavGuidance]. */
    private fun recomputeNav() {
        val wp = activeWaypoint
        val loc = lastLocation
        navLine = if (wp == null || loc == null) null else runCatching {
            val dist = Geo.distanceMeters(loc.latitude, loc.longitude, wp.latitude, wp.longitude)
            val bearing = Geo.bearingDegrees(loc.latitude, loc.longitude, wp.latitude, wp.longitude)
            val heading = headingTrue?.toDouble()
            val arrow = NavGuidance.relativeArrow(bearing, heading)
            // With a heading fix a "40° right" turn hint is clearer than the absolute cardinal.
            val tail = NavGuidance.turnHint(bearing, heading) ?: Geo.cardinal(bearing)
            "$arrow  ${Geo.formatDistance(dist, metric)} · $tail → ${wp.label}"
        }.getOrNull()
    }

    private fun disable() {
        if (!enabled) return
        enabled = false
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        runCatching { compass.stop() }
        dataJobs.forEach { it.cancel() }
        dataJobs.clear()
        navLine = null
        activeWaypoint = null
        lastLocation = null
        headingTrue = null
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
        runCatching { p.render(now, navLine, brief, latestReply) }
    }

    private companion object {
        const val BRIEF_REFRESH_MS = 60_000L
        const val NAV_REFRESH_MS = 4_000L
        val CLOCK_FMT = SimpleDateFormat("h:mm a · EEE d MMM", Locale.getDefault())
    }
}
