package dev.mascwa.pulse.desktop.standby

import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment

/**
 * Keeps the standby display current, and pushes it out to whichever of the three rungs is switched on.
 *
 * ⚠️ **One picture, three uses.** The session renders the display to a single PNG at screen
 * resolution. The lock-screen rung installs *that file* as the Windows lock-screen image; the
 * screensaver displays *that file* full screen; the HUD draws the same state live. Sharing the
 * artefact is what makes the screensaver instant — a saver has to appear the moment the machine goes
 * idle, and a process that spun up an HTTP client and five repositories first would not — and it is
 * why there is nothing to keep in step between the three.
 *
 * The picture is stamped with its own age by the display's freshness line, so a screensaver showing
 * a five-minute-old console says so rather than implying it is live.
 */
class StandbySession(
    private val scope: CoroutineScope,
    private val engine: StandbyEngine,
    private val settings: DesktopSettingsStore,
) {
    private val _state = MutableStateFlow(StandbyState())
    val state: StateFlow<StandbyState> = _state.asStateFlow()

    private var job: Job? = null
    private var registrar: Job? = null

    /**
     * What the last attempt to register the screensaver actually did.
     *
     * ⚠️ Held rather than re-derived, because the display must report what happened and not what was
     * asked for. Reporting "engaged" from the switch alone is exactly the failure the diagnostics
     * exist to prevent — a rung that quietly never engaged looks identical to a finished feature.
     */
    @Volatile
    private var saverState: StandbyDiagnostics.RungState = StandbyDiagnostics.RungState.NotTried

    /** Begin refreshing. Safe to call more than once; the second call is ignored. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { refreshNow() }
                delay(REFRESH_MS)
            }
        }
        // ⚠️ The switch writes a preference; THIS decides what Windows is told, and it re-decides on
        // every change. Two consequences worth having: flipping the switch takes effect at once
        // rather than at the next refresh, and the registration is renewed on every launch — which
        // matters because the registry value holds the launcher's path, and an upgrade that moved
        // the install would otherwise leave Windows pointing at a file that is no longer there.
        registrar = scope.launch {
            var applied: Boolean? = null
            settings.settingsFlow.collect { prefs ->
                if (prefs.standbyScreenSaver == applied) return@collect
                applied = prefs.standbyScreenSaver
                saverState = withContext(Dispatchers.IO) {
                    if (prefs.standbyScreenSaver) ScreenSaver.install() else ScreenSaver.uninstall()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        registrar?.cancel()
        job = null
        registrar = null
    }

    /**
     * Gather, draw, and offer the picture to the rungs that are switched on.
     *
     * Every rung's outcome is recorded rather than acted on here — the display shows them, and the
     * whole reason they are recorded is that a rung which quietly never engaged is indistinguishable
     * from a feature that was never finished.
     */
    suspend fun refreshNow() {
        val gathered = engine.gather()
        val prefs = runCatching { settings.current() }.getOrNull()

        val rungs = mutableMapOf<StandbyDiagnostics.Rung, StandbyDiagnostics.RungState>()

        // The HUD is the app's own window, so it is engaged exactly when it is switched on — there
        // is nothing that can refuse it.
        rungs[StandbyDiagnostics.Rung.HUD] = if (prefs?.standbyHud == true) {
            StandbyDiagnostics.RungState.Engaged("drawn live in its own window")
        } else {
            StandbyDiagnostics.RungState.NotTried
        }

        val (w, h) = screenSize()
        if (prefs?.standbyLockScreen == true || prefs?.standbyScreenSaver == true) {
            // ⚠️ Rendering is CPU work on a raster surface and takes long enough to be felt on a UI
            // thread at 4K, so it goes to the IO pool along with the file write.
            val drawn = withContext(Dispatchers.IO) {
                StandbyRender.renderToFile(gathered, w, h, LockScreenImage.imageFile())
            }
            if (drawn == null) {
                val why = StandbyDiagnostics.RungState.Unavailable("the display could not be drawn to a picture")
                if (prefs.standbyLockScreen) rungs[StandbyDiagnostics.Rung.LOCK_SCREEN] = why
                if (prefs.standbyScreenSaver) rungs[StandbyDiagnostics.Rung.SCREEN_SAVER] = why
            } else {
                if (prefs.standbyLockScreen) {
                    rungs[StandbyDiagnostics.Rung.LOCK_SCREEN] =
                        withContext(Dispatchers.IO) { LockScreenImage.apply(drawn) }
                }
                if (prefs.standbyScreenSaver) {
                    // ⚠️ What Windows actually said when we registered, not "the picture is fresh".
                    // The saver can be perfectly up to date and not registered at all — on a
                    // development run there is no launcher to copy — and those must not read alike.
                    rungs[StandbyDiagnostics.Rung.SCREEN_SAVER] = saverState
                }
            }
        }
        if (prefs?.standbyLockScreen != true) {
            rungs[StandbyDiagnostics.Rung.LOCK_SCREEN] = StandbyDiagnostics.RungState.NotTried
        }
        if (prefs?.standbyScreenSaver != true) {
            rungs[StandbyDiagnostics.Rung.SCREEN_SAVER] = StandbyDiagnostics.RungState.NotTried
        }

        val report = gathered.report?.copy(rungs = rungs)
        report?.let(StandbyDiagnostics::record)
        _state.value = gathered.copy(report = report)
    }

    private companion object {
        /**
         * How often the display is redrawn.
         *
         * ⚠️ Five minutes, and every feed underneath is unforced — so this is a redraw of warm
         * caches rather than a fetch. A standby display is read at a glance from across a room; a
         * clock that is four minutes out is fine and a machine that woke six repositories every
         * thirty seconds to say so would not be.
         */
        const val REFRESH_MS = 5 * 60 * 1000L

        /** Fallback when the display cannot be interrogated — a common enough desktop resolution. */
        const val FALLBACK_W = 1920
        const val FALLBACK_H = 1080

        fun screenSize(): Pair<Int, Int> = runCatching {
            val mode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
            if (mode.width > 0 && mode.height > 0) mode.width to mode.height else FALLBACK_W to FALLBACK_H
        }.getOrDefault(FALLBACK_W to FALLBACK_H)
    }
}
