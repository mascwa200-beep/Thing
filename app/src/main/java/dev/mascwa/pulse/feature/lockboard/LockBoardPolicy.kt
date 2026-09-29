package dev.mascwa.pulse.feature.lockboard

/**
 * When the lock-screen board may put itself in front of you, as rules a JVM test can hold.
 *
 * The board is the widget's full situation board, drawn over the lock screen so it is the first
 * thing seen when the screen comes on. It is an activity that shows while the phone is locked, and
 * everything about that is easy to get subtly wrong in a way that is only visible on a phone — so
 * each decision lives here, as arithmetic over plain facts, and the Android layer only gathers the
 * facts and obeys.
 *
 * ## Why it is launched when the screen goes OFF
 *
 * A screen-on broadcast arrives after the lock screen has already drawn, so a board started then
 * appears a fraction of a second too late to be "the first thing I see". Started as the screen goes
 * off, it is already on top when the screen comes back, which is what the request asks for. The
 * screen-on start stays as a safety net, for the case the first one could not happen.
 *
 * Pure — no Android import — so the rules are tested rather than remembered.
 */
object LockBoardPolicy {

    enum class Trigger { SCREEN_OFF, SCREEN_ON }

    /** The facts the trigger gathers at the moment a screen broadcast arrives. */
    data class Facts(
        /** The Settings switch. */
        val enabled: Boolean,
        /** Whether this app may start an activity from the background at all — see [canLaunch]. */
        val canLaunch: Boolean,
        /** A call is ringing or in progress (the audio mode is not NORMAL). */
        val inCall: Boolean,
        /** An alarm or a ringtone is sounding right now — something else woke the phone. */
        val alertSounding: Boolean,
        /** One of this app's own takeovers — the red alert, breaking news — is alive. */
        val takeoverAlive: Boolean,
        /** The board itself is already up. */
        val boardAlive: Boolean,
        /** The keyguard is locked. Only read on screen-on: at screen-off it is not decided yet. */
        val keyguardLocked: Boolean,
    )

    /**
     * Null to launch, otherwise the reason not to — a sentence, so the one place the reason is
     * recorded can say it rather than a code.
     *
     * The ORDER is the design, and each rule is here because of what goes wrong without it:
     *
     * - **A call comes before everything.** A phone held to an ear turns its screen off on the
     *   proximity sensor, so screen-off during a call is routine — and a board started then would be
     *   waiting OVER the call screen when the phone comes away from the face.
     * - **A takeover is never covered.** The condition-red alert keeps its screen until it is
     *   acknowledged, and a newer activity is drawn on top of it; starting the board would bury an
     *   official emergency under a weather readout.
     * - **An alarm or a ringtone means something else woke the phone**, and what it woke it for is
     *   on screen. The board has nothing more urgent to say than an alarm.
     * - **On screen-on, only in front of a LOCKED phone.** A screen that comes back within the lock
     *   delay returns to whatever was open, and the board has no business in front of it.
     */
    fun decide(trigger: Trigger, f: Facts): String? = when {
        !f.enabled -> "switched off in Settings"
        !f.canLaunch -> "Android will not let LCARS open a screen from the background"
        f.inCall -> "a call is ringing or in progress"
        f.takeoverAlive -> "an alert or breaking story is already on screen"
        f.alertSounding -> "an alarm or ringtone is sounding"
        f.boardAlive -> ALREADY_UP
        trigger == Trigger.SCREEN_ON && !f.keyguardLocked -> NOT_LOCKED
        else -> null
    }

    /** The board is up and the screen went off or on again: the board working, not failing. */
    const val ALREADY_UP = "the board is already up"

    /** The screen came back within the lock delay: the phone was never locked, so nothing to cover. */
    const val NOT_LOCKED = "the phone is not locked"

    // ── what became of a screen-off ─────────────────────────────────────────────────────────────

    /**
     * What the log records about one screen-off, so Settings and a debug report can say why the
     * board was or was not there. Without it, a board that did not appear is indistinguishable from
     * one that was never asked for, and the owner can only report "I still had to swipe".
     */
    enum class Outcome {
        /** Asked Android to open the board; not yet known whether it did. */
        REQUESTED,

        /** The board's screen came into being. */
        SHOWN,

        /** [decide] said no, with a reason worth keeping. */
        SKIPPED,

        /**
         * Asked, and the board never came into being. ⚠️ A background start that Android refuses is
         * DROPPED, not thrown, so there is no error to catch: the only evidence is its absence.
         */
        REFUSED,
    }

