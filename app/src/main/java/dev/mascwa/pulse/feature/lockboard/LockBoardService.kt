package dev.mascwa.pulse.feature.lockboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.notifications.NotifId

/**
 * Keeps LCARS running so it hears the screen go off — which is the only moment the lock-screen board
 * can be put in place in time to be the first thing seen.
 *
 * ⚠️ **It does nothing else, and that is the point.** The screen-off broadcast can only be received
 * by a receiver registered at runtime, so it reaches LCARS only while its process is alive
 * ([LockBoardTrigger]). The first debug report after the board shipped showed the process had been
 * dead until a widget render restarted it — and a board cannot appear for a screen-off nobody heard.
 * A foreground service is the platform's way of saying "keep this process"; this one exists solely to
 * be that, with the owner's explicit choice of one quiet notification over a board that is sometimes
 * missing.
 *
 * It runs only while the board is switched on AND Android would let LCARS open it (see
 * [shouldRun]) — a notification promising a board that the platform will refuse is worse than none.
 * Started and stopped by [LockBoardTrigger]'s settings watch (so any start of the process re-arms
 * it), revived after a reboot by `BootReceiver` and self-healed by `RefreshWorker`, the same way the
 * app's other resident services are.
 */
class LockBoardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Above everything that can fail: missing the foreground window is a hard crash.
        ensureChannel()
        ServiceCompat.startForeground(
            this, NotifId.FGS_LOCK_BOARD, ongoing(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        // ⚠️ STICKY: if Android kills the process, restarting this service is what brings the
        // listener back. The restart also re-runs the application's own start, whose settings watch
        // stops this again if the board has since been switched off.
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lock-screen board", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while LCARS keeps the lock-screen board ready for the screen coming on."
                setShowBadge(false)
            },
        )
    }

    private fun ongoing(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("Lock-screen board ready")
            .setContentText("Keeps LCARS listening for the screen going off · turn off in Settings ▸ Appearance")
            // ⚠️ The notification id as the request code, as NotifId says every PendingIntent whose
            // identity matters must: request code 0 plus MainActivity is shared app-wide.
            .setContentIntent(
                PendingIntent.getActivity(
                    this, NotifId.FGS_LOCK_BOARD, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL = "lock_board"

        /** The board is switched on and Android would let LCARS open it from the background. */
        fun shouldRun(context: Context, boardOn: Boolean): Boolean =
            boardOn && LockBoardTrigger.canLaunch(context)

        /**
         * Start or stop it to match the setting. Safe to call repeatedly: a running service is not
         * stacked. A start Android refuses from the background is written to the activity log rather
         * than thrown — a device owner is exempt, so a refusal here is itself worth reporting.
         */
        fun sync(context: Context, boardOn: Boolean) {
            if (shouldRun(context, boardOn)) {
                runCatching {
                    ContextCompat.startForegroundService(context, Intent(context, LockBoardService::class.java))
                }.onFailure { err ->
                    runCatching {
                        (context.applicationContext as? PulseApplication)?.container?.usageRepository
                            ?.log("lockboard", "lock-screen board: could not keep LCARS running (${err.javaClass.simpleName})")
                    }
                }
            } else {
                runCatching { context.stopService(Intent(context, LockBoardService::class.java)) }
            }
        }
    }
}
