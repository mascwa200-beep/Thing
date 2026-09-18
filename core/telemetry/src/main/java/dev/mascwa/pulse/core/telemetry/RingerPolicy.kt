package dev.mascwa.pulse.core.telemetry

/**
 * What the ringer should become when a hold goes on or comes off — and, more often, that it should
 * be left exactly where it is.
 *
 * ⚠️ **This is a separate file from the code that calls `AudioManager` on purpose, and the reason is
 * that it carries safety rule 1.** "Transition-only; never stomp a manual change" is a decision, and
 * a decision can be run on a build machine where an `AudioManager` cannot. Left inline in
 * `PhoneHolds` the rule would be three branches nothing could ever exercise, in the one tier that
 * reaches out and changes somebody's handset. Same split, and the same stated reason, as
 * `TranscriptSeal` and `MailNotices`.
 *
 * ⚠️ **It stops at VIBRATE and never reaches SILENT.** Going silent needs the notification-policy
 * grant, which this app does not ask for; [Mode.SILENT] exists here only because the phone can
 * already BE in it when somebody put it there, and knowing that is the whole point of the release
 * rule below.
 */
object RingerPolicy {

    /** The three states an Android ringer can be in, mapped at the platform boundary. */
    enum class Mode { NORMAL, VIBRATE, SILENT }

    /**
     * The mode to set, or null to leave the ringer alone.
     *
     * Asserting moves NORMAL to VIBRATE and does nothing else. A phone already on vibrate needs no
     * help; a phone on SILENT is quieter than this hold asks for, and turning it up to vibrate would
     * be the rule overruling a person to make their phone noisier, which is precisely backwards.
     *
     * ⚠️ **Releasing restores NORMAL only from the VIBRATE this hold set.** If the phone is on
     * SILENT now, somebody reached for it after the hold went on — a more recent and more deliberate
     * statement than any rule inferred — and putting it back to NORMAL would be this layer
     * countermanding them. If it is already NORMAL there is nothing to do. This is the half that is
     * easy to get backwards and impossible to notice: "restore what it was" reads as unconditional,
     * and unconditional is a bug.
     *
     * ⚠️ Nothing here captures a prior value, and that falls out of the rules rather than being a
     * shortcut: the only state this can ever have moved is NORMAL → VIBRATE, so the only thing to
     * restore is NORMAL. A capture would have to survive a process death to be worth anything, and
     * two interleaved capture-and-restores are what removed the alarm-volume action from the tier.
     */
    fun target(current: Mode, asserting: Boolean): Mode? = when {
        asserting && current == Mode.NORMAL -> Mode.VIBRATE
        !asserting && current == Mode.VIBRATE -> Mode.NORMAL
        else -> null
    }
}
