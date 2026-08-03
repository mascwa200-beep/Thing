package dev.mascwa.pulse.feature.tacnet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * A `mediaPlayback` foreground service whose only job is to keep the process (and thus [RadioController]'s
 * ExoPlayer) alive while audio is playing, and to show a media notification with a Stop
 * action. Playback itself lives in [RadioController]; this service mirrors its state into the notification
 * and tears itself down when playback stops. Fully defensive — a notification/foreground hiccup can't crash.
 */
class RadioService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Keep the notification in step with what's on air; drop the service when playback stops.
        RadioController.state.onEach { st ->
            if (st.status == RadioController.Status.IDLE) {
                stopForegroundCompat()
                stopSelf()
            } else {
                runCatching { startForeground(NOTIF_ID, buildNotification(st)) }
            }
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RadioController.stop(this)
            return START_NOT_STICKY
        }
        // Must call startForeground within ~5s of startForegroundService — do it immediately.
        runCatching { startForeground(NOTIF_ID, buildNotification(RadioController.state.value)) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(st: RadioController.RadioState): Notification {
        val title = st.tuned?.name ?: "Radio"
        val text = when (st.status) {
            RadioController.Status.ON_AIR -> st.tuned?.band ?: "On air"
            RadioController.Status.TUNING -> "Tuning…"
            RadioController.Status.ERROR -> "No signal"
            RadioController.Status.IDLE -> "Stopped"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Routes.TACNET),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(st.status != RadioController.Status.ERROR)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Radio", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "LCARS radio playback"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    companion object {
        private const val CHANNEL_ID = "radio_playback"
        private const val NOTIF_ID = 4201
        const val ACTION_STOP = "dev.mascwa.pulse.radio.STOP"
    }
}