    /**
     * Whether a skip is worth recording. The two ordinary ones are not: [ALREADY_UP] happens on
     * every screen-on while the board is working, and recording it would overwrite the SHOWN that
     * says so.
     */
    fun worthRecording(reason: String): Boolean = reason != ALREADY_UP && reason != NOT_LOCKED

    /**
     * Checked a moment after the screen comes on: a board still only REQUESTED — asked for, never
     * created — was refused by Android.
     *
     * ⚠️ **Resolved at screen-ON, not on a timer at screen-off.** Android may hold back creating an
     * activity until the display wakes, so "not created two seconds after screen-off" would report a
     * refusal that is not one. By the time the screen has been on for [REFUSAL_GRACE_MS] it either
     * exists or it is not coming.
     */
    fun refused(last: Outcome?, boardAlive: Boolean): Boolean = last == Outcome.REQUESTED && !boardAlive

    /** How long after the screen comes on a requested board has to appear before it counts as refused. */
    const val REFUSAL_GRACE_MS = 1_500L

    /**
     * Whether an outcome goes into the activity log that travels with a debug report.
     *
     * Only changes, so a day of screen toggles cannot flood a 300-entry log: a refusal or a skip is
     * logged when it differs from the last one logged, and SHOWN only when it follows one of those —
     * the recovery is the useful line, and an ordinary shown board is the normal state.
     */
    fun worthLogging(now: Outcome, reason: String?, lastLogged: Pair<Outcome, String?>?): Boolean = when (now) {
        Outcome.REQUESTED -> false
        Outcome.SHOWN -> lastLogged != null && lastLogged.first != Outcome.SHOWN
        Outcome.SKIPPED, Outcome.REFUSED -> (now to reason) != lastLogged
    }

    /** The outcome as a sentence, for the Settings row and the log. */
    fun describe(outcome: Outcome, reason: String?): String = when (outcome) {
        Outcome.REQUESTED -> "opening as the screen went off"
        Outcome.SHOWN -> "shown"
        Outcome.SKIPPED -> "not shown — ${reason ?: "no reason recorded"}"
        Outcome.REFUSED -> "Android refused to open it"
    }

    /**
     * Whether Android will let this app start an activity while it has no screen of its own.
     *
     * ⚠️ **A device owner may; "display over other apps" is NOT enough on Android 15+.** Holding
     * `SYSTEM_ALERT_WINDOW` used to exempt an app from the background-activity-start rules; from
     * Android 15, for an app targeting 35 or higher, it counts only while the app has an overlay
     * window actually VISIBLE — and at screen-off there is none. This app targets 35, so on the
     * owner's phone the device-owner exemption is the one that carries it. A start refused by that
     * rule is DROPPED rather than thrown, which is why this is asked before trying rather than
     * learned from a failure that never arrives.
     */
    fun canLaunch(deviceOwner: Boolean, overlayGranted: Boolean, sdkInt: Int, targetSdk: Int): Boolean =
        deviceOwner || (overlayGranted && (sdkInt < OVERLAY_RULE_SDK || targetSdk < OVERLAY_RULE_SDK))

    /**
     * Whether a board that has just become the resumed screen should get out of the way at once.
     *
     * ⚠️ **Only while the screen is ON.** A board started at screen-off is resumed while the display
     * is still dark and the lock delay has not expired, so the phone reads "not locked" — finishing
     * then would throw away the very board that was started for the screen coming back. It is asked
     * again when the screen comes on, and only an unlocked, lit phone sends it away.
     */
    fun finishOnResume(interactive: Boolean, keyguardLocked: Boolean): Boolean =
        interactive && !keyguardLocked

    /**
     * Whether the board last drawn is old enough to be worth loading again before it is shown.
     *
     * A stamp in the future counts as stale rather than fresh, for the reason `PassThrottle` gives:
     * a clock that was briefly fast would otherwise freeze the board until the clock caught up.
     */
    fun isStale(atMs: Long?, nowMs: Long): Boolean =
        atMs == null || (nowMs - atMs) !in 0..STALE_AFTER_MS

    /** Five minutes: a board older than that is re-read before it is looked at again. */
    const val STALE_AFTER_MS = 5 * 60_000L

    /** Android 15 (API 35), where an overlay grant stopped exempting background activity starts. */
    const val OVERLAY_RULE_SDK = 35
}
