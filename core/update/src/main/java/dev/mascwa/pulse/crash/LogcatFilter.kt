package dev.mascwa.pulse.crash

/**
 * Turning a raw logcat dump into the part of it that could diagnose anything.
 *
 * ⚠️ **No Android import in this file, deliberately** — the same reasoning as `HealthArchive`. The
 * `exec` that produces the dump needs a device; deciding what to keep out of it does not, and a JVM
 * test can be handed the real dumps that came off the real phones.
 *
 * ## What was wrong, measured on the reports the owner actually sent
 *
 * The uploaders ran `logcat -d -t 400` with no filter at all, printed the tail under the heading
 * *"this process only"*, and sent that.
 *
 * ⚠️ **The three reports from the standalone nutrition app carried nothing usable at all.** Counted:
 *
 *     254 lines total
 *     VRI[MainActivity]   78  ┐
 *     ImeTracker          36  ├ 149 of 254 — 59% — keyboard and window chatter
 *     InsetsController    35  ┘
 *     nativeloader/GraphicsEnvironment/CompatChangeReporter/Typeface/…  the rest
 *     lines from that app's own code:  ZERO
 *
 * ⚠️ **The LCARS reports are a different picture, and the correction matters.** There are 55 of them
 * on the `debug-reports` branch, most of them real crashes, and their dumps DO carry the thing worth
 * reading — a `FATAL EXCEPTION` and its whole stack, logged by `AndroidRuntime` at level E. So the
 * chatter is not all there is; it is what buries the part that is. `real-report-1787195884334.log`
 * in this module's test resources is one of them verbatim.
 *
 * And the heading was not true either. That report carries lines from **three different pids** —
 * logcat keeps a ring buffer that outlives a process, so `-t 400` reaches back through several
 * earlier launches, and nothing said which lines came from the run being reported.
 *
 * ## What this keeps
 *
 * ⚠️ **Warnings and errors from EVERY launch in the buffer, not only the current one.** That looks
 * like the wrong call until you consider the case these reports exist for: if the app died last
 * launch and the fault handler did not survive to write a file — a native crash, an OOM — the logcat
 * from that earlier pid is the *only* record of it. It is annotated as an earlier run rather than
 * dropped.
 *
 * ⚠️ **Warnings and errors from every launch is not a theoretical case; it is THE case.** In the real
 * report above the `FATAL EXCEPTION` belongs to pid 5178 and the report was sent from pid 7549 — the
 * app had already died and been relaunched. A filter keeping only the current process would have
 * thrown away the entire crash and kept the location-service chatter that followed it.
 *
 * ⚠️ **A continuation line is kept with the entry it belongs to** — but this is DEFENSIVE rather than
 * load-bearing, and an earlier version of this note overstated it. `AndroidRuntime` logs each frame
 * of a trace as its own record with its own prefix, so in the dump measured above nothing needed
 * re-attaching. What this handles is the other shape: anything that prints a multi-line message in
 * one call, where a per-line test would keep the first line and drop the rest.
 *
 * The routine framework chatter above is set aside from the recent tail **and counted**, so nothing
 * is quietly missing.
 */
object LogcatFilter {

    /** One logcat record: its header line plus any continuation lines beneath it. */
    data class Entry(
        val pid: Int,
        val level: Char,
        val tag: String,
        val lines: List<String>,
    ) {
        val text: String get() = lines.joinToString("\n")
    }

