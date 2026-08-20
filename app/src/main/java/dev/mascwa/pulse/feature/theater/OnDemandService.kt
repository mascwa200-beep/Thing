package dev.mascwa.pulse.feature.theater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
 * A `mediaPlayback` foreground service that keeps the process (and thus [OnDemandController]'s
 * ExoPlayer) alive while AUDIO-ONLY playback runs in the background — feature 3's literal
 * "configured for audio only background playback". Mirrors `RadioService` line for line, because
 * every discipline there was learned from a real failure: playback itself lives in the controller,
 * this only mirrors state into a notification with a Stop action and tears itself down when
 * playback ends.
 *
 * ⚠️ Started ONLY for audio-only playback. Video with no visible surface is data spent on pixels
 * nobody sees — the reason the live TV controller has no service — so watching a video and leaving
 * the screen still ends it; listening does not.
 */
class OnDemandService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        OnDemandController.state.onEach { st ->
            val alive = st.status == OnDemandController.Status.PLAYING ||
                st.status == OnDemandController.Status.PAUSED ||
                st.status == OnDemandController.Status.CONNECTING
            if (!alive) {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            } else {
                runCatching { startForeground(NOTIF_ID, buildNotification(st)) }
            }
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            OnDemandController.stop(this)
            return START_NOT_STICKY
        }
        // Must call startForeground within ~5s of startForegroundService — do it immediately.
        runCatching { startForeground(NOTIF_ID, buildNotification(OnDemandController.state.value)) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(st: OnDemandController.OnDemandState): Notification {
        val title = st.item?.title?.ifBlank { null } ?: "Playback"
        val text = when (st.status) {
            OnDemandController.Status.PLAYING -> st.item?.uploader?.ifBlank { null } ?: "Playing"
            OnDemandController.Status.PAUSED -> "Paused"
            OnDemandController.Status.CONNECTING -> "Working…"
            else -> "Stopped"
        }
        // Unique request code = this notification's id — extras are not part of a PendingIntent's
        // identity, and this tap carries a route (the RadioService lesson, held by NotifIdTest).
        val open = PendingIntent.getActivity(
            this, NOTIF_ID,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Routes.VIEWSCREEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OnDemandService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setColor(androidx.core.content.ContextCompat.getColor(this, dev.mascwa.pulse.R.color.lcars_condition_routine))
            .setSubText("VIEWSCREEN")
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Viewscreen", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "LCARS on-demand playback"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "ondemand_playback"
        private const val NOTIF_ID = dev.mascwa.pulse.notifications.NotifId.FGS_ONDEMAND
        const val ACTION_STOP = "dev.mascwa.pulse.ondemand.STOP"

        /**
         * Start the keep-alive, best-effort.
         *
         * From the Viewscreen this is a foreground start and always allowed. From the `play` tool
         * it can be a background start (a voice request with no visible activity), which Android
         * 12+ may refuse — the runCatching makes that a degraded keep-alive rather than a crash:
         * playback still runs for as long as the process lives, it just is not pinned.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, OnDemandService::class.java))
            }
        }
    }
}
