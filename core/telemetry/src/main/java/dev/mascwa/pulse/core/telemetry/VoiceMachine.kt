package dev.mascwa.pulse.core.telemetry

/**
 * Who holds the microphone, and what to do about it.
 *
 * The resident voice service arbitrates the mic between four claimants — the wake-word recogniser,
 * the command recogniser, the console's tap-to-talk, and the computer's own speech — using two
 * `@Volatile` booleans read inconsistently across a dozen call sites. Three of those sites guard
 * `waking && !capturing && !consoleActive` before re-arming; nine do not. So two in-flight paths (a
 * recogniser timing out at the same moment the console closes, say) can each open a wake session,
 * and nothing observes it: the second `start()` succeeds, the mic is held twice, and the only
 * symptom is battery and a wake word that answers oddly.
 *
 * That defect has survived several attempts to fix it by hand because there was no single place to
 * be correct in and nothing in CI could see it. This is that place. It is deliberately pure — no
 * recognisers, no Android, no coroutines — so the arbitration is decided here and merely *performed*
 * by the service.
 *
 * The property worth the file: **[settle] issued twice in a row produces one [Action.START_WAKE] and
 * then nothing.** Idempotency stops being a discipline applied at each call site and becomes a
 * statement CI can check.
 */
object VoiceMachine {

    /** Who currently holds the microphone. Exactly one claimant, or nobody. */
    enum class Owner {
        /** Nobody. Either voice is off, or a recogniser just died and has not been restarted. */
        NONE,

        /** The always-on wake-word recogniser, listening for one phrase on a keyword grammar. */
        WAKE,

        /** A one-shot command capture, after the wake phrase was heard. */
        COMMAND,

        /** The console's tap-to-talk. It is the user's explicit act, so it outranks the wake loop. */
        CONSOLE,

        /**
         * The computer is speaking. The mic is deliberately free: an open mic during speech hears
         * the reply and answers itself.
         */
        SPEAKING,
    }

    /** What the caller must do to make the world match the state. */
    enum class Action {
        /** Nothing. The mic is already where it belongs — the answer that fixes the double-arm. */
        NOTHING,

        /** Start the wake-word recogniser. */
        START_WAKE,

        /** Start a one-shot command capture. */
        START_COMMAND,

        /** Stop whatever this service has open. Someone else holds the mic, or nobody should. */
        RELEASE_MIC,
    }

    /**
     * @param owner who holds the mic right now.
     * @param wanted whether the service wants voice running at all (mic permission held, user has it on).
     * @param console whether the console has claimed the mic.
     */
    data class State(
        val owner: Owner = Owner.NONE,
        val wanted: Boolean = false,
        val console: Boolean = false,
    )

    /** The next state, and the one thing to do about it. */
    data class Step(val state: State, val action: Action)

    // ---- events ---------------------------------------------------------------------------------

    /** The service starts or stops wanting voice at all. */
    fun State.wants(on: Boolean): Step = copy(wanted = on).settle()

    /** The console claims or releases the mic. */
    fun State.console(active: Boolean): Step = copy(console = active).settle()

    /**
     * The wake phrase was recognised.
     *
     * Only the wake recogniser can produce this. A late partial arriving after the console took the
     * mic, or after a capture already began, is ignored rather than opening a second session — the
     * service's own `capturing` flag catches the second case today and nothing catches the first.
     */
    fun State.wakeHeard(): Step =
        if (owner == Owner.WAKE) to(Owner.COMMAND) else Step(this, Action.NOTHING)

    /** A reply is about to be spoken: the mic goes quiet so the computer does not hear itself. */
    fun State.speaking(): Step = to(Owner.SPEAKING)

    /**
     * A recogniser reported an error and is no longer running.
     *
     * No action: the caller backs off before retrying, and issuing `START_WAKE` here would be the
     * tight retry storm the backoff exists to prevent. Dropping to [Owner.NONE] is what makes the
     * later [settle] actually restart rather than deciding it is already listening.
     */
    fun State.micFailed(): Step = Step(copy(owner = Owner.NONE), Action.NOTHING)

    /**
     * Nothing is in flight — put the mic where it belongs. **This is the single re-arm funnel.**
     *
     * Every path that used to call `listenForWake` directly calls this instead: a blank capture, a
     * timeout, an error, the end of a reply, the console closing, the service starting. Each one is
     * the same question ("who should hold the mic now?") and they were each answering it separately.
     *
     * @param holdFloor keep the conversation open — capture again without needing the wake word.
     */
    fun State.settle(holdFloor: Boolean = false): Step = to(
        when {
            !wanted -> Owner.NONE
            console -> Owner.CONSOLE
            holdFloor -> Owner.COMMAND
            else -> Owner.WAKE
        },
    )

    // ---- the one rule ----------------------------------------------------------------------------

    /**
     * Whether this service actually has a recogniser open.
     *
     * [Owner.CONSOLE] and [Owner.SPEAKING] mean the mic is not ours, and [Owner.NONE] means nobody
     * has it — in none of those three is there anything of ours to stop.
     */
    private val Owner.holdsMic: Boolean get() = this == Owner.WAKE || this == Owner.COMMAND

    /**
     * Move to [next], or do nothing if we are already there.
     *
     * The equality check is the whole mechanism. Every double-arm this file exists to prevent is a
     * caller asking for a state it is already in.
     *
     * `RELEASE_MIC` is issued **only when we were actually holding the mic**, and that is not
     * fastidiousness: the console and the wake loop share one `VoskSpeech` instance, so a release
     * issued while the console owns it would stop the console's own recognition. Recovering from a
     * recogniser failure that happened under the console is exactly that case.
     */
    private fun State.to(next: Owner): Step =
        if (next == owner) Step(this, Action.NOTHING)
        else Step(
            copy(owner = next),
            when (next) {
                Owner.WAKE -> Action.START_WAKE
                Owner.COMMAND -> Action.START_COMMAND
                Owner.NONE, Owner.CONSOLE, Owner.SPEAKING ->
                    if (owner.holdsMic) Action.RELEASE_MIC else Action.NOTHING
            },
        )

    // ---- conversation budget ----------------------------------------------------------------------

    /**
     * Whether to keep the floor after a reply — capture again without the wake word.
     *
     * Lifted out of the service as an inline boolean expression over six inputs, which is exactly
     * the shape of thing that is never wrong until it is. The model's explicit close always wins;
     * the budget always wins over the model's request to stay open; and follow-up mode holds the
     * floor unconditionally within the budget, which is what it is for.
     *
     * @param followUp the user asked for one follow-up without re-waking.
     * @param conversation the user asked for a running conversation.
     * @param modelClosed the reply carried an explicit close marker.
     * @param modelOpened the reply asked to keep the floor (a marker, or it ended in a question).
     */
    fun holdFloor(
        followUp: Boolean,
        conversation: Boolean,
        modelClosed: Boolean,
        modelOpened: Boolean,
        turns: Int,
        maxTurns: Int,
        elapsedMs: Long,
        maxMs: Long,
    ): Boolean {
        if (modelClosed) return false
        if (turns >= maxTurns || elapsedMs > maxMs) return false
        return followUp || (conversation && modelOpened)
    }

    /** Whether the conversation has run past either budget, so it should be wound down and said so. */
    fun overBudget(turns: Int, maxTurns: Int, elapsedMs: Long, maxMs: Long): Boolean =
        turns >= maxTurns || elapsedMs > maxMs
}
