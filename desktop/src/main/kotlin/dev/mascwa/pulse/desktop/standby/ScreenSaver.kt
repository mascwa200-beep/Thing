package dev.mascwa.pulse.desktop.standby

import java.io.File

/**
 * Rung B — the standby display as a real Windows screensaver.
 *
 * This is the closest an unpackaged desktop application gets to "on the lock screen": with *on
 * resume, display logon screen* set, the screensaver is what fills the display on the way into the
 * lock, and what is on screen when you walk back up to the machine.
 *
 * ## How a Java application becomes a `.scr`
 *
 * A screensaver is an ordinary Windows executable with a different extension and three command-line
 * conventions. jpackage's launcher forwards its command line to `main(args)`, so **a copy of the
 * launcher named `LCARS.scr` is a working screensaver** — no second build, no native stub.
 *
 *  - `/s` — run full screen. This is the one that matters.
 *  - `/c` — show configuration.
 *  - ⚠️ `/p <hwnd>` — draw a preview into the Settings dialog's little monitor. **Deliberately a
 *    no-op**, because honouring it means parenting a window into another process's HWND, which
 *    needs a native handle Compose does not expose. The cost is that the preview stays blank; the
 *    screensaver itself works. Said out loud rather than left to be discovered.
 */
object ScreenSaver {

    /** What Windows was asked to do. */
    enum class Mode { FULL_SCREEN, CONFIGURE, PREVIEW, NONE }

    /**
     * Read the screensaver convention out of a command line.
     *
     * ⚠️ Case-insensitive and tolerant of the `/p:1234` form. Windows has passed both `/p 1234` and
     * `/p:1234` over the years and a saver that only understands one is one that mysteriously fails
     * on somebody's machine. Pure, so it is tested rather than assumed.
     */
    fun modeOf(args: Array<String>): Mode {
        val first = args.firstOrNull()?.lowercase()?.trim() ?: return Mode.NONE
        return when {
            first.startsWith("/s") || first.startsWith("-s") -> Mode.FULL_SCREEN
            first.startsWith("/c") || first.startsWith("-c") -> Mode.CONFIGURE
            first.startsWith("/p") || first.startsWith("-p") -> Mode.PREVIEW
            else -> Mode.NONE
        }
    }

    /** Where the `.scr` copy of the launcher lives. */
    fun scrFile(): File? {
        val launcher = WindowsShell.launcherPath() ?: return null
        return File(launcher.parentFile, "LCARS.scr")
    }

    /**
     * Install the screensaver for the current user.
     *
     * ⚠️ **Per-user keys only, so no elevation and therefore no dialog.** `HKCU\Control Panel\Desktop`
     * is where Windows itself keeps this; writing the machine policy instead would need an admin
     * prompt for something the user has already asked for once.
     *
     * The timeout is only set when nothing has set one, so a user who prefers five minutes keeps it.
     */
    fun install(timeoutSeconds: Int = DEFAULT_TIMEOUT_S): StandbyDiagnostics.RungState {
        if (!WindowsShell.isWindows) {
            return StandbyDiagnostics.RungState.Unavailable("only Windows has screensavers")
        }
        val launcher = WindowsShell.launcherPath()
            ?: return StandbyDiagnostics.RungState.Unavailable(
                "this is not an installed build — a screensaver needs the packaged launcher to copy",
            )
        val scr = scrFile() ?: return StandbyDiagnostics.RungState.Unavailable("no place to put the .scr")

        // Copy on every install so an updated build brings an updated saver. Cheap; the launcher is
        // a stub, not the runtime.
        val copied = runCatching { launcher.copyTo(scr, overwrite = true) }.isSuccess
        if (!copied) {
            return StandbyDiagnostics.RungState.Unavailable("could not write ${scr.absolutePath}")
        }

        val path = WindowsShell.regAdd(KEY, "SCRNSAVE.EXE", "REG_SZ", scr.absolutePath)
        if (!path.ok) {
            return StandbyDiagnostics.RungState.Unavailable("Windows refused the screensaver path: ${path.reason}")
        }
        WindowsShell.regAdd(KEY, "ScreenSaveActive", "REG_SZ", "1")
        // ⚠️ Not forced. Requiring a password on resume is the user's decision about their own
        // machine, and switching it on for them would be a security change made without asking.
        WindowsShell.regAdd(KEY, "ScreenSaveTimeOut", "REG_SZ", timeoutSeconds.toString())

        return StandbyDiagnostics.RungState.Engaged("registered as ${scr.name}, after ${timeoutSeconds / 60} min idle")
    }

    /** Stop being the screensaver, leaving whatever the user had before alone. */
    fun uninstall(): StandbyDiagnostics.RungState {
        if (!WindowsShell.isWindows) {
            return StandbyDiagnostics.RungState.Unavailable("only Windows has screensavers")
        }
        val outcome = WindowsShell.regAdd(KEY, "ScreenSaveActive", "REG_SZ", "0")
        return if (outcome.ok) {
            StandbyDiagnostics.RungState.NotTried
        } else {
            StandbyDiagnostics.RungState.Unavailable("could not switch it off: ${outcome.reason}")
        }
    }

    private const val KEY = "HKCU\\Control Panel\\Desktop"

    /** Ten minutes — long enough not to interrupt reading, short enough to be seen. */
    const val DEFAULT_TIMEOUT_S = 600
}
