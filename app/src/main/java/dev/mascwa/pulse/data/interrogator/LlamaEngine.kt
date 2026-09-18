package dev.mascwa.pulse.data.interrogator

import android.content.Context
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.data.model.ModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The JNI surface of llama.cpp.
 *
 * ⚠️ Present only when llama.cpp was linked — see the header of `llama_jni.cpp`. `external fun`
 * resolves lazily, so a missing method throws `UnsatisfiedLinkError` at the call rather than at
 * class load, which is the same failure the whole library being absent produces.
 */
internal object LlamaNative {

    external fun nativeInit(modelPath: String, contextTokens: Int, threads: Int): Long
    external fun nativeComplete(handle: Long, prompt: String, maxTokens: Int): String?
    external fun nativeFree(handle: Long)
}

/**
 * The acoustic interrogator's adjudicator — stage 5, and the only expensive stage.
 *
 * ⚠️ **PINNED TO THE LOCAL ENGINE. NEVER `RoutingInferenceEngine`.** That router prefers the cloud
 * whenever an API key is set, and wiring the interrogator through it would silently ship ambient
 * conversation — other people's conversation — to OpenRouter the moment a key exists. This is the
 * single most important wiring rule in the feature, which is why it is stated here rather than left
 * to a reviewer's memory. Nothing in this file has a network path except the one-time model fetch.
 *
 * ⚠️ **THE MODEL IS NOT DOWNLOADED WITHOUT BEING ASKED.** It is a gigabyte. The whisper model at
 * 57 MB is fetched on first use like the MediaPipe classifiers, which is defensible; a gigabyte is
 * not something to pull down because a background service happened to start. [prepare] refuses
 * unless the caller passes `allowDownload`, and the surface passes it only on an explicit tap.
 *
 * ⚠️ **The cascade works without it, and that is by design rather than by accident.**
 * [dev.mascwa.pulse.core.telemetry.Rebuttal.Provenance] already models "nothing read the argument"
 * as a first-class outcome, and says so on screen. So an adjudicator that is absent, still
 * downloading, or too slow leaves a feature that is honestly weaker rather than one that is broken.
 */
class LlamaEngine(
    private val context: Context,
    private val http: HttpClient,
) {
    private val lock = Mutex()

    @Volatile
    private var handle: Long = 0

    val loaded: Boolean get() = handle != 0L

    /** Whether the weights are already on disk, so a surface can offer the download or not. */
    fun modelPresent(): Boolean =
        File(context.filesDir, MODEL_FILE).let { it.exists() && it.length() > MIN_MODEL_BYTES }

    /**
     * Load the adjudicator.
     *
     * @param allowDownload fetch the model if it is missing. False from any automatic caller.
     * @return false when it is unavailable for any reason, which the caller treats as "no stage 5".
     */
    suspend fun prepare(allowDownload: Boolean = false): Boolean = lock.withLock {
        if (handle != 0L) return@withLock true
        // Touching NativeBridge is what performs System.loadLibrary — see WhisperEngine.
        if (!NativeBridge.available) return@withLock false
        val model = when {
            modelPresent() -> File(context.filesDir, MODEL_FILE)
            allowDownload -> runCatching { fetchModel() }.getOrNull() ?: return@withLock false
            else -> return@withLock false
        }
        val h = runCatching { LlamaNative.nativeInit(model.absolutePath, CONTEXT_TOKENS, threads()) }
            .getOrElse { 0L }
        handle = h
        h != 0L
    }

    /**
     * Run one prompt.
     *
     * ⚠️ Serialised on the same Mutex as loading. A quantized model on a phone is one set of weights
     * and one KV cache; two concurrent completions would interleave into each other's context and
     * return confident nonsense. The cascade only ever escalates a few times an hour, so waiting is
     * free and the alternative is a class of bug that would look like the model being bad at its job.
     */
    suspend fun complete(prompt: String, maxTokens: Int = MAX_TOKENS): String? =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val h = handle
                if (h == 0L || prompt.isBlank()) return@withLock null
                runCatching { LlamaNative.nativeComplete(h, prompt, maxTokens) }.getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }


    /** How much of the disk this model is holding — see [ModelFile], including a half-fetched one. */
    fun bytesOnDisk(): Long = ModelFile.bytes(context, MODEL_FILE)

    /**
     * Free the native handle and delete the weights.
     *
     * ⚠️ Releases FIRST, for the reason given on [WhisperEngine.discardModel]: the weights are
     * mapped while a handle is open. This one matters more — it is a gigabyte, and it is the single
     * largest thing this app can put on a phone.
     */
    suspend fun discardModel(): Boolean {
        release()
        return withContext(Dispatchers.IO) { ModelFile.discard(context, MODEL_FILE) }
    }

    suspend fun release() = lock.withLock {
        val h = handle
        handle = 0
        if (h != 0L) runCatching { LlamaNative.nativeFree(h) }
        Unit
    }

    /**
     * ⚠️ More threads than whisper gets, and for the opposite reason. Transcription runs constantly
     * and must stay out of the way; adjudication happens a handful of times an hour and the person
     * is waiting for it, so it is worth finishing quickly. Still bounded well short of every core:
     * this is a background service, not a benchmark.
     */
    private fun threads(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(MIN_THREADS, MAX_THREADS)

    private suspend fun fetchModel(): File = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, MODEL_FILE)
        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        // Renamed on completion, so an interrupted gigabyte cannot leave a truncated file that looks
        // loadable. The size floor in modelPresent() is what makes the retry work rather than
        // sticking forever on a half-written model.
        http.download(MODEL_URL, tmp, MAX_MODEL_BYTES)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        f
    }

    companion object {
        /**
         * Qwen2.5 1.5B Instruct, 4-bit: 1,066 MB measured, against 469 MB for the 0.5B, 770 MB for
         * Llama-3.2-1B and 1,926 MB for the 3B. Chosen on evidence, and the choice is about the task
         * rather than the size — stage 5 exists to REFUSE most of what stage 3 hands it, and models
         * below about a billion parameters agree with whatever a prompt suggests. An adjudicator that
         * rubber-stamps every cue would make the whole cascade a keyword matcher with extra steps.
         */
        const val MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-1.5b-instruct-q4_k_m.gguf"

        const val MAX_MODEL_BYTES = 2L * 1024 * 1024 * 1024
        const val MIN_MODEL_BYTES = 512L * 1024 * 1024

        /**
         * Enough for the persona, an utterance, a guide excerpt and a short answer. Deliberately
         * modest: the KV cache is allocated up front and scales with this, so a generous context
         * costs memory on every device whether or not any prompt ever fills it.
         */
        const val CONTEXT_TOKENS = 2048

        /** One short judgement and one question. The surface trims further; this is the hard stop. */
        const val MAX_TOKENS = 160

        const val MIN_THREADS = 2
        const val MAX_THREADS = 6
    }
}
