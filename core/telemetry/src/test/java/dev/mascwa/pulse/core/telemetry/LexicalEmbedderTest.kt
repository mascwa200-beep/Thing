package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicalEmbedderTest {

    @Test
    fun deterministic() {
        assertEquals(
            LexicalEmbedder.embed("the quick brown fox"),
            LexicalEmbedder.embed("the quick brown fox"),
        )
    }

    @Test
    fun identicalTextCosineIsOne() {
        val v = LexicalEmbedder.embed("buy milk and eggs on the way home")
        assertEquals(1.0, MemoryStream.cosine(v, v), 1e-6)
    }

    @Test
    fun lexicalOverlapBeatsDisjoint() {
        val query = LexicalEmbedder.embed("the quick brown fox jumps")
        val similar = LexicalEmbedder.embed("the quick brown dog jumps")
        val disjoint = LexicalEmbedder.embed("completely different unrelated topic entirely")
        val similarCos = MemoryStream.cosine(query, similar)
        val disjointCos = MemoryStream.cosine(query, disjoint)
        assertTrue("similar=$similarCos should exceed disjoint=$disjointCos", similarCos > disjointCos)
        assertTrue("overlap should be clearly positive", similarCos > 0.3)
    }

    @Test
    fun blankOrPunctuationOnlyIsZeroVector() {
        val v = LexicalEmbedder.embed("   ...!!!   ")
        assertEquals(LexicalEmbedder.DIM, v.size)
        assertTrue(v.all { it == 0f })
        // A zero vector yields zero cosine against anything (no false relevance).
        assertEquals(0.0, MemoryStream.cosine(v, LexicalEmbedder.embed("real text")), 1e-9)
    }

    @Test
    fun producesUnitVectorOfExpectedDimension() {
        val v = LexicalEmbedder.embed("hello world this is a durable memory about a project")
        assertEquals(LexicalEmbedder.DIM, v.size)
        val sumSq = v.sumOf { (it.toDouble() * it.toDouble()) }
        assertEquals(1.0, sumSq, 1e-4)
    }

    @Test
    fun honorsCustomDimension() {
        assertEquals(64, LexicalEmbedder.embed("some text", dim = 64).size)
        assertTrue(LexicalEmbedder.embed("some text", dim = 0).isEmpty())
    }
}
