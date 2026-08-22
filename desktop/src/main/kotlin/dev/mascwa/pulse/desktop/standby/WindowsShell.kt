package dev.mascwa.pulse.desktop.standby

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The few Windows things the standby display and the updater have to reach for.
 *
 * ⚠️ Shelling out rather than binding natively is a deliberate choice and already the pattern in
 * this module — the updater runs `msiexec` the same way. A JNI or JNA binding would buy type safety
 * for calls made a handful of times an hour, at the cost of a native dependency in a jpackage image
 * whose module list is already load-bearing.
 *
 * ⚠️ **Everything here returns a reason rather than throwing.** Every caller is a background pass on
 * a machine nobody here can test, and the whole point of the standby display's diagnostics is that a
 * refusal is reported in words instead of appearing as a feature that silently never happened.
 */
object WindowsShell {

    val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** What a command did. [ok] means exit code 0; [output] is stdout and stderr together. */
    data class Outcome(val ok: Boolean, val exitCode: Int, val output: String) {
        /** A short reason for a diagnostics line — the last thing the command said, capped. */
        val reason: String
            get() = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotBlank() }
                ?.take(StandbyDiagnostics.REASON_CHARS)
                ?: "exit code $exitCode"
    }

    /**
     * Run a command and wait, bounded.
     *
     * ⚠️ Bounded on purpose. `msiexec`, `schtasks` and PowerShell can all block indefinitely behind
     * a dialog or a lock, and a background pass that never returns is worse than one that fails: it
     * holds a thread and reports nothing at all.
     */
    fun run(vararg command: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_S): Outcome = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Outcome(false, -1, "timed out after ${timeoutSeconds}s")
        }
        Outcome(process.exitValue() == 0, process.exitValue(), text)
    }.getOrElse { Outcome(false, -1, "${it::class.simpleName}: ${it.message.orEmpty()}") }

    /**
     * Run a PowerShell script.
     *
     * ⚠️ Written to a temp file rather than passed with `-Command`. The WinRT scripts below contain
     * quotes, braces and backticks, and quoting them through two layers of process argument
     * splitting is a well-known way to produce a script that runs but does something else. The file
     * is deleted afterwards even when the script fails.
     */
    fun powershell(script: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_S): Outcome {
        if (!isWindows) return Outcome(false, -1, "not Windows")
        val file = runCatching {
            File.createTempFile("lcars-", ".ps1").apply { writeText(script) }
        }.getOrNull() ?: return Outcome(false, -1, "could not write the script")
        return try {
            run(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", file.absolutePath,
                timeoutSeconds = timeoutSeconds,
            )
        } finally {
            file.delete()
        }
    }

    /** Write one registry value. Per-user keys need no elevation; machine keys will simply refuse. */
    fun regAdd(key: String, name: String, type: String, value: String): Outcome {
        if (!isWindows) return Outcome(false, -1, "not Windows")
        return run("reg.exe", "add", key, "/v", name, "/t", type, "/d", value, "/f")
    }

    /**
     * Whether this process is running elevated.
     *
     * ⚠️ Asked so that an admin-only path can be **skipped**, never so that elevation can be
     * requested. A wallpaper is not worth a UAC dialog, and prompting for one would defeat the
     * "no user input" requirement the whole feature exists to satisfy.
     */
    fun isElevated(): Boolean {
        if (!isWindows) return false
        // `net session` succeeds only for an administrator and touches nothing.
        return run("net.exe", "session", timeoutSeconds = 8).ok
    }

    /** Where this application's own launcher lives, if it can be worked out. */
    fun launcherPath(): File? {
        // jpackage sets this; a development run from Gradle has no launcher at all, and saying so
        // is better than pointing a scheduled task at a java command line that will not survive.
        val home = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }
        return home?.let(::File)?.takeIf { it.isFile }
    }

    const val DEFAULT_TIMEOUT_S = 30L
}
