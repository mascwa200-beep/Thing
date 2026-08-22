package dev.mascwa.pulse.desktop.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps this machine on the newest green build without anyone asking it to.
 *
 * ## The two halves, and why they are separate
 *
 * **Checking and downloading happen on a slow timer.** [DesktopUpdater] is otherwise driven only by
 * the ABOUT screen, so a machine left on the news page all week never learns an update exists — the
 * update system was, in effect, opt-in by navigation.
 *
 * **Installing happens when the window closes**, and that is not politeness. Windows Installer
 * cannot replace files a running program holds open: under `/qn` it would either fail or quietly
 * schedule a reboot, so "upgrade while you work" is not a thing that can be made to work. Closing
 * the app is the moment the files are free, and it is also the moment an interruption costs nothing.
 * The upgrade then runs invisibly during shutdown and the next launch is simply the newer build.
 *
 * ⚠️ **So the honest description of "automatic" here is: it updates itself the next time you close
 * it.** A machine that is never closed is never upgraded, and no arrangement of this code changes
 * that — it is a property of Windows Installer, not a shortcut taken here.
 *
 * ## What it will not do
 *
 * It never asks anything and it never shows anything. The one thing it refuses is to stack work: a
 * downloaded installer is installed once, and nothing is re-downloaded while one is already staged.
 * Every failure is silent by design — this is a background convenience, and an error dialog from a
 * subsystem nobody invoked is worse than being one build behind. ABOUT remains the surface that
 * reports the truth, and its manual buttons are untouched.
 */
class DesktopAutoUpdater(
    private val scope: CoroutineScope,
    private val updater: DesktopUpdater,
    private val autoCheckEnabled: suspend () -> Boolean,
) {

    /** The installer waiting for the window to close. Read from the AWT thread on shutdown. */
    @Volatile
    private var staged: File? = null

    private var loop: Job? = null

    /** Begin the slow poll. Safe to call twice; the second call is ignored. */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch(start = CoroutineStart.DEFAULT) {
            // A first look shortly after launch rather than immediately: the shell is still building
            // its screens, and the newest build is very rarely seconds old.
            delay(FIRST_DELAY_MS)
            while (true) {
                runCatching { pass() }
                delay(INTERVAL_MS)
            }
        }
    }

    private suspend fun pass() {
        if (staged != null) return                       // one at a time; it installs on close
        if (!autoCheckEnabled()) return
        val result = updater.check()
        val update = result.update ?: return             // nothing newer, or not green — check() decides
        staged = runCatching { updater.download(update) { } }.getOrNull()
    }

    /**
     * Called from the window's close handler. Returns true when an upgrade was handed to Windows.
     *
     * ⚠️ Deliberately does NOT wait for the installer. `exitApplication()` calls `System.exit(0)`
     * immediately after the close handler returns, and the whole point is that msiexec was launched
     * detached so it outlives this process — see [DesktopUpdater.launchInstaller].
     */
    fun installOnExit(): Boolean {
        val file = staged ?: return false
        staged = null
        return runCatching { updater.launchInstaller(file) }.getOrDefault(false)
    }

    private companion object {
        const val FIRST_DELAY_MS = 30_000L
        const val INTERVAL_MS = 60 * 60 * 1000L
    }
}
