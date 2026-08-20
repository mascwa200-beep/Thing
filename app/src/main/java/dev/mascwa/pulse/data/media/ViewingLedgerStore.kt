package dev.mascwa.pulse.data.media

import android.content.Context
import dev.mascwa.pulse.core.telemetry.TheaterModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where you left off — the CONTINUE WATCHING shelf's memory.
 *
 * A small JSON file in the app's own sandbox, like the harvester's `harvest/` directory and unlike
 * a DataStore: the ledger is ten small rows whose whole policy lives in the CI-tested
 * [TheaterModel.remember], and a flat file keeps the store to plumbing. On-device only; wiped by
 * [clear]; never uploaded anywhere (viewing history — the same privacy class as the URLs and
 * queries the extractor refuses to log).
 */
class ViewingLedgerStore(private val appContext: Context) {

    private val mutex = Mutex()

    /**
     * The store's own scope, for the ONE write that cannot ride a caller's: the final
     * save-where-you-were as the ViewModel is torn down. `onCleared` runs after `viewModelScope`
     * is already cancelled, so a launch there silently never executes — the classic lost-last-write.
     * [rememberAsync] rides this scope instead, which lives as long as the process.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ledger = MutableStateFlow<List<TheaterModel.Viewing>>(emptyList())
    val ledger: StateFlow<List<TheaterModel.Viewing>> = _ledger.asStateFlow()

    @Volatile
    private var loaded = false

    /** Load once, lazily — callers collect [ledger] and call this on first use. */
    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return@withLock
            withContext(Dispatchers.IO) {
                _ledger.value = runCatching {
                    json.decodeFromString(Stored.serializer(), file().readText())
                }.getOrNull()?.viewings?.map { it.toModel() }.orEmpty()
            }
            loaded = true
        }
    }

    /** Fold one viewing in through the pure policy and persist the result. */
    suspend fun remember(v: TheaterModel.Viewing) {
        ensureLoaded()
        mutex.withLock {
            _ledger.value = TheaterModel.remember(_ledger.value, v)
            flushLocked()
        }
    }

    /** Fire-and-forget [remember] on the store's own scope — safe from a dying ViewModel. */
    fun rememberAsync(v: TheaterModel.Viewing) {
        scope.launch { remember(v) }
    }

    /** The saved position for [id], or null when it is not on the shelf. */
    suspend fun positionOf(id: String): Long? {
        ensureLoaded()
        return _ledger.value.firstOrNull { it.id == id }?.positionMs
    }

    /** Forget everything — the erase control. Deletes the file, not just the rows. */
    suspend fun clear() {
        mutex.withLock {
            _ledger.value = emptyList()
            loaded = true
            withContext(Dispatchers.IO) { runCatching { file().delete() } }
        }
    }

    private suspend fun flushLocked() {
        val stored = Stored(_ledger.value.map { StoredViewing.of(it) })
        withContext(Dispatchers.IO) {
            runCatching { file().writeText(json.encodeToString(Stored.serializer(), stored)) }
        }
    }

    private fun file() = File(appContext.filesDir, FILE_NAME)

    @Serializable
    private data class Stored(val viewings: List<StoredViewing> = emptyList())

    @Serializable
    private data class StoredViewing(
        val id: String = "",
        val title: String = "",
        val pageUrl: String = "",
        val thumbnailUrl: String = "",
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val updatedAtMs: Long = 0,
    ) {
        fun toModel() = TheaterModel.Viewing(id, title, pageUrl, thumbnailUrl, positionMs, durationMs, updatedAtMs)

        companion object {
            fun of(v: TheaterModel.Viewing) = StoredViewing(
                v.id, v.title, v.pageUrl, v.thumbnailUrl, v.positionMs, v.durationMs, v.updatedAtMs,
            )
        }
    }

    private companion object {
        const val FILE_NAME = "theater_resume.json"
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}
