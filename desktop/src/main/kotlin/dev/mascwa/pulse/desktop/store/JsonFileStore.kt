package dev.mascwa.pulse.desktop.store

import dev.mascwa.pulse.desktop.AppPaths
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One small JSON file on disk, held in memory and written back lazily.
 *
 * This is the desktop's stand-in for the phone's `preferencesDataStore`, and it is the reason the
 * desktop can carry the phone's features at all: roughly eighteen stores on the phone — notes, the
 * diary, saved places, the study deck, watchlists, tracked objectives — are the same thing, a small
 * serializable blob with a debounced write, and each one would otherwise need its own file handling
 * written from scratch here.
 *
 * The discipline is the one `DesktopSettingsStore` proved and is copied deliberately rather than
 * re-invented:
 *
 *  - **memory is authoritative.** Reads never touch the disk after the first load.
 *  - **writes are debounced.** Typing in a note is dozens of updates and one write.
 *  - **the write is atomic.** A temporary sibling is written and then moved over the target, so a
 *    process that dies mid-write leaves the previous file intact rather than a half-written one.
 *  - **the disk IO happens INSIDE the lock**, not just the snapshot read. ⚠️ [flushNow] cancels the
 *    debounce job, but it cannot interrupt one already past its `delay()` — so without this the
 *    forced and debounced paths could both be writing the same `.tmp` sibling at once.
 *  - ⚠️ **an unreadable file is never overwritten with defaults.** A file that exists but does not
 *    parse is left exactly as it is and the store starts empty in memory; only a real [update] will
 *    replace it. This is the `SettingsRepository` lesson: a transient read failure that silently
 *    resets someone's data is much worse than a feature that is briefly empty. The difference is
 *    reported through [loadFailed] so a screen can say so rather than showing a convincing blank.
 *
 * @param T the blob's type. Anything `@Serializable`.
 * @param name the file name under the shared per-user data directory — `notes.json`, `diary.json`.
 * @param serializer that type's serializer, passed explicitly so this class needs no reified generic
 *   and can therefore be constructed from a variable type at a call site.
 * @param default what an absent file means. Called rather than held, so a mutable default (a list, a
 *   map) can never be shared between the store and the caller by accident.
 */
class JsonFileStore<T : Any>(
    name: String,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
    private val path: Path = AppPaths.dataDir.resolve(name),
    private val json: Json = DEFAULT_JSON,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private var cached: T? = null
    private var flushJob: Job? = null

    private val _state = MutableStateFlow(default())

    /** The blob, live. Holds the default until the first [current]/[update] causes a load. */
    val state: StateFlow<T> = _state.asStateFlow()

    /**
     * True when a file was present on disk and could not be read.
     *
     * ⚠️ Distinct from "empty" on purpose, and it is the whole reason the no-clobber rule is safe to
     * ship: without this a corrupted file looks exactly like a first run, and the honest answer —
     * "your data is still there, this build cannot read it" — is unavailable to every screen above.
     */
    @Volatile
    var loadFailed: Boolean = false
        private set

    suspend fun current(): T = ensureLoaded()

    suspend fun update(transform: (T) -> T) {
        ensureLoaded()
        mutex.withLock {
            val next = transform(cached ?: default())
            cached = next
            _state.value = next
        }
        scheduleFlush()
    }

    /** Write any buffered change now. Call before the window closes — `exitApplication()` calls
     *  `System.exit(0)` immediately, and a debounced write would simply be lost. */
    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    /** Throw the contents away, on disk as well as in memory. */
    suspend fun clear() {
        mutex.withLock {
            cached = default()
            _state.value = cached!!
            loadFailed = false
        }
        flushNow()
    }

    private suspend fun ensureLoaded(): T = mutex.withLock {
        cached ?: run {
            val loaded = readFromDisk() ?: default()
            cached = loaded
            _state.value = loaded
            loaded
        }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        mutex.withLock {
            // ⚠️ Refuse to write over a file we could not read. Overwriting here would turn a
            // recoverable "this build cannot parse it" into a permanent loss, on a path that looks
            // like ordinary operation.
            if (loadFailed) return@withLock
            val snapshot = cached ?: return@withLock
            withContext(Dispatchers.IO) {
                runCatching {
                    path.parent?.let(Files::createDirectories)
                    val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                    Files.writeString(tmp, json.encodeToString(serializer, snapshot))
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private suspend fun readFromDisk(): T? = withContext(Dispatchers.IO) {
        if (!Files.exists(path)) return@withContext null
        val text = runCatching { Files.readString(path) }.getOrNull()
        if (text == null) {
            loadFailed = true
            return@withContext null
        }
        val parsed = runCatching { json.decodeFromString(serializer, text) }.getOrNull()
        if (parsed == null) loadFailed = true
        parsed
    }

    companion object {
        private const val FLUSH_DELAY_MS = 800L

        /** `ignoreUnknownKeys` so an older build can read a newer file's blob instead of discarding it,
         *  and `encodeDefaults` so the written file is readable on its own rather than depending on the
         *  reader having the same defaults compiled in. Same settings the phone's stores use. */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
