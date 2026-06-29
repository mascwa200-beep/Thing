package dev.mascwa.pulse.core.telemetry

import java.security.MessageDigest

/**
 * A tamper-evident "blackbox" audit ledger — the pure, CI-tested core.
 *
 * Every recorded event becomes a [AuditEntry] in a hash chain: each entry's [AuditEntry.hash] is the
 * SHA-256 of its own canonical content **plus the previous entry's hash**, so altering, inserting,
 * deleting or reordering any past entry breaks the chain from that point forward. [HashChain.verify]
 * detects the break and reports where. This is the same construction as a transparency log / Git's
 * commit graph: cheap to append, cheap to verify, impossible to silently edit.
 *
 * This file is deliberately free of Android types so CI gates it. The on-device layers that build on
 * it — Keystore-signing the head hash, an encrypted-file [LedgerStore], and RFC-3161 timestamp
 * anchoring for independent proof-of-time — live in the app module and are added as later slices.
 * Hashing uses [MessageDigest] (`SHA-256`), available identically on the JVM (unit tests) and Android.
 */

/** Coarse category of an audited event. The human-readable specifics ride in the entry's label/detail. */
enum class AuditEventType {
    /** App / system lifecycle: boot, update applied, config changed. */
    SYSTEM,

    /** Security posture: attestation result, device-policy toggle, gate decision. */
    SECURITY,

    /** Self-coding: a change proposed, applied (human-gated) or shipped. */
    SELF_CODE,

    /** Network actions: egress policy change, kill-switch, a privileged net command. */
    NETWORK,

    /** A diagnostic / debug-report upload. */
    DIAGNOSTIC,

    /** A user-approved action that crossed an approval gate. */
    USER_ACTION,

    /** A free-form annotation with no other category. */
    NOTE,
}

/**
 * One immutable link in the chain. [seq] is 0-based and contiguous; [prevHash] points at the previous
 * entry's [hash] (or [HashChain.GENESIS_HASH] for [seq] 0); [hash] covers all the other fields plus
 * [prevHash]. [detail] is caller-supplied — callers must scrub secrets before recording (the ledger
 * does not, by design, so it can faithfully record what it was given).
 */
data class AuditEntry(
    val seq: Long,
    val timeMs: Long,
    val type: AuditEventType,
    val label: String,
    val detail: String,
    val prevHash: String,
    val hash: String,
)

/** Outcome of verifying a chain. [brokenAtSeq]/[reason] are null when [valid]. */
data class VerificationResult(
    val valid: Boolean,
    val brokenAtSeq: Long? = null,
    val reason: String? = null,
) {
    companion object {
        val OK = VerificationResult(true)
    }
}

/**
 * Canonical, deterministic byte encoding of an entry's signable content. Length-prefixing every field
 * (`<len>:<value>|`) makes the encoding unambiguous — no two distinct field tuples can produce the same
 * bytes (so `"ab"+"c"` can't collide with `"a"+"bc"`). No JSON dependency; identical across runs and
 * platforms, which is what makes a recomputed hash reproducible.
 */
object Canonical {
    fun bytes(
        seq: Long,
        timeMs: Long,
        type: AuditEventType,
        label: String,
        detail: String,
        prevHash: String,
    ): ByteArray {
        val sb = StringBuilder()
        fun field(s: String) {
            sb.append(s.length).append(':').append(s).append('|')
        }
        field(seq.toString())
        field(timeMs.toString())
        field(type.name)
        field(label)
        field(detail)
        field(prevHash)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

/**
 * An append-only hash chain of [AuditEntry]. Construct empty or rehydrate from a persisted list (then
 * call [verify] to confirm it wasn't tampered with on disk). Not thread-safe — the on-device store
 * serializes access (the app's store mirrors the established Mutex pattern).
 */
class HashChain(initial: List<AuditEntry> = emptyList()) {

    private val entries = ArrayList<AuditEntry>(initial)

    val size: Int get() = entries.size

    /** The hash of the last entry, or [GENESIS_HASH] for an empty chain — the next entry's `prevHash`. */
    val headHash: String get() = entries.lastOrNull()?.hash ?: GENESIS_HASH

    fun entries(): List<AuditEntry> = entries.toList()

    /** Append a new event, linking it to the current head. Returns the entry that was added. */
    fun append(
        timeMs: Long,
        type: AuditEventType,
        label: String,
        detail: String = "",
    ): AuditEntry {
        val seq = entries.size.toLong()
        val prev = headHash
        val hash = recompute(seq, timeMs, type, label, detail, prev)
        val entry = AuditEntry(seq, timeMs, type, label, detail, prev, hash)
        entries.add(entry)
        return entry
    }

    /**
     * Walk the chain and confirm it is intact: sequence numbers are contiguous from 0, each entry links
     * to the previous one's hash (genesis for the first), and every stored hash recomputes from its own
     * content. Returns the first break found (lowest seq), or [VerificationResult.OK].
     */
    fun verify(): VerificationResult {
        var prev = GENESIS_HASH
        for ((i, e) in entries.withIndex()) {
            if (e.seq != i.toLong()) {
                return VerificationResult(false, e.seq, "sequence mismatch: expected $i, got ${e.seq}")
            }
            if (e.prevHash != prev) {
                return VerificationResult(false, e.seq, "broken link: prevHash does not match prior entry")
            }
            val expected = recompute(e.seq, e.timeMs, e.type, e.label, e.detail, e.prevHash)
            if (e.hash != expected) {
                return VerificationResult(false, e.seq, "hash mismatch: entry content was altered")
            }
            prev = e.hash
        }
        return VerificationResult.OK
    }

    companion object {
        /** prevHash of the first entry: 64 hex zeros (32 zero bytes). */
        const val GENESIS_HASH: String = "0000000000000000000000000000000000000000000000000000000000000000"

        private val HEX = "0123456789abcdef".toCharArray()

        /** The canonical SHA-256 (hex) for an entry's fields — used to both create and verify a hash. */
        fun recompute(
            seq: Long,
            timeMs: Long,
            type: AuditEventType,
            label: String,
            detail: String,
            prevHash: String,
        ): String = sha256Hex(Canonical.bytes(seq, timeMs, type, label, detail, prevHash))

        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }
    }
}

/**
 * Persistence boundary for the chain. The pure [InMemoryLedgerStore] backs tests and a no-persistence
 * fallback; the on-device encrypted-file implementation (later slice) implements the same two methods.
 */
interface LedgerStore {
    fun load(): List<AuditEntry>
    fun persist(entries: List<AuditEntry>)
}

/** A non-persistent [LedgerStore] holding the chain in memory. */
class InMemoryLedgerStore(initial: List<AuditEntry> = emptyList()) : LedgerStore {
    private var data: List<AuditEntry> = initial.toList()
    override fun load(): List<AuditEntry> = data
    override fun persist(entries: List<AuditEntry>) {
        data = entries.toList()
    }
}
