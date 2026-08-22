package dev.mascwa.pulse.desktop.diagnostics

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One recorded fault: the file it lives in, when it happened, and a one-line summary. */
data class CrashEntry(val file: File, val timeMs: Long, val summary: String)

/**
 * Records uncaught exceptions to plain-text files so they can be read back from the app.
 *
 * ⚠️ **This matters more here than on the phone, for a reason particular to a desktop program.** On
 * Android an uncaught exception kills the process and the OS says so. On this machine an exception
 * thrown while drawing goes to AWT's event loop, which logs it and **carries on pumping events** —
 * so a screen can fail, keep its window open, and leave nothing on screen or in any log the user
 * would think to look at. A window that quietly stopped working is the failure this exists to make
 * legible.
 *
 * ⚠️ **One handler covers everything, and that is worth stating because it did not always.** In
 * JDK 21 `EventDispatchThread.processException` calls `getUncaughtExceptionHandler()` on the thread,
 * which falls through to the process-wide default — verified by disassembling the shipped class,
 * where the legacy `sun.awt.exception.handler` system property no longer appears at all. So the
 * default handler reaches drawing failures, coroutine failures and everything else alike, and there
 * is no second mechanism to install.
 *
 * One file per fault, so recording never reads-modifies-writes at the moment the program is least
 * healthy, and every step is wrapped: a crash reporter that throws while reporting a crash is worse
 * than no crash reporter. Nothing is transmitted anywhere.
 */
class CrashReporter(dataDir: File) {

    private val dir = File(dataDir, "diagnostics").apply { runCatching { mkdirs() } }

    /**
     * Install as the process-wide handler.
     *
     * The previous handler still runs afterwards, so nothing about how the program ends changes —
     * this only writes down what happened on the way.
     */
    fun install(buildLabel: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(thread, throwable, buildLabel) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Write one report. Safe from the failing thread; never throws.
     *
     * [nowMs] is injectable only so a test can place reports in time; nothing in the program passes it.
     */
    fun record(
        thread: Thread,
        throwable: Throwable,
        buildLabel: String,
        nowMs: Long = System.currentTimeMillis(),
        /**
         * Which window, or which subsystem, this came from.
         *
         * ⚠️ Every window in this app is declared inside ONE composition — the main pane, the standby
         * HUD, each torn-off screen, the ops wall — so they all route through the same
         * [WindowFaultHandler] and, before this, produced byte-identical-looking reports. A fault in
         * the always-on-top HUD read exactly like a fault in the page you were looking at. Defaulted
         * so existing callers are unchanged.
         */
        where: String? = null,
    ) {
        runCatching {
            val now = nowMs
            val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            val header = buildString {
                append("LCARS desktop ").append(buildLabel).append('\n')
                append("time: ").append(SimpleDateFormat(TIME_FMT, Locale.US).format(Date(now))).append('\n')
                append("machine: ").append(System.getProperty("os.name"))
                    .append(' ').append(System.getProperty("os.version"))
                    .append(" · ").append(System.getProperty("os.arch"))
                    .append(" · Java ").append(System.getProperty("java.version")).append('\n')
                append("thread: ").append(thread.name).append('\n')
                where?.let { append("where: ").append(it).append('\n') }
                append("fault: ").append(summaryOf(throwable)).append("\n\n")
            }
            // ⚠️ A millisecond is not unique enough. One failure commonly brings down several threads
            // at once, and two reports named for the same instant would mean the second silently
            // overwriting the first — losing exactly the corroborating detail a cascade is read for.
            var target = File(dir, "$PREFIX$now.txt")
            var bump = 1
            while (target.exists() && bump < 50) target = File(dir, "$PREFIX$now-$bump.txt").also { bump++ }
            target.writeText(header + trace)
            trim()
        }
    }

    /** Most recent first. */
    fun entries(): List<CrashEntry> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) } ?: emptyArray())
            .map { f -> CrashEntry(f, stampOf(f), firstFault(f)) }
            .sortedByDescending { it.timeMs }

    fun read(entry: CrashEntry): String = runCatching { entry.file.readText() }.getOrDefault("")

    fun clear() {
        dir.listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { runCatching { it.delete() } }
    }

    /**
     * Keep the most recent [MAX]. Old faults are rarely the ones being chased.
     *
     * ⚠️ Ordered by the timestamp parsed out of the name, not by the name itself. Sorting the
     * strings gives the same answer today only because every millisecond value happens to be
     * thirteen digits long; the moment that stopped being true the wrong reports would be deleted,
     * and nothing would say so.
     */
    private fun trim() {
        runCatching {
            val files = dir.listFiles { f -> f.name.startsWith(PREFIX) } ?: return
            files.sortedByDescending { stampOf(it) }.drop(MAX).forEach { runCatching { it.delete() } }
        }
    }

    /** `fault-1737000000000.txt` and `fault-1737000000000-1.txt` both stamp to the same instant. */
    private fun stampOf(f: File): Long =
        f.name.removePrefix(PREFIX).removeSuffix(".txt").substringBefore('-').toLongOrNull()
            ?: f.lastModified()

    /**
     * The line the list shows.
     *
     * The deepest cause rather than the outermost wrapper, because a wrapper is almost always
     * something generic and the cause is the thing that actually went wrong.
     *
     * ⚠️ Frames come from [FaultTrace], which is shared with the fault dialog. This used to take
     * `stackTrace.first()` and so did the dialog — two independent copies of one rule, both wrong the
     * same way, because for a Compose `require` that frame is always the internal throw helper. One
     * frame here rather than the dialog's several: this is a single row in a list.
     */
    private fun summaryOf(t: Throwable): String {
        val cause = FaultTrace.rootCause(t)
        val where = FaultTrace.locate(cause, max = 1).firstOrNull()?.let { " at $it" }
        return "${FaultTrace.summary(cause)}${where.orEmpty()}"
    }

    private fun firstFault(f: File): String = runCatching {
        f.useLines { lines -> lines.firstOrNull { it.startsWith("fault: ") }?.removePrefix("fault: ") }
    }.getOrNull().orEmpty().ifBlank { "unreadable report" }

    companion object {
        private const val PREFIX = "fault-"
        private const val TIME_FMT = "yyyy-MM-dd HH:mm:ss"
        const val MAX = 20
    }
}
