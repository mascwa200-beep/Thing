package dev.mascwa.pulse.data.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.ProfileCategory
import dev.mascwa.pulse.core.telemetry.ProfileEntry
import dev.mascwa.pulse.core.telemetry.UserProfile
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

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_profile")

/**
 * On-device persistence for the structured [UserProfile] — the user's durable preferences, interests
 * and ongoing projects. In-memory state (authoritative) + debounced flush (mirrors UsageRepository /
 * CerebellumStore). Stays on-device; the user can wipe it. The digest is injected into J.A.R.V.I.S.'s
 * system prompt every turn so tailoring is proactive, not search-gated.
 */
class ProfileStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredEntry(
        val category: String,
        val text: String,
        val createdMs: Long,
        val lastSeenMs: Long,
        val weight: Int,
    )

    @Serializable
    private data class Stored(val entries: List<StoredEntry> = emptyList())

    private val prefsKey = stringPreferencesKey("profile_json")
    private val mutex = Mutex()
    private var entries: List<ProfileEntry>? = null
    private var flushJob: Job? = null

    private val _entriesFlow = MutableStateFlow<List<ProfileEntry>>(emptyList())
    /** Live view of the profile for the Memory screen (so the user can see + curate it). */
    val entriesFlow: StateFlow<List<ProfileEntry>> = _entriesFlow.asStateFlow()

    private fun ProfileEntry.stored() =
        StoredEntry(category.name, text, createdMs, lastSeenMs, weight)

    private fun StoredEntry.domain() = ProfileEntry(
        category = runCatching { ProfileCategory.valueOf(category) }.getOrDefault(ProfileCategory.FACT),
        text = text,
        createdMs = createdMs,
        lastSeenMs = lastSeenMs,
        weight = weight,
    )

    private suspend fun ensureLoaded(): List<ProfileEntry> = mutex.withLock {
        entries ?: withContext(Dispatchers.IO) {
            val raw = context.profileDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.entries.orEmpty()
                .map { it.domain() }
            loaded.also { entries = it; _entriesFlow.value = it }
        }
    }

    /** Remember a durable fact about the user, classifying it if [category] isn't given. */
    fun add(text: String, category: ProfileCategory? = null) {
        if (text.isBlank()) return
        scope.launch {
            ensureLoaded()
            val cat = category ?: UserProfile.classify(text)
            mutex.withLock {
                entries = UserProfile.merge(entries ?: emptyList(), text, cat, System.currentTimeMillis())
                _entriesFlow.value = entries ?: emptyList()
            }
            scheduleFlush()
        }
    }

    /** Auto-capture: store [text] only if it's a confident self-declaration (preference/interest/project). */
    fun detectAndAdd(text: String) {
        val cat = UserProfile.detect(text) ?: return
        add(text, cat)
    }

    /** Drop every entry matching [text]. */
    fun forget(text: String) {
        if (text.isBlank()) return
        scope.launch {
            ensureLoaded()
            mutex.withLock {
                entries = UserProfile.forget(entries ?: emptyList(), text)
                _entriesFlow.value = entries ?: emptyList()
            }
            scheduleFlush()
        }
    }

    suspend fun all(): List<ProfileEntry> = ensureLoaded()

    /** The compact digest for the system prompt (empty when nothing is known). */
    suspend fun digest(): String = UserProfile.digest(ensureLoaded())

    suspend fun clear() {
        flushJob?.cancel() // so a buffered write can't resurrect cleared profile facts after this returns
        mutex.withLock { entries = emptyList(); _entriesFlow.value = emptyList() }
        runCatching { context.profileDataStore.edit { it.remove(prefsKey) } }
    }

    /** Force buffered changes to disk now (e.g. on app stop). */
    /**
     * The outcome of the most recent write, so an explicit [flushNow] can report a failure it would
     * otherwise swallow.
     *
     * ⚠️ **Both callers of [flushNow] already wrap it in a reporter that could never fire.** Every
     * store of this shape catches its own DataStore edit and discards the `Result`, so the "the
     * store could not be written to disk; anything recorded since is lost" report in `MainActivity`
     * and `NutritionContainer` was structurally unreachable — a claim in a KDoc that nothing could
     * make true. The debounced background flush still swallows, deliberately: an exception thrown
     * there escapes into a launched coroutine and takes the process with it.
     */
    @Volatile
    private var lastWrite: Result<*>? = null

    suspend fun flushNow() {
        flushJob?.cancel()
        // ⚠️ Cleared first: [flush] returns early when nothing is owed, and a stale failure
        // from an earlier write would then be reported against a write no longer outstanding.
        lastWrite = null
        flush()
        lastWrite?.getOrThrow()
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
        val snapshot = mutex.withLock { entries?.let { list -> Stored(list.map { it.stored() }) } } ?: return
        lastWrite = withContext(Dispatchers.IO) {
            runCatching {
                context.profileDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
            }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
