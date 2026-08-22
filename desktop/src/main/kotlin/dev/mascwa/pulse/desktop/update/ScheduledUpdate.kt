package dev.mascwa.pulse.desktop.update

import dev.mascwa.pulse.desktop.standby.StandbyDiagnostics
import dev.mascwa.pulse.desktop.standby.WindowsShell
import java.io.File

/**
 * The console upgrades itself whether or not it is open, and with nothing to click.
 *
 * ## Why a scheduled task rather than the running app
 *
 * [DesktopAutoUpdater] can only install when the window closes, and its own notes say why: Windows
 * Installer cannot replace files a running program holds open. That leaves the honest description
 * *"it updates itself the next time you close it"* — which is not what was asked for, and a machine
 * left open for a fortnight simply never updates.
 *
 * A per-user scheduled task closes that gap. It runs hourly, needs no administrator, and does the
 * whole thing with no window: check, green-gate, download, ask a running console to stand down,
 * upgrade, and put the console back if it had been open.
 *
 * ## ⚠️ The trap that shapes the whole design
 *
 * The task runs **this application's own launcher**, and the MSI upgrade replaces that exact file.
 * A running `.exe` is locked on Windows, so this process can no more install over itself than the
 * console can — running `msiexec` here and waiting for it would deadlock against our own image.
 *
 * So the install is handed to a small detached script that **waits for this process to exit** before
 * touching anything. That is why there is a batch file at all: it is the only participant that is
 * not itself being replaced.
 */
object ScheduledUpdate {

    /** The task's name in the user's own scheduled-task folder. */
    const val TASK_NAME = "LCARS Console Update"

    /** The flag the scheduled task passes to say "do the update and nothing else". */
    const val UPDATE_ONLY_FLAG = "--update-only"

    /**
     * Register the hourly task for the current user.
     *
     * ⚠️ **No `/ru` and no `/rl HIGHEST`.** Without them the task is created in the current user's
     * own folder and runs as that user with ordinary rights, so no administrator prompt appears —
     * which is the entire requirement. Asking for elevation here would trade the feature for a UAC
     * dialog on first run.
     */
    fun install(): StandbyDiagnostics.RungState {
        if (!WindowsShell.isWindows) {
            return StandbyDiagnostics.RungState.Unavailable("scheduled tasks are a Windows feature")
        }
        val launcher = WindowsShell.launcherPath()
            ?: return StandbyDiagnostics.RungState.Unavailable(
                "this is not an installed build — there is no launcher for a task to run",
            )
        // One argv element, quoted inside: schtasks re-parses this string itself, so the path needs
        // quotes of its own or a directory with a space in it silently becomes two arguments.
        val command = "\"${launcher.absolutePath}\" $UPDATE_ONLY_FLAG"
        val outcome = WindowsShell.run(
            "schtasks.exe", "/create", "/tn", TASK_NAME, "/tr", command,
            "/sc", "hourly", "/f",
        )
        return if (outcome.ok) {
            StandbyDiagnostics.RungState.Engaged("hourly, as you, with no window")
        } else {
            StandbyDiagnostics.RungState.Unavailable("Windows refused the task: ${outcome.reason}")
        }
    }

    /** Remove the task. Leaves the in-app close-time upgrade alone; that path is independent. */
    fun uninstall(): StandbyDiagnostics.RungState {
        if (!WindowsShell.isWindows) {
            return StandbyDiagnostics.RungState.Unavailable("scheduled tasks are a Windows feature")
        }
        val outcome = WindowsShell.run("schtasks.exe", "/delete", "/tn", TASK_NAME, "/f")
        return if (outcome.ok) {
            StandbyDiagnostics.RungState.NotTried
        } else {
            StandbyDiagnostics.RungState.Unavailable("could not remove the task: ${outcome.reason}")
        }
    }

    /** Whether the task is registered. Read by the ABOUT screen so it reports rather than assumes. */
    fun isInstalled(): Boolean {
        if (!WindowsShell.isWindows) return false
        return WindowsShell.run("schtasks.exe", "/query", "/tn", TASK_NAME, timeoutSeconds = 15).ok
    }

