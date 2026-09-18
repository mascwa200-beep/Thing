package dev.mascwa.pulse.data.sensing

import dev.mascwa.pulse.core.telemetry.AmbientAction
import dev.mascwa.pulse.core.telemetry.AmbientIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the acting layer is holding right now, where anything can ask.
 *
 * ⚠️ **A process-wide object rather than a dependency, and the precedent is deliberate**: this is
 * the same shape as `MicFloor`, `AudioFloor` and `CameraFloor`. The consumers are four unrelated
 * subsystems — the notification board, the assistant's voice, the two video controllers and the
 * Sensorium's own sampler — and threading a reference through all four would mean touching four
 * constructors and every call site that builds them, to deliver one boolean. A star topology is what
 * those three Floors already chose for exactly this reason: the writer knows about nobody, and a
 * fifth consumer is one read rather than a plumbing change.
 *
 * ⚠️ **[AmbientActuator] is the only writer.** Everything else reads. That is what makes the held set
 * a single source of truth rather than a cache that can disagree with the actuator's own state — and
 * why the write side takes the whole set rather than offering add/remove: a reconciliation is one
 * transition, not a sequence of them, and publishing it in pieces would let a reader observe a state
 * that never existed.
 *
 * ⚠️ **This deliberately says nothing about WHY.** A consumer's question is only ever "may I?", and
 * giving it the reason would invite it to second-guess the decision. The reasons, the instants and
 * the undo sentences live in [AmbientActuator.holds], which is what a surface reads.
 */
object AmbientHolds {

    private val _held = MutableStateFlow<Set<AmbientAction>>(emptySet())

    /**
     * The live set. Empty is the overwhelmingly common value and not a failure.
     *
     * ⚠️ A fresh process starts empty, and that is correct rather than a gap: every APP-tier hold
     * lives in memory, so a process that died has already released them all. The holds that need
     * reconciling from disk are the ones that changed the PHONE, and those arrive in a later slice —
     * see [AmbientActuator.reconcileFromDisk], which runs first whether or not it has work to do.
     */
    val held: StateFlow<Set<AmbientAction>> = _held.asStateFlow()

    /** Cheap enough for a hot path — one set lookup, no allocation, no suspension. */
    fun isHeld(action: AmbientAction): Boolean = action in _held.value

    /**
     * Replace the whole set. Idempotent — a StateFlow does not re-emit an unchanged value.
     *
     * Internal so that the compiler, rather than a comment, is what keeps the writer single.
     */
    internal fun publish(intents: List<AmbientIntent>) {
        _held.value = intents.mapTo(mutableSetOf()) { it.action }
    }
}
