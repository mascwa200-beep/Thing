package dev.mascwa.pulse.feature.phone

/**
 * Missed calls, now that LCARS posts them instead of Android — the two decisions that make that
 * safe, as arithmetic.
 *
 * ⚠️ **Why the claim is a rule at all.** Android's Telecom stops posting its own missed-call
 * notification the moment the default phone app has a receiver for `SHOW_MISSED_CALLS_NOTIFICATION`
 * (`MissedCallNotifierImpl.shouldManageNotificationThroughDefaultDialer`). It then SENDS that broadcast
 * with `READ_PHONE_STATE` required of the receiver. So a receiver that is present but cannot receive
 * — the permission revoked, notifications switched off — is the worst possible state: Android has
 * stepped aside, and nobody posts anything. A missed call simply vanishes.
 *
 * So the receiver ships DISABLED and is enabled only while [claim] says every condition holds, and a
 * disabled receiver is invisible to Telecom's query. **Whenever LCARS cannot post a missed call,
 * Android posts its own** — structurally, not by anything remembering to.
 */
object MissedCalls {

    /**
     * The channel missed-call notices are posted on. Its own id — never a retired one, since Android
     * freezes a channel's settings once created — and named here, in a pure file, so the home
     * console's notice panel can recognise it without an Android type.
     */
    const val CHANNEL = "channel_missed_calls"

    enum class Claim {
        /** LCARS posts missed calls. */
        LCARS,
        NOT_PHONE_APP,
        NO_PHONE_STATE,
        NOTIFICATIONS_OFF,
        STOOD_DOWN,
    }

    /**
     * Who posts missed calls. Every condition must hold for LCARS to; the first that fails is the
     * reason, checked in the order a person would fix them.
     */
    fun claim(isPhoneApp: Boolean, readPhoneState: Boolean, notificationsAllowed: Boolean, stoodDown: Boolean): Claim = when {
        stoodDown -> Claim.STOOD_DOWN
        !isPhoneApp -> Claim.NOT_PHONE_APP
        !readPhoneState -> Claim.NO_PHONE_STATE
        !notificationsAllowed -> Claim.NOTIFICATIONS_OFF
        else -> Claim.LCARS
    }

    /** What Settings says about [claim]. */
    fun sentence(claim: Claim): String = when (claim) {
        Claim.LCARS -> "LCARS posts them"
        Claim.NOT_PHONE_APP -> "Android posts them — LCARS is not the phone app"
        Claim.NO_PHONE_STATE -> "Android posts them — LCARS may not read the phone's state"
        Claim.NOTIFICATIONS_OFF -> "Android posts them — LCARS's notifications are switched off"
        Claim.STOOD_DOWN -> "Android posts them — the LCARS call screen stood down after repeated crashes"
    }

    /** What a missed-call notification says. */
    data class Text(val title: String, val body: String, val callBack: Boolean)

    /**
     * The notification for [count] missed calls, the latest from [name] at [number]; null means there
     * is nothing to show and any notice up should go (Telecom sends 0 when they have been seen).
     *
     * CALL BACK is offered only for a single missed call with a number: with several, the one it would
     * ring is ambiguous, which is also Telecom's own rule.
     */
    fun text(count: Int, name: String?, number: String?): Text? {
        if (count <= 0) return null
        val dialable = PhoneMatch.key(number) != null
        val who = name?.trim()?.takeIf { it.isNotEmpty() } ?: number?.trim()?.takeIf { dialable } ?: "Private number"
        return if (count == 1) {
            Text(title = "Missed call", body = who, callBack = dialable)
        } else {
            Text(title = "$count missed calls", body = "Latest: $who", callBack = false)
        }
    }
}
