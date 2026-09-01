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

    /**
     * An app that has been seen to notify, and whether it has ever claimed to be mail.
     *
     * ⚠️ A package name and a boolean. Nothing about what it notified, or when, or how often — the
     * picker needs a list to offer and nothing more, and anything richer would be a log of when the
     * user's apps talk to them.
     */
    @Serializable
    private data class StoredSeen(val pkg: String, val everEmail: Boolean = false)

    @Serializable
    private data class Stored(
        val apps: List<StoredApp> = emptyList(),
        /** When the listener last recomputed. Kept for the diagnostics surface, not for expiry. */
        val atMs: Long = 0,
        /** Everything that has notified since access was granted, for the picker. Defaulted. */
        val seen: List<StoredSeen> = emptyList(),
    )

    /** One candidate for the picker: has it notified, and did it call itself mail? */
    data class SeenApp(val pkg: String, val everEmail: Boolean)

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
     * What the picker can offer, newest observation last.
     *
     * ⚠️ Not gated on the grant, unlike [read]. This is a list of app names the user is about to be
     * shown so they can choose from it, not a reading taken from the shade — and a picker that
     * emptied itself the moment access was revoked would give somebody nothing to look at on the
     * screen that explains how to grant it again.
     */
    suspend fun seen(): List<SeenApp> = ensureLoaded().seen.map { SeenApp(it.pkg, it.everEmail) }

    /**
     * Record that a package notified, and whether it called itself mail.
     *
     * ⚠️ `everEmail` only ever goes from false to true. An app that sets `CATEGORY_EMAIL` on some
     * notifications and not others — a sync summary against a per-message alert, say — would
     * otherwise flicker in and out of the picker's suggested half depending on what happened to be
     * on the shade when it was last opened.
     *
     * ⚠️ **Nothing is written for an app already recorded**, which is what keeps this off the hot
     * path: the common case is a notification from a package seen a hundred times before, and it
     * costs a list scan and no write at all.
     *
     * ⚠️ Capped, and the eviction policy is therefore oldest-DISCOVERED rather than
     * least-recently-seen — an app is only ever appended on first sight, and re-ordering it on
     * every notification is precisely the write this avoids. The cap is generous enough that it
     * should never bind on a real phone; it exists so the list cannot grow with the install list
     * for ever, not to make a judgement about which apps matter. Being evicted only means the app
     * is missing from the picker — an app already ticked carries on counting.
     */
    fun noticeSeen(pkg: String, isEmail: Boolean) = noticeSeenAll(listOf(SeenApp(pkg, isEmail)))

    /**
     * The same, for a whole shade at once.
     *
     * ⚠️ One lock and one scheduled write for the lot. The listener records every notification it
     * can see the moment it connects, so the picker has something to offer immediately rather than
     * being empty until the next mail arrives — and doing that one package at a time would launch a
     * coroutine per notification on a shade that can hold fifty.
     */
    fun noticeSeenAll(apps: List<SeenApp>) {
        val wanted = apps.filter { it.pkg.isNotBlank() }
        if (wanted.isEmpty()) return
        scope.launch {
            ensureLoaded()
            val changed = mutex.withLock {
                val current = held ?: Stored()
                val byPkg = LinkedHashMap<String, Boolean>()
                current.seen.forEach { byPkg[it.pkg] = it.everEmail }
                var any = false
                for (app in wanted) {
                    val known = byPkg[app.pkg]
                    // Nothing to write for a package already recorded, unless this is the first
                    // time it has called itself mail. That is what keeps this off the hot path.
                    if (known != null && (known || !app.everEmail)) continue
                    byPkg[app.pkg] = app.everEmail || (known == true)
                    any = true
                }
                if (any) {
                    held = current.copy(
                        seen = byPkg.entries.map { StoredSeen(it.key, it.value) }.takeLast(MAX_SEEN),
                    )
                }
                any
            }
            if (changed) scheduleFlush()
        }
    }

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

        /** How many notifying packages the picker will remember. See [noticeSeen]. */
        const val MAX_SEEN = 300
    }
}
