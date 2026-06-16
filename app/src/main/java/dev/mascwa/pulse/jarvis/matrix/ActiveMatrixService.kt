package dev.mascwa.pulse.jarvis.matrix

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
import dev.mascwa.pulse.core.telemetry.BanterContextEngine
import dev.mascwa.pulse.core.telemetry.DeviceContext
import dev.mascwa.pulse.core.telemetry.DeviceContextProvider
import dev.mascwa.pulse.jarvis.voice.VoskListener
import dev.mascwa.pulse.jarvis.voice.VoskSpeech
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
    // Wake-word orchestration runs on Main: Vosk delivers its callbacks on the main looper.
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val banter = BanterContextEngine()
    private lateinit var provider: DeviceContextProvider
    private var observing = false

    @Volatile private var waking = false
    @Volatile private var capturing = false

    private val container get() = (application as? PulseApplication)?.container

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
        // Only run the always-on mic if the wake word is enabled AND the permission is held.
        val withMic = intent?.getBooleanExtra(EXTRA_WAKE_WORD, false) == true && hasRecordAudio()
        // Foreground start can throw (ForegroundServiceStartNotAllowed / FGS-type errors on
        // Android 14+). Retry without the mic, then stand down gracefully rather than crash.
        try {
            startForegroundCompat(buildNotification("Active · standing by."), withMic)
        } catch (t: Throwable) {
            val recovered = withMic && runCatching {
                startForegroundCompat(buildNotification("Active · standing by."), withMic = false)
            }.isSuccess
            if (!recovered) {
                stopSelf()
                return START_NOT_STICKY
            }
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
        if (withMic && !waking) {
            waking = true
            startWakeWord()
        }
        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification, withMic: Boolean) {
        var type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Provision/load the speech model, then begin keyword spotting for the wake word. */
    private fun startWakeWord() {
        val vosk = container?.voskSpeech ?: return
        voiceScope.launch {
            if (!vosk.ensureModel()) {
                update("Wake word unavailable — voice model couldn't load.")
                return@launch
            }
            listenForWake(vosk)
        }
    }

    private fun listenForWake(vosk: VoskSpeech) {
        capturing = false
        update("Listening for \"J.A.R.V.I.S.\"…")
        vosk.start(grammar = WAKE_GRAMMAR, listener = object : VoskListener {
            override fun onPartial(text: String) { maybeWake(vosk, text) }
            override fun onFinal(text: String) { maybeWake(vosk, text) }
            override fun onError(message: String) { /* keep the resident notice; no retry storm */ }
        })
    }

    private fun maybeWake(vosk: VoskSpeech, text: String) {
        if (capturing || !text.contains("jarvis", ignoreCase = true)) return
        capturing = true
        voiceScope.launch { captureCommand(vosk) }
    }

    /** After the wake word, capture one free-form command, answer it, and speak the reply. */
    private fun captureCommand(vosk: VoskSpeech) {
        update("Yes? Listening…")
        vosk.start(grammar = null, listener = object : VoskListener {
            override fun onPartial(text: String) { if (text.isNotBlank()) update("◌ $text") }
            override fun onFinal(text: String) {
                voiceScope.launch {
                    if (text.isNotBlank()) respond(vosk, text) else listenForWake(vosk)
                }
            }
            override fun onError(message: String) {
                voiceScope.launch { listenForWake(vosk) }
            }
        })
    }

    private suspend fun respond(vosk: VoskSpeech, command: String) {
        vosk.stop()
        update("Thinking…")
        val engine = container?.inferenceEngine
        if (engine == null) {
            listenForWake(vosk)
            return
        }
        runCatching { engine.ensureReady() }
        val sb = StringBuilder()
        runCatching {
            engine.generate(command, emptyList(), SYSTEM_PROMPT).collect { sb.append(it) }
        }
        val reply = sb.toString().ifBlank { "Standing by." }
        update(reply.take(140))
        runCatching { container?.textToSpeech?.speak(reply) }
        listenForWake(vosk)
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
        waking = false
        runCatching { container?.voskSpeech?.stop() }
        voiceScope.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "jarvis_active_matrix"
        private const val NOTIF_ID = 7301
        private const val ACTION_STOP = "dev.mascwa.pulse.jarvis.matrix.STOP"
        private const val EXTRA_WAKE_WORD = "dev.mascwa.pulse.jarvis.matrix.WAKE_WORD"

        // Keyword-spotting grammar: only "jarvis" plus the unknown-word token.
        private const val WAKE_GRAMMAR = "[\"jarvis\", \"[unk]\"]"
        private const val SYSTEM_PROMPT =
            "You are J.A.R.V.I.S. Matrix, a concise, deadpan, privacy-first on-device assistant. " +
                "You run entirely on the user's phone. Be brief and helpful. Never invent facts."

        fun start(context: Context, wakeWord: Boolean = false) {
            val intent = Intent(context, ActiveMatrixService::class.java)
                .putExtra(EXTRA_WAKE_WORD, wakeWord)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ActiveMatrixService::class.java))
        }
    }
}
