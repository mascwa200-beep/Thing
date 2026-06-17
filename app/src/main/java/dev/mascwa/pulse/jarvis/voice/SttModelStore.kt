package dev.mascwa.pulse.jarvis.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Provisions an offline Vosk speech model on demand: streams the zip to private storage and unpacks
 * it, so voice input needs no bundled asset bloat and no Play Services. The model stays in
 * [Context.getFilesDir] and never leaves the device.
 *
 * One store per [Model]: a small model for the always-on wake word (light, fast download) and the
 * full model for accurate tap-to-talk dictation — see [SttModelStore.WAKE] / [SttModelStore.DICTATION].
 */
class SttModelStore(context: Context, private val model: Model) {

    /** A provisionable Vosk model: where to fetch it, a version tag, and its private subdirectory. */
    data class Model(val url: String, val version: String, val dirName: String)

    sealed interface State {
        data object Idle : State
        data class Downloading(val pct: Int) : State
        data object Unpacking : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val appContext = context.applicationContext
    private val root: File = File(appContext.filesDir, "jarvis/stt/${model.dirName}").apply { mkdirs() }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<State>(if (isReady) State.Ready else State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isReady: Boolean get() = modelDir() != null

    /** The unpacked model directory (the folder containing `conf/`), or null if not provisioned. */
    fun modelDir(): File? =
        root.listFiles()?.firstOrNull { it.isDirectory && File(it, "conf").isDirectory }

    /** Ensure the model is unpacked, downloading it first if necessary. */
    suspend fun ensure(url: String = model.url): Result<File> = withContext(Dispatchers.IO) {
        // Re-provision when the model version changes; otherwise reuse the unpacked one.
        val versionFile = File(root, ".model_version")
        val installed = runCatching { versionFile.readText().trim() }.getOrNull()
        if (installed == model.version) {
            modelDir()?.let {
                _state.value = State.Ready
                return@withContext Result.success(it)
            }
        } else {
            // A previous (smaller/older) model is present — clear it so the new one is fetched cleanly.
            root.listFiles()?.forEach { it.deleteRecursively() }
        }
        val zip = File(root, "model.zip.part")
        try {
            _state.value = State.Downloading(0)
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    _state.value = State.Failed("HTTP ${response.code}")
                    return@withContext Result.failure(IllegalStateException("HTTP ${response.code}"))
                }
                val body = response.body
                    ?: run {
                        _state.value = State.Failed("Empty response body.")
                        return@withContext Result.failure(IllegalStateException("empty body"))
                    }
                val total = body.contentLength()
                zip.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(1 shl 16)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            downloaded += n
                            if (total > 0L) {
                                val pct = ((downloaded * 100L) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _state.value = State.Downloading(pct)
                                }
                            }
                        }
                    }
                }
            }
            // Unpack into a staging dir, then promote atomically — so a process killed mid-unzip
            // can never leave a half-extracted model that passes the readiness check yet won't load.
            _state.value = State.Unpacking
            val staging = File(root, ".staging")
            staging.deleteRecursively()
            staging.mkdirs()
            unzip(zip, staging)
            zip.delete()
            val unpacked = staging.takeIf { File(it, "conf").isDirectory }
                ?: staging.listFiles()?.firstOrNull { it.isDirectory && File(it, "conf").isDirectory }
            if (unpacked == null) {
                staging.deleteRecursively()
                _state.value = State.Failed("Model archive missing expected layout.")
                return@withContext Result.failure(IllegalStateException("no model dir after unzip"))
            }
            val finalDir = File(root, "model")
            finalDir.deleteRecursively()
            if (!unpacked.renameTo(finalDir)) {
                unpacked.copyRecursively(finalDir, overwrite = true)
            }
            staging.deleteRecursively()
            runCatching { versionFile.writeText(model.version) }
            _state.value = State.Ready
            Result.success(finalDir)
        } catch (t: Throwable) {
            runCatching { zip.delete() }
            _state.value = State.Failed(t.message ?: "Voice model download failed.")
            Result.failure(t)
        }
    }

    /** Extract [zip] into [dest], guarding against path traversal (zip-slip). */
    private fun unzip(zip: File, dest: File) {
        val destCanonical = dest.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(dest, entry.name)
                if (!target.canonicalPath.startsWith(destCanonical + File.separator)) {
                    throw SecurityException("Zip entry escapes target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        /** en-US 0.22 "lgraph" model (~128 MB) for the whole wake-word flow (spotting AND the spoken
         *  command). Far more accurate than the 40 MB small model — it has a real language model, so
         *  many fewer phonetic mix-ups ("file"→"fire") — yet light enough to run always-on alongside
         *  the ~2.5 GB on-device LLM without exhausting memory. Supports runtime grammar for spotting. */
        val WAKE = Model(
            url = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip",
            version = "en-us-0.22-lgraph",
            dirName = "wake",
        )

        /** Full en-US 0.22 model (~1.8 GB) — the most accurate model, for deliberate tap-to-talk
         *  dictation in the chat. Static graph (no runtime grammar). Downloaded on first chat-mic use.
         *  Too heavy to also run on the always-on wake path beside the LLM. */
        val DICTATION = Model(
            url = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip",
            version = "en-us-0.22-full",
            dirName = "full",
        )
    }
}
