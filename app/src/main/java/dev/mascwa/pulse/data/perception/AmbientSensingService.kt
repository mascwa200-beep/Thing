package dev.mascwa.pulse.data.perception

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.navigation.Routes

/**
 * The **always-on** ambient-sensing foreground service. Normally the camera/mic samplers only run while the
 * S.P.E.C.I.A.L. game screen is open; with this opt-in service they keep running in the background (and over
 * the lock screen) so the activity-attestation lie-catcher has a continuous evidence history — the point of
 * "it'll know if I'm lying because it goes through its history."
 *
 * Safe/honest by construction, mirroring [RadioService]:
 *  - **Opt-in, default OFF** (`AppSettings.ambientSensingAlways`); [MainActivity] starts/stops it.
 *  - **On-device only** — the samplers emit text labels; no image/audio is ever stored or transmitted.
 *  - Respects the granular switches: the starter passes which senses are enabled as intent extras, and the
 *    service intersects them with the live runtime permissions — a sense with no permission is never used.
 *  - A persistent, low-importance notification with a **STOP** action (the GrapheneOS mic/camera indicators
 *    also show while it samples), so it's always visible and one tap kills it.
 *  - Fully defensive — a sampler / notification / foreground hiccup can never crash the app.
 */
class AmbientSensingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSensing()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // What the owner enabled (from the starter) ∩ what we actually hold permission for right now.
        val wantMic = intent?.getBooleanExtra(EXTRA_MIC, true) ?: true
        val wantCam = intent?.getBooleanExtra(EXTRA_CAM, true) ?: true
        val mic = wantMic && hasPermission(Manifest.permission.RECORD_AUDIO)
        val cam = wantCam && hasPermission(Manifest.permission.CAMERA)
        if (!mic && !cam) { // nothing we can sense — don't sit foreground for no reason
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // Go foreground immediately (must be within ~5s of startForegroundService), typed to what we'll use.
        runCatching { startForegroundCompat(buildNotification(), mic, cam) }
        startSensing(mic, cam)
        // NOT_STICKY on purpose: a camera/mic FGS auto-restarted from the background on API 34+ can't legally
        // go foreground (would be killed / throw), so we don't let the OS resurrect it — the MainActivity
        // LaunchedEffect re-starts it the next time the app is foregrounded. It keeps running until then.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSensing()
        super.onDestroy()
    }

    private fun container() = runCatching { (application as PulseApplication).container }.getOrNull()

    private fun startSensing(mic: Boolean, cam: Boolean) {
        val c = container() ?: return
        runCatching { if (mic) c.ambientPerceptionSampler.start() }
        runCatching { if (cam) c.cameraPerceptionSampler.start() }
        runCatching { c.activityEvidenceStore.start() } // turn the labels into the attestation history
    }

    private fun stopSensing() {
        val c = container() ?: return
        runCatching { c.ambientPerceptionSampler.stop() }
        runCatching { c.cameraPerceptionSampler.stop() }
        runCatching { c.activityEvidenceStore.stop() }
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat(notification: Notification, mic: Boolean, cam: Boolean) {
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (cam) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (type == 0 && Build.VERSION.SDK_INT >= 29) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Routes.TACNET),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AmbientSensingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Ambient sensing active")
            .setContentText("Sensing your surroundings on-device (labels only). Tap to open, or Stop.")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
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
                NotificationChannel(CHANNEL_ID, "Ambient sensing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Background on-device camera/mic sensing for the life-sim (labels only)"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "ambient_sensing"
        private const val NOTIF_ID = 4301
        const val ACTION_STOP = "dev.mascwa.pulse.sensing.STOP"
        const val EXTRA_MIC = "sensing_mic"
        const val EXTRA_CAM = "sensing_cam"

        /** Start the always-on sensing service with the given senses (call after checking the settings flag). */
        fun start(context: Context, mic: Boolean, cam: Boolean) {
            if (!mic && !cam) return
            val intent = Intent(context, AmbientSensingService::class.java)
                .putExtra(EXTRA_MIC, mic).putExtra(EXTRA_CAM, cam)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Stop the always-on sensing service (releases the mic/camera). */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AmbientSensingService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
