package dev.mascwa.pulse.data.sensing

import dev.mascwa.pulse.core.telemetry.AmbientAction
import dev.mascwa.pulse.core.telemetry.AmbientDecision
import dev.mascwa.pulse.core.telemetry.AmbientRefusal

/**
 * A layer that can be asked whether this particular handset can carry out an action.
 *
 * ⚠️ **Null means "nothing to say", not "yes".** Each implementation speaks only about its own
 * actions and returns null for everything else, which is what lets several be consulted in turn
 * without any of them needing to know the others exist. An action is blocked when ANY layer names a
 * reason, and passes when none does.
 */
interface HoldCapability {
    /** Why this phone cannot do it, in the words a person would use, or null. */
    fun cannotDo(action: AmbientAction): String?
}

/**
 * The same decision with anything this particular handset cannot carry out moved from `allowed` into
 * `refused`, each with the reason.
 *
 * ⚠️ **Moved rather than dropped, and that is the point.** `AmbientRules.permit` already keeps what
 * it refused so the scanner can say "would pause your distracting apps · device-owner controls are
 * off" instead of going quiet, and "no torch on this phone" is the same kind of fact arriving from a
 * different direction. Filtering it away instead would leave a rule that fires, a hold that never
 * appears and nothing anywhere saying why.
 *
 * ⚠️ It is deliberately NOT inside `AmbientRules.permit`. That function is pure and lives in a
 * module with no Android in it — which is what lets CI hold every tier rule — and what a given
 * handset physically has is not something a pure function can be told without being handed the
 * handset. So the tier decision stays in the core and the hardware decision stays here, and they
 * compose.
 *
 * ⚠️ **A vararg rather than one argument per tier, and that is not generality for its own sake.**
 * The tiers are switched on one at a time and a tier's capability check is the thing most likely to
 * be forgotten when a new one lands — leaving a rule that fires, a hold nothing can carry out, and
 * the scanner reporting it as held. Taking a list means adding a tier is one argument at the call
 * site rather than an edit to this function, and this function never learns which tiers exist.
 */
fun AmbientDecision.minusWhatThisHandsetCannotDo(vararg layers: HoldCapability): AmbientDecision {
    if (layers.isEmpty()) return this
    val blocked = allowed.mapNotNull { i ->
        layers.firstNotNullOfOrNull { it.cannotDo(i.action) }?.let { AmbientRefusal(i, it) }
    }
    if (blocked.isEmpty()) return this
    val barred = blocked.mapTo(mutableSetOf()) { it.intent.action }
    return copy(allowed = allowed.filter { it.action !in barred }, refused = refused + blocked)
}
