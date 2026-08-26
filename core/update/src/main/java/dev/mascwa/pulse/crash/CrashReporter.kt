package dev.mascwa.pulse.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/** Whether something killed the process, or merely went wrong and was survived. */
enum class FaultKind {
    /** An uncaught throwable. The process is gone. */
    FATAL,

    /**
     * Something that failed and was handled — a database that would not open, a lookup that threw
     * and was swallowed, a permission refused. The app carried on, usually showing nothing.
     */
    NONFATAL,
}

/** One recorded fault: the backing file, when it happened, whether it was fatal, and a one-line summary. */
data class CrashEntry(
    val file: File,
    val timeMs: Long,
    val summary: String,
    val kind: FaultKind = FaultKind.FATAL,
)

/**
 * Captures faults to plain-text files in private storage, so they can be reviewed in the app and
 * sent on by [CrashUploader].
 *
 * One file per fault, so there is no read-modify-write at crash time; recording is allocation-light
 * and never throws, because the JVM is unstable while a crash is being handled.
 *
 * ## Two kinds, and the second is the one that finds the quiet problems
 *
 * A crash is loud and rare. **The failures that make an app feel broken are usually neither**: a
 * bundled database that would not open so every barcode misses, a network call that threw and was
 * caught so a search silently returns nothing, a permission refused so a panel is permanently blank.
 * None of those produce a throwable anybody sees, and every one of them is a fault report worth
 * having — hence [reportNonFatal].
 *
 * ⚠️ **The two are capped separately.** A repeating background failure fires on a timer; a crash
 * happens once. Under a shared cap the noisy one evicts the one that actually killed the app, so
 * [MAX_FATAL] and [MAX_NONFATAL] are trimmed independently.
 *
 * ## What this cannot capture, said once
 *
 * ⚠️ A **native** crash (a C++ SIGSEGV in whisper, llama or QuickJS) terminates the process with no
 * JVM throwable and cannot be seen from here. Neither can an **ANR** — the process is alive and the
 * main thread is simply not returning, which no handler is told about. Only JVM-level throwables,
 * [OutOfMemoryError] included, are recorded.
 *
 * ## Why this lives in `:core:update`
 *
 * ⚠️ **The package is deliberately unchanged from where it was carved out of.** `:app` names it in
 * imports and in its container, so keeping `dev.mascwa.pulse.crash` is what makes the move cost no
 * churn — the same reasoning `:core:health`, `:core:feeds` and `:core:update` itself all record.
 * The Gradle path is `:core:update` because that module is already "the part that talks to GitHub
 * about this build", which is precisely what sending a report is.
 *
 * @param appLabel which application this is, in a report header — "LCARS", "Nutrition".
 * @param versionName the running build's display version.
 * @param versionCode the running build number.
 *
 * ⚠️ The three build facts are **required, not defaulted**. A default lets a consumer silently ship
 * "unknown" as its own version, and which build a fault came from is the single most load-bearing
 * line in the report: without it a fixed bug and a live one look identical.
 */
