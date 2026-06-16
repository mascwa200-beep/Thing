package dev.mascwa.pulse.jarvis.orchestrator

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import dev.mascwa.pulse.jarvis.vitals.VitalsTrackingService

/** Outcome of a single lockdown command — honest about what the OS actually allowed. */
enum class CommandStatus { DONE, NEEDS_PERMISSION, UNSUPPORTED }

data class LockdownResult(val label: String, val status: CommandStatus, val detail: String = "")

/**
 * Executes the "Lockdown" macro as a sequence of independent commands, each reporting its
 * real outcome. It never fakes success: actions the OS forbids (e.g. toggling Wi-Fi / airplane
 * mode, which are Settings-only on modern Android / GrapheneOS) are reported as Unsupported
 * with a pointer to Settings, and actions needing a special grant report NeedsPermission.
 */
class ActionOrchestrator(context: Context) {

    private val app = context.applicationContext

    fun executeLockdownSequence(): List<LockdownResult> = listOf(
        wipeClipboard(),
        enterDoNotDisturb(),
        silenceAudio(),
        haltBleRadioUse(),
        networkNote(),
    )

    private fun wipeClipboard(): LockdownResult = runCatching {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return LockdownResult("Clipboard wipe", CommandStatus.UNSUPPORTED, "no clipboard service")
        if (Build.VERSION.SDK_INT >= 28) {
            cm.clearPrimaryClip()
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        LockdownResult("Clipboard wiped", CommandStatus.DONE)
    }.getOrElse { LockdownResult("Clipboard wipe", CommandStatus.UNSUPPORTED, it.message ?: "failed") }

    private fun enterDoNotDisturb(): LockdownResult {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return LockdownResult("Do Not Disturb", CommandStatus.UNSUPPORTED, "no notification service")
        if (!nm.isNotificationPolicyAccessGranted) {
            return LockdownResult("Do Not Disturb", CommandStatus.NEEDS_PERMISSION, "grant DND access in Settings")
        }
        return runCatching {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            LockdownResult("Do Not Disturb on", CommandStatus.DONE)
        }.getOrElse { LockdownResult("Do Not Disturb", CommandStatus.UNSUPPORTED, it.message ?: "failed") }
    }

    private fun silenceAudio(): LockdownResult {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null && !nm.isNotificationPolicyAccessGranted) {
            return LockdownResult("Silent profile", CommandStatus.NEEDS_PERMISSION, "needs DND access")
        }
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return LockdownResult("Silent profile", CommandStatus.UNSUPPORTED, "no audio service")
        return runCatching {
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
            LockdownResult("Silent profile", CommandStatus.DONE)
        }.getOrElse { LockdownResult("Silent profile", CommandStatus.NEEDS_PERMISSION, it.message ?: "failed") }
    }

    private fun haltBleRadioUse(): LockdownResult = runCatching {
        // We never advertise; this stops our own BLE scan/connection (the vitals monitor).
        VitalsTrackingService.stop(app)
        LockdownResult("BLE radio use halted", CommandStatus.DONE)
    }.getOrElse { LockdownResult("BLE radio use", CommandStatus.UNSUPPORTED, it.message ?: "failed") }

    private fun networkNote(): LockdownResult = LockdownResult(
        "Network / airplane",
        CommandStatus.UNSUPPORTED,
        "Settings-only on this OS — open Settings to toggle",
    )
}
