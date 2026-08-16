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
import dev.mascwa.pulse.core.telemetry.EmergencyTriage
import dev.mascwa.pulse.core.telemetry.VoiceMachine
import dev.mascwa.pulse.core.telemetry.VoiceMachine.console
import dev.mascwa.pulse.core.telemetry.VoiceMachine.micFailed
import dev.mascwa.pulse.core.telemetry.VoiceMachine.settle
import dev.mascwa.pulse.core.telemetry.VoiceMachine.speaking
import dev.mascwa.pulse.core.telemetry.VoiceMachine.wakeHeard
import dev.mascwa.pulse.core.telemetry.VoiceMachine.wants
import dev.mascwa.pulse.core.telemetry.WakePhrase
import dev.mascwa.pulse.data.jarvis.db.Speaker
import dev.mascwa.pulse.jarvis.JarvisPersona
import dev.mascwa.pulse.jarvis.agent.AgentOrchestrator
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

    /**
     * Who holds the microphone, decided by [VoiceMachine] rather than by whichever call site got
     * there first. Written only from [voiceScope] (the main looper); volatile so the context and
     * battery observers on [scope] see it.
     */
    @Volatile private var voice = VoiceMachine.State()

    /** Whether the wake loop is meant to be running at all. */
    private val waking get() = voice.wanted

    /**
     * Whether a spoken exchange is in flight — capturing a command, or speaking the reply.
     *
     * Speaking counts. Anything that calls `TextToSpeech.speak` mid-exchange QUEUE_FLUSHes the
     * utterance in progress and takes its done-callback with it, which is what re-arms the mic; the
     * previous `capturing`-only guard covered the capture and left the reply exposed, which is the
     * larger of the two windows.
     */
    private val voiceBusy get() =
        voice.owner == VoiceMachine.Owner.COMMAND || voice.owner == VoiceMachine.Owner.SPEAKING

    @Volatile private var pollingNews = false

    // Critical-battery care: conserve (pause heavy polling) + warn once until power recovers.
    @Volatile private var lowPowerConserve = false
    @Volatile private var batteryWarned = false

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
                        if (!line.isNullOrBlank()) {
                            update(line)
                            maybeSpeakProactive(line)
                        }
                        handleBattery(now)
                    }
                }
            }
        }
        if (micActive && !waking) {
            // Marks intent only — the mic is taken in startWakeWord, once the model has loaded.
            // Setting it here is what makes the `!waking` guard above re-entrant: a second start
            // command arriving during the (suspending) model load must not launch a second loop.
            voice = voice.copy(wanted = true)
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

    /** Speak a proactive context remark aloud when the user has opted in — but never while the console is
     *  open, mid-command, or in quiet hours, so it can't talk over the user or surprise them at night. */
    private suspend fun maybeSpeakProactive(line: String) {
        if (voiceBusy) return
        val c = container ?: return
        if (runCatching { c.voskSpeech.consoleActive.value }.getOrDefault(false)) return
        val prefs = runCatching { c.settingsRepository.current() }.getOrNull() ?: return
        if (!prefs.jarvis.speakProactive || inQuietNow(prefs.notifications)) return
        runCatching { c.textToSpeech.speak(line) }
    }

    /** Self-preservation: at critical battery, conserve (pause heavy polling) and warn once — spoken if
     *  voice is available — then stand down quietly. Resets when charging or the level recovers. */
    private fun handleBattery(ctx: DeviceContext) {
        if (ctx.isCriticalBattery) {
            lowPowerConserve = true
            if (!batteryWarned) {
                batteryWarned = true
                val msg = "Battery critical at ${ctx.batteryPct}% — conserving power."
                update("⚠ $msg")
                // Never mid-exchange: a TTS speak() here QUEUE_FLUSHes an in-flight spoken reply and
                // takes its done-callback with it, so the wake mic never re-arms. The guard covers the
                // reply as well as the capture — the reply is the longer window and was the exposed one.
                // The notification shows regardless.
                if (!voiceBusy) runCatching { container?.textToSpeech?.speak(msg) }
            }
        } else if (ctx.isCharging || ctx.batteryPct >= BATTERY_RECOVER_PCT) {
            lowPowerConserve = false
            batteryWarned = false
        }
    }

    /** Poll the news feeds on a short interval and keep the one LCARS board (and the breaking-news
     *  takeover check) near-real-time. Cheap and guarded: it only fetches when the user has enabled live
     *  news polling and isn't in quiet hours. */
    private suspend fun liveNewsLoop() {
        while (true) {
            runCatching {
                val c = container
                val settings = c?.settingsRepository?.current()
                val prefs = settings?.notifications
                if (c != null && settings != null && prefs != null && prefs.masterEnabled &&
                    prefs.liveBreakingNews && !inQuietNow(prefs) && !lowPowerConserve
                ) {
                    // The takeover check force-fetches the feeds (warming the cache) and fires the
                    // full-screen takeover on a fresh MAJOR event; the board republish then reads warm.
                    if (prefs.breakingInterrupt) BreakingNewsPulse.check(c)
                    dev.mascwa.pulse.notifications.BriefEngine.publish(
                        context = applicationContext,
                        container = c,
                        settings = settings,
                        forceNews = !prefs.breakingInterrupt, // if the pulse didn't fetch, fetch here
                    )
                }
            }
            delay(LIVE_NEWS_INTERVAL_MS)
        }
    }

    private fun inQuietNow(prefs: dev.mascwa.pulse.data.settings.NotificationPrefs): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return dev.mascwa.pulse.core.telemetry.QuietHours.isQuiet(
            prefs.quietHoursEnabled, prefs.quietStartHour, prefs.quietEndHour, hour,
        )
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
            // Seed the arbiter with the console's current claim BEFORE taking the mic, so a wake
            // session is never opened underneath a console that already holds it — then start.
            voice = voice.copy(console = vosk.consoleActive.value)
            perform(voice.wants(true), vosk)
            // The console and the wake loop share one recogniser; the arbiter decides who holds it.
            // The first replayed emission is therefore a no-op, which is what idempotence buys.
            vosk.consoleActive.collect { inConsole ->
                if (inConsole) update("Paused — console has the mic.")
                perform(voice.console(inConsole), vosk)
            }
        }
    }

    /**
     * Apply one decision from the arbiter: **take the new state now, do the work next frame.**
     *
     * Taking the state synchronously is what makes the machine's idempotence real — a second caller
     * racing this one already sees the new owner and is told to do nothing. Posting the work is the
     * long-standing constraint of this file: Vosk delivers on the main looper and `stop()` joins the
     * recogniser thread, so a restart must never run inside the callback frame that triggered it.
     */
    private fun perform(step: VoiceMachine.Step, vosk: VoskSpeech) {
        voice = step.state
        when (step.action) {
            VoiceMachine.Action.NOTHING -> Unit
            VoiceMachine.Action.START_WAKE -> voiceScope.launch { startWakeRecognizer(vosk) }
            VoiceMachine.Action.START_COMMAND -> voiceScope.launch { startCommandRecognizer(vosk) }
            VoiceMachine.Action.RELEASE_MIC -> voiceScope.launch { releaseMic(vosk) }
        }
    }

    /**
     * **The single re-arm funnel.** Nothing is in flight — ask the arbiter where the mic belongs.
     *
     * Every path that used to call `listenForWake` itself calls this instead: a blank capture, a
     * timeout, a recogniser error, the end of a reply, the console closing. They were each answering
     * the same question separately, and inconsistently.
     */
    private fun rearm(vosk: VoskSpeech, holdFloor: Boolean = false) =
        perform(voice.settle(holdFloor), vosk)

    /**
     * Let go of the mic — both recognisers, because a command capture may be running on the system
     * one while the offline recogniser holds nothing. Only ever reached when we actually had it.
     */
    private fun releaseMic(vosk: VoskSpeech) {
        vosk.stop()
        runCatching { container?.deviceSpeech?.cancel() }
    }

    /** A recogniser died: drop ownership, back off, then ask the arbiter again rather than retrying blind. */
    private fun retryAfterFailure(vosk: VoskSpeech) {
        perform(voice.micFailed(), vosk)
        voiceScope.launch {
            delay(WAKE_RETRY_MS)
            rearm(vosk)
        }
    }

    /** Listen for the wake word. Reached only through [perform]; nothing else calls it. */
    private fun startWakeRecognizer(vosk: VoskSpeech) {
        resetConvo() // arriving back at the wake word means any open conversation is over
        update("Listening for \"${WakePhrase.WORD.replaceFirstChar { it.uppercase() }}\"…")
        // Wake model (128 MB lgraph) with a keyword grammar: tight, cheap spotting for an always-on
        // mic. WakePhrase still catches the near-homophones the model emits.
        val started = vosk.start(dictation = false, grammar = WAKE_GRAMMAR, listener = object : VoskListener {
            override fun onPartial(text: String) { maybeWake(vosk, text) }
            override fun onFinal(text: String) { maybeWake(vosk, text) }
            override fun onError(message: String) {
                // Don't die silently: show it and self-heal after a backoff (no tight retry storm).
                Log.w(TAG, "wake recognizer error: $message")
                update("Mic hiccup ($message) — relistening shortly…")
                retryAfterFailure(vosk)
            }
        })
        if (!started) {
            Log.e(TAG, "startWakeRecognizer: vosk.start returned false (mic unavailable)")
            update("Couldn't open the microphone for the wake word — retrying shortly…")
            retryAfterFailure(vosk)
        }
    }

    private fun maybeWake(vosk: VoskSpeech, text: String) {
        if (!isWakePhrase(text)) return
        // Only the wake recogniser can open a capture. A partial arriving late — after the console
        // took the mic, or after a capture already began — is discarded rather than starting a second
        // session underneath whoever now holds it.
        val step = voice.wakeHeard()
        if (step.action == VoiceMachine.Action.NOTHING) return
        Log.i(TAG, "wake phrase detected in: \"$text\"")
        perform(step, vosk)
    }

    /**
     * Wake detection now lives in [WakePhrase], a tested core.
     *
     * It was moved because the version here gated its fuzzy pass on `token.length in 4..7` — sized
     * for "jarvis". Renaming the wake word to an eight-letter one would have left the strict matches
     * working and the lenient ones silently dead, with nothing to report it. The window is derived
     * from the word now, and there is a test that fails if it ever excludes the word again.
     */
    private fun isWakePhrase(text: String): Boolean = WakePhrase.matches(text)

    /** After the wake word, capture one free-form command, answer it, and speak the reply.
     *  Prefer the device's on-device Google recognizer (far more accurate, private, no app memory);
     *  fall back to the offline Vosk model where on-device recognition isn't available. Every exit —
     *  a transcript, silence, a timeout, an error — goes through [rearm], so ownership cannot latch. */
    private fun startCommandRecognizer(vosk: VoskSpeech) {
        val google = container?.deviceSpeech
        if (google != null && google.available) {
            update("Listening.")
            vosk.stop() // hand the mic to the system recognizer (no two-mic contention)
            google.listen(COMMAND_TIMEOUT_MS, object : VoskListener {
                override fun onPartial(text: String) { if (text.isNotBlank()) update("◌ $text") }
                override fun onFinal(text: String) {
                    Log.i(TAG, "command heard (on-device): \"$text\"")
                    voiceScope.launch {
                        if (text.isBlank()) rearm(vosk) else respond(vosk, interpret(text))
                    }
                }
                override fun onError(message: String) {
                    if (message == DeviceSpeechRecognizer.UNAVAILABLE) {
                        Log.w(TAG, "on-device recognizer unavailable — falling back to Vosk")
                        voiceScope.launch { captureCommandVosk(vosk) }
                    } else {
                        Log.w(TAG, "on-device command error: $message — re-arming wake")
                        voiceScope.launch { rearm(vosk) }
                    }
                }
                override fun onTimeout() { Log.i(TAG, "on-device command timeout — re-arming wake"); voiceScope.launch { rearm(vosk) } }
            })
        } else {
            captureCommandVosk(vosk)
        }
    }

    /** Offline fallback command capture on the Vosk wake model (used when on-device recognition
     *  isn't available). Same 128 MB model as wake spotting, so it never loads the heavy model. */
    private fun captureCommandVosk(vosk: VoskSpeech) {
        update("Listening.")
        vosk.start(dictation = false, grammar = null, timeoutMs = COMMAND_TIMEOUT_MS, listener = object : VoskListener {
            override fun onPartial(text: String) { if (text.isNotBlank()) update("◌ $text") }
            override fun onFinal(text: String) {
                Log.i(TAG, "command heard (vosk): \"$text\"")
                voiceScope.launch {
                    if (text.isBlank()) rearm(vosk) else respond(vosk, interpret(text))
                }
            }
            override fun onError(message: String) { Log.w(TAG, "command error: $message"); voiceScope.launch { rearm(vosk) } }
            override fun onTimeout() { Log.i(TAG, "command timeout — re-arming wake"); voiceScope.launch { rearm(vosk) } }
        })
    }

    /** When a cloud brain (OpenRouter etc.) is active, run the raw STT transcript through it once to
     *  repair mishears before acting — "understand better" without a heavier STT model. Returns the raw
     *  text unchanged when cloud is off, the feature is disabled, generation fails, or the rewrite looks
     *  wrong (so the user's words are never replaced by a hallucination). Routes to the cloud provider
     *  automatically via the same RoutingInferenceEngine the console uses. */
    private suspend fun interpret(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return raw
        val j = runCatching { container?.settingsRepository?.current()?.jarvis }.getOrNull() ?: return raw
        if (!j.cloudActive || !j.voiceCloudInterpret) return raw
        val engine = container?.inferenceEngine ?: return raw
        val cleaned = runCatching {
            val sb = StringBuilder()
            engine.generate(text, emptyList(), CORRECTION_SYS).collect { sb.append(it) }
            sanitizeInterpretation(sb.toString())
        }.getOrNull().orEmpty()
        // Reject empty or wildly-divergent rewrites — keep the user's words rather than a hallucination.
        if (cleaned.isBlank() || cleaned.length > text.length * 4 + 40) return raw
        if (!cleaned.equals(text, ignoreCase = true)) Log.i(TAG, "interpreted \"$text\" → \"$cleaned\"")
        return cleaned
    }

    /** Keep only the first non-blank line, drop floor-control markers / wrapping quotes, and cap length —
     *  the model occasionally adds a stray note or quotes despite the prompt. */
    private fun sanitizeInterpretation(s: String): String {
        var t = s.replace(MARK_OPEN, "").replace(MARK_CLOSE, "").trim()
        t = t.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') t = t.substring(1, t.length - 1).trim()
        return t.take(240)
    }

    private suspend fun respond(vosk: VoskSpeech, command: String) {
        vosk.stop()
        // Honour an explicit "stop" before doing anything else.
        if (isStopCue(command)) {
            endConversation(vosk, "Acknowledged.")
            return
        }

        // An emergency spoken aloud is answered by the device, before anything else is consulted.
        //
        // Every other reply on this path waits on an inference engine — and when none is configured
        // the branch below simply returns, so the phone says NOTHING AT ALL. That is an acceptable
        // outcome for "what's the weather" and not for "he's not breathing". Even with a model it is
        // a round-trip, possibly to a cloud provider over the same failing signal that made the
        // situation an emergency. So the curated table answers first: no model, no network, no
        // settings, no agent loop.
        //
        // The same EmergencyTriage table backs the library tool and the SOS fast path, so all three
        // surfaces give the same first action by construction rather than by three authors agreeing.
        EmergencyTriage.match(command)?.let { e ->
            Log.i(TAG, "emergency recognised in \"$command\" → ${e.id}")
            val spoken = e.label + ". " + e.firstAction
            update(spoken.take(140))
            speakThen(spoken, vosk) { resetConvo(); rearm(vosk) }
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
                charter = runCatching { container?.selfEditStore?.current()?.charter }.getOrNull().orEmpty(),
                address = jarvisSettings?.address.orEmpty(),
            )
            // Only in conversation mode: let the model self-direct whether to keep the floor open.
            // (Appended here, not to the global persona, so the markers never leak into the console.)
            if (conversation) persona += CONVO_HINT

            if (convoTurns == 0) convoStartedAt = System.currentTimeMillis()
            val history = convo.toList()
            convo.addLast(ChatTurn(Speaker.USER, command))

            // When tools/self-coding are on, answer spoken commands through the agent loop so voice can
            // actually DO things (weather, location, reminders, read/change its own code) — not just chat.
            // Otherwise stream a plain reply. Either way the recent conversation is threaded in.
            val useAgent = jarvisSettings != null &&
                (jarvisSettings.agentToolsEnabled || jarvisSettings.selfCodingEnabled || jarvisSettings.selfEditEnabled)
            val orchestrator = if (useAgent) container?.agentOrchestrator else null
            val sb = StringBuilder()
            if (orchestrator != null) {
                orchestrator.run(command, persona, history).collect { step ->
                    when (step.kind) {
                        AgentOrchestrator.Kind.TOOL -> update("⚙ ${step.text}")
                        AgentOrchestrator.Kind.FINAL -> sb.append(step.text)
                        else -> {}
                    }
                }
            } else {
                engine.generate(command, history, persona).collect { sb.append(it) }
            }
            var reply = sb.toString().ifBlank { "Standing by." }
            // Autonomous floor-control: explicit markers win; otherwise a trailing "?" implies it
            // expects an answer. Strip markers before showing/speaking either way.
            val wantsClose = reply.contains(MARK_CLOSE)
            val wantsOpen = reply.contains(MARK_OPEN) || reply.trimEnd().endsWith("?")
            reply = stripMarkers(reply)
            convo.addLast(ChatTurn(Speaker.JARVIS, reply))
            convoTurns++
            while (convo.size > CONVO_BUFFER) convo.removeFirst()
            update(reply.take(140))

            val elapsed = System.currentTimeMillis() - convoStartedAt
            val overLimit = VoiceMachine.overBudget(convoTurns, MAX_CONVO_TURNS, elapsed, MAX_CONVO_MS)
            val keepOpen = VoiceMachine.holdFloor(
                followUp = followUp, conversation = conversation,
                modelClosed = wantsClose, modelOpened = wantsOpen,
                turns = convoTurns, maxTurns = MAX_CONVO_TURNS, elapsedMs = elapsed, maxMs = MAX_CONVO_MS,
            )

            when {
                // Keep the floor: the arbiter reopens the mic for a command, no wake word needed.
                keepOpen -> speakThen(reply, vosk) { rearm(vosk, holdFloor = true) }
                // Conversation mode decides to wind down (model closed, or we hit the safeguard): it
                // says so, but checks first — if you keep talking it resumes; silence/"stop" ends it.
                conversation && (wantsClose || overLimit) -> speakThen(reply, vosk) { wrapUp(vosk) }
                // Plain answer: speak it and return to wake listening.
                else -> speakThen(reply, vosk) { resetConvo(); rearm(vosk) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // honour service teardown — don't re-arm the mic on a dying service
        } catch (e: Throwable) {
            Log.e(TAG, "respond: failed to answer", e)
            update("I couldn't answer that one.")
            resetConvo()
            rearm(vosk)
        }
    }

    /**
     * Speak [text], then run [next] on the main thread once speech finishes, so the mic never picks
     * up the computer's own voice. Falls straight through to [next] if TTS is unavailable.
     *
     * Entering the speaking state hands the mic back first. Previously nothing did: whether the
     * recogniser was still running through the reply depended on which capture path had produced it.
     */
    private fun speakThen(text: String, vosk: VoskSpeech, next: () -> Unit) {
        perform(voice.speaking(), vosk)
        val tts = container?.textToSpeech
        if (tts == null) { voiceScope.launch { next() } } else tts.speak(text) { voiceScope.launch { next() } }
    }

    /** J.A.R.V.I.S. signals it's winding down but leaves the floor open: it asks if that's all, then
     *  listens once more. If you keep talking it resumes with a fresh budget; silence (or "stop") ends
     *  it — so it never cuts you off while you still want to talk. */
    private fun wrapUp(vosk: VoskSpeech) {
        convoTurns = 0
        convoStartedAt = System.currentTimeMillis() // fresh budget if the user carries on
        speakThen("Will that be all?", vosk) { rearm(vosk, holdFloor = true) }
    }

    /** End any open conversation: optionally speak a closing line, then return to wake listening. */
    private fun endConversation(vosk: VoskSpeech, closing: String?) {
        resetConvo()
        if (closing == null) { rearm(vosk); return }
        update(closing)
        speakThen(closing, vosk) { rearm(vosk) }
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
            .setColor(androidx.core.content.ContextCompat.getColor(this, R.color.lcars_condition_routine))
            .setSubText("STANDING BY")
            .setContentTitle("Computer · Active Matrix")
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
                    description = "Keeps the computer resident and surfaces proactive remarks."
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onDestroy() {
        observing = false
        // Nobody holds the mic and nothing wants it, so no pending path can re-arm on the way down.
        voice = VoiceMachine.State()
        // Hands back the ~128 MB wake model as well as stopping the session. Deliberately NOT
        // shutdown(), which would also free the console's 1.8 GB dictation model and cost it a
        // multi-second reload on the next tap-to-talk.
        runCatching { container?.voskSpeech?.releaseWakeModel() }
        runCatching { container?.deviceSpeech?.cancel() } // release the system recognizer + mic
        runCatching { container?.textToSpeech?.stop() }    // don't keep talking after "Stand down"
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
        private const val WAKE_GRAMMAR = WakePhrase.GRAMMAR
        private const val TAG = "JarvisVoice"
        // Re-arm the wake loop if no command is spoken within this window after waking.
        private const val COMMAND_TIMEOUT_MS = 8000
        // Backoff before re-arming the wake loop after a recognizer/mic error (avoids a tight storm).
        private const val WAKE_RETRY_MS = 4000L
        // Live breaking-news poll cadence — as fresh as Android allows without a push server.
        private const val LIVE_NEWS_INTERVAL_MS = 90_000L
        // Battery % at which we leave conserve mode (when not charging).
        private const val BATTERY_RECOVER_PCT = 25

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
        // System prompt for the cloud transcript-repair pass (Increment 2): fix STT mishears only.
        private const val CORRECTION_SYS =
            "You repair speech-to-text errors in a single short voice command to an assistant. The text " +
                "may contain mishearings (for example \"fire\" instead of \"file\"). Return ONLY the most " +
                "likely intended command — no quotes, no explanation, no preamble. If it already looks " +
                "correct, return it unchanged."
        // Short whole-utterance phrases that end an exchange.
        private val STOP_CUES = listOf(
            "stop", "that's all", "thats all", "that is all", "thank you", "thanks",
            "thank you computer", "thanks computer", "goodbye", "bye", "dismiss", "stand down",
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
