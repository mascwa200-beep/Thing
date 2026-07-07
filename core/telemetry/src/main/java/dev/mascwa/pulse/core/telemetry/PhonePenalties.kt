package dev.mascwa.pulse.core.telemetry

/**
 * PHONE PENALTIES — the survival needs bleed into the phone itself. Let a need fall to critical and the game
 * revokes a real device capability (via the Device-Owner [DevicePolicyController] on-device) until you tend
 * that need in the real world — the ultimate "the game bleeds into reality." This file is the PURE, CI-tested
 * decision layer: given the current needs, which locks should be enforced right now. The Android layer
 * ([PhonePenaltyController]) turns the returned set into DevicePolicyManager calls.
 *
 * Safety is designed in and load-bearing:
 *  - **Opt-in.** The whole subsystem is gated by a master toggle (default OFF) in the app layer; this core is
 *    inert until asked.
 *  - **Hysteresis.** A need is penalised only once it drops to [ENGAGE_AT] (critical), and stays penalised
 *    until it climbs back to [RELEASE_AT] (healthy) — so a need hovering near the line can't flap the lock.
 *  - **Reversible.** Every [PhoneLock] maps to a reversible DevicePolicy flag; tending the need lifts it.
 *  - **The core never expresses an irreversible action** (no wipe, no "lock out of Pulse"): the Android layer
 *    additionally refuses to suspend Pulse itself, the dialer, or anything that would strand the owner.
 *
 * "Confirmed the need was taken care of" = the owner performing the real-world care action (DRINK / EAT /
 * REST / WASH / BRUSH / FLOSS), which restores the need past [RELEASE_AT] and releases that need's lock.
 */
object PhonePenalties {

    /** A need at/below this (critical neglect) engages its penalty. Matches [LifeStats.NEED_CRITICAL]. */
    const val ENGAGE_AT = LifeStats.NEED_CRITICAL

    /** A penalised need releases only once it climbs back to this (healthy). Matches [LifeStats.NEED_HEALTHY]. */
    const val RELEASE_AT = LifeStats.NEED_HEALTHY

    /**
     * A revocable phone capability. Each maps to a reversible Device-Owner control in the app layer. Ordered
     * least → most disruptive; [blurb] is a one-liner for the UI.
     */
    enum class PhoneLock(val label: String, val blurb: String) {
        PAUSE_DISTRACTIONS("Distraction apps", "Your chosen time-sink apps are paused"),
        BLOCK_INSTALLS("App installs", "You can't install new apps"),
        LOCK_QUICK_SETTINGS("Quick settings", "The notification shade & quick settings are locked"),
        DISABLE_CAMERA("Camera", "The camera is switched off device-wide"),
        LOCK_VOLUME("Media volume", "The volume is locked where it is"),
        BLOCK_SCREENSHOTS("Screenshots", "Screen capture is blocked"),
    }

    /**
     * The default need → lock mapping (retunable per-need in the app layer). Chosen to be thematic and,
     * roughly, least-disruptive for the needs that lapse most often.
     */
    val DEFAULT_MAPPING: Map<NeedKind, PhoneLock> = mapOf(
        NeedKind.HYDRATION to PhoneLock.PAUSE_DISTRACTIONS,
        NeedKind.NOURISHMENT to PhoneLock.BLOCK_INSTALLS,
        NeedKind.ENERGY to PhoneLock.LOCK_QUICK_SETTINGS,
        NeedKind.HYGIENE to PhoneLock.DISABLE_CAMERA,
        NeedKind.BRUSHING to PhoneLock.LOCK_VOLUME,
        NeedKind.FLOSSING to PhoneLock.BLOCK_SCREENSHOTS,
    )

    /**
     * The needs under penalty right now, applying hysteresis against [currentlyPenalised]: a need engages when
     * its value falls to [ENGAGE_AT], and only clears once it recovers to [RELEASE_AT]; in between it holds its
     * prior state. Pass the previously-penalised set (empty on a cold start) and persist the result.
     */
    fun penalisedNeeds(p: LifeProfile, currentlyPenalised: Set<NeedKind>): Set<NeedKind> =
        NeedKind.entries.filterTo(mutableSetOf()) { need ->
            val v = need.value(p)
            when {
                v <= ENGAGE_AT -> true
                v >= RELEASE_AT -> false
                else -> need in currentlyPenalised
            }
        }

    /** The locks to enforce for a set of [penalised] needs under [mapping] (unmapped needs contribute nothing). */
    fun locksFor(penalised: Set<NeedKind>, mapping: Map<NeedKind, PhoneLock> = DEFAULT_MAPPING): Set<PhoneLock> =
        penalised.mapNotNullTo(mutableSetOf()) { mapping[it] }

    /**
     * Whether the harsher "kiosk" pin should be engaged — true whenever ANY need is under penalty, so the owner
     * must tend it. The app layer keeps the emergency dialer reachable and un-pins the moment nothing is penalised.
     */
    fun kioskEngaged(penalised: Set<NeedKind>): Boolean = penalised.isNotEmpty()

    /** A one-line prompt telling the owner how to lift a penalised [need]'s lock, e.g. "DRINK to get hydrated". */
    fun restoreHint(need: NeedKind): String = "${need.verb} to get ${need.restoreLabel}"
}
