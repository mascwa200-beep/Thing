package dev.mascwa.pulse.data.blackbox

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.AuditEntry
import dev.mascwa.pulse.core.telemetry.AuditEventType
import dev.mascwa.pulse.core.telemetry.HashChain
import dev.mascwa.pulse.core.telemetry.VerificationResult
import dev.mascwa.pulse.security.SecretCrypto
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

private val Context.blackboxDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_blackbox")

/**
 * On-device persistence + recording for the tamper-evident "blackbox" audit ledger
 * ([dev.mascwa.pulse.core.telemetry.HashChain]). Mirrors [dev.mascwa.pulse.data.profile.ProfileStore]:
 * an in-memory chain (authoritative) + a [Mutex] + debounced flush, with flush-on-stop and
 * clear-cancels-flush.
 *
 * The persisted blob is **encrypted at rest** via [SecretCrypto] (StrongBox/TEE-bound AES-GCM) — the
 * chain already makes the log tamper-*evident*; encryption adds confidentiality of the (operational)
 * details. The hash chain is what proves integrity: every entry carries its own hash over its content
 * plus the prior hash, so [verify] re-checks the whole chain on demand and after a disk round-trip.
 *
 * Credential hygiene (load-bearing): callers must scrub secrets out of [record]'s `detail` before
 * recording — the ledger faithfully records whatever it is given, exactly like a real flight recorder.
 *
 * Undecodable-blob safety (the SettingsRepository lesson): if a stored blob exists but can't be
 * decrypted/parsed, the store keeps the in-memory chain empty AND refuses to flush over it, so a
 * transient decrypt failure or a key change can't silently erase prior evidence.
 */
class AuditLedgerStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredEntry(
        val seq: Long,
        val timeMs: Long,
        val type: String,
        val label: String,
        val detail: String,
        val prevHash: String,
        val hash: String,
    )

    @Serializable
    private data class Stored(val entries: List<StoredEntry> = emptyList())

    private val prefsKey = stringPreferencesKey("ledger_blob")
    private val mutex = Mutex()
    private var chain: HashChain? = null

    /** A stored blob existed but couldn't be read — refuse to flush so we never clobber the evidence. */
    private var corrupt = false

    private val _entriesFlow = MutableStateFlow<List<AuditEntry>>(emptyList())
    /** Live, newest-handling-left-to-the-UI view of the chain (for a future Memory/Settings surface). */
    val entriesFlow: StateFlow<List<AuditEntry>> = _entriesFlow.asStateFlow()

    private fun AuditEntry.stored() = StoredEntry(seq, timeMs, type.name, label, detail, prevHash, hash)

    private fun StoredEntry.domain() = AuditEntry(
        seq = seq,
        timeMs = timeMs,
        type = runCatching { AuditEventType.valueOf(type) }.getOrDefault(AuditEventType.NOTE),
        label = label,
        detail = detail,
        prevHash = prevHash,
        hash = hash,
    )

    private suspend fun ensureLoaded(): HashChain = mutex.withLock {
        chain ?: run {
            val raw = context.blackboxDataStore.data.first()[prefsKey]
            val loaded = if (raw == null) {
                HashChain() // no prior ledger — start fresh
            } else {
                val plain = if (SecretCrypto.isEncrypted(raw)) SecretCrypto.decrypt(raw) else raw
                val parsed = plain?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                if (parsed == null) {
                    corrupt = true // blob present but unreadable → preserve, don't overwrite
                    HashChain()
                } else {
                    HashChain(parsed.entries.map { it.domain() })
                }
            }
            loaded.also { chain = it; _entriesFlow.value = it.entries() }
        }
    }

    /**
     * Record an audited event, linking it to the head of the chain. Fire-and-forget (serialized through
     * [mutex]); [detail] must already be free of secrets.
     */
    fun record(type: AuditEventType, label: String, detail: String = "") {
        if (label.isBlank()) return
        scope.launch {
            val c = ensureLoaded()
            mutex.withLock {
                c.append(System.currentTimeMillis(), type, label, detail)
                _entriesFlow.value = c.entries()
            }
            scheduleFlush()
        }
    }

    /** Current chain entries (oldest first). */
    suspend fun entries(): List<AuditEntry> = ensureLoaded().let { mutex.withLock { it.entries() } }

    /** Re-verify the whole chain (incl. the on-disk round-trip): is it intact, and if not, where did it break? */
    suspend fun verify(): VerificationResult = ensureLoaded().let { mutex.withLock { it.verify() } }

    /** Wipe the ledger (user-initiated). Cancels any buffered flush and clears the corrupt guard. */
    suspend fun clear() {
        flushJobCancel()
        mutex.withLock {
            chain = HashChain()
            corrupt = false
            _entriesFlow.value = emptyList()
        }
        runCatching { context.blackboxDataStore.edit { it.remove(prefsKey) } }
    }

    /** Force buffered changes to disk now (e.g. on app stop). */
    suspend fun flushNow() {
        flushJobCancel()
        flush()
    }

    private var flushJob: Job? = null

    private fun flushJobCancel() {
        flushJob?.cancel()
    }

    /** Trailing throttle (see ProfileStore/UsageRepository): one flush per window. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        if (corrupt) return // never overwrite an undecodable blob
        val snapshot = mutex.withLock { chain?.let { c -> Stored(c.entries().map { it.stored() }) } } ?: return
        runCatching {
            val plain = json.encodeToString(Stored.serializer(), snapshot)
            val blob = SecretCrypto.encrypt(plain) ?: plain // plaintext fallback if no secure element
            context.blackboxDataStore.edit { it[prefsKey] = blob }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
