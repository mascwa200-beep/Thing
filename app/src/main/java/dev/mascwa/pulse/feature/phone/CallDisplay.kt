package dev.mascwa.pulse.feature.phone

/**
 * What the call screen shows for a call, and which of its buttons may be pressed.
 *
 * Pure — every Telecom constant is restated below — so the rules run under JUnit. The rules that
 * matter most are the ones that stop a button doing the wrong thing on a live call:
 *
 * - ⚠️ **ANSWER and DECLINE exist only while the call is ringing.** Anywhere else, "decline" would
 *   reject a call somebody is on.
 * - **A button appears only when the network says the call can do it.** HOLD needs
 *   `CAPABILITY_HOLD`, MERGE needs `CAPABILITY_MERGE_CONFERENCE`, MUTE needs `CAPABILITY_MUTE`. A
 *   button the network will refuse is a button that looks broken.
 * - **ADD CALL only while this is the only call.** Telecom manages two calls at most (one held); a
 *   third is refused.
 */
object CallDisplay {

    // ⚠️ `android.telecom.Call.STATE_*`, restated. Checked with `javap -constants` against the
    // platform-35 SDK stub: NEW 0, DIALING 1, RINGING 2, HOLDING 3, ACTIVE 4, DISCONNECTED 7,
    // SELECT_PHONE_ACCOUNT 8, CONNECTING 9, DISCONNECTING 10, PULLING_CALL 11, AUDIO_PROCESSING 12,
    // SIMULATED_RINGING 13.
    const val STATE_NEW = 0
    const val STATE_DIALING = 1
    const val STATE_RINGING = 2
    const val STATE_HOLDING = 3
    const val STATE_ACTIVE = 4
    const val STATE_DISCONNECTED = 7
    const val STATE_SELECT_PHONE_ACCOUNT = 8
    const val STATE_CONNECTING = 9
    const val STATE_DISCONNECTING = 10
    const val STATE_PULLING_CALL = 11
    const val STATE_AUDIO_PROCESSING = 12
    const val STATE_SIMULATED_RINGING = 13

    // `Call.Details.CAPABILITY_*`, checked the same way: HOLD 1, SUPPORT_HOLD 2, MERGE_CONFERENCE 4,
    // SWAP_CONFERENCE 8, MUTE 64.
    const val CAPABILITY_HOLD = 1
    const val CAPABILITY_SUPPORT_HOLD = 2
    const val CAPABILITY_MERGE_CONFERENCE = 4
    const val CAPABILITY_SWAP_CONFERENCE = 8
    const val CAPABILITY_MUTE = 64

    enum class Phase { RINGING, DIALING, CONNECTING, ACTIVE, HOLDING, ENDING, ENDED, WAITING }

    fun phase(state: Int): Phase = when (state) {
        STATE_RINGING, STATE_SIMULATED_RINGING -> Phase.RINGING
        STATE_DIALING -> Phase.DIALING
        STATE_NEW, STATE_CONNECTING, STATE_PULLING_CALL -> Phase.CONNECTING
        STATE_ACTIVE -> Phase.ACTIVE
        STATE_HOLDING -> Phase.HOLDING
        STATE_DISCONNECTING -> Phase.ENDING
        STATE_DISCONNECTED -> Phase.ENDED
        // Choosing a SIM, or a call being screened: it exists and nothing can be done to it yet.
        else -> Phase.WAITING
    }

    /** Which buttons the screen offers. */
    data class Controls(
        val answer: Boolean = false,
        val decline: Boolean = false,
        val end: Boolean = false,
        val mute: Boolean = false,
        val keypad: Boolean = false,
        val hold: Boolean = false,
        val resume: Boolean = false,
        val addCall: Boolean = false,
        val merge: Boolean = false,
        val swap: Boolean = false,
        val audioRoute: Boolean = false,
    )

    /**
     * The buttons for a call in [phase] with [capabilities], alongside [otherCalls] other calls, of
     * which [otherHeld] is true when one of them is on hold.
     */
    fun controls(phase: Phase, capabilities: Int, otherCalls: Int, otherHeld: Boolean): Controls {
        fun can(bit: Int) = (capabilities and bit) != 0
        return when (phase) {
            Phase.RINGING -> Controls(answer = true, decline = true)
            Phase.DIALING, Phase.CONNECTING -> Controls(end = true, mute = can(CAPABILITY_MUTE), audioRoute = true)
            Phase.ACTIVE -> Controls(
                end = true,
                mute = can(CAPABILITY_MUTE),
                keypad = true,
                hold = can(CAPABILITY_HOLD),
                addCall = otherCalls == 0,
                merge = otherCalls > 0 && can(CAPABILITY_MERGE_CONFERENCE),
                swap = otherCalls > 0 && (otherHeld || can(CAPABILITY_SWAP_CONFERENCE)),
                audioRoute = true,
            )
            Phase.HOLDING -> Controls(end = true, resume = can(CAPABILITY_HOLD) || can(CAPABILITY_SUPPORT_HOLD))
            Phase.WAITING -> Controls(end = true)
            Phase.ENDING, Phase.ENDED -> Controls()
        }
    }

