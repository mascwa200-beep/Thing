package dev.mascwa.pulse.jarvis.inference

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the LLM running in a separate process ([InferenceService]). Loading a multi-GB model can
 * abort *natively* — uncatchable in-process and fatal to the whole app. Here that abort only kills
 * the `:inference` process: the binder call throws, we surface [EngineState.Error], and the app
 * stays alive. The model is single-shot, so one blocking IPC call per turn suffices (we re-stream
 * word-by-word locally).
 */
class IsolatedInferenceEngine(
    context: Context,
    private val modelManager: ModelManager,
    private val maxTokens: Int = 1024,
    /** Supplies the live chat-template choice + model URL, read fresh on each generation so a
     *  Setup change takes effect without reloading the model. */
    private val promptConfig: suspend () -> PromptConfig = { PromptConfig() },
    /** Preferred backend, read fresh at load time: 0=auto, 1=GPU, 2=CPU. */
    private val backendProvider: suspend () -> Int = { 0 },
    /** Invoked when generation dies with a native crash on a non-CPU backend, so the app layer can
     *  switch the persisted backend to CPU (the engine then reloads on the next ensureReady). */
    private val onNativeCrash: suspend () -> Unit = {},
    /** The model's total token budget (input + output), read fresh at load time. */
    private val maxTokensProvider: suspend () -> Int = { maxTokens },
) : LocalInferenceEngine {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<EngineState>(EngineState.Unavailable)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Backend used for the current load (so a crash can decide whether to auto-fall back to CPU). */
    @Volatile private var lastBackend = 0

    /** Total token budget the model was loaded with; sets the input clamp (see [generate]). */
    @Volatile private var activeMaxTokens = maxTokens

    @Volatile private var service: IInferenceService? = null
    @Volatile private var bound = false
    private val genMutex = Mutex()
    private var connectSignal: CompletableDeferred<Unit>? = null

    /** True only while bound to a live process that has the model loaded. */
    val isReady: Boolean get() = service != null && _state.value is EngineState.Ready

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IInferenceService.Stub.asInterface(binder)
            connectSignal?.complete(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _state.value = EngineState.Error("The inference process stopped — that model may be too heavy for this device, sir. Try a lighter one (Qwen 1.5B).")
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            _state.value = EngineState.Error("The inference process died, sir.")
        }
    }

    override suspend fun ensureReady() {
        if (service != null && _state.value is EngineState.Ready) return
        if (!modelManager.isModelPresent) {
            _state.value = EngineState.Unavailable
            return
        }
        _state.value = EngineState.Preparing
        val svc = connect()
        if (svc == null) {
            _state.value = EngineState.Error("I couldn't start the inference process, sir.")
            return
        }
        // load() blocks while the model is read in the other process; a native abort there throws
        // DeadObjectException here rather than crashing us.
        val backend = runCatching { backendProvider() }.getOrDefault(0)
        lastBackend = backend
        val tokens = runCatching { maxTokensProvider() }.getOrDefault(maxTokens).let { if (it > 0) it else maxTokens }
        activeMaxTokens = tokens
        withContext(Dispatchers.IO) { runCatching { svc.load(modelManager.modelPath(), tokens, backend) } }
            .onSuccess { ok ->
                _state.value = if (ok) EngineState.Ready else EngineState.Error("The model failed to load, sir.")
            }
            .onFailure {
                service = null
                _state.value =
                    EngineState.Error("The model failed to load — it may be too heavy for this device, sir. Try Qwen 1.5B.")
            }
    }

    private suspend fun connect(): IInferenceService? {
        service?.let { return it }
        // Stale binding (process died, service nulled by onServiceDisconnected): unbind for a clean rebind.
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
        val signal = CompletableDeferred<Unit>()
        connectSignal = signal
        bound = runCatching {
            appContext.bindService(
                Intent(appContext, InferenceService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) return null
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { signal.await() }
        return service
    }

    override fun generate(prompt: String, history: List<ChatTurn>, system: String?): Flow<String> = flow {
        val svc = service
        if (svc == null) {
            emit("// inference offline")
            return@flow
        }
        val cfg = promptConfig()
        // Reserve part of the model's total budget for the answer; clamp the input to the rest so a
        // long chat can't overflow the context and abort generation natively.
        val inputBudget = (activeMaxTokens * INPUT_BUDGET_PCT) / 100
        val full = renderPrompt(ChatFormat.resolve(cfg.format, cfg.modelUrl), prompt, history, system, maxInputTokens = inputBudget)
        val response = withContext(Dispatchers.IO) {
            genMutex.withLock { runCatching { svc.generate(full) }.getOrNull() }
        }
        // The model itself reported the prompt was too long (caught in the service, returns a marker
        // rather than aborting): surface it plainly instead of as a fault bubble.
        if (response != null && response.trimStart().startsWith("// prompt too long")) {
            emit("That turn was a little long for my context, sir — try a shorter prompt or clear the history.")
            return@flow
        }
        if (response == null) {
            // The :inference process died mid-generation (a native abort the JVM can't catch). Read
            // the real exit reason and, if it was a crash on a non-CPU backend, ask the app to switch
            // to CPU; the next message reloads there (ensureReady runs before each send).
            service = null
            var exit = readInferenceExit()
            if (exit == null) { delay(200); exit = readInferenceExit() }
            val reason = exit?.first ?: "process lost"
            val crashed = exit?.second ?: true // unknown → assume a crash and try CPU
            if (crashed && lastBackend != BACKEND_CPU) {
                runCatching { onNativeCrash() }
                _state.value = EngineState.Error("Inference crashed ($reason). Switched to the CPU backend — try again, sir.")
                emit("// inference fault: $reason — switching to the CPU backend. Try again, sir.")
            } else {
                _state.value = EngineState.Error("Inference crashed ($reason).")
                emit("// inference fault: $reason")
            }
            return@flow
        }
        response.split(' ').forEachIndexed { i, word ->
            emit(if (i == 0) word else " $word")
        }
    }.flowOn(Dispatchers.Default)

    /** Most-recent `:inference` process exit, as (human reason, wasNativeCrash) — or null if unknown. */
    private fun readInferenceExit(): Pair<String, Boolean>? = runCatching {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 6)
            .firstOrNull { it.processName.endsWith(":inference") } ?: return null
        val crashed = info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            info.reason == ApplicationExitInfo.REASON_CRASH ||
            (info.reason == ApplicationExitInfo.REASON_SIGNALED && info.status in intArrayOf(4, 6, 7, 8, 11))
        val text = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_CRASH -> "app crash"
            ApplicationExitInfo.REASON_SIGNALED -> "signal ${info.status}"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            else -> "exit ${info.reason}"
        } + (info.description?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        text to crashed
    }.getOrNull()

    override fun close() {
        runCatching { if (bound) appContext.unbindService(connection) }
        bound = false
        service = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8000L
        const val BACKEND_CPU = 2
        // Share of the total token budget reserved for the *input*; the remainder is for the answer.
        const val INPUT_BUDGET_PCT = 60
    }
}