class CrashReporter(
    context: Context,
    private val appLabel: String,
    private val versionName: String,
    private val versionCode: Int,
) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "diagnostics").apply { runCatching { mkdirs() } }

    /**
     * Tags already reported this process, so a failure on a timer files one report rather than one
     * every thirty seconds.
     *
     * ⚠️ Per PROCESS, not per session or per day. Deliberate in both directions: a fault that
     * survives a restart is worth hearing about again (it is no longer a one-off), and a fault that
     * repeats within one run is the same fault.
     */
    private val reportedTags: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Install as the process-wide uncaught-exception handler. Records the crash, then delegates to
     * the previous handler so the OS still terminates and relaunches the app normally — we do not
     * swallow the crash or auto-restart, which could crash-loop.
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
        write(FaultKind.FATAL, thread.name, summaryOf(throwable), traceOf(throwable), null)
    }

    /**
     * Record something that failed and was handled.
     *
     * @param tag a stable identifier for the failing thing — `food.db.open`, `healthconnect.read`.
     *   ⚠️ It is the de-duplication key, so it must not carry anything variable: a tag with a
     *   timestamp or a barcode in it defeats the rate limit entirely.
     * @param throwable what was caught, if anything was.
     * @param note one line of context, under the same content rule as [Breadcrumbs] — no food names,
     *   no weights, nothing typed.
     *
     * @return true when a report was written, false when this tag has already been reported.
     */
    fun reportNonFatal(tag: String, throwable: Throwable? = null, note: String? = null): Boolean {
        if (!reportedTags.add(tag)) return false
        val summary = buildString {
            append(tag)
            if (throwable != null) append(" — ").append(summaryOf(throwable))
            else if (note != null) append(" — ").append(note)
        }
        write(FaultKind.NONFATAL, Thread.currentThread().name, summary, throwable?.let { traceOf(it) }, note)
        return true
    }

    /** Most-recent-first list of recorded faults, both kinds. */
    fun entries(): List<CrashEntry> =
        (dir.listFiles { f -> f.isFile && (f.name.startsWith(FATAL_PREFIX) || f.name.startsWith(NONFATAL_PREFIX)) }
            ?: emptyArray())
            .map { f ->
                val fatal = f.name.startsWith(FATAL_PREFIX)
                val prefix = if (fatal) FATAL_PREFIX else NONFATAL_PREFIX
                val ms = f.name.removePrefix(prefix).removeSuffix(".txt").toLongOrNull() ?: f.lastModified()
                CrashEntry(f, ms, firstFault(f), if (fatal) FaultKind.FATAL else FaultKind.NONFATAL)
            }
            .sortedByDescending { it.timeMs }

    fun read(entry: CrashEntry): String = runCatching { entry.file.readText() }.getOrDefault("")

    fun clear() {
        dir.listFiles { f -> f.name.startsWith(FATAL_PREFIX) || f.name.startsWith(NONFATAL_PREFIX) }
            ?.forEach { runCatching { it.delete() } }
        reportedTags.clear()
        Breadcrumbs.clear()
    }

    // --- internals -------------------------------------------------------------------------------

    private fun write(kind: FaultKind, threadName: String, summary: String, trace: String?, note: String?) {
        runCatching {
            val now = System.currentTimeMillis()
            val prefix = if (kind == FaultKind.FATAL) FATAL_PREFIX else NONFATAL_PREFIX
            val body = buildString {
                append(appLabel).append(' ').append(versionName)
                    .append(" (#").append(versionCode).append(")\n")
                append("kind: ").append(kind.name.lowercase(Locale.US)).append('\n')
                append("time: ").append(SimpleDateFormat(TIME_FMT, Locale.US).format(Date(now))).append('\n')
                append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" · Android ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("thread: ").append(threadName).append('\n')
                append("fault: ").append(summary).append('\n')
                if (note != null) append("note: ").append(note).append('\n')
                append('\n')
                // ⚠️ Read at write time and not a moment later: for a fatal this is the last chance,
                // and for a non-fatal it is what makes the report about a moment rather than a class.
                append("--- what was happening ---\n").append(Breadcrumbs.render(now)).append('\n')
                if (trace != null) append("--- trace ---\n").append(trace)
            }
            File(dir, prefix + freeStamp(prefix, now) + ".txt").writeText(body)
            trim(prefix, if (kind == FaultKind.FATAL) MAX_FATAL else MAX_NONFATAL)
        }
    }

    /**
     * A millisecond that no report of this kind already occupies.
     *
     * ⚠️ Two non-fatals can genuinely land in the same millisecond, and the timestamp is not just a
     * name — [CrashUploader] uses it as the "already sent" key. A collision would overwrite one
     * report with another and make the survivor look already-uploaded.
     */
    private fun freeStamp(prefix: String, from: Long): Long {
        var t = from
        while (File(dir, "$prefix$t.txt").exists()) t++
        return t
    }

    /** Keep only the most recent [max] reports of one kind. */
    private fun trim(prefix: String, max: Int) {
        val files = dir.listFiles { f -> f.name.startsWith(prefix) } ?: return
        if (files.size <= max) return
        files.sortedByDescending { it.lastModified() }.drop(max).forEach { runCatching { it.delete() } }
    }

    private fun firstFault(f: File): String = runCatching {
        f.useLines { lines -> lines.firstOrNull { it.startsWith("fault: ") }?.removePrefix("fault: ") }
    }.getOrNull() ?: "Unknown fault"

    private fun traceOf(t: Throwable): String =
        StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    private fun summaryOf(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"

    internal companion object {
        /** ⚠️ Unchanged, so reports already on a device from an earlier build still list and still send. */
        const val FATAL_PREFIX = "crash_"
        const val NONFATAL_PREFIX = "fault_"
        const val MAX_FATAL = 20
        const val MAX_NONFATAL = 20
        const val TIME_FMT = "yyyy-MM-dd HH:mm:ss"
    }
}
