package dev.mascwa.pulse.data.news

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Collections

private val Context.newsAnalysisDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_news_analysis")

/**
 * On-device cache of [NewsAnalysis] keyed by article url. A cloud LLM call costs real money, so a story is
 * analyzed **at most once ever** — persisted across app restarts, mirroring MemoryStreamStore/ProfileStore:
 * in-memory authoritative state + debounced flush, LRU-capped by generation time (oldest evicted first). A
 * session-only `failed` set stops a malformed/errored call from being retried on every recomposition; a
 * later app restart (fresh session) will retry.
 */
class NewsAnalysisStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class Stored(val byUrl: Map<String, NewsAnalysis> = emptyMap())

    private val prefsKey = stringPreferencesKey("news_analysis_json")
    private val mutex = Mutex()
    private var byUrl: MutableMap<String, NewsAnalysis>? = null
    private var flushJob: Job? = null

    private val _analysesFlow = MutableStateFlow<Map<String, NewsAnalysis>>(emptyMap())
    /** Live view of the cache, keyed by article url — for reactive UI (an entry appearing upgrades a
     *  card's copy from the heuristic fallback to the LLM synthesis in place). */
    val analysesFlow: StateFlow<Map<String, NewsAnalysis>> = _analysesFlow.asStateFlow()

    /** Session-only — a url that errored/produced nothing isn't retried again until the next app run. */
    private val failed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private suspend fun ensureLoaded(): MutableMap<String, NewsAnalysis> = mutex.withLock {
        byUrl ?: withContext(Dispatchers.IO) {
            val raw = context.newsAnalysisDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.byUrl.orEmpty()
                .toMutableMap()
            loaded.also { byUrl = it; _analysesFlow.value = it }
        }
    }

    /** Warm the in-memory cache from disk. Call once (e.g. from the owning ViewModel's init) so [get]'s
     *  synchronous read is populated before the first article composes. */
    suspend fun preload() { ensureLoaded() }

    /** The cached analysis for [url], if any. Synchronous — reads whatever's in memory right now (populated
     *  by [preload] / a prior [record]); returns null before the store has ever loaded, which just means a
     *  redundant analysis on a very first cold-start read (harmless, self-correcting). */
    fun get(url: String): NewsAnalysis? = byUrl?.get(url)

    fun hasFailed(url: String): Boolean = url in failed

    fun markFailed(url: String) { failed += url }

    suspend fun record(url: String, analysis: NewsAnalysis) {
        ensureLoaded()
        mutex.withLock {
            val current = byUrl ?: mutableMapOf()
            current[url] = analysis
            if (current.size > CAP) {
                current.entries.minByOrNull { it.value.generatedAtMs }?.key?.let { current.remove(it) }
            }
            byUrl = current
            _analysesFlow.value = current.toMap()
        }
        scheduleFlush()
    }

    /** Trailing throttle (see UsageRepository/MemoryStreamStore): one flush per window, never deferred
     *  indefinitely. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { byUrl?.let { Stored(it.toMap()) } } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                context.newsAnalysisDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
            }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
        const val CAP = 200
    }
}
