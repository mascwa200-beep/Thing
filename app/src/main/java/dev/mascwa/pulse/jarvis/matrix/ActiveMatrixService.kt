package dev.mascwa.pulse.jarvis.matrix

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
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.BanterContextEngine
import dev.mascwa.pulse.core.telemetry.DeviceContext
import dev.mascwa.pulse.core.telemetry.DeviceContextProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps J.A.R.V.I.S. resident: a foreground service that watches device context and
 * surfaces proactive remarks in an ongoing notification, so the assistant has a presence
 * even when the app isn't open. Everything is computed on-device — nothing is transmitted.
 */
class ActiveMatrixService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val banter = BanterContextEngine()
    private lateinit var provider: DeviceContextProvider
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        provider = DeviceContextProvider(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Foreground start can throw (ForegroundServiceStartNotAllowed / FGS-type errors on
        // Android 14+). Stand down gracefully instead of crashing the app.
        try {
            startForegroundCompat(buildNotification("Active · standing by."))
        } catch (t: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                runCatching {
                    var prev: DeviceContext? = null
                    provider.updates.collect { now ->
                        val line = if (prev == null) banter.greeting(now) else banter.reactTo(prev, now)
                        prev = now
                        if (!line.isNullOrBlank()) update(line)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun update(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val standDown = PendingIntent.getService(
            this, 1, Intent(this, ActiveMatrixService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("J.A.R.V.I.S. Matrix")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .addAction(0, "Stand down", standDown)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Active-Matrix", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps J.A.R.V.I.S. resident and surfaces proactive remarks."
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onDestroy() {
        observing = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "jarvis_active_matrix"
        private const val NOTIF_ID = 7301
        private const val ACTION_STOP = "dev.mascwa.pulse.jarvis.matrix.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ActiveMatrixService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ActiveMatrixService::class.java))
        }
    }
}