    /**
     * What the scheduled task actually does. Returns a line describing the outcome, for the log.
     *
     * Every step can decline, and declining is normal: nothing newer, the build is not green, the
     * console would not close. None of those is an error worth a window, which is why this returns a
     * sentence rather than throwing.
     */
    suspend fun runPass(updater: DesktopUpdater): String = record(pass(updater))

    private suspend fun pass(updater: DesktopUpdater): String {
        val check = runCatching { updater.check() }.getOrElse {
            return "could not reach the release: ${it.message.orEmpty()}"
        }
        val update = check.update ?: return "already on the newest green build"

        val installer = runCatching { updater.download(update) { } }.getOrElse {
            return "could not download build ${update.build}: ${it.message.orEmpty()}"
        }

        // ⚠️ Asked BEFORE the console is told to quit, because it is also the answer to "should the
        // console be put back afterwards". A console that was closed all along must not be opened on
        // somebody's screen by a background task.
        val wasRunning = SingleInstance.isRunning()
        if (wasRunning) {
            SingleInstance.requestQuit()
            if (!SingleInstance.awaitExit()) {
                // Deliberately gives up rather than installing over a live process — see
                // SingleInstance.awaitExit. The next pass is an hour away and costs nothing.
                SingleInstance.clearQuitRequest()
                return "the console did not close; leaving build ${update.build} for the next pass"
            }
        }

        return if (handOff(installer, relaunch = wasRunning)) {
            "handed build ${update.build} to Windows Installer"
        } else {
            "could not start the installer for build ${update.build}"
        }
    }

    /**
     * What the last pass did, for the ABOUT screen to report.
     *
     * ⚠️ Its own small file rather than a crash report. Every one of these outcomes is ordinary —
     * *already on the newest build* is the commonest by far — and filing routine successes among the
     * faults would bury the one entry somebody is actually looking for. One line, overwritten, so
     * nothing accumulates: an hourly task writing a log forever is a slow disk leak.
     */
    fun lastPass(): String? = runCatching {
        logFile().takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
    }.getOrNull()

    private fun record(line: String): String {
        runCatching {
            val stamp = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ')
            logFile().writeText("$stamp — $line")
        }
        return line
    }

    private fun logFile(): File =
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "LCARS")
            .apply { runCatching { mkdirs() } }
            .let { File(it, "update-pass.log") }

    /**
     * Write and launch the detached upgrade script.
     *
     * ⚠️ The wait loop is the point of the whole file. `tasklist` and `timeout` are Windows built-ins,
     * so nothing has to be installed for this to work, and polling our own process id is the only way
     * a child can know that the parent whose image is about to be overwritten has genuinely gone.
     *
     * The script deletes itself last, so a temp directory does not fill with one of these an hour.
     */
    private fun handOff(installer: File, relaunch: Boolean): Boolean {
        val launcher = WindowsShell.launcherPath() ?: return false
        val pid = runCatching { ProcessHandle.current().pid() }.getOrNull() ?: return false

        val relaunchLine = if (relaunch) "start \"\" \"${launcher.absolutePath}\"" else "rem console was not running"
        val script = """
            @echo off
            :wait
            tasklist /fi "PID eq $pid" 2>nul | find "$pid" >nul
            if not errorlevel 1 (
                timeout /t 1 /nobreak >nul
                goto wait
            )
            msiexec /i "${installer.absolutePath}" /qn /norestart
            $relaunchLine
            del "%~f0"
        """.trimIndent()

        val file = runCatching {
            File.createTempFile("lcars-upgrade-", ".cmd").apply { writeText(script) }
        }.getOrNull() ?: return false

        return runCatching {
            // `start` with an empty title, exactly as DesktopUpdater does — without it the shell
            // reads the quoted path as the window title and runs nothing.
            ProcessBuilder("cmd", "/c", "start", "", "cmd", "/c", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            true
        }.getOrDefault(false)
    }
}
