package dev.mascwa.pulse.desktop.standby

/**
 * What the standby display managed to gather, and — more importantly — **which of the three ways
 * onto the lock screen actually engaged**.
 *
 * ## Why this file exists before any of the drawing does
 *
 * ⚠️ **Windows does not let any application draw on the lock screen.** Winlogon owns a separate
 * desktop object; only the credential provider and system components render there. That is a
 * security boundary, not a permission that can be requested — no always-on-top window, no overlay
 * and no amount of elevation reaches it. Windows 11's own lock-screen widgets come from the Widgets
 * platform, which needs an MSIX-packaged provider serving Adaptive Cards over COM, and jpackage
 * produces an MSI.
 *
 * So "on the lock screen" is answered by three genuinely different mechanisms ([Rung]), each of
 * which can independently fail on a machine nobody here can test. The failure that matters is not a
 * crash — it is a rung that quietly never engaged, leaving a feature that looks built and isn't.
 * That is the exact defect the phone's widget arc just corrected, and this is the same instrument:
 * every source and every rung records a *reason*, so a screenshot or a report answers "why is this
 * not on my lock screen" without anyone guessing.
 *
 * Free of Compose and of Windows, so it can be exercised by an ordinary JVM test.
 */
object StandbyDiagnostics {

    /** A feed the standby display draws from. Labelled so a report reads without the source open. */
    enum class Source(val label: String) {
        ORACLE("advisories"),
        WEATHER("weather"),
        MARKETS("markets"),
        NEWS("news"),
        STUDY("study"),
        SPACE("space wx"),
        SKY("sky"),
        RADIO("radio"),
        MACHINE("machine"),
    }

    /**
     * How one source finished.
     *
     * ⚠️ [Empty] and [Failed] are separate and must stay separate. "There is nothing to report" is a
     * statement about the world; "I could not find out" is a statement about us. Rendering them the
     * same way is how a broken dashboard comes to look like a quiet one.
     */
    sealed interface Outcome {
        data object Ok : Outcome
        data object Empty : Outcome
        data class Failed(val reason: String) : Outcome
        data object TimedOut : Outcome
        data class Skipped(val why: String) : Outcome
    }

    /** The three ways this display reaches you when the app is not in front of you. */
    enum class Rung(val label: String, val what: String) {
        LOCK_SCREEN("lock screen", "the Windows lock-screen image is this display"),
        SCREEN_SAVER("screensaver", "fills the screen when the machine goes idle"),
        HUD("hud", "an always-on-top panel while the desktop is visible"),
    }

    /**
     * Whether a rung is actually carrying the display.
     *
     * [NotTried] is deliberately distinct from [Unavailable]: "switched off" and "tried and refused"
     * are different answers, and only one of them is a bug.
     */
    sealed interface RungState {
        data class Engaged(val detail: String) : RungState
        data class Unavailable(val reason: String) : RungState
        data object NotTried : RungState
    }

    data class Report(
        val atMs: Long,
        val outcomes: Map<Source, Outcome>,
        val rungs: Map<Rung, RungState>,
        val elapsedMs: Long,
    ) {
        val drawn: Int get() = outcomes.count { it.value is Outcome.Ok }
        val unavailable: List<Source>
            get() = outcomes.filterValues { it is Outcome.Failed || it is Outcome.TimedOut }.keys.toList()
        val engagedRungs: List<Rung>
            get() = rungs.filterValues { it is RungState.Engaged }.keys.toList()
    }

    /**
     * The last attempt, for the diagnostics panel.
     *
     * In-memory only. A render happens inside whichever process asked for it, and the standalone
     * `--lock-image` process is gone seconds later — so anything that must outlive it is written by
     * the caller. Holding the rich form here is cheaper than persisting and re-reading it.
     */
    @Volatile
    var last: Report? = null
        private set

    fun record(report: Report) {
        last = report
    }

    /** Trim a throwable to something a panel row and a log line can both hold. */
    fun describe(t: Throwable): String {
        val name = t::class.simpleName ?: "Error"
        val msg = t.message?.trim()?.takeIf { it.isNotBlank() } ?: return name
        return "$name: ${msg.take(REASON_CHARS)}"
    }

    /**
     * The one line the display itself shows when some feeds could not answer. Empty when everything
     * that was asked either answered or genuinely had nothing to say.
     */
    fun degradedLine(outcomes: Map<Source, Outcome>): String {
        val bad = outcomes.filterValues { it is Outcome.Failed || it is Outcome.TimedOut }.keys.toList()
        if (bad.isEmpty()) return ""
        val named = bad.take(3).joinToString(", ") { it.label }
        val more = if (bad.size > 3) " +${bad.size - 3}" else ""
        return "NO ANSWER FROM $named$more".uppercase()
    }

    /**
     * One line per rung, in plain English, for the diagnostics panel and for a bug report.
     *
     * ⚠️ This is the answer to the question the owner will actually ask. A rung that never engaged
     * has to say so in words, because "I don't see it on my lock screen" and "the feature isn't
     * finished" are indistinguishable from outside.
     */
    fun rungLines(report: Report?): List<String> {
        val rungs = report?.rungs.orEmpty()
        return Rung.entries.map { rung ->
            when (val state = rungs[rung]) {
                is RungState.Engaged -> "${rung.label.uppercase()}  ✓  ${state.detail}"
                is RungState.Unavailable -> "${rung.label.uppercase()}  ✗  ${state.reason}"
                RungState.NotTried, null -> "${rung.label.uppercase()}  —  not switched on"
            }
        }
    }

    /** The full breakdown, including the sources that behaved, so "asked and got nothing" is visible. */
    fun report(report: Report?): String? {
        val r = report ?: return null
        return buildString {
            append(r.drawn).append(" of ").append(r.outcomes.size).append(" panels drawn")
            append(" · ").append(r.elapsedMs).append(" ms")
            append("\n")
            r.outcomes.forEach { (source, outcome) ->
                append('\n').append(source.label.padEnd(11)).append("  ")
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
            append("\n")
            rungLines(r).forEach { append('\n').append(it) }
        }
    }

    /**
     * How much of a failure message to keep. Long enough to name a host or a refused API call, short
     * enough that it cannot dominate the panel it appears in.
     */
    const val REASON_CHARS = 160
}
