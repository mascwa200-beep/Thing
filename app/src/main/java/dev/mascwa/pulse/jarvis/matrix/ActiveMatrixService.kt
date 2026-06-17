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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.BanterContextEngine
import dev.mascwa.pulse.core.telemetry.DeviceContext
import dev.mascwa.pulse.core.telemetry.DeviceContextProvider
import dev.mascwa.pulse.data.jarvis.db.Speaker
import dev.mascwa.pulse.jarvis.JarvisPersona
import dev.mascwa.pulse.jarvis.inference.ChatTurn
import dev.mascwa.pulse.jarvis.voice.DeviceSpeechRecognizer
import dev.mascwa.pulse.jarvis.voice.SttModelStore
import dev.mascwa.pulse.jarvis.voice.VoskListener
import dev.mascwa.pulse.jarvis.voice.VoskSpeech
import dev.mascwa.pulse.notifications.BreakingNewsPulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps J.A.R.V.I.S. resident: a foreground service that watches device context and
 * surfaces proactive remarks in an ongoing notification, so the assistant has a presence
 * even when the app isn't open. Everything is computed on-device — nothing is transmitted.
 */
class ActiveMatrixService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Wake-word orchestration runs on Main (non-immediate on purpose): Vosk delivers callbacks on
    // the main looper, and restarting the recognizer must be POSTED off the callback frame — never
    // run synchronously inside it (stop() joins the recognizer thread).
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val banter = BanterContextEngine()
    private lateinit var provider: DeviceContextProvider
    private var observing = false

    @Volatile private var waking = false
    @Volatile private var capturing = false
    @Volatile private var pollingNews = false

    // Rolling spoken-conversation context for follow-up / conversation mode (reset on each new wake).
    private val convo = ArrayDeque<ChatTurn>()
    @Volatile private var convoTurns = 0
    @Volatile private var convoStartedAt = 0L

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
        val wantWake = intent?.getBooleanExtra(EXTRA_WAKE_WORD, false) == true
        val hasMic = hasRecordAudio()
        val wantMic = wantWake && hasMic
        Log.i(TAG, "onStartCommand: wantWake=$wantWake hasMicPermission=$hasMic waking=$waking sdk=${Build.VERSION.SDK_INT}")
        // Foreground start can throw (ForegroundServiceStartNotAllowed / FGS-type errors on
        // Android 14+). Retry without the mic, then stand down gracefully rather than crash.
        // micActive tracks whether the *microphone* FGS type actually started — we must NOT open
        // the mic if we fell back to a non-mic foreground service.
        var micActive = wantMic
        try {
            startForegroundCompat(buildNotification("Active · standing by."), wantMic)
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand: startForeground(withMic=$wantMic) failed; retrying without mic", t)
            micActive = false
            val recovered = wantMic && runCatching {
                startForegroundCompat(buildNotification("Active · standing by."), withMic = false)
            }.isSuccess
            if (!recovered) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Make a non-running wake word explainable instead of silent.
        if (wantWake && !hasMic) {
            Log.w(TAG, "Wake word requested but RECORD_AUDIO not granted")
            update("Wake word off — grant the Microphone permission, then re-enable it.")
        } else if (wantWake && !micActive) {
            Log.w(TAG, "Wake word requested but the foreground microphone service couldn't start")
            update("Wake word off — the system blocked the microphone service. Reopen the app and retry.")
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
        if (micActive && !waking) {
            waking = true
            startWakeWord()
        }
        // Near-real-time breaking news while the resident assistant is up (opt-in; ~90s cadence).
        // This is the only legitimate sub-15-minute path on Android (a periodic worker floors at 15m).
        if (!pollingNews) {
            pollingNews = true
            scope.launch { liveNewsLoop() }
        }
        return START_STICKY
    }

    /** Poll the TOP feed on a short interval and push genuinely new headlines immediately. Cheap and
     *  guarded: it only fetches when the user has enabled live breaking news and isn't in quiet hours. */
    private suspend fun liveNewsLoop() {
        while (true) {
            runCatching {
                val c = container
                val prefs = c?.settingsRepository?.current()?.notifications
                if (c != null && prefs != null && prefs.masterEnabled && prefs.breakingNews &&
                    prefs.liveBreakingNews && !inQuietNow(prefs)
                ) {
                    BreakingNewsPulse.check(c)
                }
            }
            delay(LIVE_NEWS_INTERVAL_MS)
        }
    }

    private fun inQuietNow(prefs: dev.mascwa.pulse.data.settings.NotificationPrefs): Boolean {
        if (!prefs.quietHoursEnabled) return false
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val s = prefs.quietStartHour
        val e = prefs.quietEndHour
        return if (s <= e) hour in s until e else hour >= s || hour < e
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
            // Reflect the model download/unpack so the user sees progress, not a frozen "standing by".
            // The wake model is ~128 MB; the first run downloads + unpacks it (one time).
            update("Preparing voice model…")
            val progress = launch {
                vosk.wakeProvisioning.collect { st ->
                    when (st) {
                        is SttModelStore.State.Downloading -> update("Downloading voice model ${st.pct}% (one-time)…")
                        is SttModelStore.State.Unpacking -> update("Unpacking voice model…")
                        else -> {}
                    }
                }
            }
            val ok = vosk.ensureWakeModel()
            progress.cancel()
            if (!ok) {
                Log.w(TAG, "startWakeWord: model unavailable")
                update("Wake word unavailable — voice model couldn't load (check storage/network).")
                return@launch
            }
            Log.i(TAG, "startWakeWord: model ready, entering wake loop")
            // The console and the wake loop share one recognizer. Pause while the console owns the
            // mic for tap-to-talk; resume automatically when it's released. (StateFlow emits its
            // current value on collect, so this also performs the initial start.)
            vosk.consoleActive.collect { inConsole ->
                if (inConsole) {
                    vosk.stop()
                    update("Paused — console has the mic.")
                } else if (waking) {
                    listenForWake(vosk)
                }
            }
        }
    }

    private fun listenForWake(vosk: VoskSpeech) {
        if (vosk.consoleActive.value) {
            update("Paused — console has the mic.")
            return
        }
        resetConvo() // back to idle: any open conversation is over
        capturing = false
        update("Listening for \"J.A.R.V.I.S.\"…")
        // Wake model (128 MB lgraph) with a keyword grammar: tight, cheap spotting for an always-on
        // mic. The lenient isWakePhrase below still catches the near-homophones the model emits.
        val started = vosk.start(dictation = false, grammar = WAKE_GRAMMAR, listener = object : VoskListener {
            override fun onPartial(text: String) { maybeWake(vosk, text) }
            override fun onFinal(text: String) { maybeWake(vosk, text) }
            override fun onError(message: String) {
                // Don't die silently: show it and self-heal after a backoff (no tight retry storm).
                Log.w(TAG, "wake recognizer error: $message")
                update("Mic hiccup ($message) — relistening shortly…")
                voiceScope.launch {
                    delay(WAKE_RETRY_MS)
                    if (waking && !capturing && !vosk.consoleActive.value) listenForWake(vosk)
                }
            }
        })
        if (!started) {
            Log.e(TAG, "listenForWake: vosk.start returned false (mic unavailable)")
            update("Couldn't open the microphone for the wake word — retrying shortly…")
            voiceScope.launch {
                delay(WAKE_RETRY_MS)
                if (waking && !capturing && !vosk.consoleActive.value) listenForWake(vosk)
            }
        }
    }

    private fun maybeWake(vosk: VoskSpeech, text: String) {
        if (capturing || !isWakePhrase(text)) return
        Log.i(TAG, "wake phrase detected in: \"$text\"")
        capturing = true
        voiceScope.launch { captureCommand(vosk) }
    }

    /** Lenient wake detection: accepts "jarvis"/"hey jarvis"/etc., the near-homophones the small STT
     *  model tends to mishear, and any token within an edit distance of 1 of "jarvis". */
    private fun isWakePhrase(text: String): Boolean {
        val lower = text.lowercase()
        if (WAKE_WORDS.any { lower.contains(it) }) return true
        return lower.split(Regex("[^a-z]+")).any { it.length in 4..7 && levenshtein(it, "jarvis") <= 1 }
    }

    /** Iterative Levenshtein edit distance (small strings; allocation-light). */
    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** After the wake word, capture one free-form command, answer it, and speak the reply.
     *  Prefer the device's on-device Google recognizer (far more accurate, private, no app memory);
     *  fall back to the offline Vosk model where on-device recognition isn't available. A timeout
     *  re-arms the wake loop if the user says nothing (so `capturing` never latches). */
    private fun captureCommand(vosk: VoskSpeech) {
        val google = container?.deviceSpeech
        if (google != null && google.available) {
            update("Yes, sir? Listening…")
            vosk.stop() // hand the mic to the system recognizer (no two-mic contention)
            google.listen(COMMAND_TIMEOUT_MS, object : VoskListener {
                override fun onPartial(text: String) { if (text.isNotBlank()) update("◌ $text") }
                override fun onFinal(text: String) {
                    Log.i(TAG, "command heard (on-device): \"$text\"")
                    voiceScope.launch { if (text.isNotBlank()) respond(vosk, text) else listenForWake(vosk) }
                }
                override fun onError(message: String) {
                    if (message == DeviceSpeechRecognizer.UNAVAILABLE) {
                        Log.w(TAG, "on-device recognizer unavailable — falling back to Vosk")
                        voiceScope.launch { captureCommandVosk(vosk) }
                    } else {
                        Log.w(TAG, "on-device command error: $message — re-arming wake")
                        voiceScope.launch { listenForWake(vosk) }
                    }
                }
                override fun onTimeout() { Log.i(TAG, "on-device command timeout — re-arming wake"); voiceScope.launch { listenForWake(vosk) } }
            })
        } else {
            captureCommandVosk(vosk)
        }
    }

    /** Offline fallback command capture on the Vosk wake model (used when on-device recognition
     *  isn't available). Same 128 MB model as wake spotting, so it never loads the heavy model. */
    private fun captureCommandVosk(vosk: VoskSpeech) {
        update("Yes, sir? Listening…")
        vosk.start(dictation = false, grammar = null, timeoutMs = COMMAND_TIMEOUT_MS, listener = object : VoskListener {
            override fun onPartial(text: String) { if (text.isNotBlank()) update("◌ $text") }
            override fun onFinal(text: String) {
                Log.i(TAG, "command heard (vosk): \"$text\"")
                voiceScope.launch {
                    if (text.isNotBlank()) respond(vosk, text) else listenForWake(vosk)
                }
            }
            override fun onError(message: String) { Log.w(TAG, "command error: $message"); voiceScope.launch { listenForWake(vosk) } }
            override fun onTimeout() { Log.i(TAG, "command timeout — re-arming wake"); voiceScope.launch { listenForWake(vosk) } }
        })
    }

    private suspend fun respond(vosk: VoskSpeech, command: String) {
        vosk.stop()
        // Honour an explicit "stop" before doing anything else.
        if (isStopCue(command)) {
            endConversation(vosk, "Very good, sir.")
            return
        }
        update("One moment…")
        val engine = container?.inferenceEngine
        if (engine == null) {
            endConversation(vosk, null)
            return
        }
        val jarvisSettings = runCatching { container?.settingsRepository?.current()?.jarvis }.getOrNull()
        val followUp = jarvisSettings?.followUpMode == true
        val conversation = jarvisSettings?.conversationMode == true
        try {
            runCatching { engine.ensureReady() }
            var persona = JarvisPersona.compose(
                runCatching { container?.selfEditStore?.current()?.charter }.getOrNull().orEmpty(),
            )
            // Only in conversation mode: let the model self-direct whether to keep the floor open.
            // (Appended here, not to the global persona, so the markers never leak into the console.)
            if (conversation) persona += CONVO_HINT

            if (convoTurns == 0) convoStartedAt = System.currentTimeMillis()
            val history = convo.toList()
            convo.addLast(ChatTurn(Speaker.USER, command))

            val sb = StringBuilder()
            engine.generate(command, history, persona).collect { sb.append(it) }
            var reply = sb.toString().ifBlank { "Standing by, sir." }
            // Autonomous floor-control: explicit markers win; otherwise a trailing "?" implies it
            // expects an answer. Strip markers before showing/speaking either way.
            val wantsClose = reply.contains(MARK_CLOSE)
            val wantsOpen = reply.contains(MARK_OPEN) || reply.trimEnd().endsWith("?")
            reply = stripMarkers(reply)
            convo.addLast(ChatTurn(Speaker.JARVIS, reply))
            convoTurns++
            while (convo.size > CONVO_BUFFER) convo.removeFirst()
            update(reply.take(140))

            val overLimit = convoTurns >= MAX_CONVO_TURNS ||
                System.currentTimeMillis() - convoStartedAt > MAX_CONVO_MS
            val keepOpen = !wantsClose && !overLimit && (followUp || (conversation && wantsOpen))

            when {
                // Keep the floor: reopen the mic (no wake word) once J.A.R.V.I.S. stops speaking.
                keepOpen -> speakThen(reply) { if (waking) captureCommand(vosk) else listenForWake(vosk) }
                // Conversation mode decides to wind down (model closed, or we hit the safeguard): it
                // says so, but checks first — if you keep talking it resumes; silence/"stop" ends it.
                conversation && (wantsClose || overLimit) -> speakThen(reply) { wrapUp(vosk) }
                // Plain answer: speak it and return to wake listening.
                else -> speakThen(reply) { resetConvo(); listenForWake(vosk) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // honour service teardown — don't re-arm the mic on a dying service
        } catch (e: Throwable) {
            Log.e(TAG, "respond: failed to answer", e)
            update("I couldn't answer that one, sir.")
            resetConvo()
            listenForWake(vosk)
        }
    }

    /** Speak [text], then run [next] on the main thread once speech finishes (so the mic never picks
     *  up J.A.R.V.I.S.'s own voice). Falls straight through to [next] if TTS is unavailable. */
    private fun speakThen(text: String, next: () -> Unit) {
        val tts = container?.textToSpeech
        if (tts == null) { voiceScope.launch { next() } } else tts.speak(text) { voiceScope.launch { next() } }
    }

    /** J.A.R.V.I.S. signals it's winding down but leaves the floor open: it asks if that's all, then
     *  listens once more. If you keep talking it resumes with a fresh budget; silence (or "stop") ends
     *  it — so it never cuts you off while you still want to talk. */
    private fun wrapUp(vosk: VoskSpeech) {
        convoTurns = 0
        convoStartedAt = System.currentTimeMillis() // fresh budget if the user carries on
        speakThen("Will that be all, sir?") { if (waking) captureCommand(vosk) else listenForWake(vosk) }
    }

    /** End any open conversation: optionally speak a closing line, then return to wake listening. */
    private fun endConversation(vosk: VoskSpeech, closing: String?) {
        resetConvo()
        if (closing == null) { listenForWake(vosk); return }
        update(closing)
        speakThen(closing) { listenForWake(vosk) }
    }

    private fun resetConvo() {
        convo.clear()
        convoTurns = 0
        convoStartedAt = 0L
    }

    /** A short, whole-utterance request to end the exchange (kept tight to avoid false positives). */
    private fun isStopCue(text: String): Boolean {
        val t = text.lowercase().trim().trim('.', '!', ',')
        if (t.split(Regex("\\s+")).size > 5) return false
        return STOP_CUES.any { t == it || t.endsWith(" $it") || t.startsWith("$it ") }
    }

    private fun stripMarkers(text: String): String =
        text.replace(MARK_OPEN, "").replace(MARK_CLOSE, "").trim()

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
        runCatching { container?.deviceSpeech?.cancel() } // release the system recognizer + mic
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

        // Keyword-spotting grammar for the small wake model: the wake word, common lead-ins, and the
        // unknown-word token. Vosk only emits words it's told about, so multi-word forms are explicit.
        private const val WAKE_GRAMMAR =
            "[\"jarvis\", \"hey jarvis\", \"ok jarvis\", \"okay jarvis\", \"hi jarvis\", \"[unk]\"]"
        // Near-homophones the STT model often produces for "jarvis"; matched leniently below.
        private val WAKE_WORDS = listOf("jarvis", "jervis", "jarvas", "jarvix", "javis", "travis", "charvis")
        private const val TAG = "JarvisVoice"
        // Re-arm the wake loop if no command is spoken within this window after waking.
        private const val COMMAND_TIMEOUT_MS = 8000
        // Backoff before re-arming the wake loop after a recognizer/mic error (avoids a tight storm).
        private const val WAKE_RETRY_MS = 4000L
        // Live breaking-news poll cadence — as fresh as Android allows without a push server.
        private const val LIVE_NEWS_INTERVAL_MS = 90_000L

        // --- Follow-up / conversation mode ---
        // Safeguards so an open conversation can't run forever (it then wraps up on its own).
        private const val MAX_CONVO_TURNS = 8
        private const val MAX_CONVO_MS = 5 * 60_000L
        // How many recent turns of context to keep (Part A's input clamp trims further at render time).
        private const val CONVO_BUFFER = 12
        // Autonomous floor-control markers (stripped before display/speech).
        private const val MARK_OPEN = "[[OPEN]]"
        private const val MARK_CLOSE = "[[CLOSE]]"
        private const val CONVO_HINT =
            "\n\nYou are mid-conversation by voice; keep replies brief and natural. If you expect the " +
                "user to respond, end with [[OPEN]]; if the conversation is naturally complete, end with [[CLOSE]]."
        // Short whole-utterance phrases that end an exchange.
        private val STOP_CUES = listOf(
            "stop", "that's all", "thats all", "that is all", "thank you", "thanks",
            "thank you jarvis", "thanks jarvis", "goodbye", "bye", "dismiss", "stand down",
            "that'll be all", "thatll be all", "nevermind", "never mind", "cancel", "be quiet",
        )

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
