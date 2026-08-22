package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.desktop.standby.StandbyDiagnostics
import dev.mascwa.pulse.desktop.standby.WindowsShell
import java.io.File

/**
 * The long watch keeps watching with the app closed.
 *
 * A recorder that only runs while somebody is looking has holes exactly where the interesting things
 * happen — overnight, over a weekend, during the week the machine sat untouched. And a baseline
 * built from "the hours you happened to have the window open" is not a baseline of anything.
 *
 * A per-user scheduled task closes that. Every quarter of an hour, no window, no administrator.
 * ⚠️ **No `/ru` and no `/rl HIGHEST`**, exactly as
 * [dev.mascwa.pulse.desktop.update.ScheduledUpdate] documents: without them the task lands in the
 * user's own folder and runs with ordinary rights, so no UAC prompt ever appears. Asking for
 * elevation would trade the feature for a dialog.
 *
 * ⚠️ Unlike the update task, this one has **no trap around replacing its own launcher** — it reads
 * some numbers and writes a text file, then exits. There is no detached script here because nothing
 * is being overwritten.
 */
object ScheduledCollect {

    /** The task's name in the user's own scheduled-task folder. */
    const val TASK_NAME = "LCARS Long Watch"

    /** The flag that says "collect and nothing else" — no window, no shell, straight out again. */
    const val COLLECT_ONLY_FLAG = "--collect"

    /**
     * How often the task fires.
     *
     * ⚠️ The *task* cadence is the floor, not the collection cadence. Each domain decides for itself
     * whether it is due (see [MetricRegistry.Domain]), so waking every quarter hour costs a handful
     * of file-tail reads on the passes where nothing is owed.
     */
    const val EVERY_MINUTES = 15

    fun install(): StandbyDiagnostics.RungState {
        if (!WindowsShell.isWindows) {
            return StandbyDiagnostics.RungState.Unavailable("scheduled tasks are a Windows feature")
        }
        val launcher = WindowsShell.launcherPath()
            ?: return StandbyDiagnostics.RungState.Unavailable(
                "this is not an installed build — there is no launcher for a task to run",
            )
        // Quoted inside one argv element: schtasks re-parses this string, so a directory with a
        // space in it silently becomes two arguments without them.
        val command = "\"${launcher.absolutePath}\" $COLLECT_ONLY_FLAG"
        val outcome = WindowsShell.run(
            "schtasks.exe", "/create", "/tn", TASK_NAME, "/tr", command,
            "/sc", "minute", "/mo", EVERY_MINUTES.toString(), "/f",
        )
        return if (outcome.ok) {
            StandbyDiagnostics.RungState.Engaged("every $EVERY_MINUTES minutes, as you, with no window")
        } else {
            StandbyDiagnostics.RungState.Unavailable("Windows refused the task: ${outcome.reason}")
        }
    }

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

    fun isInstalled(): Boolean {
        if (!WindowsShell.isWindows) return false
        return WindowsShell.run("schtasks.exe", "/query", "/tn", TASK_NAME, timeoutSeconds = 15).ok
    }

    /**
     * What the last pass did, for the scanner to report.
     *
     * ⚠️ One line, overwritten, in its own small file — the same reasoning `ScheduledUpdate.lastPass`
     * gives. Every outcome here is ordinary ("nothing was due" is the commonest by a distance), and a
     * task writing a log line four times an hour forever is a slow disk leak.
     */
    fun lastPass(): String? = runCatching {
        logFile().takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
    }.getOrNull()

    /** Record one pass's outcome, and hand the sentence back for the caller to print. */
    fun record(line: String): String {
        runCatching {
            val stamp = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ')
            logFile().writeText("$stamp — $line")
        }
        return line
    }

    private fun logFile(): File =
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "LCARS")
            .apply { runCatching { mkdirs() } }
            .let { File(it, "collect-pass.log") }
}
