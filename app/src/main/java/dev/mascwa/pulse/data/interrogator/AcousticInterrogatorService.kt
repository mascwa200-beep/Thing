package dev.mascwa.pulse.data.interrogator

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
import dev.mascwa.pulse.R
import dev.mascwa.pulse.feature.media.MicFloor
import dev.mascwa.pulse.notifications.NotifId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The acoustic interrogator's foreground service — stage 0's host.
 *
 * Holds the microphone continuously, hands each detected utterance to [InterrogatorCascade], and
 * keeps an ongoing notification up for as long as it is listening. That notification is not
 * decoration and not merely a platform requirement: it is the only always-visible statement that
 * this is running, which matters more here than for anything else in the app.
 *
 * ⚠️ **NOT STICKY, UNLIKE [dev.mascwa.pulse.data.sensing.SensoriumService].** That one is worth
 * resurrecting because its type-free core still does useful work. This one is not, for two separate
 * reasons and either alone would be enough. A system-initiated restart is a background start, and a
 * background start cannot arm the `microphone` foreground-service type on Android 14+ — so a sticky
 * restart would come back deaf, holding the floor away from the wake word while recording nothing.
 * And re-opening a microphone that captures conversation, unasked, after the system killed it, is
 * not a decision to make on the user's behalf.
 */
class AcousticInterrogatorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false

    private val container get() = (application as? PulseApplication)?.container

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // ⚠️ Turn the FEATURE off, not just this instance — the Sensorium's Stop button undid
            // itself for exactly this reason, coming back within a worker period with the Settings
            // switch still reading ON. On the application scope, because onDestroy cancels this
            // service's own scope as soon as its teardown finishes and would race the write away.
            val app = application as? PulseApplication
            val repo = app?.container?.settingsRepository
            if (app != null && repo != null) {
                app.appScope.launch {
                    runCatching { repo.update { it.copy(sensing = it.sensing.copy(interrogator = false)) } }
                }
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val c = container ?: run { stopSelf(); return START_NOT_STICKY }

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            stopSelf()
            return START_NOT_STICKY
        }

        // ⚠️ Foreground FIRST, before any work — a service that reaches its first suspension point
        // without having called startForeground is killed with
        // ForegroundServiceDidNotStartInTimeException, which is a crash rather than a failure.
        if (!tryStartForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) return START_NOT_STICKY
        running = true

        // Claim the floor BEFORE opening the recorder, so the wake loop has already released the
        // microphone by the time this one asks the platform for it.
        MicFloor.claim()

        scope.launch {
            // Whisper first: with no model there is nothing to transcribe, and holding the
            // microphone to throw the audio away would be the worst of both.
            if (!c.whisperEngine.prepare()) {
                update(getString(R.string.interrogator_no_model))
                standDown()
                return@launch
            }
            // The adjudicator is optional by design — see LlamaEngine. allowDownload stays false:
            // a gigabyte is fetched from a tap, never because a service started.
            c.llamaEngine.prepare(allowDownload = false)
            update(statusText())

            val capture = InterrogatorCapture { pcm, cut ->
                runCatching { c.interrogatorCascade.process(pcm, cut) }
            }
            val opened = capture.run()
            // run() only returns when the recorder could not be opened at all, or when this scope is
            // cancelled. Neither resolves by retrying in a loop.
            if (!opened) update(getString(R.string.interrogator_no_mic))
            standDown()
        }
        return START_NOT_STICKY
    }

    private fun standDown() {
        stopSelf()
    }

    private fun tryStartForeground(): Boolean = runCatching {
        var type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, ongoing(statusText()), type)
    }.isSuccess

    private fun statusText(): String = getString(R.string.interrogator_listening)

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun ongoing(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            // Its own id as the request code: this intent carries a route, and every extras-free
            // `Intent(MainActivity)` at request code 0 in the app is one and the same PendingIntent.
            NOTIF_ID,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, ROUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            // The SAME request code as the open intent, deliberately: PendingIntent identity is the
            // request code plus `Intent.filterEquals`, which compares component and action — these
            // two differ in both, so they are already distinct. Inventing NOTIF_ID + 1 would put an
            // unregistered number into the space that NotifIdTest exists to keep unique.
            NOTIF_ID,
            Intent(this, AcousticInterrogatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(getString(R.string.interrogator_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.interrogator_stop), stop)
            .build()
    }

    private fun update(text: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.notify(NOTIF_ID, ongoing(text))
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    getString(R.string.interrogator_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onDestroy() {
        // ⚠️ The floor is released before the scope is cancelled. Leaving the claim standing would
        // leave the wake word stood down forever, with nothing on screen to say why — the latching
        // failure the TTS watchdog exists to prevent, in a different subsystem.
        MicFloor.release()
        runCatching { scope.cancel() }
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ONGOING = "interrogator_ongoing"
        private const val NOTIF_ID = NotifId.FGS_INTERROGATOR
        private const val ACTION_STOP = "dev.mascwa.pulse.data.interrogator.STOP"

        /** Where the notification's tap lands. */
        const val ROUTE = "interrogator"

        /**
         * Start listening.
         *
         * ⚠️ Only from a visible activity. The `microphone` foreground-service type cannot be armed
         * from a background start on Android 14+, so a background caller would produce a service
         * that holds the floor and records nothing.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AcousticInterrogatorService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AcousticInterrogatorService::class.java))
        }
    }
}
