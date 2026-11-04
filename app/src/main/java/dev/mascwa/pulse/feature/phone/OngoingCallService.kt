package dev.mascwa.pulse.feature.phone

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.notifications.NotifId

/**
 * Owns the ongoing-call card while a call is in progress, as a foreground service of type
 * `phoneCall` — the only owner Android accepts for a call-style card with no full-screen intent.
 *
 * It holds no state: every start rebuilds the card from [CallRegistry], so [CallNotices.update]
 * restarting it IS the update. When there is no call left it stops itself.
 *
 * ⚠️ **A start can be refused, and a refusal must still leave a card.** [start] posts the plain
 * ongoing card itself when Android will not start the service, and [onStartCommand] posts it when
 * Android will not take the call-style one. Either way the call is visible in the tray, and either way
 * it can be hung up from there.
 */
class OngoingCallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallNotices.ensureChannel(this)
        val focus = CallRegistry.focus(CallRegistry.calls.value)
        // ⚠️ startForeground is owed for EVERY start, even one that has nothing to show, or Android
        // ends the app for missing it. The two early cases satisfy it with the right card and let go.
        if (focus == null) {
            // The call ended between the start being asked for and arriving.
            runCatching { foreground(CallNotices.ended(this)) }
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (focus.phase == CallDisplay.Phase.RINGING) {
            // A second call began ringing in the meantime. Its card is the ringing one — never an
            // ongoing card for a call nobody has answered.
            val (styled, plain) = CallNotices.ringing(this, focus)
            if (runCatching { foreground(styled) }.isFailure) runCatching { foreground(plain) }
            stop()
            return START_NOT_STICKY
        }
        val (styled, plain) = CallNotices.ongoing(this, focus, System.currentTimeMillis())
        if (runCatching { foreground(styled) }.isFailure && runCatching { foreground(plain) }.isFailure) {
            // Android would not let this be a foreground service at all; the card is still owed.
            runCatching { getSystemService(NotificationManager::class.java)?.notify(NotifId.CALL, plain) }
            stopSelf()
        }
        // Not sticky: a call that outlives this process is Telecom's to rebind, not ours to resurrect.
        return START_NOT_STICKY
    }

    private fun foreground(card: android.app.Notification) {
        ServiceCompat.startForeground(this, NotifId.CALL, card, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
    }

    private fun stop() {
        // DETACH, not remove: when a second call rings during this one, its card has just replaced
        // this one under the same id, and removing would take the ringing card with it.
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH) }
        stopSelf()
    }

    companion object {
        fun start(context: Context) {
            val started = runCatching {
                ContextCompat.startForegroundService(context, Intent(context, OngoingCallService::class.java))
            }.isSuccess
            if (!started) {
                val focus = CallRegistry.focus(CallRegistry.calls.value) ?: return
                CallNotices.ensureChannel(context)
                runCatching {
                    context.getSystemService(NotificationManager::class.java)
                        ?.notify(NotifId.CALL, CallNotices.ongoing(context, focus, System.currentTimeMillis()).second)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OngoingCallService::class.java)) }
        }
    }
}