    /**
     * `08-26 19:59:01.505  5421  5421 W RemoteInputConnectionImpl: getExtractedText …`
     *
     * The `threadtime` format both uploaders ask for. Pid and tid are space-padded to a width that
     * varies with the number, hence `\s+` rather than a fixed column.
     */
    private val HEADER =
        Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+(\d+)\s+(\d+)\s+([VDIWEFAS])\s+([^:]*?)\s*:""")

    /** Levels worth keeping whatever else is going on. */
    private val LOUD = setOf('W', 'E', 'F', 'A')

    /**
     * Tags whose output is the framework narrating itself.
     *
     * ⚠️ Matched as PREFIXES, because some of them carry an instance in the tag —
     * `VRI[MainActivity]@c2a7eee` is a different string every launch and would defeat an exact list.
     *
     * ⚠️ This list only ever affects the recent tail. A warning or an error keeps its place no matter
     * which tag produced it, so a genuine framework complaint — the kind that says a window leaked or
     * a surface was abandoned — is never removed by being on this list.
     */
    private val CHATTER = listOf(
        "VRI[", "ViewRootImpl", "ImeTracker", "InsetsController", "InsetsSourceConsumer",
        "InputMethodManager", "InputTransport", "RemoteInputConnectionImpl", "WindowOnBackDispatcher",
        "BLASTBufferQueue", "Surface", "DisplayManager", "InteractionJankMonitor", "cutils-trace",
        "nativeloader", "GraphicsEnvironment", "CompatChangeReporter", "Typeface", "SystemFonts",
        "AssetManager", "DesktopExperienceFlags", "FeatureFlagsImplExport", "Zygote", "libc",
        "DynCodeLoading", "OpenGLRenderer", "HWUI", "Choreographer", "ziparchive", "chatty",
    )

    private fun chatter(tag: String) = CHATTER.any { tag.startsWith(it) }

    /** Split a dump into records, attaching each continuation line to the record above it. */
    fun parse(raw: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var current: MutableList<String>? = null
        for (line in raw.lineSequence()) {
            // `--------- beginning of main` and friends belong to no record.
            if (line.startsWith("---------")) { current = null; continue }
            val m = HEADER.find(line)
            if (m != null) {
                val lines = mutableListOf(line)
                out += Entry(
                    pid = m.groupValues[1].toIntOrNull() ?: 0,
                    level = m.groupValues[3].firstOrNull() ?: 'I',
                    tag = m.groupValues[4],
                    lines = lines,
                )
                current = lines
            } else if (line.isNotBlank()) {
                // ⚠️ A continuation, which is where a stack trace lives. With no record open it is
                // an orphan from before the window and there is nothing to attach it to.
                current?.add(line)
            }
        }
        return out
    }

    /**
     * The logcat section of a report: what went wrong, then what was happening, within [budget].
     *
     * [pid] is the process doing the reporting. Records from any other pid are earlier launches of
     * this same app — logcat only ever shows an ordinary app its own UID.
     */
    fun report(raw: String, pid: Int, budget: Int = DEFAULT_BUDGET): String {
        val entries = parse(raw)
        if (entries.isEmpty()) return "(nothing in the log buffer for this app)"

        val loud = entries.filter { it.level in LOUD }
        val mine = entries.filter { it.pid == pid }
        val quiet = mine.filter { it.level !in LOUD && !chatter(it.tag) }
        val setAside = mine.count { it.level !in LOUD && chatter(it.tag) }

        val out = StringBuilder()

        if (loud.isEmpty()) {
            out.append("### Warnings and errors\n\n(none in the buffer)\n\n")
        } else {
            out.append("### Warnings and errors — every launch still in the buffer\n\n```\n")
            // ⚠️ Newest last, and trimmed from the FRONT when it will not fit: the most recent
            // trouble is the trouble worth reading, and dropping from the end would cut it off.
            out.append(fit(loud.map { stamp(it, pid) }, budget / 2))
            out.append("\n```\n\n")
        }

        out.append("### This run, most recent\n\n```\n")
        out.append(
            if (quiet.isEmpty()) "(nothing from this run but the routine chatter below)"
            else fit(quiet.map { it.text }, budget - minOf(budget / 2, out.length)),
        )
        out.append("\n```\n")
        if (setAside > 0) {
            out.append("\n_")
                .append(setAside)
                .append(" routine framework line(s) — keyboard, window and loader chatter — set ")
                .append("aside from the section above. Warnings and errors were kept whatever their tag._\n")
        }
        return out.toString()
    }

    /** An earlier launch's line says so; this run's does not need to. */
    private fun stamp(e: Entry, pid: Int) =
        if (e.pid == pid) e.text else e.text + "   ⟵ an earlier launch (pid ${e.pid})"

    /**
     * Join newest-last, dropping from the front until it fits.
     *
     * ⚠️ **Except that it never trims past a fatal, and that exception was found by running this over
     * a real dump rather than by reading it.** Dropping from the front is right for a chronological
     * list — the most recent trouble is the trouble worth reading — and applied to a stack trace it
     * decapitates it, because `AndroidRuntime` logs the exception type and its MESSAGE first and the
     * frames after. At a 1,200-character budget over report `1787195884334` this cut both the
     * `FATAL EXCEPTION` header and `Key "search" was already used`, and kept eight obfuscated frames
     * that say nothing on their own. So once the front reaches a fatal, the trim comes off the end
     * instead, and says that it did.
     */
    private fun fit(texts: List<String>, budget: Int): String {
        if (budget <= 0) return ""
        val fatal = texts.indexOfFirst { FATAL in it }
        var from = 0
        while (from < texts.size) {
            val joined = texts.subList(from, texts.size).joinToString("\n")
            if (joined.length <= budget) return joined
            if (fatal >= 0 && from >= fatal) return fromTheTop(texts.subList(fatal, texts.size), budget)
            from++
        }
        // Even one record is over budget: keep its tail, which is where a trace ends.
        return texts.lastOrNull()?.takeLast(budget).orEmpty()
    }

    /** Keep the head of a trace and say what was cut, rather than silently keeping the wrong half. */
    private fun fromTheTop(texts: List<String>, budget: Int): String {
        val note = "\n… trace truncated"
        val room = (budget - note.length).coerceAtLeast(0)
        val kept = StringBuilder()
        for (t in texts) {
            if (kept.length + t.length + 1 > room) break
            if (kept.isNotEmpty()) kept.append('\n')
            kept.append(t)
        }
        return if (kept.length < texts.sumOf { it.length + 1 } - 1) kept.append(note).toString()
        else kept.toString()
    }

    const val DEFAULT_BUDGET = 24_000

    /**
     * What `AndroidRuntime` prints at the top of an uncaught exception.
     *
     * ⚠️ Matched as text rather than by tag, because the tag alone is not the distinction: an
     * `AndroidRuntime` record can be an ordinary error, and it is specifically the fatal whose head
     * must not be trimmed away.
     */
    private const val FATAL = "FATAL EXCEPTION"
}
