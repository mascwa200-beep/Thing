package dev.mascwa.pulse.data.blackbox

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.AuditEntry
import dev.mascwa.pulse.core.telemetry.AuditEventType
import dev.mascwa.pulse.core.telemetry.HashChain
import dev.mascwa.pulse.core.telemetry.LedgerSignature
import dev.mascwa.pulse.core.telemetry.LedgerSigner
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
    private val signer: LedgerSigner? = null,
    private val tsa: TsaClient? = null,
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
    private data class Stored(
        val entries: List<StoredEntry> = emptyList(),
        val headSig: String? = null,        // base64 ECDSA signature over the persisted head hash
        val publicKeySpki: String? = null,  // base64 X.509 SPKI of the signing key
        val anchorTokenB64: String? = null, // base64 RFC-3161 timeStampToken anchoring a head
        val anchorGenTimeMs: Long? = null,  // the TSA's asserted time for that anchor
        val anchorHeadHash: String? = null, // which head hash the anchor covers
    )

    private val prefsKey = stringPreferencesKey("ledger_blob")
    private val mutex = Mutex()
    private var chain: HashChain? = null

    /** A stored blob existed but couldn't be read — refuse to flush so we never clobber the evidence. */
    private var corrupt = false

    // The head signature as it was last persisted — checked by [headSignatureValid] (proof the on-disk
    // chain was sealed by this device's key). Captured at load; the live head is re-signed on each flush.
    private var loadedHeadHash: String? = null
    private var loadedHeadSig: ByteArray? = null
    private var loadedSpki: ByteArray? = null

    // The latest RFC-3161 anchor (independent proof-of-time for a head). Persisted + surfaced for the UI.
    private var anchorTokenB64: String? = null
    private var anchorGenTimeMs: Long? = null
    private var anchorHeadHash: String? = null

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
                    HashChain(parsed.entries.map { it.domain() }).also { c ->
                        loadedHeadHash = c.headHash
                        loadedHeadSig = parsed.headSig?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                        loadedSpki = parsed.publicKeySpki?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                        anchorTokenB64 = parsed.anchorTokenB64
                        anchorGenTimeMs = parsed.anchorGenTimeMs
                        anchorHeadHash = parsed.anchorHeadHash
                    }
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

    /**
     * Whether the persisted head signature verifies against the persisted key — proof the chain on disk
     * was sealed by this device's secure-element key. `null` when nothing is signed yet (empty ledger, or
     * signing unavailable on this device); the [verify] chain-integrity check stands on its own regardless.
     */
    suspend fun headSignatureValid(): Boolean? {
        ensureLoaded()
        val head = loadedHeadHash ?: return null
        val sig = loadedHeadSig ?: return null
        val spki = loadedSpki ?: return null
        return LedgerSignature.verify(head, sig, spki)
    }

    /**
     * Best-effort: anchor the current chain head to a trusted timestamp authority (RFC-3161) and persist
     * the token + asserted time. Independent proof the chain existed at/before the TSA's clock. Opt-in and
     * fully defensive — returns false (no-op) when no [TsaClient] is configured, the chain is empty, or the
     * TSA can't be reached. Network-bound; call sparingly (a UI control / periodic worker), not per-record.
     */
    suspend fun anchorHead(): Boolean {
        val client = tsa ?: return false
        ensureLoaded()
        val head = mutex.withLock { chain?.headHash } ?: return false
        if (head == HashChain.GENESIS_HASH) return false // nothing recorded yet
        val token = client.stamp(head.toByteArray(Charsets.US_ASCII)) ?: return false
        val tokenB64 = token.token?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: return false
        mutex.withLock {
            anchorTokenB64 = tokenB64
            anchorGenTimeMs = token.genTimeMs
            anchorHeadHash = head
        }
        flushNow() // persist the anchor immediately
        return true
    }

    /** The TSA-asserted time (epoch millis) of the latest anchor, or null if never anchored. */
    suspend fun anchorTimeMs(): Long? {
        ensureLoaded()
        return anchorGenTimeMs
    }

    /** The head hash the latest anchor covers (so the UI can tell if it's still current), or null. */
    suspend fun anchoredHead(): String? {
        ensureLoaded()
        return anchorHeadHash
    }

    /** Wipe the ledger (user-initiated). Cancels any buffered flush and clears the corrupt guard. */
    suspend fun clear() {
        flushJobCancel()
        mutex.withLock {
            chain = HashChain()
            corrupt = false
            loadedHeadHash = null
            loadedHeadSig = null
            loadedSpki = null
            anchorTokenB64 = null
            anchorGenTimeMs = null
            anchorHeadHash = null
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

    private class FlushSnap(
        val head: String,
        val entries: List<StoredEntry>,
        val anchorTokenB64: String?,
        val anchorGenTimeMs: Long?,
        val anchorHeadHash: String?,
    )

    private suspend fun flush() {
        if (corrupt) return // never overwrite an undecodable blob
        // Snapshot head + entries + anchor under the lock; sign outside it (Keystore I/O is comparatively slow).
        val snap = mutex.withLock {
            chain?.let { c ->
                FlushSnap(c.headHash, c.entries().map { it.stored() }, anchorTokenB64, anchorGenTimeMs, anchorHeadHash)
            }
        } ?: return
        val head = snap.head
        val sig = signer?.sign(LedgerSignature.headBytes(head))
        val spki = sig?.let { signer?.publicKeySpki() } // only emit a key when we actually produced a signature
        val snapshot = Stored(
            entries = snap.entries,
            headSig = sig?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            publicKeySpki = spki?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            anchorTokenB64 = snap.anchorTokenB64,
            anchorGenTimeMs = snap.anchorGenTimeMs,
            anchorHeadHash = snap.anchorHeadHash,
        )
        runCatching {
            val plain = json.encodeToString(Stored.serializer(), snapshot)
            val blob = SecretCrypto.encrypt(plain) ?: plain // plaintext fallback if no secure element
            context.blackboxDataStore.edit { it[prefsKey] = blob }
            // Track what's now sealed on disk so headSignatureValid() reflects the latest flush this session.
            loadedHeadHash = head
            loadedHeadSig = sig
            loadedSpki = spki
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
