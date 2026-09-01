package dev.mascwa.pulse.data.comms

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.MailGlance
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

private val Context.mailNoticeDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_mail_notices")

/**
 * The last count of waiting mail worked out from the notification shade, kept where a widget update
 * in a fresh process can read it.
 *
 * ## ⚠️ Why a DataStore of its own, and not the shared disk cache
 *
 * Everything else the comms block draws is cached in `DiskCache`, whose own KDoc says outright that
 * nothing in it is a system of record and that it LRU-prunes at eight megabytes. That is exactly
 * right for a market quote, which is refetched the moment it is missed. **This snapshot has no
 * upstream to refetch from.** Nothing can reconstruct it except the notifications currently on the
 * shade, and the only component that can see those is the listener — which may not run again for
 * hours. Pruned, the count would simply be gone until the next mail arrived.
 *
 * ## ⚠️ The snapshot is void the moment the grant is gone
 *
 * [read] answers null when notification access is not held, rather than yesterday's confident
 * number. Revoking access is a decision, and a widget that carried on reporting a count afterwards
 * would be reporting from data it is no longer allowed to have.
 *
 * It is deliberately **not** aged out. A count with no new mail since Tuesday is legitimately days
 * old and still exactly right, so a staleness cutoff would delete correct answers for tidiness.
 *
 * ## ⚠️ Counts only
 *
 * What is persisted is a package name and a number, because that is all [MailGlance.Notice] carries
 * — no sender, no subject, nothing that a debug report could leak and that `SecretScrub` could not
 * recognise. That decision is made in the core, and this file simply has nothing else to write down.
 */
class MailNoticeStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    @Serializable
    private data class StoredApp(val pkg: String, val waiting: Int)

    @Serializable
    private data class Stored(
        val apps: List<StoredApp> = emptyList(),
        /** When the listener last recomputed. Kept for the diagnostics surface, not for expiry. */
        val atMs: Long = 0,
    )

    private val prefsKey = stringPreferencesKey("mail_notices_json")
    private val mutex = Mutex()
    private var held: Stored? = null
    private var flushJob: Job? = null

    /**
     * The outcome of the most recent write, so [flushNow] can report a failure it would otherwise
     * swallow. The debounced background flush still swallows on purpose: an exception thrown there
     * escapes into a launched coroutine and takes the process with it.
     */
    @Volatile
    private var lastWrite: Result<*>? = null

    /**
     * What is waiting, or null when this app may not look.
     *
     * ⚠️ The grant is checked on **every** read rather than cached, because it can be revoked while
     * the process is alive and the answer must change with it. It is a single `Settings.Secure`
     * lookup, which is far cheaper than being wrong about it.
     *
     * A total of zero is a real answer once the grant is held — "nothing has been notified" — and
     * [MailGlance.Glance.line] is what decides that it should be said as nothing at all.
     */
    suspend fun read(): MailGlance.Glance? {
        if (!NotificationAccess.isGranted(context)) return null
        val stored = ensureLoaded()
        val apps = stored.apps.map { MailGlance.App(it.pkg, it.waiting) }
        return MailGlance.Glance(apps, apps.sumOf { it.waiting })
    }

    /** When the listener last recomputed, or 0 if it never has. For the diagnostics surface. */
    suspend fun lastRecomputedMs(): Long = ensureLoaded().atMs

    /**
     * Replace the whole snapshot.
     *
     * ⚠️ Replace, never merge. The listener recomputes from `getActiveNotifications()` every time,
     * so what it hands over is the complete truth about the shade — and merging would mean a
     * notification the user cleared while the process was dead could never be forgotten.
     */
    fun publish(glance: MailGlance.Glance, atMs: Long = System.currentTimeMillis()) {
        scope.launch {
            ensureLoaded()
            mutex.withLock {
                held = Stored(glance.apps.map { StoredApp(it.pkg, it.waiting) }, atMs)
            }
            scheduleFlush()
        }
    }

    suspend fun clear() {
        // So a buffered write cannot resurrect a cleared count after this returns.
        flushJob?.cancel()
        mutex.withLock { held = Stored() }
        runCatching { context.mailNoticeDataStore.edit { it.remove(prefsKey) } }
    }

    /** Force a buffered change to disk now (e.g. on app stop). */
    suspend fun flushNow() {
        flushJob?.cancel()
        // Cleared first: [flush] returns early when nothing is owed, and a stale failure from an
        // earlier write would otherwise be reported against a write no longer outstanding.
        lastWrite = null
        flush()
        lastWrite?.getOrThrow()
    }

    private suspend fun ensureLoaded(): Stored = mutex.withLock {
        held ?: withContext(Dispatchers.IO) {
            val raw = context.mailNoticeDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?: Stored()
            loaded.also { held = it }
        }
    }

    /** Trailing throttle: one write per window, never deferred indefinitely. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { held } ?: return
        lastWrite = withContext(Dispatchers.IO) {
            runCatching {
                context.mailNoticeDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
            }
        }
    }

    private companion object {
        /**
         * ⚠️ Longer than the two seconds every other store uses, and for a reason particular to this
         * one: a mail sync delivering five messages posts six notifications in a second or so, and
         * each one triggers a recompute. A short window would write the same growing snapshot six
         * times over. Nothing is lost by waiting — the listener recomputes from the shade whenever
         * it reconnects, so a snapshot missed by a killed process is rebuilt rather than gone.
         */
        const val FLUSH_DELAY_MS = 5_000L
    }
}
