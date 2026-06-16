package dev.mascwa.pulse.crash

import android.content.Context
import android.os.Build
import dev.mascwa.pulse.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One recorded crash: the backing file, when it happened, and a one-line fault summary. */
data class CrashEntry(val file: File, val timeMs: Long, val summary: String)

/**
 * Captures uncaught exceptions to plain-text files in private storage so they can be reviewed
 * and shared from the in-app crash console. Fully on-device — nothing is transmitted. One file
 * per crash avoids any read-modify-write at crash time; recording is allocation-light and never
 * throws (the JVM is unstable while a crash is being handled).
 *
 * Note: native crashes (e.g. a MediaPipe C++ SIGSEGV) terminate the process without a JVM
 * exception, so they cannot be captured here — only JVM-level throwables (including
 * [OutOfMemoryError]) are recorded.
 */
class CrashReporter(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "diagnostics").apply { runCatching { mkdirs() } }

    /**
     * Install as the process-wide uncaught-exception handler. Records the crash, then delegates
     * to the previous handler so the OS still terminates and relaunches the app normally — we do
     * not swallow the crash or auto-restart (which could crash-loop).
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Persist a crash report. Safe to call from the crashing thread; never throws. */
    fun record(thread: Thread, throwable: Throwable) {
        runCatching {
            val now = System.currentTimeMillis()
            val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            val header = buildString {
                append("Pulse ").append(BuildConfig.VERSION_NAME)
                    .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                append("time: ").append(SimpleDateFormat(TIME_FMT, Locale.US).format(Date(now))).append('\n')
                append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" · Android ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("thread: ").append(thread.name).append('\n')
                append("fault: ").append(summaryOf(throwable)).append("\n\n")
            }
            File(dir, "$PREFIX$now.txt").writeText(header + trace)
            trim()
        }
    }

    /** Most-recent-first list of recorded crashes. */
    fun entries(): List<CrashEntry> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) } ?: emptyArray())
            .map { f ->
                val ms = f.name.removePrefix(PREFIX).removeSuffix(".txt").toLongOrNull() ?: f.lastModified()
                CrashEntry(f, ms, firstFault(f))
            }
            .sortedByDescending { it.timeMs }

    fun read(entry: CrashEntry): String = runCatching { entry.file.readText() }.getOrDefault("")

    fun clear() {
        dir.listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { runCatching { it.delete() } }
    }

    /** Keep only the most recent [MAX] reports. */
    private fun trim() {
        val files = dir.listFiles { f -> f.name.startsWith(PREFIX) } ?: return
        if (files.size <= MAX) return
        files.sortedByDescending { it.lastModified() }.drop(MAX).forEach { runCatching { it.delete() } }
    }

    private fun firstFault(f: File): String = runCatching {
        f.useLines { lines -> lines.firstOrNull { it.startsWith("fault: ") }?.removePrefix("fault: ") }
    }.getOrNull() ?: "Unknown fault"

    private fun summaryOf(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"

    private companion object {
        const val PREFIX = "crash_"
        const val MAX = 20
        const val TIME_FMT = "yyyy-MM-dd HH:mm:ss"
    }
}
