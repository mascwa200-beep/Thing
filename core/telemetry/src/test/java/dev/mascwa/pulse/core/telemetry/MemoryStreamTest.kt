package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStreamTest {

    private fun mem(
        id: Long,
        text: String = "memory $id",
        kind: MemoryKind = MemoryKind.OBSERVATION,
        importance: Int = 5,
        created: Long = 0L,
        accessed: Long = 0L,
        embedding: List<Float> = listOf(1f, 0f),
    ) = Memory(id, text, kind, importance, created, accessed, embedding)

    // ---- cosine ----

    @Test
    fun cosineIdenticalIsOne() {
        assertEquals(1.0, MemoryStream.cosine(listOf(1f, 2f, 3f), listOf(1f, 2f, 3f)), 1e-9)
    }

    @Test
    fun cosineOrthogonalIsZero() {
        assertEquals(0.0, MemoryStream.cosine(listOf(1f, 0f), listOf(0f, 1f)), 1e-9)
    }

    @Test
    fun cosineOppositeIsMinusOne() {
        assertEquals(-1.0, MemoryStream.cosine(listOf(1f, 0f), listOf(-1f, 0f)), 1e-9)
    }

    @Test
    fun cosineHandlesEmptyMismatchAndZero() {
        assertEquals(0.0, MemoryStream.cosine(emptyList(), listOf(1f)), 1e-9)
        assertEquals(0.0, MemoryStream.cosine(listOf(1f, 2f), listOf(1f)), 1e-9)
        assertEquals(0.0, MemoryStream.cosine(listOf(0f, 0f), listOf(1f, 1f)), 1e-9)
    }

    // ---- recency ----

    @Test
    fun recencyDecayHalvesEachHalfLife() {
        assertEquals(1.0, MemoryStream.recencyDecay(0, 1000), 1e-9)
        assertEquals(0.5, MemoryStream.recencyDecay(1000, 1000), 1e-9)
        assertEquals(0.25, MemoryStream.recencyDecay(2000, 1000), 1e-9)
    }

    @Test
    fun recencyDecayClampsNegativeAndZeroHalfLife() {
        assertEquals(1.0, MemoryStream.recencyDecay(-500, 1000), 1e-9)
        assertEquals(1.0, MemoryStream.recencyDecay(0, 0), 1e-9)
        assertEquals(0.0, MemoryStream.recencyDecay(5, 0), 1e-9)
    }

    @Test
    fun recencyDecayIsMonotonicallyDecreasing() {
        val a = MemoryStream.recencyDecay(1_000, 10_000)
        val b = MemoryStream.recencyDecay(5_000, 10_000)
        assertTrue(a > b)
    }

    // ---- retrieve ----

    @Test
    fun retrieveRanksByRecencyImportanceRelevance() {
        val now = 100_000L
        val hl = 1_000L
        val query = listOf(1f, 0f)
        val m1 = mem(1, accessed = now, embedding = listOf(1f, 0f))         // recent + relevant
        val m2 = mem(2, accessed = now, embedding = listOf(0f, 1f))         // recent + irrelevant
        val m3 = mem(3, accessed = now - 10_000, embedding = listOf(1f, 0f)) // stale + relevant
        val out = MemoryStream.retrieve(listOf(m1, m2, m3), query, now, topK = 3, halfLifeMs = hl)
        assertEquals(3, out.size)
        assertEquals(1L, out.first().memory.id) // wins on all three components
        // m2 (recent) edges out m3 (stale) on the recency tiebreak at equal score
        assertEquals(2L, out[1].memory.id)
    }

    @Test
    fun retrieveRespectsTopKAndEmpty() {
        val now = 10L
        val out = MemoryStream.retrieve(
            listOf(mem(1), mem(2), mem(3)), listOf(1f, 0f), now, topK = 2,
        )
        assertEquals(2, out.size)
        assertTrue(MemoryStream.retrieve(emptyList(), listOf(1f, 0f), now).isEmpty())
        assertTrue(MemoryStream.retrieve(listOf(mem(1)), listOf(1f, 0f), now, topK = 0).isEmpty())
    }

    @Test
    fun retrieveWithRelevanceOnlyWeightingFavorsSimilarVectors() {
        val now = 0L
        val relevant = mem(1, accessed = now, embedding = listOf(1f, 0f))
        val irrelevant = mem(2, accessed = now, embedding = listOf(0f, 1f))
        val out = MemoryStream.retrieve(
            listOf(irrelevant, relevant), listOf(1f, 0f), now, topK = 1,
            weights = RetrievalWeights(recency = 0.0, importance = 0.0, relevance = 1.0),
        )
        assertEquals(1L, out.single().memory.id)
    }

    @Test
    fun retrieveWithEmptyQueryRanksByRecencyAndImportance() {
        val now = 100L
        val recentLow = mem(1, importance = 3, accessed = 100)
        val staleHigh = mem(2, importance = 9, accessed = 0)
        val out = MemoryStream.retrieve(listOf(recentLow, staleHigh), emptyList(), now, topK = 2, halfLifeMs = 50)
        // recentLow: recencyNorm 1 + importanceNorm 0 = 1 ; staleHigh: recencyNorm ~0 + importanceNorm 1 = 1
        // tie → recency tiebreak picks the recently accessed one first
        assertEquals(1L, out.first().memory.id)
    }

    @Test
    fun touchBumpsAccessTimeForwardOnly() {
        val m = mem(1, accessed = 100)
        assertEquals(200L, MemoryStream.touch(m, 200).lastAccessedMs)
        assertEquals(100L, MemoryStream.touch(m, 50).lastAccessedMs) // never moves backward
    }

    // ---- importance ----

    @Test
    fun estimateImportanceRatesAffectiveAboveMundane() {
        val mundane = MemoryStream.estimateImportance("the weather is mild today")
        val significant = MemoryStream.estimateImportance(
            "I have an important deadline tomorrow and I'm worried I'll fail",
        )
        assertTrue(significant > mundane)
        assertTrue(significant >= 7)
        assertTrue(mundane <= 3)
    }

    @Test
    fun estimateImportanceBoundsAndQuestions() {
        assertEquals(1, MemoryStream.estimateImportance(""))
        assertTrue(MemoryStream.estimateImportance("what time is it?") <= 2)
        val v = MemoryStream.estimateImportance("I love this so much it is important important important")
        assertTrue(v in 1..10)
    }

    // ---- reflection ----

    @Test
    fun recentImportanceSumCountsRecentObservationsOnly() {
        val mems = listOf(
            mem(1, importance = 10, created = 100, kind = MemoryKind.OBSERVATION),
            mem(2, importance = 8, created = 50, kind = MemoryKind.OBSERVATION),   // too old
            mem(3, importance = 9, created = 120, kind = MemoryKind.REFLECTION),   // not an observation
            mem(4, importance = 7, created = 200, kind = MemoryKind.OBSERVATION),
        )
        assertEquals(17, MemoryStream.recentImportanceSum(mems, sinceMs = 100))
    }

    @Test
    fun shouldReflectAtThreshold() {
        assertFalse(MemoryStream.shouldReflect(49, threshold = 50))
        assertTrue(MemoryStream.shouldReflect(50, threshold = 50))
    }

    @Test
    fun reflectionSeedsPicksSalientRecentObservations() {
        val mems = listOf(
            mem(1, importance = 3, created = 10),
            mem(2, importance = 9, created = 20),
            mem(3, importance = 9, created = 30),
            mem(4, importance = 5, created = 40, kind = MemoryKind.REFLECTION),
        )
        val seeds = MemoryStream.reflectionSeeds(mems, count = 2)
        assertEquals(listOf(3L, 2L), seeds.map { it.id }) // top importance, recent first; reflection excluded
    }

    // ---- capacity ----

    @Test
    fun cappedEvictsLeastImportantPreservingOrder() {
        val mems = listOf(
            mem(1, importance = 1),
            mem(2, importance = 5),
            mem(3, importance = 9),
        )
        val out = MemoryStream.capped(mems, cap = 2)
        assertEquals(listOf(2L, 3L), out.map { it.id })
        assertEquals(emptyList<Long>(), MemoryStream.capped(mems, cap = 0).map { it.id })
        assertEquals(3, MemoryStream.capped(mems, cap = 5).size)
    }
}
