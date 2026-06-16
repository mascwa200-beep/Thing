package dev.mascwa.pulse.jarvis.inference

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
) : LocalInferenceEngine {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<EngineState>(EngineState.Unavailable)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

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
            _state.value = EngineState.Error("Inference process crashed — the model may be too heavy for this device.")
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            _state.value = EngineState.Error("Inference process died.")
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
            _state.value = EngineState.Error("Couldn't start the inference process.")
            return
        }
        // load() blocks while the model is read in the other process; a native abort there throws
        // DeadObjectException here rather than crashing us.
        withContext(Dispatchers.IO) { runCatching { svc.load(modelManager.modelPath(), maxTokens) } }
            .onSuccess { ok ->
                _state.value = if (ok) EngineState.Ready else EngineState.Error("Model failed to load.")
            }
            .onFailure {
                service = null
                _state.value =
                    EngineState.Error("Inference process crashed loading the model (likely too heavy for this device).")
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
        val full = renderPrompt(ChatFormat.resolve(cfg.format, cfg.modelUrl), prompt, history, system)
        val response = withContext(Dispatchers.IO) {
            genMutex.withLock { runCatching { svc.generate(full) }.getOrNull() }
        } ?: "// inference fault: process lost"
        response.split(' ').forEachIndexed { i, word ->
            emit(if (i == 0) word else " $word")
        }
    }.flowOn(Dispatchers.Default)

    override fun close() {
        runCatching { if (bound) appContext.unbindService(connection) }
        bound = false
        service = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8000L
    }
}
