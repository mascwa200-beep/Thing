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
 * The JNI surface of whisper.cpp.
 *
 * ⚠️ **These methods exist only when whisper.cpp was actually linked.** The upstream tree is cloned
 * by CI rather than vendored, so `whisper_jni.cpp` guards every entry point behind `HAVE_WHISPER` —
 * which means a build that lost speech recognition is missing the symbols rather than silently
 * exporting stubs. `external fun` resolves lazily, so a missing method throws `UnsatisfiedLinkError`
 * at the call rather than at class load: the same failure the library being absent produces, so
 * [WhisperEngine] has one path to handle instead of two.
 */
internal object WhisperNative {

    external fun nativeInit(modelPath: String, useGpu: Boolean): Long
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, threads: Int): String?
    external fun nativeFree(handle: Long)
    external fun nativeBackends(): String
}

/**
 * Offline speech recognition for the acoustic interrogator — stage 1 of the cascade.
 *
 * ⚠️ **The model is fetched once at runtime and never bundled**, the same discipline the MediaPipe
 * classifiers already follow, for the same reason: a 57 MB blob in the APK is 57 MB every user pays
 * for whether or not they switch the feature on. It lands in `filesDir` and is reused thereafter.
 *
 * ⚠️ **Every failure is quiet and total.** No native library, no model, no disk space, a wedged
 * keystore — all of it comes back as "unavailable" and the interrogator does nothing. That is the
 * correct posture for an always-listening feature: a subsystem that degrades noisily, or that
 * retries hard, is one that costs battery for no output.
 */
class WhisperEngine(
    private val context: Context,
    private val http: HttpClient,
) {
    private val lock = Mutex()

    @Volatile
    private var handle: Long = 0

    /** True once a model is loaded and the native side answered. */
    val loaded: Boolean get() = handle != 0L

    /**
     * Load the model, fetching it first if this device has never had it.
     *
     * ⚠️ Guarded by a Mutex rather than `synchronized`, because the fetch is a suspending network
     * call that can take minutes on a slow connection: blocking a thread for that would be worse
     * than the contention it prevents, and two concurrent starts downloading the same 57 MB twice
     * is exactly what this stops.
     */
    suspend fun prepare(): Boolean = lock.withLock {
        if (handle != 0L) return@withLock true
        // ⚠️ Touching [NativeBridge] is what performs `System.loadLibrary` — it happens in that
        // object's initialiser, so reading this property both answers the question and guarantees
        // the library is loaded before any `external fun` here is resolved. Calling WhisperNative
        // first would work by accident on a good build and throw on a bad one.
        if (!NativeBridge.available) return@withLock false
        val model = runCatching { ensureModel() }.getOrNull() ?: return@withLock false
        val h = runCatching { WhisperNative.nativeInit(model.absolutePath, USE_GPU) }
            // UnsatisfiedLinkError is an Error, not an Exception, so runCatching's Throwable catch is
            // what covers the case of whisper never having been linked at all.
            .getOrElse { 0L }
        handle = h
        h != 0L
    }

    /**
     * Transcribe 16 kHz mono float PCM in [-1, 1].
     *
     * ⚠️ Null and empty are different answers and callers must keep them apart. Null means the
     * engine could not run; empty means whisper ran and heard nothing worth writing down, which for
     * a room with no speech in it is the normal outcome several times a minute.
     */
    suspend fun transcribe(pcm: FloatArray): String? = withContext(Dispatchers.Default) {
        val h = handle
        if (h == 0L || pcm.isEmpty()) return@withContext null
        runCatching { WhisperNative.nativeTranscribe(h, pcm, threads()) }.getOrNull()
    }

    /** What the native build actually has, for the diagnostic screen. Null when nothing is linked. */
    fun backends(): String? =
        if (!NativeBridge.available) null else runCatching { WhisperNative.nativeBackends() }.getOrNull()

    /** Release the model. Safe to call twice, and safe when nothing ever loaded. */

    /** How much of the disk this model is holding — see [ModelFile], including a half-fetched one. */
    fun bytesOnDisk(): Long = ModelFile.bytes(context, MODEL_FILE)

    /**
     * Free the native handle and delete the weights.
     *
     * ⚠️ Releases FIRST. The model is mapped by native code while a handle is open, and deleting a
     * file out from under a live mapping is the kind of thing that works on one filesystem and
     * crashes on another. Ordering it here rather than at the call site means no caller can get it
     * wrong.
     *
     * ⚠️ The caller is responsible for the feature being off. Discarding while the service is
     * transcribing would leave it holding a handle to a model that is no longer on disk, and the
     * next `prepare` would silently re-download the whole thing.
     */
    suspend fun discardModel(): Boolean {
        release()
        return withContext(Dispatchers.IO) { ModelFile.discard(context, MODEL_FILE) }
    }

    suspend fun release() = lock.withLock {
        val h = handle
        handle = 0
        if (h != 0L) runCatching { WhisperNative.nativeFree(h) }
        Unit
    }

    /**
     * ⚠️ Deliberately not "all of them". This runs continuously in the background on a phone that is
     * also doing everything else; saturating every core would make the device hot and the foreground
     * janky for a workload whose deadline is measured in seconds, not milliseconds. Half the cores,
     * bounded, leaves the big cluster for whatever the person is actually looking at.
     */
    private fun threads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(MIN_THREADS, MAX_THREADS)

    private suspend fun ensureModel(): File = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, MODEL_FILE)
        if (f.exists() && f.length() > MIN_MODEL_BYTES) return@withContext f
        // ⚠️ Downloaded to a `.part` and renamed, so an interrupted fetch cannot leave a truncated
        // file that looks loadable. The length check above is what makes that recovery work on the
        // next attempt rather than sticking on a half-written model forever.
        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        http.download(MODEL_URL, tmp, MAX_MODEL_BYTES)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        f
    }

    companion object {
        /**
         * base.en, 5-bit quantized: 57 MB measured, against 31 MB for tiny.en and 181 MB for
         * small.en. Chosen on evidence rather than instinct — the whole point of the feature is
         * judging the reasoning in what somebody said, and tiny mishears often enough that the
         * cascade downstream would be screening a paraphrase. English-only because the fallacy cues
         * and the guide library are both English; a multilingual model would cost size for output
         * nothing downstream can read.
         */
        const val MODEL_FILE = "ggml-base.en-q5_1.bin"
        const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"

        /** Measured 59,721,011; the ceiling leaves room for the file to be revised upward. */
        const val MAX_MODEL_BYTES = 96L * 1024 * 1024
        const val MIN_MODEL_BYTES = 32L * 1024 * 1024

        /**
         * ⚠️ Off. GPU offload on Android goes through a backend that varies enormously by vendor,
         * and a wrong guess is a crash inside a driver rather than a failure this class can report.
         * Worth revisiting once there is a device to measure it on; not worth guessing at.
         */
        const val USE_GPU = false

        const val MIN_THREADS = 2
        const val MAX_THREADS = 4
    }
}
