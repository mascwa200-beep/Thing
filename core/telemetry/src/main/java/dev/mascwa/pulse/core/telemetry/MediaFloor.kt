package dev.mascwa.pulse.core.telemetry

/**
 * Who holds the speaker, and what to do about it.
 *
 * The app is about to have two independent players: the radio, which is a process-wide object whose
 * ExoPlayer is built with `handleAudioFocus = true`, and live video, which needs the same. Two
 * players in one process both asking for audio focus is not a stalemate — the second one wins, and
 * the first falls silent. The problem is that **nothing observes it**: `RadioController.state` would
 * still read `ON_AIR`, its foreground notification would still say so, and the user would be looking
 * at a "playing" radio that makes no sound while a video talks over it.
 *
 * That is the same shape [VoiceMachine] was written for — several claimants, one resource, the
 * arbitration spread thinly across call sites where nothing in CI can see it. This is the same
 * answer: decide here, perform there.
 *
 * **Two properties worth the file, both of which CI can state:**
 *
 * 1. **[claim]ing twice in a row produces one stop and then nothing.** Tapping a channel that is
 *    already playing must not tear down and rebuild it.
 * 2. **[released] from an owner that no longer holds the floor does nothing.** This is the one that
 *    matters, and it is not tidiness — see its own note.
 *
 * ⚠️ **Deliberately not mirrored to the desktop.** The companion has no radio, so there is only ever
 * one player there and nothing to arbitrate. A mirror would be a method with no caller on the far
 * side, which is precisely the defect the Oracle arc was written to remove.
 */
object MediaFloor {

    /** Who currently has the speaker. Exactly one claimant, or nobody. */
    enum class Owner {
        /** Nobody is playing. */
        NONE,

        /** The radio, which keeps playing in the background behind a foreground service. */
        RADIO,

        /** A live video feed, which plays only while its surface is on screen. */
        VIDEO,

        /** On-demand playback — a resolved video or track with a timeline. */
        ONDEMAND,
    }

    /** What the caller must do to make the world match the state. */
    enum class Action {
        /** Nothing. The speaker is already where it belongs. */
        NOTHING,

        /** Stop the radio — something else is taking the speaker. */
        STOP_RADIO,

        /** Stop the live video — something else is taking the speaker. */
        STOP_VIDEO,

        /** Stop on-demand playback — something else is taking the speaker. */
        STOP_ONDEMAND,
    }

    data class State(val owner: Owner = Owner.NONE)

    /** The next state, and the one thing to do about it. */
    data class Step(val state: State, val action: Action)

    // ---- events ---------------------------------------------------------------------------------

    /**
     * [who] wants the speaker. Whoever had it is told to stop.
     *
     * Claiming what you already hold is [Action.NOTHING], which is what makes a second tap on a
     * playing channel a no-op rather than a rebuild.
     */
    fun State.claim(who: Owner): Step = to(who)

    /**
     * [who] reports that it has stopped. A **report**, never a request — it can only free the floor,
     * and it never issues a stop of its own.
     *
     * ⚠️ **The owner check is load-bearing, and the reason is re-entrancy.** Claiming for video
     * issues [Action.STOP_RADIO]; performing that runs the radio's teardown; the radio's teardown
     * reports its own release — and that report arrives *after* the floor has already moved to
     * video. An unconditional release would blank the floor while video is playing, so the next
     * claim would find nobody to stop and both would sound at once. The late report is exactly the
     * message that must be ignored.
     */
    fun State.released(who: Owner): Step =
        if (owner == who) Step(State(Owner.NONE), Action.NOTHING) else Step(this, Action.NOTHING)

    // ---- the one rule ----------------------------------------------------------------------------

    /**
     * Move to [next], or do nothing if we are already there.
     *
     * The equality check is the whole mechanism, as in [VoiceMachine]: every redundant teardown this
     * file exists to prevent is a caller asking for a state it is already in.
     */
    private fun State.to(next: Owner): Step =
        if (next == owner) Step(this, Action.NOTHING)
        else Step(State(next), stopOf(owner))

    /**
     * What it takes to stop [previous].
     *
     * Spelled out per owner rather than carried on the action, so adding a third claimant is a
     * compile error here instead of a silent fall-through to "stop nothing".
     */
    private fun stopOf(previous: Owner): Action = when (previous) {
        Owner.RADIO -> Action.STOP_RADIO
        Owner.VIDEO -> Action.STOP_VIDEO
        Owner.ONDEMAND -> Action.STOP_ONDEMAND
        Owner.NONE -> Action.NOTHING
    }

    // ---- what to tell the user --------------------------------------------------------------------

    /**
     * The line to show when a claim displaced something, or null when nothing was displaced.
     *
     * Audio stopping on its own is alarming unless it is explained, and the explanation belongs in
     * one tested place rather than being written twice in two screens that would drift.
     */
    fun displacedNote(previous: Owner, next: Owner): String? {
        if (previous == next || previous == Owner.NONE || next == Owner.NONE) return null
        // What stopped is the alarming half; who took the speaker is said from the taker's side.
        val stopped = when (previous) {
            Owner.RADIO -> "Radio stopped"
            Owner.VIDEO -> "Live feed stopped"
            Owner.ONDEMAND -> "Playback stopped"
            Owner.NONE -> return null
        }
        val taker = when (next) {
            Owner.RADIO -> "the radio"
            Owner.VIDEO -> "the live feed"
            Owner.ONDEMAND -> "the player"
            Owner.NONE -> return null
        }
        return "$stopped — $taker has the speaker."
    }
}
