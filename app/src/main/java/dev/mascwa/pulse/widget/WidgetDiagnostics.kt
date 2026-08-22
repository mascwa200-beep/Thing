package dev.mascwa.pulse.widget

/**
 * What happened the last time the widget tried to draw itself, in enough detail that a photograph
 * of the home screen is a usable bug report.
 *
 * ## Why this exists
 *
 * Two complaints, one root cause: **the widget could not say anything about its own failures.**
 *
 *  - Every source was `runCatching { … }.getOrDefault("")`, and a blank line hid itself. So a feed
 *    that threw and a feed that genuinely had nothing to report rendered **identically** — as
 *    absence. The widget appeared to quietly shrink, and nothing anywhere recorded why.
 *  - When the render itself failed, `onUpdate` caught the throwable and applied **nothing**, which
 *    is exactly the condition under which the launcher draws its own *"Can't load widget"*.
 *
 * ⚠️ **That string belongs to the launcher and cannot be replaced by this app.** The host shows it
 * when it fails to apply our `RemoteViews`, or when `updateAppWidget` is never called at all. The
 * only real fix is to make sure the host never has to: every path now ends in a *successful*
 * `updateAppWidget`, applying a deliberately tiny error card when the rich one could not be built.
 *
 * ## Where a reason ends up
 *
 *  1. **On the widget** — [faultLine] on the error card, and [degradedLine] as one row inside an
 *     otherwise-healthy widget when only some feeds are missing. Both are screenshot-sized.
 *  2. **In the Crash Console** — [report], the full per-source breakdown.
 *  3. **On GitHub** — the caller writes [logLine] through `UsageRepository.log("widget", …)`, which
 *     is already persisted and already embedded in `DebugUploader`'s bundle. No new storage, no new
 *     upload path, and the existing central credential scrub covers it.
 *
 * Deliberately free of Android imports so it can be exercised by an ordinary JVM test.
 */
object WidgetDiagnostics {

    /** Every feed the widget draws from. Named so a report is readable without the source open. */
    enum class Source(val label: String) {
        ORACLE("advisory"),
        DAY_AHEAD("day ahead"),
        WEATHER("weather"),
        MARKETS("markets"),
        NEWS("news"),
        TASKS("tasks"),
        STUDY("study"),
        SPACE("space wx"),
        SKY("sky"),
        FUEL("fuel"),
        ECONOMY("economy"),
        SAFETY("safety"),
        DEVICE("device"),
    }

    /**
     * How one source finished.
     *
     * ⚠️ [Empty] and [Failed] are separate on purpose, and keeping them separate is the whole point
     * of this file. "There is nothing to report" is a statement about the world; "I could not find
     * out" is a statement about us, and rendering them the same way is how the widget came to look
     * like it was losing features.
     */
    sealed interface Outcome {
        /** Produced something to draw. */
        data object Ok : Outcome

        /** Asked, answered, nothing worth a line — a quiet market, no pending task. */
        data object Empty : Outcome

        /** Threw. [reason] is the exception's class and message, capped. */
        data class Failed(val reason: String) : Outcome

        /** Did not finish inside its own budget. Its own — see [WidgetCommon]. */
        data object TimedOut : Outcome

        /** Never asked, because something it needs is absent (no location, no saved place). */
        data class Skipped(val why: String) : Outcome
    }

    /** One complete render attempt. */
    data class Render(
        val atMs: Long,
        /** Which size variant the host asked for, or "?" when it did not say. */
        val size: String,
        val outcomes: Map<Source, Outcome>,
        val elapsedMs: Long,
        /** Set when the whole render failed and the error card was shown instead. */
        val fault: String? = null,
    ) {
        val failed: List<Source> get() = outcomes.filterValues { it is Outcome.Failed }.keys.toList()
        val timedOut: List<Source> get() = outcomes.filterValues { it is Outcome.TimedOut }.keys.toList()
        val drawn: Int get() = outcomes.count { it.value is Outcome.Ok }

        /** Sources that could not answer, as opposed to those that answered "nothing". */
        val unavailable: List<Source> get() = failed + timedOut
    }

