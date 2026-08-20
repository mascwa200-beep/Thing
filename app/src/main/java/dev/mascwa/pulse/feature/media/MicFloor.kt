package dev.mascwa.pulse.feature.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who is holding the microphone across the whole process.
 *
 * The speaker has [AudioFloor] and the microphone had nothing: three subsystems can want it — the
 * wake loop, the Sensorium's short sips, and now the acoustic interrogator's continuous capture —
 * and the arbiter that decides between the first two lives as a private field inside
 * `ActiveMatrixService`, where nothing else can reach it.
 *
 * ⚠️ **A SIGNAL, NOT A CONTROLLER, AND DELIBERATELY SO.** This carries a claim; it does not open or
 * close anything. `ActiveMatrixService` observes it, folds it into the CI-tested
 * [dev.mascwa.pulse.core.telemetry.VoiceMachine], and performs whatever that decides — so the rule
 * about who yields to whom stays in the tested core rather than being reimplemented here. The same
 * star topology [AudioFloor] uses: the claimants do not know about each other.
 *
 * ⚠️ **THE WAKE WORD YIELDS WHILE THE INTERROGATOR RUNS, AND THAT IS A DELIBERATE DEGRADATION.**
 * Whether two `AudioRecord` clients in one app both receive real audio is device-specific and cannot
 * be settled from a build machine, so the interrogator takes the microphone outright rather than
 * gambling that sharing works and shipping a wake word that silently never fires. A single capture
 * fanned out to every consumer is the better design and a much larger change; recorded rather than
 * attempted blind. The interrogator is opt-in and off by default, so nothing changes for anyone who
 * does not turn it on.
 */
object MicFloor {

    private val _interrogating = MutableStateFlow(false)

    /** True while the interrogator holds the microphone. */
    val interrogating: StateFlow<Boolean> = _interrogating.asStateFlow()

    /** Claim the microphone. Idempotent — a StateFlow does not re-emit an unchanged value. */
    fun claim() {
        _interrogating.value = true
    }

    /**
     * Give it back.
     *
     * ⚠️ Called from the service's teardown, which must run even when its own scope is being
     * cancelled — leaving this true would leave the wake word standing down forever with nothing on
     * screen to explain it, which is the same latching failure the TTS watchdog exists to prevent.
     */
    fun release() {
        _interrogating.value = false
    }
}
