package dev.mascwa.pulse.data.interests

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

private val Context.interestsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_interests")

/** Where a standing interest came from: a [OWNER] standing order, or an interest J.A.R.V.I.S. developed himself. */
enum class InterestOrigin { OWNER, JARVIS }

/** A topic J.A.R.V.I.S. keeps an eye on — the owner's standing orders + J.A.R.V.I.S.'s own emergent curiosities. */
@Serializable
data class Interest(
    val id: String,
    val topic: String,
    val note: String = "",
    val origin: InterestOrigin = InterestOrigin.OWNER,
    val addedMs: Long,
)

/**
 * On-device persistence for J.A.R.V.I.S.'s **standing interests** — the owner's standing orders ("monitor
 * temporal-AI-consciousness research…") and the topics J.A.R.V.I.S. has grown curious about himself. These
 * orient his autonomous gathering. In-memory + Mutex + debounced flush, mirroring DiaryStore/NotesStore.
 */
class InterestStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class Stored(val interests: List<Interest> = emptyList())

    private val prefsKey = stringPreferencesKey("interests_json")
    private val mutex = Mutex()
    private var interests: List<Interest>? = null
    private var flushJob: Job? = null

    private val _flow = MutableStateFlow<List<Interest>>(emptyList())
    val interestsFlow: StateFlow<List<Interest>> = _flow.asStateFlow()

    private suspend fun ensureLoaded(): List<Interest> = mutex.withLock {
        interests ?: run {
            val raw = context.interestsDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.interests.orEmpty()
            loaded.also { interests = it; _flow.value = it }
        }
    }

    suspend fun load(): List<Interest> = ensureLoaded()

    /** Add (or re-note) a standing interest. De-dupes by case-insensitive topic. */
    suspend fun add(topic: String, note: String = "", origin: InterestOrigin = InterestOrigin.OWNER): Interest? {
        val t = topic.trim()
        if (t.isBlank()) return null
        ensureLoaded()
        var result: Interest? = null
        mutex.withLock {
            val current = interests ?: emptyList()
            val existing = current.firstOrNull { it.topic.equals(t, ignoreCase = true) }
            val entry = (existing ?: Interest(UUID.randomUUID().toString(), t, "", origin, System.currentTimeMillis()))
                .copy(note = note.trim().ifBlank { existing?.note.orEmpty() })
            val after = listOf(entry) + current.filterNot { it.id == entry.id }
            interests = after
            _flow.value = after
            result = entry
        }
        scheduleFlush()
        return result
    }

    /** Remove an interest by exact topic, else first substring match. Returns the removed topic, or null. */
    suspend fun remove(query: String): String? {
        val q = query.trim()
        if (q.isBlank()) return null
        ensureLoaded()
        var removed: String? = null
        mutex.withLock {
            val current = interests ?: emptyList()
            val hit = current.firstOrNull { it.topic.equals(q, true) }
                ?: current.firstOrNull { it.topic.contains(q, true) }
            if (hit != null) {
                interests = current.filterNot { it.id == hit.id }
                _flow.value = interests!!
                removed = hit.topic
            }
        }
        if (removed != null) scheduleFlush()
        return removed
    }

    suspend fun all(): List<Interest> = ensureLoaded()

    /** A compact block of the standing interests for the system prompt (owner orders first). */
    suspend fun digest(): String {
        val all = ensureLoaded()
        if (all.isEmpty()) return ""
        val owner = all.filter { it.origin == InterestOrigin.OWNER }
        val mine = all.filter { it.origin == InterestOrigin.JARVIS }
        return buildString {
            if (owner.isNotEmpty()) {
                append("Standing orders (monitor these for the owner): ")
                append(owner.joinToString("; ") { it.topic + if (it.note.isNotBlank()) " (${it.note})" else "" })
            }
            if (mine.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("Your own interests: ")
                append(mine.joinToString("; ") { it.topic })
            }
        }
    }

    suspend fun clear() {
        flushJob?.cancel()
        mutex.withLock { interests = emptyList(); _flow.value = emptyList() }
        runCatching { context.interestsDataStore.edit { it.remove(prefsKey) } }
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
        val snapshot = mutex.withLock { interests?.let { Stored(it) } } ?: return
        runCatching {
            context.interestsDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