    /**
     * The line under the caller's name. [connectMs] is Telecom's connect time, 0 before the call
     * connects.
     */
    fun label(phase: Phase, connectMs: Long, nowMs: Long): String = when (phase) {
        Phase.RINGING -> "INCOMING CALL"
        Phase.DIALING -> "CALLING"
        Phase.CONNECTING -> "CONNECTING"
        Phase.ACTIVE -> if (connectMs > 0) elapsed(connectMs, nowMs) else "CONNECTED"
        Phase.HOLDING -> "ON HOLD"
        Phase.ENDING -> "ENDING"
        Phase.ENDED -> "CALL ENDED"
        Phase.WAITING -> "PLEASE WAIT"
    }

    /**
     * How long a call has lasted: 00:42, 12:05, 1:02:03. A connect time in the future — a clock
     * corrected backwards mid-call — reads 00:00, never a negative length.
     */
    fun elapsed(connectMs: Long, nowMs: Long): String {
        val s = ((nowMs - connectMs) / 1_000).coerceAtLeast(0)
        val h = s / 3_600
        val m = (s % 3_600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${two(m)}:${two(sec)}" else "${two(m)}:${two(sec)}"
    }

    private fun two(n: Long): String = if (n < 10) "0$n" else "$n"

    // ── where the sound goes ─────────────────────────────────────────────────────────────────────

    /** Where a call's audio is going, in one vocabulary for both of Android's route APIs. */
    enum class Route { EARPIECE, SPEAKER, BLUETOOTH, WIRED, STREAMING, UNKNOWN }

    // ⚠️ The two APIs number the routes DIFFERENTLY, which is why this exists. `CallAudioState.ROUTE_*`
    // are bits (EARPIECE 1, BLUETOOTH 2, WIRED_HEADSET 4, SPEAKER 8, STREAMING 16) and
    // `CallEndpoint.TYPE_*` are plain values (EARPIECE 1, BLUETOOTH 2, WIRED_HEADSET 3, SPEAKER 4,
    // STREAMING 5, UNKNOWN -1). Both checked with `javap -constants`. Mapping one with the other's
    // table would put a speaker call on the wired headset.

    fun routeFromLegacy(route: Int): Route = when (route) {
        1 -> Route.EARPIECE
        2 -> Route.BLUETOOTH
        4 -> Route.WIRED
        8 -> Route.SPEAKER
        16 -> Route.STREAMING
        else -> Route.UNKNOWN
    }

    /** The routes in a `CallAudioState.supportedRouteMask`. */
    fun routesFromLegacyMask(mask: Int): Set<Route> =
        listOf(1, 2, 4, 8, 16).filter { (mask and it) != 0 }.map(::routeFromLegacy).toSet()

    fun legacyRoute(route: Route): Int? = when (route) {
        Route.EARPIECE -> 1
        Route.BLUETOOTH -> 2
        Route.WIRED -> 4
        Route.SPEAKER -> 8
        Route.STREAMING -> 16
        Route.UNKNOWN -> null
    }

    fun routeFromEndpoint(type: Int): Route = when (type) {
        1 -> Route.EARPIECE
        2 -> Route.BLUETOOTH
        3 -> Route.WIRED
        4 -> Route.SPEAKER
        5 -> Route.STREAMING
        else -> Route.UNKNOWN
    }

    /**
     * Where SPEAKER sends the sound when pressed while [current]: onto the speaker, or — when the
     * speaker is already on — back to the best private route there is. A headset or a car beats the
     * earpiece, because somebody who connected one meant to use it.
     */
    fun speakerToggle(current: Route, available: Set<Route>): Route = when {
        current != Route.SPEAKER -> Route.SPEAKER
        Route.BLUETOOTH in available -> Route.BLUETOOTH
        Route.WIRED in available -> Route.WIRED
        else -> Route.EARPIECE
    }

    /** Whether the proximity sensor should blank the screen: only while the phone is held to an ear. */
    fun heldToEar(route: Route, phase: Phase): Boolean =
        route == Route.EARPIECE && phase in setOf(Phase.DIALING, Phase.CONNECTING, Phase.ACTIVE)
}
