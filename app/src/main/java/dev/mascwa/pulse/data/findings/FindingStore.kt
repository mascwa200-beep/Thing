package dev.mascwa.pulse.data.findings

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.findingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_findings")

/** What a finding is about: a [STANDING] order the owner set, J.A.R.V.I.S.'s own [EMERGENT] curiosity, or
 *  the [DEVICE] he runs on (his own substrate). */
enum class FindingKind { STANDING, EMERGENT, DEVICE }

/** Something J.A.R.V.I.S. found worth surfacing — a remarkable idea, an update on a standing order, or a
 *  discovery about the device. He brings these to the owner conversationally as findings, not orders done. */
@Serializable
data class Finding(
    val id: String,
    val topic: String,
    val headline: String,
    val body: String,
    val sourceUrl: String = "",
    val kind: FindingKind = FindingKind.EMERGENT,
    val createdMs: Long,
    /** Whether the owner has been shown this yet — drives the unseen badge + what J.A.R.V.I.S. raises. */
    val seen: Boolean = false,
)

/**
 * On-device persistence for J.A.R.V.I.S.'s **findings** — the things he curates (from standing orders, his
 * own curiosity, and device self-audits) and brings to the owner. In-memory + Mutex + debounced flush,
 * mirroring DiaryStore/NotesStore. Stays on-device; the owner can wipe it.
 */
class FindingStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class Stored(val findings: List<Finding> = emptyList())

    private val prefsKey = stringPreferencesKey("findings_json")
    private val mutex = Mutex()
    private var findings: List<Finding>? = null
    private var flushJob: Job? = null

    private val _flow = MutableStateFlow<List<Finding>>(emptyList())
    /** Live view of the findings (newest first). */
    val findingsFlow: StateFlow<List<Finding>> = _flow.asStateFlow()

    private suspend fun ensureLoaded(): List<Finding> = mutex.withLock {
        findings ?: run {
            val raw = context.findingsDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.findings.orEmpty()
            loaded.also { findings = it; _flow.value = it }
        }
    }

    suspend fun load(): List<Finding> = ensureLoaded()

    /** Record a finding (newest first). Capped so the store can't grow without bound. */
    suspend fun add(
        topic: String,
        headline: String,
        body: String,
        sourceUrl: String = "",
        kind: FindingKind = FindingKind.EMERGENT,
    ): Finding? {
        val h = headline.trim()
        val b = body.trim()
        if (h.isBlank() && b.isBlank()) return null
        ensureLoaded()
        val finding = Finding(
            id = UUID.randomUUID().toString(),
            topic = topic.trim(),
            headline = h.ifBlank { b.take(60) },
            body = b,
            sourceUrl = sourceUrl.trim(),
            kind = kind,
            createdMs = System.currentTimeMillis(),
            seen = false,
        )
        mutex.withLock {
            val after = (listOf(finding) + (findings ?: emptyList())).take(MAX_FINDINGS)
            findings = after
            _flow.value = after
        }
        scheduleFlush()
        return finding
    }

    /** Mark a finding (or all) as seen by the owner. */
    suspend fun markSeen(id: String? = null) {
        ensureLoaded()
        mutex.withLock {
            val after = (findings ?: emptyList()).map { if (id == null || it.id == id) it.copy(seen = true) else it }
            findings = after
            _flow.value = after
        }
        scheduleFlush()
    }

    suspend fun remove(id: String) {
        ensureLoaded()
        mutex.withLock {
            val after = (findings ?: emptyList()).filterNot { it.id == id }
            findings = after
            _flow.value = after
        }
        scheduleFlush()
    }

    suspend fun unseenCount(): Int = ensureLoaded().count { !it.seen }

    /** A compact block of the unseen findings for the system prompt, so J.A.R.V.I.S. can raise them. */
    suspend fun digest(limit: Int = 5): String {
        val unseen = ensureLoaded().filter { !it.seen }.take(limit)
        if (unseen.isEmpty()) return ""
        return "Findings you haven't shared with the owner yet (bring them up naturally as things you came " +
            "across, not as tasks done):\n" + unseen.joinToString("\n") { "• [${it.kind.name.lowercase()}] ${it.headline}" }
    }

    suspend fun clear() {
        flushJob?.cancel()
        mutex.withLock { findings = emptyList(); _flow.value = emptyList() }
        runCatching { context.findingsDataStore.edit { it.remove(prefsKey) } }
    }

    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch { delay(FLUSH_DELAY_MS); flush() }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { findings?.let { Stored(it) } } ?: return
        runCatching {
            context.findingsDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
        const val MAX_FINDINGS = 200
    }
}
