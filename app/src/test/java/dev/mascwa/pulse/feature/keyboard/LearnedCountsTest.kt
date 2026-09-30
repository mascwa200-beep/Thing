package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.LearnedCounts.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedCountsTest {

    @Test
    fun `a word typed once is not yet a word, and twice is`() {
        val once = LearnedCounts.saw(emptyMap(), "Kubernetes", 1L)
        assertTrue(LearnedCounts.known(once).isEmpty())
        val twice = LearnedCounts.saw(once, "kubernetes", 2L)
        assertEquals(listOf("kubernetes"), LearnedCounts.known(twice))
    }

    @Test
    fun `a word kept on purpose is a word at once`() {
        assertEquals(listOf("teh"), LearnedCounts.known(LearnedCounts.accept(emptyMap(), "teh", 1L)))
    }

    @Test
    fun `one entry per word, whatever its capitals, with the capitals last typed`() {
        val m = LearnedCounts.saw(LearnedCounts.saw(emptyMap(), "Zork", 1L), "zork", 2L)
        assertEquals(1, m.size)
        assertEquals(Entry("zork", 2, 2L), m.values.single())
    }

    @Test
    fun `only words are kept`() {
        for (w in listOf("a", "x1y", "hi!", "'''", "", "a".repeat(33))) {
            assertTrue("'$w' was kept", LearnedCounts.saw(emptyMap(), w, 1L).isEmpty())
            assertTrue("'$w' was kept", LearnedCounts.accept(emptyMap(), w, 1L).isEmpty())
        }
        assertFalse(LearnedCounts.saw(emptyMap(), "don't", 1L).isEmpty())
    }

    @Test
    fun `past the cap the least used word goes, the oldest first among equals`() {
        // The fillers are used often and long ago; "old" and "new" once each, recently. How often wins
        // over how recently, so a filler typed before either of them must still be kept.
        var m = emptyMap<String, Entry>()
        for (i in 0 until LearnedCounts.CAP) {
            val w = "w" + ('a' + i % 26) + ('a' + (i / 26) % 26) + ('a' + i / 676)
            m = m + (Dictionary.key(w) to Entry(w, 3, i.toLong()))
        }
        // Two fillers out, two words in: exactly at the cap, so one more word must push exactly one out.
        // "new" goes in before "old", so a rule that ignores the time would throw out the wrong one.
        m = m - "waaa" - "wbaa" + ("new" to Entry("new", 1, 9_000L)) + ("old" to Entry("old", 1, 5_000L))
        assertEquals(LearnedCounts.CAP, m.size)
        val after = LearnedCounts.saw(m, "fresh", 10_000L)
        assertEquals(LearnedCounts.CAP, after.size)
        assertFalse("old" in after)
        assertTrue("new" in after)
        assertTrue("fresh" in after)
        assertTrue("wcaa" in after) // the oldest filler, kept because it is used
    }

    @Test
    fun `what is kept reads back as it was`() {
        val m = LearnedCounts.accept(LearnedCounts.saw(emptyMap(), "Zork", 7L), "don't", 8L)
        assertEquals(m, LearnedCounts.decode(LearnedCounts.encode(m)))
    }

    @Test
    fun `a line that does not read is dropped rather than trusted`() {
        val m = LearnedCounts.decode("zork\t2\t9\nbad line\nx1y\t4\t1\nfoo\t0\t1\nbar\tx\t1\n")
        assertEquals(setOf("zork"), m.keys)
    }
}
