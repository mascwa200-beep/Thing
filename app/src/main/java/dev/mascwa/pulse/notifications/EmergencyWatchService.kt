package dev.mascwa.pulse.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.EmergencyAlert
import dev.mascwa.pulse.feature.redalert.RedAlertActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
// CoroutineScope.cancel() is an extension, not a member — omitting this import is a compile error
// that the resolve-check cannot see, because every other name in this file cascades anyway.
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The watch that makes an emergency alert arrive in time.
 *
 * A dedicated always-on foreground service, which is the owner's explicit choice over the two
 * cheaper options. The reasoning is worth keeping: riding the fifteen-minute [RefreshWorker] would
 * cost nothing and could deliver a tornado warning a quarter of an hour late, and folding it into
 * the ambient-sensing service would tie a life-safety feature to a privacy toggle, so that turning
 * off the microphone would silently turn off the emergency watch. This one does nothing but watch,
 * and it is switched on and off on its own terms.
 *
 * ⚠️ **What it can and cannot do, plainly.** It polls the same CAP feed that drives Wireless
 * Emergency Alerts, roughly every minute. It **cannot** intercept or preempt WEA itself — that is
 * delivered by the modem to the system CellBroadcastService and is not reachable by any ordinary
 * app. In practice a one-minute poll often surfaces a warning at or before the broadcast arrives;
 * sometimes after. Nothing in the UI claims otherwise.
 *
 * ⚠️ **Empty is not "all clear".** [EmergencyAlertRepository] returns an empty list both when
 * nothing is published and when the request failed, and `api.weather.gov` covers only the United
 * States. So this raises alerts and never lowers a verdict — an absence is treated as an absence of
 * information, not as safety.
 *
 * Revived by [BootReceiver] and self-healed by [RefreshWorker], the same two mechanisms the app's
 * other resident services use.
 */
class EmergencyWatchService : Service() {

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /**
     * What the ongoing notification currently says, so it is re-posted only when it changes.
     *
     * ⚠️ It used to say one fixed thing — "Watching for official alerts in your area." — whatever
     * was actually happening. With location unavailable (permission revoked, services off, no fix
     * yet) `sweep` returns at its first line and the loop spins every minute doing nothing, while
     * the notification keeps asserting a watch that structurally cannot happen: this feature is
     * geographic, and without a position there is no area to check. A life-safety feature that has
     * silently stopped working is worse than one that says so, and the user can only act on what
     * they are told. The sibling VitalsTrackingService already words its degraded states this way.
     */
    private var ongoingText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Above everything that can fail: missing the foreground window is a hard crash.
        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, ongoing(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (job?.isActive == true) return START_STICKY // already watching; don't stack loops

        val container = (application as? PulseApplication)?.container ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            while (isActive) {
                runCatching { sweep(container) }
                delay(POLL_MS)
            }
        }
        // ⚠️ STICKY on purpose, unlike most services here: if the OS kills this one, the thing that
        // stops working is the warning. Being restarted with a null intent is fine — it takes no
        // parameters.
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
        super.onDestroy()
    }

    private suspend fun sweep(container: dev.mascwa.pulse.di.AppContainer) {
        val settings = container.settingsRepository.current()
        if (!settings.notifications.emergencyTakeover) {
            stopSelf()
            return
        }
        val loc = container.locationProvider.current()
        if (loc == null) {
            updateOngoing(NEEDS_LOCATION)
            return
        }
        updateOngoing(WATCHING)
        val alerts = container.emergencyAlertRepository.active(loc.latitude, loc.longitude)
        if (alerts.isEmpty()) return

        val now = System.currentTimeMillis()
        val state = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: NotifyState()
        val worst = EmergencyAlert.pick(alerts, state.raisedAlertIds.toSet(), now) ?: return

        // Record BEFORE launching. If the Activity start throws on some OEM, the alternative is a
        // loop that re-sounds the alarm every minute for the same warning, which is worse than
        // missing one launch — and the board still carries it either way.
        val latest = container.diskCache.readAny(STATE_KEY, NotifyState.serializer())?.value ?: state
        container.diskCache.write(
            STATE_KEY,
            latest.copy(raisedAlertIds = (latest.raisedAlertIds + worst.id).distinct().takeLast(50)),
            NotifyState.serializer(),
        )

        runCatching {
            startActivity(
                RedAlertActivity.intent(
                    context = this,
                    condition = EmergencyAlert.condition(EmergencyAlert.tierFor(worst, now)),
                    hazard = worst.event.ifBlank { worst.headline },
                    area = worst.area,
                    urgency = worst.urgency,
                    certainty = worst.certainty,
                    remaining = EmergencyAlert.remaining(worst, now),
                    instruction = worst.instruction,
                    source = worst.source,
                ),
            )
        }
        // The board says it too, so the alert survives being acknowledged and is still there to
        // re-read afterwards.
        runCatching {
            container.notifier.notifyUrgentLine(
                headline = EmergencyAlert.summary(worst),
                detail = worst.instruction ?: worst.headline.ifBlank { worst.event },
                key = "gov:${worst.id}",
                red = true,
            )
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Emergency watch", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while the phone is watching for official emergency alerts."
                setShowBadge(false)
            },
        )
    }

    /** Re-post only on a real change: this runs every minute and the tray is not a log. */
    private fun updateOngoing(text: String) {
        if (text == ongoingText) return
        ongoingText = text
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, ongoing(text))
        }
    }

    private fun ongoing(detail: String = WATCHING): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("Emergency watch active")
            .setContentText(detail)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL = "emergency_watch"
        private const val NOTIF_ID = NotifId.FGS_EMERGENCY_WATCH
        private const val STATE_KEY = "notify_state"

        /** A minute. Fast enough to matter, slow enough that the battery cost is a rounding error. */
        private const val POLL_MS = 60_000L

        private const val WATCHING = "Watching for official alerts in your area."
        private const val NEEDS_LOCATION =
            "Needs location access — without a position there is no area to check."

        /** Start it if the user has it on. Safe to call repeatedly — a running loop is not stacked. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, EmergencyWatchService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, EmergencyWatchService::class.java)) }
        }
    }
}
