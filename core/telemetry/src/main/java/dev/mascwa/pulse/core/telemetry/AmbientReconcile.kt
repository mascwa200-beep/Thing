package dev.mascwa.pulse.core.telemetry

/**
 * Turning what the rules want into what changes — the arithmetic behind the acting layer.
 *
 * [AmbientRules] answers "what should be held right now?" afresh on every heartbeat, with no memory.
 * This answers the question that follows: given what is ALREADY held, what has to be asserted, what
 * has to be released, and what must be released whether the rules still want it or not.
 *
 * ⚠️ **Pure, and separated from the Android actuator on purpose.** What a phone does with a hold —
 * silencing a ringer, pausing a video — cannot be run on a build machine, but *deciding* to release
 * something can, and every one of the safety rules below is a decision rather than a side effect.
 * This is the [TranscriptSeal] split: put the consequential rule where a test can reach it, and
 * leave the platform call in the thin shell around it.
 *
 * Three of the six safety rules live here and the other three do not, which is worth saying plainly
 * so nobody looks for them:
 *
 * | rule | where |
 * |---|---|
 * | transition-only — never re-assert, never stomp | [reconcile], and it is most of the file |
 * | guaranteed release at the backstop | [reconcile], via [AmbientIntent.untilMs] |
 * | everything visible and undoable | [Released.why], so a surface can say which of four things happened |
 * | survive death — reconcile from disk first | the actuator: this has no disk |
 * | sensing down ⇒ release everything | the actuator, by calling [releaseAll] |
 * | never block an emergency | [AmbientAction] itself, by what is absent from it |
 */
object AmbientReconcile {

    /** What is held, and what is barred from being re-held. */
    data class HoldState(
        /**
         * Every hold in force, each carrying the instant it was ASSERTED.
         *
         * ⚠️ That instant is the whole reason this is a list of intents rather than a set of
         * actions — see the note in [reconcile] about why a held intent is never replaced.
         */
        val held: List<AmbientIntent> = emptyList(),
        /**
         * Actions released by the backstop whose rule is still asking for them.
         *
         * ⚠️ **Without this the backstop is a no-op.** A rule that never stops firing would have its
         * hold released on one heartbeat and asserted again on the next, which is a release in name
         * only. An action stays barred until its rule stops wanting it at least once — at which
         * point the situation has genuinely changed and asking again is legitimate.
         */
        val expiredWhileWanted: Set<AmbientAction> = emptySet(),
    ) {
        fun isHeld(action: AmbientAction): Boolean = held.any { it.action == action }

        /** The set form, for a consumer that only asks "is this on?". */
        fun actions(): Set<AmbientAction> = held.mapTo(mutableSetOf()) { it.action }
    }

    /** Why a hold ended. Every one of these is a different sentence on a surface. */
    enum class ReleaseReason {
        /** The rule stopped asking. The ordinary path, and the one nobody needs telling about. */
        NO_LONGER_WANTED,

        /**
         * It ran to [AmbientIntent.untilMs]. Worth saying out loud: it means a rule went on wanting
         * something for hours, which is either a genuinely long situation or a rule to look at.
         */
        BACKSTOP,

        /** Sensing was switched off, or the watch stopped. Safety rule 4. */
        STOOD_DOWN,

        /** The person pressed RELEASE. */
        BY_HAND,
    }

    data class Released(val intent: AmbientIntent, val why: ReleaseReason)

    /** What changed on this heartbeat, and the state to carry into the next one. */
    data class Reconciliation(
        val state: HoldState,
        val asserted: List<AmbientIntent> = emptyList(),
        val released: List<Released> = emptyList(),
    ) {
        /** Nothing to do — the overwhelmingly common answer, and not a failure. */
        val quiet: Boolean get() = asserted.isEmpty() && released.isEmpty()
    }

