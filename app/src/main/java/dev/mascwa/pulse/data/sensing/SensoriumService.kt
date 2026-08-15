package dev.mascwa.pulse.data.sensing

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.Sensorium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Sensorium's adaptive-24/7 foreground service — an UPGRADEABLE service, the design the Android
 * while-in-use law forces and the honest way to run ambient camera/mic sensing:
 *
 *  - **Background starts** (boot, worker self-heal, a sticky restart) run with the special-use type
 *    only and NEVER attempt the mic/camera types — Android 14+ throws for those from background, so
 *    we don't try (ActiveMatrix's boot path catches this exact exception; we avoid it instead). The
 *    type-free core still runs at full power: motion/light/barometer/magnetics fusion, radio
 *    density, baseline learning, anomaly detection.
 *  - **Foreground starts** (MainActivity onStart re-calls [start] with [EXTRA_FOREGROUND] true)
 *    re-invoke startForeground ADDING microphone|camera — legal with a visible activity, and the
 *    while-in-use access then persists in background for the service's life. That is the whole game.
 *  - Degradation is stepwise and honest: full → no camera → no mic → special-use only; the armed
 *    state is surfaced in the engine's flows and the ongoing notification, never faked.
 *
 * START_STICKY — a deliberate divergence from the deleted sensing service's NOT_STICKY: that one WAS
 * its mic/camera types, so a restart without them was pointless; this one has a valuable type-free
 * core worth resurrecting (a sticky null-intent restart simply takes the background path).
 */
class SensoriumService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var looping = false
    @Volatile private var micArmed = false
    @Volatile private var camArmed = false
    private var lastInteractiveMs = System.currentTimeMillis()

    private val container get() = (application as? PulseApplication)?.container

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val c = container ?: run { stopSelf(); return START_NOT_STICKY }
        // The enabled-toggle is enforced by every caller AND by the loop's first iteration (settings
        // reads are suspend, so a disabled sticky-restart runs one instant heartbeat and stops).

        val foregroundLaunch = intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true
        val wantMic = foregroundLaunch && hasPermission(Manifest.permission.RECORD_AUDIO)
        val wantCam = foregroundLaunch && hasPermission(Manifest.permission.CAMERA)

        // Stepwise arming: full → no camera → no mic → special-use only → give up. A background
        // start goes straight to the last legal rung by construction (wantMic/wantCam false).
        var mic = wantMic
        var cam = wantCam
        val started = tryStartForeground(mic, cam) ||
            run { cam = false; tryStartForeground(mic, cam) } ||
            run { mic = false; cam = wantCam; tryStartForeground(mic, cam) } ||
            run { mic = false; cam = false; tryStartForeground(false, false) }
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Arming can only ever be upgraded by a foreground relaunch — a background restart while
        // already armed must not silently disarm a running engine's sips (the service keeps its
        // while-in-use grants for its lifetime once armed).
        if (mic) micArmed = true
        if (cam) camArmed = true
        c.sensoriumEngine.micArmed.value = micArmed
        c.sensoriumEngine.camArmed.value = camArmed

        if (!looping) {
            looping = true
            c.sensorFusion.start()
            scope.launch { loop() }
        } else {
            updateOngoing(statusText())
        }
        return START_STICKY
    }

    private suspend fun loop() {
        val c = container ?: return
        val engine = c.sensoriumEngine
        var level = Sensorium.SenseLevel.NOMINAL
        var lastNotifMs = 0L
        while (scope.isActive) {
            runCatching {
                val settings = c.settingsRepository.current()
                if (!settings.sensing.enabled) {
                    stopSelf()
                    return
                }
                val device = c.deviceContextProvider.snapshot()
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isInteractive) lastInteractiveMs = System.currentTimeMillis()
                val screenOffMin = ((System.currentTimeMillis() - lastInteractiveMs) / 60_000L).toInt()

                level = Sensorium.level(
                    previous = level,
                    batteryPct = device.batteryPct,
                    charging = device.isCharging,
                    powerSave = pm.isPowerSaveMode,
                    screenOffMinutes = screenOffMin,
                    movement = c.sensorFusion.snapshot.value.movement,
                )
                engine.level.value = level
                val cadence = Sensorium.cadenceFor(level)

                engine.step(
                    cadence = cadence,
                    micAllowed = micArmed && settings.sensing.micSensing,
                    camAllowed = camArmed && settings.sensing.cameraSensing,
                    radioAllowed = settings.sensing.radioSensing &&
                        level != Sensorium.SenseLevel.CONSERVE && level != Sensorium.SenseLevel.STANDDOWN,
                )

                val now = System.currentTimeMillis()
                if (now - lastNotifMs >= NOTIF_REFRESH_MS) {
                    lastNotifMs = now
                    updateOngoing(statusText())
                }
                delay(cadence.fusionHeartbeatSec * 1000L)
            }.onFailure {
                // One bad heartbeat must never kill the watch — back off briefly and continue.
                delay(30_000L)
            }
        }
    }

    private fun statusText(): String {
        val engine = container?.sensoriumEngine
        val readingLine = engine?.reading?.value?.describe() ?: "warming up"
        val armed = buildString {
            append(if (micArmed) "ears armed" else "ears on standby — open the app to arm")
            append(" · ")
            append(if (camArmed) "eyes armed" else "eyes on standby")
        }
        return when (engine?.level?.value) {
            Sensorium.SenseLevel.CONSERVE -> "Conserving battery · $readingLine"
            Sensorium.SenseLevel.STANDDOWN -> "Standing down (battery) · heartbeat only"
            else -> "$readingLine · $armed"
        }
    }

    private fun tryStartForeground(withMic: Boolean, withCam: Boolean): Boolean = runCatching {
        var type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (withCam) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        ServiceCompat.startForeground(this, NOTIF_ID, ongoing(statusText()), type)
    }.isSuccess

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun ongoing(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SensoriumService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(this, R.color.lcars_condition_routine))
            .setSubText("SENSORIUM")
            .setContentTitle("Environment scanner")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateOngoing(text: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, ongoing(text))
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Environment scanner", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    override fun onDestroy() {
        runCatching { container?.sensorFusion?.stop() }
        val c = container
        scope.launch {
            runCatching { c?.ambientAudioSampler?.close() }
            runCatching { c?.ambientCameraSampler?.close() }
            runCatching { c?.sensoriumStore?.flushNow() }
        }.invokeOnCompletion { scope.cancel() }
        c?.sensoriumEngine?.micArmed?.value = false
        c?.sensoriumEngine?.camArmed?.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ONGOING = "sensorium_ongoing"
        private const val NOTIF_ID = 7401
        private const val ACTION_STOP = "dev.mascwa.pulse.data.sensing.STOP"
        private const val EXTRA_FOREGROUND = "foreground_launch"
        private const val NOTIF_REFRESH_MS = 3 * 60_000L

        /** [foregroundLaunch] true ONLY from a visible activity — it arms the mic/camera types. */
        fun start(context: Context, foregroundLaunch: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SensoriumService::class.java).putExtra(EXTRA_FOREGROUND, foregroundLaunch),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SensoriumService::class.java))
        }
    }
}