    /**
     * The last attempt, for the Crash Console.
     *
     * ⚠️ In-memory only, and that is not an oversight. A widget update runs in the app's own
     * process, so the console can read this directly; but the process is short-lived, so a report
     * that must survive it goes through the activity log instead ([logLine]). Holding both is
     * cheaper than persisting the rich form and re-reading it.
     */
    @Volatile
    var last: Render? = null
        private set

    fun record(render: Render) {
        last = render
    }

    /** Trim an exception to something that fits a widget row and a log line. */
    fun describe(t: Throwable): String {
        val name = t::class.simpleName ?: "Error"
        val msg = t.message?.trim()?.takeIf { it.isNotBlank() } ?: return name
        return "$name: ${msg.take(REASON_CHARS)}"
    }

    /**
     * The single line shown on the error card. Short enough to photograph and specific enough to
     * act on — it names the failure, not the fact that there was one.
     */
    fun faultLine(render: Render?): String =
        render?.fault?.take(REASON_CHARS) ?: "Unknown fault — no render was recorded."

    /**
     * The row an otherwise-healthy widget shows when some feeds could not answer. Absent when
     * everything that was asked either answered or had nothing to say.
     */
    fun degradedLine(render: Render?): String = degradedLine(render?.outcomes.orEmpty())

    /**
     * The same line, from outcomes alone.
     *
     * Exists because the renderer has the outcomes in hand but not yet a [Render] — it is still
     * deciding what to draw — and manufacturing a throwaway record with zeroed timings just to ask
     * one question would put a fake in the type that the Crash Console reads.
     */
    fun degradedLine(outcomes: Map<Source, Outcome>): String {
        val bad = outcomes.filterValues { it is Outcome.Failed || it is Outcome.TimedOut }.keys.toList()
        if (bad.isEmpty()) return ""
        val named = bad.take(3).joinToString(", ") { it.label }
        val more = if (bad.size > 3) " +${bad.size - 3}" else ""
        return "⚠ no answer from $named$more — tap for why"
    }

    /** The compact form written to the activity log, and thence to a debug report. */
    fun logLine(render: Render): String = buildString {
        append(if (render.fault != null) "FAULT " else "render ")
        append(render.size).append(' ')
        append(render.drawn).append('/').append(render.outcomes.size).append(" drawn")
        append(" in ").append(render.elapsedMs).append("ms")
        render.fault?.let { append(" · ").append(it.take(REASON_CHARS)) }
        render.outcomes.forEach { (source, outcome) ->
            when (outcome) {
                is Outcome.Failed -> append(" · ").append(source.label).append(" failed(").append(outcome.reason).append(')')
                is Outcome.TimedOut -> append(" · ").append(source.label).append(" timeout")
                is Outcome.Skipped -> append(" · ").append(source.label).append(" skipped(").append(outcome.why).append(')')
                else -> Unit          // ok and empty are the uninteresting majority
            }
        }
    }

    /**
     * The full breakdown for the Crash Console — every source, including the ones that behaved, so
     * a reader can tell "asked and got nothing" from "never asked".
     */
    fun report(render: Render?): String? {
        val r = render ?: return null
        return buildString {
            append(if (r.fault != null) "FAULT" else "OK")
            append(" · size ").append(r.size)
            append(" · ").append(r.drawn).append(" of ").append(r.outcomes.size).append(" drawn")
            append(" · ").append(r.elapsedMs).append(" ms")
            r.fault?.let { append("\n\nfault: ").append(it) }
            append("\n")
            r.outcomes.forEach { (source, outcome) ->
                append('\n').append(source.label.padEnd(10)).append("  ")
                append(
                    when (outcome) {
                        Outcome.Ok -> "drawn"
                        Outcome.Empty -> "nothing to report"
                        Outcome.TimedOut -> "gave up waiting"
                        is Outcome.Failed -> "FAILED — ${outcome.reason}"
                        is Outcome.Skipped -> "not asked — ${outcome.why}"
                    },
                )
            }
        }
    }

    /**
     * How much of an exception message to keep.
     *
     * Long enough to name a host or a missing class, short enough that it cannot dominate a widget
     * row or push useful entries out of the activity ring.
     */
    const val REASON_CHARS = 120
}
