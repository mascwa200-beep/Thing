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
        WATER("water"),
        CALENDAR("calendar"),
        DATA("data used"),
        COMMS("texts & mail"),
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

        /**
         * Never asked, because something it needs is absent (no location, no saved place).
         *
         * [fixable] says the owner can do something about it — grant a permission, add a place —
         * and only those reach the NEEDS YOU line ([needsYouLine]). ⚠️ Defaulted to false: a
         * future skip that is simply not applicable here (a US-only feed abroad) must not tell
         * somebody to go and fix what cannot be fixed, so opting in is the explicit act.
         */
        data class Skipped(val why: String, val fixable: Boolean = false) : Outcome
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

    /**
     * What the owner could do to make the widget show more, or "" when nothing.
     *
     * The other half of [degradedLine]. That one names feeds that TRIED and got no answer; this names
     * the ones that were never asked because of something the owner controls — a permission, a
     * place. Without it those went quiet with a reason recorded only in the crash console, where a
     * widget missing its weather looked the same as a weather service that was down.
     *
     * ⚠️ Deduplicated by the sentence, not the source: four feeds skipped for want of a location are
     * one thing to do, and listing it four times would read as four problems.
     */
    fun needsYouLine(outcomes: Map<Source, Outcome>): String {
        val why = outcomes.values.filterIsInstance<Outcome.Skipped>()
            .filter { it.fixable }
            .map { it.why }
            .distinct()
        if (why.isEmpty()) return ""
        val more = if (why.size > MAX_NEEDS) " +${why.size - MAX_NEEDS}" else ""
        return "NEEDS YOU · ${why.take(MAX_NEEDS).joinToString(" · ")}$more"
    }

    /** How many causes the NEEDS YOU line names before it counts the rest. */
    private const val MAX_NEEDS = 3

    /**
     * How old a feed's data may be before the widget names it beside its UPDATED stamp.
     *
     * ⚠️ The stamp is when the board was REDRAWN, and a redraw does not mean fresh data: every feed is
     * read with `force = false`, and when the network fails a repository hands back whatever it last
     * saved, however old — yesterday's quotes stamped "UPDATED 14:05". `Fetched` has always carried
     * when its data was saved; the widget kept only `.data` and threw that away.
     *
     * Only feeds whose value is about NOW are listed. Fuel prices are weekly and the economy series
     * annual — a day-old copy of either is simply what those feeds are, and naming it would teach the
     * reader to ignore the note. An hour for a quote, because markets move by the minute and any TTL
     * hit is far younger than that; six hours for the rest, which update a few times a day.
     */
    val STALE_AFTER_MS: Map<Source, Long> = mapOf(
        Source.MARKETS to 60 * 60_000L,
        Source.NEWS to 6 * 60 * 60_000L,
        Source.SPACE to 6 * 60 * 60_000L,
        Source.SAFETY to 6 * 60 * 60_000L,
    )

    /**
     * "MARKETS FROM 9:12 AM", naming the oldest feed older than its limit, or "" when none is.
     *
     * [dataAt] is when each drawn feed's data was saved; [time] renders an instant the way the reader
     * expects (a clock time today, a day name before that). One feed is named and the rest counted,
     * because this rides a one-line subhead. A time in the future — a clock that moved — is not stale.
     */
    fun staleNote(dataAt: Map<Source, Long>, nowMs: Long, time: (Long) -> String): String {
        val stale = dataAt.entries
            .filter { (src, at) -> STALE_AFTER_MS[src]?.let { limit -> nowMs - at > limit } == true }
            .sortedBy { it.value }
        val oldest = stale.firstOrNull() ?: return ""
        val more = if (stale.size > 1) " +${stale.size - 1}" else ""
        return "${oldest.key.label.uppercase()} FROM ${time(oldest.value)}$more"
    }

    /**
     * How old a kept location may be before the widget says so. See [placeNote].
     *
     * Six hours, the same as the slower feeds above: long enough that an ordinary morning away from
     * LCARS draws no note, short enough that yesterday's place is always named.
     */
    const val PLACE_NOTE_AFTER_MS = 6 * 60 * 60_000L

    /**
     * "PLACE FROM 9:12 AM" when the location under the weather, air, water, safety and sky lines is a
     * kept fix older than [PLACE_NOTE_AFTER_MS], or "" otherwise.
     *
     * ⚠️ The widget renders in the background, where the platform refuses it any live fix, so those
     * lines are usually drawn for the place LCARS last saw. Within a few hours that is simply here; past
     * that it is a claim about where the phone WAS, and a weather line that does not say so passes it
     * off as where the phone is. [recordedAtMs] null means a saved place or a live fix — nothing to
     * confess. A time in the future is a clock that moved, not an old place.
     */
    fun placeNote(recordedAtMs: Long?, nowMs: Long, time: (Long) -> String): String {
        val at = recordedAtMs ?: return ""
        return if (nowMs - at > PLACE_NOTE_AFTER_MS) "PLACE FROM ${time(at)}" else ""
    }

    /**
     * The rows a widget with [cap] slots should actually draw, with [notice] — the [degradedLine]
     * row — guaranteed one of them.
     *
     * ⚠️ **This exists because the row form silently dropped its own failure report.** The renderer
     * ended in a bare `rows.take(limit)`, and the degraded line was appended to the end of the list
     * with a comment saying it went last *"so it never pushes real content off a small widget"* —
     * but going last is precisely what made it the FIRST row dropped. Counted at the time: twenty-one
     * `rows +=` sites against twenty slots, plus this one, so a healthy-looking busy widget shed the
     * very line whose only job is to say a feed had died. Silent truncation, and the casualty was
     * the diagnostic.
     *
     * So the notice is placed *above* the cut rather than below it, and a real row is shed to make
     * room. That is the deliberate trade: one row of content is worth knowing that another row is
     * missing for a reason. Rows are shed from the TAIL because the caller appends them in
     * descending consequence — the advisory first, the system footnote last.
     *
     * ⚠️ [cap] of zero returns nothing at all, notice included. `List.take` throws on a negative
     * count, so without that guard a widget with no slots would crash rather than draw nothing —
     * and a crash here is the condition that makes the launcher print *"Can't load widget"*, which
     * is the one string this file exists to prevent.
     *
     * Generic over the row type so the rule can be exercised on the JVM: the renderer's own `Row` is
     * private to it and carries Android colours, and what is worth testing is the decision, not the
     * struct it is made of.
     */
    fun <T> fit(rows: List<T>, cap: Int, notice: T?): List<T> {
        if (cap <= 0) return emptyList()
        if (notice == null) return rows.take(cap)
        return rows.take(cap - 1) + notice
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
