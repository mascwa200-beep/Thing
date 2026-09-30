package dev.mascwa.pulse.feature.launcher

/**
 * Whether this start should make the LCARS console the phone's Home without being asked.
 *
 * The owner wants LCARS to BE the home screen, not a switch somebody has to find. It is applied
 * once, on the first start of a build that carries this rule, and never again.
 *
 * ## The three conditions, and why each one is load-bearing
 *
 * - **Never applied before.** It is applied once, not on every launch. Otherwise switching the home
 *   screen off in Settings would be undone at the next start, and the switch would be a control that
 *   does nothing.
 * - **Device owner.** Only a device owner can make LCARS THE Home. Without that, enabling the Home
 *   alias makes Android ask "which Home?" at the next press of the button. An automatic update must
 *   never put that question in front of anyone unasked. A phone that is not device owner keeps the
 *   Settings switch, off by default.
 * - **Not stood down.** A stand-down by the crash-loop guard ([HomeGuardPolicy]) records the time it
 *   happened. Applying the default over that would send the phone straight back into the loop it
 *   just escaped.
 *
 * Pure, so the three are tested rather than remembered.
 */
object HomeDefaultPolicy {

    fun shouldApply(applied: Boolean, deviceOwner: Boolean, stoodDownAtMs: Long?): Boolean =
        !applied && deviceOwner && stoodDownAtMs == null
}
