package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditLedgerTest {

    private fun sampleChain(): HashChain = HashChain().apply {
        append(1_000L, AuditEventType.SYSTEM, "boot", "cold start")
        append(2_000L, AuditEventType.SECURITY, "attest", "strongbox ok")
        append(3_000L, AuditEventType.SELF_CODE, "apply", "PR #42")
    }

    @Test
    fun emptyChainIsValidAndHeadIsGenesis() {
        val chain = HashChain()
        assertEquals(0, chain.size)
        assertEquals(HashChain.GENESIS_HASH, chain.headHash)
        assertTrue(chain.verify().valid)
    }

    @Test
    fun appendLinksEntriesAndAdvancesHead() {
        val chain = HashChain()
        val a = chain.append(1_000L, AuditEventType.SYSTEM, "boot")
        val b = chain.append(2_000L, AuditEventType.NOTE, "hello")

        assertEquals(0L, a.seq)
        assertEquals(1L, b.seq)
        assertEquals(HashChain.GENESIS_HASH, a.prevHash) // genesis links the first entry
        assertEquals(a.hash, b.prevHash)                 // each entry links to the prior hash
        assertEquals(b.hash, chain.headHash)             // head advances to the newest entry
        assertEquals(2, chain.size)
    }

    @Test
    fun fullChainVerifies() {
        assertTrue(sampleChain().verify().valid)
    }

    @Test
    fun alteringAnEntryBreaksTheChainAtThatSeq() {
        val entries = sampleChain().entries().toMutableList()
        // Tamper: change the detail of entry #1 but keep its (now stale) hash.
        entries[1] = entries[1].copy(detail = "tampered")
        val result = HashChain(entries).verify()

        assertFalse(result.valid)
        assertEquals(1L, result.brokenAtSeq)
        assertTrue(result.reason!!.contains("altered"))
    }

    @Test
    fun reorderingBreaksTheLink() {
        val original = sampleChain().entries()
        // Swap entries 1 and 2 (their seq fields go along, so seq stays contiguous but links break).
        val reordered = listOf(original[0], original[2], original[1])
        val result = HashChain(reordered).verify()

        assertFalse(result.valid)
        // Entry now at index 1 carries seq 2 → caught as a sequence mismatch first.
        assertEquals(2L, result.brokenAtSeq)
    }

    @Test
    fun deletingAMiddleEntryIsDetected() {
        val original = sampleChain().entries()
        val pruned = listOf(original[0], original[2]) // drop seq 1
        val result = HashChain(pruned).verify()

        assertFalse(result.valid)
        assertEquals(2L, result.brokenAtSeq) // the entry that now sits at index 1 has seq 2
    }

    @Test
    fun insertingAForgedEntryIsDetected() {
        val chain = sampleChain()
        val entries = chain.entries().toMutableList()
        // Forge a fully self-consistent entry (correct hash for its own content) and splice it in,
        // without re-linking the entries after it — the next real entry's prevHash no longer matches.
        val forgedPrev = entries[0].hash
        val forgedHash = HashChain.recompute(1L, 1_500L, AuditEventType.NOTE, "forged", "", forgedPrev)
        val forged = AuditEntry(1L, 1_500L, AuditEventType.NOTE, "forged", "", forgedPrev, forgedHash)
        entries.add(1, forged) // now two entries claim seq 1

        val result = HashChain(entries).verify()
        assertFalse(result.valid)
        // index 2 should hold seq 2, but the real entry that slid there still has seq 1 → mismatch.
        assertEquals(1L, result.brokenAtSeq)
    }

    @Test
    fun rehydratedUntamperedChainVerifies() {
        val persisted = sampleChain().entries()
        val reloaded = HashChain(persisted)
        assertTrue(reloaded.verify().valid)
        assertEquals(persisted.last().hash, reloaded.headHash)
    }

    @Test
    fun canonicalIsUnambiguousAcrossFieldBoundaries() {
        // Without length-prefixing, label="ab",detail="c" and label="a",detail="bc" could collide.
        // The encoding must keep them distinct → different hashes.
        val h1 = HashChain.recompute(0L, 0L, AuditEventType.NOTE, "ab", "c", HashChain.GENESIS_HASH)
        val h2 = HashChain.recompute(0L, 0L, AuditEventType.NOTE, "a", "bc", HashChain.GENESIS_HASH)
        assertNotEquals(h1, h2)
    }

    @Test
    fun recomputeIsDeterministicAndHashIsHex64() {
        val h1 = HashChain.recompute(5L, 9L, AuditEventType.SECURITY, "x", "y", HashChain.GENESIS_HASH)
        val h2 = HashChain.recompute(5L, 9L, AuditEventType.SECURITY, "x", "y", HashChain.GENESIS_HASH)
        assertEquals(h1, h2)
        assertEquals(64, h1.length)
        assertTrue(h1.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun differentTypeOrTimeChangesTheHash() {
        val base = HashChain.recompute(0L, 1L, AuditEventType.SYSTEM, "e", "", HashChain.GENESIS_HASH)
        val diffType = HashChain.recompute(0L, 1L, AuditEventType.NETWORK, "e", "", HashChain.GENESIS_HASH)
        val diffTime = HashChain.recompute(0L, 2L, AuditEventType.SYSTEM, "e", "", HashChain.GENESIS_HASH)
        assertNotEquals(base, diffType)
        assertNotEquals(base, diffTime)
    }

    @Test
    fun inMemoryStoreRoundTrips() {
        val store: LedgerStore = InMemoryLedgerStore()
        assertTrue(store.load().isEmpty())

        val entries = sampleChain().entries()
        store.persist(entries)
        val loaded = store.load()

        assertEquals(entries, loaded)
        assertTrue(HashChain(loaded).verify().valid) // survives a store round-trip intact
    }

    @Test
    fun storePersistIsDefensivelyCopied() {
        val mutable = sampleChain().entries().toMutableList()
        val store = InMemoryLedgerStore()
        store.persist(mutable)
        mutable.clear() // mutate the caller's list after persisting
        assertEquals(3, store.load().size) // the store kept its own copy
    }
}
