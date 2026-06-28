package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcedureLibraryTest {

    private val seq = listOf("web_search", "download", "finding")

    @Test
    fun keywordsDropStopwordsAndDedupe() {
        val kw = ProcedureLibrary.keywords("Please research the latest research on solar storms for me")
        assertTrue("research" in kw)
        assertTrue("solar" in kw)
        assertTrue("storms" in kw)
        assertEquals("no stopwords", kw, kw.filter { it !in setOf("the", "for", "please") })
        assertEquals("deduped", kw.distinct(), kw)
    }

    @Test
    fun singleStepIsNotAProcedure() {
        val after = ProcedureLibrary.learn(emptyList(), "summarize my notes", listOf("notes"), true, 1L)
        assertTrue(after.isEmpty())
    }

    @Test
    fun failureDoesNotSeedANewProcedure() {
        val after = ProcedureLibrary.learn(emptyList(), "research solar storms", seq, success = false, nowMs = 1L)
        assertTrue(after.isEmpty())
    }

    @Test
    fun learnsThenBecomesRecallableAfterPractice() {
        var ps = ProcedureLibrary.learn(emptyList(), "research solar storms", seq, true, 1L)
        assertEquals(1, ps.size)
        // One observation isn't practiced yet → not recalled.
        assertNull(ProcedureLibrary.recall(ps, "research solar storms"))
        // A second matching success crosses the practice threshold.
        ps = ProcedureLibrary.learn(ps, "research geomagnetic solar storms", seq, true, 2L)
        assertEquals("reinforced, not duplicated", 1, ps.size)
        assertEquals(2, ps.first().timesApplied)
        val hit = ProcedureLibrary.recall(ps, "can you research the solar storms situation")
        assertNotNull(hit)
        assertEquals(seq, hit!!.steps)
    }

    @Test
    fun lowReliabilityIsNotRecalled() {
        // applied 3x, succeeded once → reliability 0.33 < 0.6 → not recalled even though practiced.
        var ps = ProcedureLibrary.learn(emptyList(), "deploy the build", seq, true, 1L)
        ps = ProcedureLibrary.learn(ps, "deploy the build", seq, false, 2L)
        ps = ProcedureLibrary.learn(ps, "deploy the build", seq, false, 3L)
        assertTrue(ps.first().practiced())
        assertTrue(ps.first().reliability < 0.6)
        assertNull(ProcedureLibrary.recall(ps, "deploy the build"))
    }

    @Test
    fun unrelatedRequestDoesNotMatch() {
        var ps = ProcedureLibrary.learn(emptyList(), "research solar storms", seq, true, 1L)
        ps = ProcedureLibrary.learn(ps, "research solar storms", seq, true, 2L)
        assertNull(ProcedureLibrary.recall(ps, "play some jazz music on the radio"))
    }

    @Test
    fun digestListsTrustworthyProceduresOnly() {
        var ps = ProcedureLibrary.learn(emptyList(), "research solar storms", seq, true, 1L)
        ps = ProcedureLibrary.learn(ps, "research solar storms", seq, true, 2L)
        // A second, not-yet-practiced procedure must be excluded from the digest.
        ps = ProcedureLibrary.learn(ps, "book a flight to tokyo", listOf("calendar", "maps"), true, 3L)
        val digest = ProcedureLibrary.digest(ps)
        assertTrue(digest.contains("web_search → download → finding"))
        assertTrue("not-yet-practiced excluded", !digest.contains("calendar → maps"))
    }

    @Test
    fun cappedKeepsMostReliable() {
        val now = 100L
        val many = (1..10).map {
            Procedure("p$it", listOf("kw$it"), seq, timesApplied = 5, timesSucceeded = it / 2, createdMs = now, lastUsedMs = now)
        }
        val kept = ProcedureLibrary.capped(many, cap = 3)
        assertEquals(3, kept.size)
        // The kept ones are the highest-reliability.
        assertTrue(kept.all { p -> p.reliability >= many.sortedByDescending { it.reliability }[2].reliability })
    }
}
