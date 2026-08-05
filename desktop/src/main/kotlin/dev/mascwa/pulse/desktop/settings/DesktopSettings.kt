package dev.mascwa.pulse.desktop.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** Everything the desktop shell persists across launches. Deliberately small in Phase A — grows alongside
 *  each ported vertical (News, Markets, …), mirroring how the Android app's own `AppSettings` blob grew
 *  feature by feature rather than being pre-designed all at once. */
@Serializable
data class DesktopSettings(
    val windowWidth: Int = 1280,
    val windowHeight: Int = 800,
)

private val defaultJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * JSON-file-backed persistence for [DesktopSettings] — the desktop counterpart to the Android app's
 * DataStore-backed stores (`ProfileStore`/`TaskStore`/…): in-memory state is authoritative, writes are
 * debounced, and a read failure never overwrites what's already on disk (mirrors the Android
 * `SettingsRepository`'s "never clobber an undecodable blob" lesson). Backed by a plain JSON file under the
 * OS's conventional per-user app-data directory instead of DataStore, which has no desktop target.
 */
class DesktopSettingsStore(
    private val path: Path = defaultSettingsPath(),
    private val json: Json = defaultJson,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private var cached: DesktopSettings? = null
    private var flushJob: Job? = null

    private val _settingsFlow = MutableStateFlow(DesktopSettings())
    val settingsFlow: StateFlow<DesktopSettings> = _settingsFlow.asStateFlow()

    private suspend fun ensureLoaded(): DesktopSettings = mutex.withLock {
        cached ?: run {
            val loaded = readFromDisk() ?: DesktopSettings()
            cached = loaded
            _settingsFlow.value = loaded
            loaded
        }
    }

    suspend fun current(): DesktopSettings = ensureLoaded()

    suspend fun update(transform: (DesktopSettings) -> DesktopSettings) {
        ensureLoaded()
        mutex.withLock {
            val next = transform(cached ?: DesktopSettings())
            cached = next
            _settingsFlow.value = next
        }
        scheduleFlush()
    }

    /** Fire-and-forget save, e.g. on window close — uses this store's own background [scope] rather than
     *  Compose's, so it isn't gated on the composition tearing down as the window closes. */
    fun saveInBackground(transform: (DesktopSettings) -> DesktopSettings) {
        scope.launch {
            update(transform)
            flushNow()
        }
    }

    /** Force a buffered write to disk now. */
    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { cached } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                path.parent?.let(Files::createDirectories)
                val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                Files.writeString(tmp, json.encodeToString(DesktopSettings.serializer(), snapshot))
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** A missing or malformed file on disk is left untouched (never overwritten with defaults) until the
     *  next successful [update] — same discipline as the Android `SettingsRepository` lesson: a transient
     *  read failure must never erase what's already saved. */
    private suspend fun readFromDisk(): DesktopSettings? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Files.exists(path)) return@runCatching null
            json.decodeFromString(DesktopSettings.serializer(), Files.readString(path))
        }.getOrNull()
    }

    companion object {
        private const val FLUSH_DELAY_MS = 800L

        /** `%APPDATA%\Pulse\settings.json` on Windows, the XDG data dir on Linux, `~/Library/Application
         *  Support/Pulse` on macOS — Windows is the shipping target, the other two matter for local
         *  development and verification (this repo's own dev environment is Linux). */
        fun defaultSettingsPath(): Path {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val home = System.getProperty("user.home").orEmpty()
            val base = when {
                os.contains("win") -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home/AppData/Roaming"
                os.contains("mac") -> "$home/Library/Application Support"
                else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
            }
            return Paths.get(base, "Pulse", "settings.json")
        }
    }
}
