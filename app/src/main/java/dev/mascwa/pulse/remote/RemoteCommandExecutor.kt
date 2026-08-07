package dev.mascwa.pulse.remote

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import dev.mascwa.pulse.BuildConfig
import dev.mascwa.pulse.core.telemetry.AuditEventType
import dev.mascwa.pulse.core.telemetry.RemoteCommand
import dev.mascwa.pulse.core.telemetry.RemoteReply
import dev.mascwa.pulse.core.telemetry.RemoteRequest
import dev.mascwa.pulse.core.telemetry.RemoteWire
import dev.mascwa.pulse.core.util.SecretScrub
import dev.mascwa.pulse.data.blackbox.AuditLedgerStore
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.jarvis.matrix.ActiveMatrixService
import dev.mascwa.pulse.jarvis.vitals.VitalsTrackingService

/**
 * Turns an allowlisted [RemoteCommand] into an actual change on the phone. **This is the only place a
 * remote peer can affect anything**, which is why it is a plain `when` over a closed enum with no
 * reflection, no dynamic dispatch and no string-driven behaviour: reading this function tells you the
 * complete set of things a paired computer can do.
 *
 * Every command — accepted or refused — lands in the tamper-evident audit ledger. The ledger does not
 * scrub, by design, so details go through [SecretScrub] here before being recorded.
 *
 * Deliberately absent, and to stay absent: anything from `DevicePolicyController`/`WifiPolicyController`
 * (a wipe arm is irreversible; USB, Wi-Fi and app-suspension lockdowns can strand the device or kill the
 * very link needed to undo them), the self-coding flags (code changes belong to the human-approval gate),
 * and every credential. `STATUS` is likewise curated field by field rather than dumping settings.
 */
class RemoteCommandExecutor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val audit: AuditLedgerStore,
    private val onRefreshRequested: suspend () -> Unit,
    private val onBriefRequested: suspend () -> Unit,
    private val onRadioStop: () -> Unit,
    private val onRadioPlay: suspend (String) -> Boolean,
) {

    suspend fun execute(request: RemoteRequest, peerFingerprint: String): RemoteReply {
        val ok = runCatching { dispatch(request) }.getOrElse { false }
        audit.record(
            AuditEventType.NETWORK,
            "remote.${request.command.wire}",
            SecretScrub.scrub("peer=$peerFingerprint arg=${request.arg.take(40)} ok=$ok"),
        )
        return if (request.command == RemoteCommand.STATUS && ok) {
            RemoteReply(ok = true, payload = status())
        } else {
            RemoteReply(ok = ok)
        }
    }

    /** Record a refused connection. Refusals matter more than successes when reading a security log. */
    fun auditRefusal(reason: String, detail: String) {
        audit.record(AuditEventType.SECURITY, "remote.refused.$reason", SecretScrub.scrub(detail))
    }

    /** Record a new pairing — a genuine security event: a new machine can now issue commands. */
    fun auditPairing(peerFingerprint: String) {
        audit.record(AuditEventType.SECURITY, "remote.paired", SecretScrub.scrub("peer=$peerFingerprint"))
    }

    private suspend fun dispatch(request: RemoteRequest): Boolean {
        val on = request.arg.equals("true", ignoreCase = true) || request.arg == "1"
        return when (request.command) {
            RemoteCommand.PING, RemoteCommand.STATUS -> true

            RemoteCommand.SET_NOTIFICATIONS -> update { s ->
                s.copy(notifications = s.notifications.copy(masterEnabled = on))
            }
            RemoteCommand.SET_QUIET_HOURS -> update { s ->
                s.copy(notifications = s.notifications.copy(quietHoursEnabled = on))
            }
            RemoteCommand.SET_BREAKING_INTERRUPT -> update { s ->
                s.copy(notifications = s.notifications.copy(breakingInterrupt = on))
            }
            RemoteCommand.SET_LIVE_NEWS -> update { s ->
                s.copy(notifications = s.notifications.copy(liveBreakingNews = on))
            }

            // The assistant and vitals flags each own a foreground service. The app's established idiom is
            // that writing the flag and starting/stopping the service are two side-effects at the call
            // site (nothing observes the setting to drive the service), so both must happen here or the
            // switch would report a state the phone is not actually in.
            RemoteCommand.SET_ASSISTANT -> {
                val wake = settings.current().jarvis.wakeWord
                update { s -> s.copy(jarvis = s.jarvis.copy(residentService = on)) }
                if (on) ActiveMatrixService.start(context, wake) else ActiveMatrixService.stop(context)
                true
            }
            RemoteCommand.SET_VITALS -> {
                update { s -> s.copy(jarvis = s.jarvis.copy(vitalsTracking = on)) }
                if (on) VitalsTrackingService.start(context) else VitalsTrackingService.stop(context)
                true
            }
            RemoteCommand.SET_WAKE_WORD -> {
                update { s -> s.copy(jarvis = s.jarvis.copy(wakeWord = on)) }
                // The wake word changes the service's foreground type, so a running service is restarted
                // rather than left in its previous mode.
                if (settings.current().jarvis.residentService) {
                    ActiveMatrixService.stop(context)
                    ActiveMatrixService.start(context, on)
                }
                true
            }
            RemoteCommand.SET_VOICE_REPLIES -> update { s ->
                s.copy(jarvis = s.jarvis.copy(voiceReplies = on))
            }

            RemoteCommand.REFRESH_NOW -> { onRefreshRequested(); true }
            RemoteCommand.SEND_BRIEF -> { onBriefRequested(); true }
            RemoteCommand.RADIO_STOP -> { onRadioStop(); true }
            RemoteCommand.RADIO_PLAY -> if (request.arg.isBlank()) false else onRadioPlay(request.arg)
        }
    }

    private suspend fun update(transform: (AppSettings) -> AppSettings): Boolean =
        runCatching { settings.update(transform); true }.getOrDefault(false)

    /**
     * The status readout, assembled field by field. Curated on purpose: it would be easy to serialise the
     * settings object wholesale and far too easy for that to start carrying an API key the day someone
     * adds one.
     */
    private suspend fun status(): String {
        val s = runCatching { settings.current() }.getOrNull()
        val battery = runCatching {
            (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
        val charging = runCatching {
            (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging
        }.getOrNull()

        val fields = buildList {
            add("device=${s?.remote?.deviceLabel?.takeIf { it.isNotBlank() } ?: Build.MODEL}")
            add("version=${BuildConfig.VERSION_NAME}")
            add("android=${Build.VERSION.RELEASE}")
            battery?.let { add("battery=$it") }
            charging?.let { add("charging=${it.bool()}") }
            add("uptime=${uptime()}")
            s?.let {
                add("notifications=${it.notifications.masterEnabled.bool()}")
                add("quietHours=${it.notifications.quietHoursEnabled.bool()}")
                add("breakingInterrupt=${it.notifications.breakingInterrupt.bool()}")
                add("liveNews=${it.notifications.liveBreakingNews.bool()}")
                add("assistant=${it.jarvis.residentService.bool()}")
                add("wakeWord=${it.jarvis.wakeWord.bool()}")
                add("vitals=${it.jarvis.vitalsTracking.bool()}")
                add("voiceReplies=${it.jarvis.voiceReplies.bool()}")
            }
        }
        return String(RemoteWire.encode(fields), Charsets.UTF_8)
    }

    private fun Boolean.bool(): String = if (this) "1" else "0"

    private fun uptime(): String {
        val totalMinutes = SystemClock.elapsedRealtime() / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