    /**
     * Work out the difference between what is held and what is wanted.
     *
     * ⚠️ **A held intent is never replaced by the fresh one that wants it.** The obvious
     * implementation takes [wanted] as the new truth and keeps the intents from it — and that
     * advances `fromMs` on every heartbeat, so [AmbientIntent.untilMs] moves with it and the
     * backstop can never arrive. A four-hour ceiling would silently become "four hours after the
     * rule last stopped", which is never. The original intent is kept, with its original instant and
     * its original sentence; only [wanted]'s ACTIONS are read.
     *
     * ⚠️ **Nothing is asserted twice.** That is the transition-only rule, and it matters beyond
     * tidiness: re-asserting means re-applying, and re-applying a hold on something the person has
     * since changed by hand stomps their choice. The deleted `PhonePenaltyController` learned that
     * one the hard way and it is recorded in CLAUDE.md.
     *
     * @param wanted this heartbeat's permitted intents, straight from [AmbientRules.permit].
     */
    fun reconcile(state: HoldState, wanted: List<AmbientIntent>, nowMs: Long): Reconciliation {
        val wantedActions = wanted.mapTo(mutableSetOf()) { it.action }

        val keep = mutableListOf<AmbientIntent>()
        val released = mutableListOf<Released>()
        val expired = mutableSetOf<AmbientAction>()

        for (h in state.held) {
            when {
                h.action !in wantedActions -> released += Released(h, ReleaseReason.NO_LONGER_WANTED)
                nowMs >= h.untilMs -> {
                    released += Released(h, ReleaseReason.BACKSTOP)
                    expired += h.action
                }
                else -> keep += h
            }
        }

        // ⚠️ A bar only survives while its rule is still asking. Once the rule stops, the situation
        // has changed and the next ask is a fresh one — so this is filtered against `wanted` rather
        // than carried forward whole.
        expired += state.expiredWhileWanted.filter { it in wantedActions }

        val heldActions = keep.mapTo(mutableSetOf()) { it.action }
        val asserted = wanted.filter { it.action !in heldActions && it.action !in expired }

        return Reconciliation(
            state = HoldState(held = keep + asserted, expiredWhileWanted = expired),
            asserted = asserted,
            released = released,
        )
    }

    /**
     * Let go of everything.
     *
     * Safety rule 4 when [why] is [ReleaseReason.STOOD_DOWN], and the RELEASE ALL control when it is
     * [ReleaseReason.BY_HAND].
     *
     * ⚠️ The returned state is genuinely empty, bar included. Standing down is not the same as a
     * backstop expiry: nothing here is a judgement that a rule misbehaved, so barring it from asking
     * again once sensing comes back would be punishing it for the wrong thing.
     */
    fun releaseAll(state: HoldState, why: ReleaseReason): Reconciliation = Reconciliation(
        state = HoldState(),
        released = state.held.map { Released(it, why) },
    )

    /**
     * Let go of one hold by hand.
     *
     * ⚠️ It is barred from coming straight back, which is the difference between this and the rule
     * simply stopping. Somebody who presses RELEASE while the situation that caused the hold is
     * still true means "not this, not now" — and without the bar the very next heartbeat would
     * assert it again and the button would look broken.
     */
    fun release(state: HoldState, action: AmbientAction): Reconciliation {
        val gone = state.held.filter { it.action == action }
        if (gone.isEmpty()) return Reconciliation(state)
        return Reconciliation(
            state = HoldState(
                held = state.held.filterNot { it.action == action },
                expiredWhileWanted = state.expiredWhileWanted + action,
            ),
            released = gone.map { Released(it, ReleaseReason.BY_HAND) },
        )
    }

    /**
     * "held back non-urgent notices · you are driving" — one row of the scanner's HOLDING NOW list.
     *
     * ⚠️ Built here rather than in the screen for the reason the situation's own label was hoisted
     * one slice ago: a second copy in a surface is a second thing that can disagree, and this
     * repository has converged that same mistake seven times.
     */
    fun describe(intent: AmbientIntent): String = "${intent.action.label} · ${intent.reason}"
}
