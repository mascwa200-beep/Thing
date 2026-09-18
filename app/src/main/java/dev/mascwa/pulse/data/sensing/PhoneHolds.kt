package dev.mascwa.pulse.data.sensing

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import dev.mascwa.pulse.core.telemetry.AmbientAction
import dev.mascwa.pulse.core.telemetry.RingerPolicy
import dev.mascwa.pulse.data.sensors.SurvivalTools

/**
 * The PHONE tier's two effects: the ringer and the torch.
 *
 * ⚠️ **Two, and the list is short because three of the four candidates were removed rather than
 * implemented.** Do Not Disturb went because no rule asked for it and the meeting rule had already
 * decided against taking over a mode people set deliberately. Raising the alarm volume went because
 * as wired it could not have worked — it moved `STREAM_ALARM`, which nothing in this subsystem's
 * alert path plays on — and because `EmergencyKlaxon` already captures and restores that stream,
 * and two independent capture-and-restores interleave into a phone stuck at maximum for ever. Both
 * are written up where they used to be declared.
 *
 * ## ⚠️ Neither of these needs a new permission, and that is why they are the whole tier
 *
 * `setTorchMode` has needed no permission since API 23. Setting the ringer to VIBRATE needs none
 * either — it is SILENT that trips the notification-policy grant, which is exactly why this stops at
 * vibrate and says so in the action's own words rather than asking for a grant the user would have
 * to hunt through a system screen to give.
 *
 * ## ⚠️ Nothing here captures a prior value, and that is a consequence of the rules rather than a
 * shortcut
 *
 * A capture-and-restore has to survive a process death or it restores nothing, which means
 * persisting it — and the interleaving that destroys is what removed the volume action above.
 * Neither of these needs one: [RingerPolicy] can only ever set VIBRATE, so the only thing there is
 * to restore is NORMAL, and a torch has no prior state worth the name. Off is off.
 *
 * ## ⚠️ The ringer rule is [RingerPolicy], and it is deliberately not written down twice
 *
 * What is left in this file is the platform boundary — read an Int, map it, write one back. The
 * decision lives in the core so it can be run on a build machine, which is the only way a rule
 * inside a class that needs an `AudioManager` gets tested at all.
 *
 * ⚠️ **`AmbientRules` gates on the ringer too, and the two are not a duplicated definition.** That
 * one reads the FUSED state, which is a heartbeat old, and refuses to ask at all unless the ringer
 * was measurably on — making transition-only structural at the decision layer. This one reads the
 * live mode at the instant of writing, and is the only one of the two that cannot be stale. A
 * person who reaches for silent between the heartbeat and the write is caught by the second gate
 * alone.
 */
class PhoneHolds(
    private val context: Context,
    private val tools: SurvivalTools,
) : HoldCapability {

    private val audio get() = context.getSystemService<AudioManager>()

    /**
     * Carry out one PHONE-tier hold, or undo it. Unknown actions are ignored — this tier owns two.
     *
     * Every call is best-effort: a phone with no flash, a manufacturer that refuses a ringer change,
     * a `SecurityException` from a policy this app does not hold. A refusal costs the hold and
     * nothing else, and the actuator writes the failure into its history where somebody can see it.
     */
    fun run(action: AmbientAction, asserting: Boolean) {
        when (action) {
            AmbientAction.TORCH_ON -> runCatching { tools.setTorch(asserting) }
            AmbientAction.SILENCE_RINGER -> ringer(asserting)
            else -> Unit
        }
    }

    /**
     * Why this handset cannot carry out [action], in the words a person would use — or null when
     * nothing stops it.
     *
     * ⚠️ **Null for an action this tier does not own, and that is the whole shape of the answer.**
     * The obvious signature is `can(action): Boolean`, and it is wrong in a way that is easy to
     * miss: `false` would then mean both "this phone has no torch" and "that is an APP-tier action,
     * ask somebody else", so using it as a filter refuses everything. Returning the REASON collapses
     * the three-valued muddle into one honest question — is there anything stopping this? — and
     * hands the caller the sentence it needs rather than making it invent one.
     *
     * ⚠️ This exists because a hold that silently does nothing is the defect this whole arc is
     * about. Without it, a phone with no flash records "turned the torch on", the scanner shows it
     * held with an undo, and the room stays dark: the app claiming more than it did.
     */
    override fun cannotDo(action: AmbientAction): String? = when (action) {
        AmbientAction.TORCH_ON ->
            if (runCatching { tools.torchAvailable() }.getOrDefault(false)) null
            else "this phone has no torch"
        AmbientAction.SILENCE_RINGER ->
            if (audio != null) null else "this phone does not expose ringer control"
        else -> null
    }

    /**
     * ⚠️ The decision is [RingerPolicy], not this function. What is left here is the platform
     * boundary — read a mode, map it, write one back — because that is the half CI cannot run. The
     * rule it carries is safety rule 1, and inline it would have been three branches nothing could
     * ever exercise.
     */
    private fun ringer(asserting: Boolean) {
        val am = audio ?: return
        runCatching {
            val current = modeOf(am.ringerMode) ?: return@runCatching
            val target = RingerPolicy.target(current, asserting) ?: return@runCatching
            am.ringerMode = platformOf(target)
        }
    }

    /**
     * ⚠️ Null for a mode this policy has no word for. `ringerMode` is an Int and nothing stops a
     * manufacturer returning something outside the three documented values — and the safe answer to
     * a state we cannot name is to leave it alone, not to guess which of the three it resembles.
     */
    private fun modeOf(platform: Int): RingerPolicy.Mode? = when (platform) {
        AudioManager.RINGER_MODE_NORMAL -> RingerPolicy.Mode.NORMAL
        AudioManager.RINGER_MODE_VIBRATE -> RingerPolicy.Mode.VIBRATE
        AudioManager.RINGER_MODE_SILENT -> RingerPolicy.Mode.SILENT
        else -> null
    }

    private fun platformOf(mode: RingerPolicy.Mode): Int = when (mode) {
        RingerPolicy.Mode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        RingerPolicy.Mode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
        RingerPolicy.Mode.SILENT -> AudioManager.RINGER_MODE_SILENT
    }
}
