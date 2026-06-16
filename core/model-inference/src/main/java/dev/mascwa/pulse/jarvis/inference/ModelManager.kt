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
 */
class ModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val modelsDir: File = File(appContext.filesDir, "jarvis/models").apply { mkdirs() }
    private val modelFile: File = File(modelsDir, "model.task")
    private val partFile: File = File(modelsDir, "model.task.part")

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
        get() = modelFile.exists() && modelFile.length() > 0L

    fun modelPath(): String = modelFile.absolutePath

    fun modelSizeBytes(): Long = if (modelFile.exists()) modelFile.length() else 0L

    /** Remove the provisioned model and revert to the idle state. */
    fun deleteModel(): Boolean {
        val removed = !modelFile.exists() || modelFile.delete()
        runCatching { partFile.delete() }
        _state.value = ModelDownloadState.Idle
        return removed
    }

    /**
     * Streams [url] into private storage, reporting progress through [state]. Writes to a
     * `.part` file and renames on success so a partial download is never mistaken for a
     * complete model. Returns the model [File] on success.
     */
    suspend fun download(url: String, authToken: String? = null): Result<File> =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) {
                _state.value = ModelDownloadState.Failed("No model URL configured.")
                return@withContext Result.failure(IllegalArgumentException("blank model url"))
            }
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
                    if (modelFile.exists()) modelFile.delete()
                    if (!partFile.renameTo(modelFile)) {
                        partFile.copyTo(modelFile, overwrite = true)
                        partFile.delete()
                    }
                    _state.value = ModelDownloadState.Done
                    Result.success(modelFile)
                }
            } catch (t: Throwable) {
                runCatching { partFile.delete() }
                _state.value = ModelDownloadState.Failed(t.message ?: "Download failed.")
                Result.failure(t)
            }
        }
}
