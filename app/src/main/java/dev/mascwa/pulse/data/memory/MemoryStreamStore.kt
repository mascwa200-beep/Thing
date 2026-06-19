package dev.mascwa.pulse.data.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.LexicalEmbedder
import dev.mascwa.pulse.core.telemetry.Memory
import dev.mascwa.pulse.core.telemetry.MemoryKind
import dev.mascwa.pulse.core.telemetry.MemoryStream
import dev.mascwa.pulse.core.telemetry.ScoredMemory
import dev.mascwa.pulse.core.telemetry.TemporalReasoner
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.memoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_memory")

/**
 * On-device persistence for the episodic [MemoryStream] — timestamped observations + synthesised
 * reflections, retrieved by recency·importance·relevance. In-memory state (authoritative) + debounced
 * flush, mirroring ProfileStore / TaskStore. Stays on-device; the user can wipe it.
 *
 * Embeddings are **derived, not stored**: because [LexicalEmbedder] is deterministic, we persist only
 * the text + metadata and re-embed on load, keeping the store compact (no vectors on disk). If we ever
 * swap in a learned transformer embedder, this becomes "persist the vector or re-embed via the model".
 */
class MemoryStreamStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredMemory(
        val id: Long,
        val text: String,
        val kind: String,
        val importance: Int,
        val createdMs: Long,
        val lastAccessedMs: Long,
    )

    @Serializable
    private data class Stored(val memories: List<StoredMemory> = emptyList())

    private val prefsKey = stringPreferencesKey("memory_json")
    private val mutex = Mutex()
    private var memories: List<Memory>? = null
    private var flushJob: Job? = null

    private val _memoriesFlow = MutableStateFlow<List<Memory>>(emptyList())
    /** Live view of the stream (for a future Memory-screen surface). */
    val memoriesFlow: StateFlow<List<Memory>> = _memoriesFlow.asStateFlow()

    private fun Memory.stored() = StoredMemory(id, text, kind.name, importance, createdMs, lastAccessedMs)

    private fun StoredMemory.domain() = Memory(
        id = id,
        text = text,
        kind = runCatching { MemoryKind.valueOf(kind) }.getOrDefault(MemoryKind.OBSERVATION),
        importance = importance,
        createdMs = createdMs,
        lastAccessedMs = lastAccessedMs,
        embedding = LexicalEmbedder.embed(text), // derived, not persisted
    )

    private suspend fun ensureLoaded(): List<Memory> = mutex.withLock {
        memories ?: run {
            val raw = context.memoryDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.memories.orEmpty()
                .map { it.domain() }
            loaded.also { memories = it; _memoriesFlow.value = it }
        }
    }

    /**
     * Record a memory. [importance] defaults to the on-device heuristic. Returns the stored memory,
     * or null when [text] is blank. Caps the stream, evicting the least valuable first.
     */
    suspend fun record(
        text: String,
        kind: MemoryKind = MemoryKind.OBSERVATION,
        importance: Int? = null,
    ): Memory? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        ensureLoaded()
        val now = System.currentTimeMillis()
        val result = mutex.withLock {
            val current = memories ?: emptyList()
            val id = (current.maxOfOrNull { it.id } ?: 0L) + 1L
            val memory = Memory(
                id = id,
                text = clean,
                kind = kind,
                importance = importance ?: MemoryStream.estimateImportance(clean),
                createdMs = now,
                lastAccessedMs = now,
                embedding = LexicalEmbedder.embed(clean),
            )
            val after = MemoryStream.capped(current + memory, CAP)
            memories = after
            _memoriesFlow.value = after
            memory
        }
        scheduleFlush()
        return result
    }

    /**
     * Always-on capture: record [text] as an observation, but only when it clears a significance floor
     * (so trivial chatter like "ok thanks" doesn't fill the stream). Fire-and-forget.
     */
    fun captureObservation(text: String) {
        val clean = text.trim()
        if (clean.length < MIN_CAPTURE_CHARS) return
        if (MemoryStream.estimateImportance(clean) < MIN_CAPTURE_IMPORTANCE) return
        scope.launch { record(clean, MemoryKind.OBSERVATION) }
    }

    /**
     * Retrieve the most relevant memories for [query] and mark them accessed (so recall keeps a memory
     * fresh). Returns the scored results; the access-time bump is flushed.
     */
    suspend fun recall(query: String, topK: Int = MemoryStream.DEFAULT_TOP_K): List<ScoredMemory> {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val scored = mutex.withLock {
            val current = memories ?: emptyList()
            if (current.isEmpty()) return@withLock emptyList()
            val results = MemoryStream.retrieve(current, LexicalEmbedder.embed(query), now, topK)
            val hitIds = results.map { it.memory.id }.toSet()
            if (hitIds.isNotEmpty()) {
                val touched = current.map { if (it.id in hitIds) MemoryStream.touch(it, now) else it }
                memories = touched
                _memoriesFlow.value = touched
            }
            results
        }
        if (scored.isNotEmpty()) scheduleFlush()
        return scored
    }

    /** A compact, recall-ranked block of relevant past memories — each stamped with its relative time
     *  (so "yesterday: …") — for the prompt. Empty when none. */
    suspend fun digest(query: String, topK: Int = DEFAULT_DIGEST_K): String {
        val scored = recall(query, topK)
        if (scored.isEmpty()) return ""
        val now = System.currentTimeMillis()
        return scored.joinToString("\n") { "- ${TemporalReasoner.describeWhen(it.memory, now)}: ${it.memory.text}" }
    }

    /** A newest-first chronological digest of recent memories, each stamped with its relative time. */
    suspend fun timeline(max: Int = TemporalReasoner.DEFAULT_TIMELINE): String =
        TemporalReasoner.timeline(ensureLoaded(), System.currentTimeMillis(), max)

    suspend fun all(): List<Memory> = ensureLoaded()

    suspend fun clear() {
        flushJob?.cancel() // so a buffered write can't resurrect cleared memories after this returns
        mutex.withLock { memories = emptyList(); _memoriesFlow.value = emptyList() }
        runCatching { context.memoryDataStore.edit { it.remove(prefsKey) } }
    }

    /** Force buffered changes to disk now (e.g. on app stop). */
    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    /** Trailing throttle (see UsageRepository): one flush per window, never deferred indefinitely. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { memories?.let { list -> Stored(list.map { it.stored() }) } } ?: return
        runCatching {
            context.memoryDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
        const val CAP = 500
        const val MIN_CAPTURE_CHARS = 12
        const val MIN_CAPTURE_IMPORTANCE = 3
        const val DEFAULT_DIGEST_K = 4
    }
}
