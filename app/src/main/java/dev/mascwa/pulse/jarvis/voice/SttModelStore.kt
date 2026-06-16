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
 * Provisions the offline Vosk speech model on demand: streams the (~40 MB) zip to private
 * storage and unpacks it, so voice input needs no bundled asset bloat and no Play Services.
 * The model stays in [Context.getFilesDir] and never leaves the device.
 */
class SttModelStore(context: Context) {

    sealed interface State {
        data object Idle : State
        data class Downloading(val pct: Int) : State
        data object Unpacking : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val appContext = context.applicationContext
    private val root: File = File(appContext.filesDir, "jarvis/stt").apply { mkdirs() }

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
    suspend fun ensure(url: String = DEFAULT_URL): Result<File> = withContext(Dispatchers.IO) {
        modelDir()?.let {
            _state.value = State.Ready
            return@withContext Result.success(it)
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
        /** Vosk small US-English model (~40 MB) — good accuracy at a small footprint. */
        const val DEFAULT_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }
}
