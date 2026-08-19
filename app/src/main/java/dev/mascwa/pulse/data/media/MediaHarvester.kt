package dev.mascwa.pulse.data.media

import android.content.Context
import dev.mascwa.pulse.core.telemetry.MediaItem
import dev.mascwa.pulse.core.telemetry.MediaResolution
import dev.mascwa.pulse.data.python.PythonRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The hardware data harvester's engine: download the currently viewed media into the app's own
 * sandboxed storage, via the bundled yt-dlp, in a background coroutine.
 *
 * ⚠️ **The download runs through yt-dlp, not through a plain GET of the resolved stream URL** — an
 * HLS "stream" is a playlist of thousands of fragments, not a file, and yt-dlp is the thing that
 * knows how to turn one into a single playable file on disk. That is also why the harvest is keyed
 * on the item's PAGE address rather than its signed stream address: the extractor re-resolves
 * freshly, so a harvest started an hour after the resolve does not inherit an expired URL.
 *
 * **Sandboxed local storage, literally**: `filesDir/harvest`. Not Downloads, not shared storage —
 * the owner's spec named the sandbox, it needs no permission on any Android version, and it is
 * wiped with the app. [harvested] lists what is held; [delete] removes one.
 *
 * One harvest at a time, refusals said in a sentence. A second request while one runs is refused
 * rather than queued — the trigger is a held volume key, and a hold that repeats must not build an
 * invisible queue of gigabyte downloads.
 */
class MediaHarvester(private val python: PythonRuntime, private val appContext: Context) {

    sealed interface Harvest {
        data object Idle : Harvest
        data class Working(val title: String) : Harvest
        data class Done(val file: String, val bytes: Long) : Harvest
        data class Failed(val reason: MediaResolution.Reason, val detail: String) : Harvest
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)

    private val _state = MutableStateFlow<Harvest>(Harvest.Idle)
    val state: StateFlow<Harvest> = _state.asStateFlow()

    /** Where harvested media lives. Created on demand; the ONE place that decides the location. */
    fun dir(): File = File(appContext.filesDir, "harvest")

    /**
     * Start harvesting [item]. Returns false when a harvest is already running (the caller says so).
     *
     * Fire-and-forget on the harvester's own scope rather than the caller's: the trigger can be a
     * key press in an Activity that is destroyed a second later, and a download tied to that
     * lifecycle would die with the screen rotation.
     */
    fun harvest(item: MediaItem): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        val url = item.pageUrl.ifBlank { item.streamUrl }
        _state.value = Harvest.Working(item.title.ifBlank { url })
        scope.launch {
            try {
                val raw = python.callString(MODULE, "download", url, dir().absolutePath)
                if (raw == null) {
                    _state.value = Harvest.Failed(
                        MediaResolution.Reason.UNAVAILABLE,
                        python.unavailableReason ?: python.lastError.orEmpty(),
                    )
                    return@launch
                }
                val wire = runCatching { json.decodeFromString(Wire.serializer(), raw) }.getOrNull()
                if (wire == null) {
                    _state.value = Harvest.Failed(
                        MediaResolution.Reason.FAILED, "The downloader returned something unreadable.",
                    )
                    return@launch
                }
                _state.value = when {
                    wire.kind != null -> Harvest.Failed(reasonOf(wire.kind), wire.error.orEmpty())
                    wire.file.isBlank() || wire.bytes <= 0 ->
                        Harvest.Failed(MediaResolution.Reason.FAILED, "No file was produced.")
                    else -> Harvest.Done(wire.file, wire.bytes)
                }
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /** Everything harvested so far, newest first. On-device only; listing reads the sandbox. */
    fun harvested(): List<File> =
        dir().listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** Dismiss a finished/failed readout. Does not touch a running harvest. */
    fun clearResult() {
        if (_state.value !is Harvest.Working) _state.value = Harvest.Idle
    }

    @Serializable
    private data class Wire(
        val file: String = "",
        val path: String = "",
        val bytes: Long = 0,
        val title: String = "",
        val kind: String? = null,
        val error: String? = null,
    )

    private companion object {
        const val MODULE = "lcars_extract"
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun reasonOf(kind: String): MediaResolution.Reason = when (kind.uppercase()) {
            "UNSUPPORTED" -> MediaResolution.Reason.UNSUPPORTED
            "BLOCKED" -> MediaResolution.Reason.BLOCKED
            "NO_STREAM" -> MediaResolution.Reason.NO_STREAM
            "UNAVAILABLE" -> MediaResolution.Reason.UNAVAILABLE
            else -> MediaResolution.Reason.FAILED
        }
    }
}
