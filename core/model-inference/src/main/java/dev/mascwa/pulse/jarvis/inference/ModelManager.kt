package dev.mascwa.pulse.jarvis.inference

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

/** Progress of provisioning the on-device model. */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Running(val pct: Int, val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadState
    data object Done : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

/**
 * Pulls a single LLM model file to private app storage and tracks where it lives.
 * Fully self-contained: it streams over plain HTTPS (optionally with a Bearer token
 * for gated hosts like Hugging Face) and never touches Play Services. The model stays
 * in [Context.getFilesDir], so it is wiped on uninstall and never leaves the device.
 *
 * The on-disk filename mirrors the source URL's extension (`model.litertlm` for a
 * `.litertlm` URL, otherwise `model.task`) so the MediaPipe runtime can tell the two
 * container formats apart — saving a `.litertlm` as `model.task` made it try to open a
 * non-zip file as a zip, which surfaced as "Unable to open zip archive".
 */
class ModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val modelsDir: File = File(appContext.filesDir, "jarvis/models").apply { mkdirs() }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Model files are large; let reads and the overall call run without a cap.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<ModelDownloadState>(
        if (isModelPresent) ModelDownloadState.Done else ModelDownloadState.Idle,
    )
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    val isModelPresent: Boolean
        get() = existingModel() != null

    /** Absolute path of the provisioned model (or the default name if none exists yet). */
    fun modelPath(): String = (existingModel() ?: File(modelsDir, "${PREFIX}task")).absolutePath

    fun modelSizeBytes(): Long = existingModel()?.length() ?: 0L

    /** Remove every provisioned model file (any extension) and revert to the idle state. */
    fun deleteModel(): Boolean {
        var removed = true
        modelsDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(PREFIX)) removed = f.delete() && removed
        }
        _state.value = ModelDownloadState.Idle
        return removed
    }

    /**
     * Streams [url] into private storage, reporting progress through [state]. Writes to a
     * `.part` file and renames on success so a partial download is never mistaken for a
     * complete model. The target filename is chosen from [url] (see [targetFileFor]); any
     * previously-provisioned model is removed first. Returns the model [File] on success.
     */
    suspend fun download(url: String, authToken: String? = null): Result<File> =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) {
                _state.value = ModelDownloadState.Failed("No model URL configured.")
                return@withContext Result.failure(IllegalArgumentException("blank model url"))
            }
            val target = targetFileFor(url)
            val partFile = File(modelsDir, "${target.name}$PART_SUFFIX")
            try {
                _state.value = ModelDownloadState.Running(0, 0L, -1L)
                val request = Request.Builder()
                    .url(url)
                    .apply { if (!authToken.isNullOrBlank()) header("Authorization", "Bearer $authToken") }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _state.value = ModelDownloadState.Failed("HTTP ${response.code}")
                        return@withContext Result.failure(IllegalStateException("HTTP ${response.code}"))
                    }
                    val body = response.body
                        ?: run {
                            _state.value = ModelDownloadState.Failed("Empty response body.")
                            return@withContext Result.failure(IllegalStateException("empty body"))
                        }
                    val total = body.contentLength()
                    partFile.outputStream().use { out ->
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
                                        _state.value = ModelDownloadState.Running(pct, downloaded, total)
                                    }
                                } else {
                                    _state.value = ModelDownloadState.Running(0, downloaded, -1L)
                                }
                            }
                        }
                    }
                    // Drop any previously-provisioned model (possibly a different extension)
                    // so only the freshly-downloaded one remains.
                    modelsDir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.startsWith(PREFIX) && f.name != partFile.name) f.delete()
                    }
                    if (!partFile.renameTo(target)) {
                        partFile.copyTo(target, overwrite = true)
                        partFile.delete()
                    }
                    _state.value = ModelDownloadState.Done
                    Result.success(target)
                }
            } catch (t: Throwable) {
                runCatching { partFile.delete() }
                _state.value = ModelDownloadState.Failed(t.message ?: "Download failed.")
                Result.failure(t)
            }
        }

    /** The fully-provisioned model file on disk (ignoring any in-progress `.part`), if present. */
    private fun existingModel(): File? =
        modelsDir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith(PREFIX) && !it.name.endsWith(PART_SUFFIX) && it.length() > 0L
        }

    private fun targetFileFor(url: String): File = File(modelsDir, modelFileNameFor(url))

    companion object {
        private const val PREFIX = "model."
        private const val PART_SUFFIX = ".part"

        /**
         * Picks the on-disk filename from a model URL so the runtime can detect the format:
         * a `.litertlm` URL → `model.litertlm`, everything else → `model.task`. Query strings
         * and fragments (e.g. Hugging Face `?download=true`) are ignored.
         */
        internal fun modelFileNameFor(url: String): String {
            val clean = url.substringBefore('?').substringBefore('#').trimEnd('/')
            return if (clean.endsWith(".litertlm", ignoreCase = true)) "${PREFIX}litertlm" else "${PREFIX}task"
        }
    }
}
