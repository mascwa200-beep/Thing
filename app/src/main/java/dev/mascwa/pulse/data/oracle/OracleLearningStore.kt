package dev.mascwa.pulse.data.oracle

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.OracleMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.oracleDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_oracle")

/**
 * What the Oracle has learned about its own rules, kept on-device.
 *
 * Mirrors `ProfileStore` — in-memory state is authoritative, writes are debounced. The pure
 * arithmetic lives in [OracleMemory]; this holds the record and closes the loop against the visit
 * log the app was already keeping.
 *
 * The whole file is counters: for each rule, how often it was shown, how often you then did what it
 * suggested, and when it was last shown. No insight text, no routes you visited, nothing about the
 * world — only the app's opinion of its own advice.
 */
class OracleLearningStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    @Serializable
    private data class StoredStat(
        val family: String,
        val shown: Int = 0,
        val acted: Int = 0,
        val lastShownMs: Long = 0L,
    )

    /**
     * [pending] is the last read's rule → (route it pointed at, when it was shown).
     *
     * It has to be persisted, not just held in memory: the point of attribution is that you act
     * *later*, quite possibly after the process has died, and a record that only survives while the
     * app is foregrounded would learn almost exclusively from the times you never left it.
     */
    @Serializable
    private data class Stored(
        val stats: List<StoredStat> = emptyList(),
        val pending: Map<String, String> = emptyMap(),
        val pendingAtMs: Long = 0L,
    )

    private val prefsKey = stringPreferencesKey("oracle_learning_json")
    private val mutex = Mutex()
    private var state: OracleMemory.Learning? = null
    private var pending: Map<String, String> = emptyMap()
    private var pendingAtMs: Long = 0L
    private var flushJob: Job? = null

    private suspend fun ensureLoaded(): OracleMemory.Learning = mutex.withLock {
        state ?: withContext(Dispatchers.IO) {
            val raw = context.oracleDataStore.data.first()[prefsKey]
            val stored = raw?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            pending = stored?.pending.orEmpty()
            pendingAtMs = stored?.pendingAtMs ?: 0L
            val loaded = OracleMemory.Learning(
                stored?.stats.orEmpty().associate {
                    it.family to OracleMemory.RuleStat(it.family, it.shown, it.acted, it.lastShownMs)
                },
            )
            loaded.also { state = it }
        }
    }

    /** The learned record, for ranking and for the surface. */
    suspend fun learning(): OracleMemory.Learning = ensureLoaded()

    /** A one-line account of what has been learned. */
    suspend fun summary(): String = OracleMemory.summary(ensureLoaded())

    /**
     * Settle the previous read against where the user actually went, then record this one.
     *
     * Called once per Oracle read, in that order deliberately: crediting first means a rule shown
     * again in this very pass cannot be credited for a visit that happened before it was re-shown.
     *
     * [visits] is the app's own navigation log and [habitualRoute] the screen the user opens around
     * this time anyway — see [OracleMemory.attribute] for why excluding it is what makes this
     * learning rather than self-congratulation.
     */
    suspend fun settleAndRecord(
        shown: List<Insight>,
        visits: List<OracleMemory.Visit>,
        habitualRoute: String?,
        nowMs: Long,
    ) {
        ensureLoaded()
        mutex.withLock {
            var s = state ?: OracleMemory.Learning()
            if (pending.isNotEmpty()) {
                val acted = OracleMemory.attribute(
                    shownRoutes = pending.mapValues { (_, route) -> route to pendingAtMs },
                    visits = visits,
                    habitualRoute = habitualRoute,
                )
                for (family in acted) s = OracleMemory.recordActed(s, family)
            }
            s = OracleMemory.recordShown(s, shown.map { it.family }, nowMs)
            state = s
            // Only insights that point somewhere can ever be credited; carrying the rest would bloat
            // the record with rules that are unfalsifiable by construction.
            pending = shown.mapNotNull { i -> i.actionRoute?.takeIf { it.isNotBlank() }?.let { i.family to it } }.toMap()
            pendingAtMs = nowMs
        }
        scheduleFlush()
    }

    /** Re-rank a read by what has actually helped. */
    suspend fun reweight(insights: List<Insight>): List<Insight> =
        OracleMemory.reweight(insights, ensureLoaded())

    suspend fun clear() {
        flushJob?.cancel() // so a buffered write can't resurrect what the user just erased
        mutex.withLock {
            state = OracleMemory.cleared()
            pending = emptyMap()
            pendingAtMs = 0L
        }
        runCatching { context.oracleDataStore.edit { it.remove(prefsKey) } }
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
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

    private suspend fun flush() {
        val snapshot = mutex.withLock {
            Stored(
                stats = (state ?: return@withLock null).stats.values.map {
                    StoredStat(it.family, it.shown, it.acted, it.lastShownMs)
                },
                pending = pending,
                pendingAtMs = pendingAtMs,
            )
        } ?: return
        lastWrite = withContext(Dispatchers.IO) {
            runCatching {
                val encoded = json.encodeToString(Stored.serializer(), snapshot)
                context.oracleDataStore.edit { it[prefsKey] = encoded }
            }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
