package dev.mascwa.pulse.data.perception

import dev.mascwa.pulse.core.telemetry.ActivityEvidence
import dev.mascwa.pulse.core.telemetry.NeedKind
import dev.mascwa.pulse.core.telemetry.NeedSensing
import dev.mascwa.pulse.data.game.SpecialGameStore
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * AUTO-CARE — the phone tends your needs FOR you when it catches you doing it in real life. Observes the live
 * [ActivityEvidence] the camera + microphone produce ([ActivityEvidenceStore.evidenceFlow]); when it
 * confidently senses you drinking / eating / washing / brushing ([NeedSensing]), it auto-restores the matching
 * game need — no button press — and quietly tells you. The life-sim genuinely reads your real behaviour.
 *
 * Rate-limited per need (a long meal or continuous running water can't credit over and over) and gated by the
 * ambient-sensing master toggle, so it does nothing unless the camera/mic are already sampling with your
 * consent. This also closes the penalty loop: the kiosk gate releases when a need recovers, so the phone
 * SEEING you drink lifts the lock automatically — you tended it for real.
 */
class NeedAutoCare(
    private val evidence: StateFlow<List<ActivityEvidence>>,
    private val game: SpecialGameStore,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
) {
    private val _sensed = MutableSharedFlow<NeedKind>(extraBufferCapacity = 8)
    /** Emits each time a need was auto-credited from sensed real-world care (drives a UI flourish). */
    val sensed: SharedFlow<NeedKind> = _sensed

    /** Begin observing the evidence stream in [scope] (the app scope) — runs for the process lifetime; cheap
     *  (it only reacts when the samplers produce new evidence, and only while ambient sensing is on). No
     *  cooldown (owner's explicit choice: max notification frequency, applied everywhere) — a long,
     *  continuous activity (a slow meal, a long shower) can now credit its need more than once per sitting
     *  as fresh evidence keeps arriving, trading the earlier "credit once per activity" guarantee for the
     *  requested maximum responsiveness. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            evidence.collect { list ->
                if (!runCatching { settings.current().ambientSensing }.getOrDefault(false)) return@collect
                val now = System.currentTimeMillis()
                val recent = list.filter { now - it.atMs <= WINDOW_MS }
                NeedSensing.sensedNeeds(recent).forEach { need ->
                    restore(need)
                    runCatching { notifier.notifySensedCare(need) }
                    _sensed.tryEmit(need)
                }
            }
        }
    }

    private fun restore(need: NeedKind) = when (need) {
        NeedKind.HYDRATION -> game.drink()
        NeedKind.NOURISHMENT -> game.eat()
        NeedKind.HYGIENE -> game.wash()
        NeedKind.BRUSHING -> game.brushTeeth()
        NeedKind.FLOSSING -> game.floss()
        NeedKind.ENERGY -> game.rest()
    }

    companion object {
        /** Only evidence this recent counts — a live catch, not stale history from hours ago. */
        private const val WINDOW_MS = 5L * 60 * 1000 // 5 minutes
    }
}
