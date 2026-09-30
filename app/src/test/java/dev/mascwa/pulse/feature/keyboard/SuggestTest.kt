package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.Suggest.Candidate
import dev.mascwa.pulse.feature.keyboard.Suggest.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestTest {

    private val dict = Dictionary.of(
        listOf(
            "the" to 222, "ten" to 150, "then" to 180, "they" to 190, "tea" to 120,
            "hello" to 120, "help" to 150, "held" to 140, "don't" to 190, "font" to 100,
            "London" to 150, "keyboard" to 90, "receive" to 130,
        ),
    )
    private val eps = 1e-4f

    // ── the dictionary ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a word is found whatever its capitals`() {
        assertTrue(dict.contains("THE"))
        assertEquals(150, dict.frequency("london"))
        assertFalse(dict.contains("teh"))
        assertNull(dict.frequency("teh"))
    }

    @Test
    fun `a word spelled two ways is as common as its commonest spelling`() {
        // Sorted by (lowercase, word), "Will" comes before "will": the answer must not be whichever is last.
        val d = Dictionary.of(listOf("will" to 90, "Will" to 200))
        assertEquals(200, d.frequency("WILL"))
    }

    @Test
    fun `the words starting with something are one run`() {
        val r = dict.prefixRange("he")
        assertEquals(listOf("held", "hello", "help"), r.map { dict.word(it) })
        assertTrue(dict.prefixRange("zz").isEmpty())
    }

    @Test
    fun `entries in any order are sorted before they are searched`() {
        val d = Dictionary.of(listOf("zebra" to 1, "apple" to 2, "Mango" to 3))
        assertTrue(d.contains("apple"))
        assertTrue(d.contains("mango"))
        assertEquals(listOf("apple", "Mango", "zebra"), (0 until d.size).map { d.word(it) })
    }

    // ── the keyboard-aware distance ─────────────────────────────────────────────────────────

    @Test
    fun `neighbouring keys are side by side or diagonal, and nothing further`() {
        assertTrue(KeyNeighbours.adjacent('q', 'w'))
        assertTrue(KeyNeighbours.adjacent('q', 'a'))
        assertTrue(KeyNeighbours.adjacent('g', 't'))
        assertTrue(KeyNeighbours.adjacent('g', 'y'))
        assertTrue(KeyNeighbours.adjacent('a', 'z'))
        assertFalse(KeyNeighbours.adjacent('t', 'h'))
        assertFalse(KeyNeighbours.adjacent('q', 'p'))
        assertFalse(KeyNeighbours.adjacent('q', 'q'))
    }

    @Test
    fun `the slips thumbs make cost half a mistake, and an apostrophe a quarter`() {
        assertEquals(0.5f, Suggest.cost("teh", "the"), eps) // two letters swapped
        assertEquals(0.5f, Suggest.cost("helo", "hello"), eps) // a double letter missed
        assertEquals(0.5f, Suggest.cost("helllo", "hello"), eps) // and one typed twice
        assertEquals(0.5f, Suggest.cost("qat", "wat"), eps) // a neighbouring key
        assertEquals(1f, Suggest.cost("pat", "wat"), eps) // an unrelated one
        assertEquals(0.25f, Suggest.cost("dont", "don't"), eps)
    }

    @Test
    fun `a distance that cannot come in under the limit stops early`() {
        assertTrue(Suggest.cost("abcdefgh", "zzzzzzzz", limit = 1f) > 1f)
    }

    @Test
    fun `a swap is not given up on halfway through`() {
        // Half way through "teh" against "the" every column costs a whole mistake; the swap that makes
        // it half a mistake only arrives on the next row, reaching back two. Stopping there is wrong.
        assertEquals(0.5f, Suggest.cost("teh", "the", limit = 0.5f), eps)
    }

    // ── suggestions ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a slip is corrected to the word it was meant to be`() {
        assertEquals("the", Suggest.candidates("teh", dict).first().word)
        assertEquals(Kind.CORRECTION, Suggest.candidates("teh", dict).first().kind)
    }

    @Test
    fun `two letters swapped at the very start are still found`() {
        assertEquals("the", Suggest.candidates("hte", dict).first().word)
    }

    @Test
    fun `a first letter slipped onto its neighbour is still found`() {
        // r sits beside t: the words starting with r are not the only ones worth looking at.
        assertEquals("the", Suggest.candidates("rhe", dict).first().word)
    }

    @Test
    fun `the word itself comes first when it is one`() {
        val c = Suggest.candidates("the", dict)
        assertEquals("the", c.first().word)
        assertEquals(Kind.EXACT, c.first().kind)
        // Even when it is rare and a common word starts with it.
        val d = Dictionary.of(listOf("fo" to 20, "for" to 250))
        assertEquals("fo", Suggest.candidates("fo", d).first().word)
    }

    @Test
    fun `suggestions take the capitals they are being typed with`() {
        assertEquals("The", Suggest.candidates("Teh", dict).first().word)
        assertEquals("THE", Suggest.candidates("TEH", dict).first().word)
        // A name keeps its capital however it was typed.
        assertEquals("London", Suggest.candidates("london", dict).first().word)
        // One capital is the start of a capitalised word, not the start of shouting.
        assertEquals("The", Suggest.cased("the", "T"))
    }

    @Test
    fun `typing in lower case puts an acronym behind an ordinary word`() {
        val d = Dictionary.of(listOf("ABC" to 250, "abd" to 150))
        assertEquals("abd", Suggest.candidates("abx", d).first().word)
        // Typed with a capital, the acronym is not held back.
        assertEquals("ABC", Suggest.candidates("Abx", d).first().word)
    }

    @Test
    fun `I and its contractions are not held back as though they were names`() {
        val d = Dictionary.of(listOf("in" to 215, "I'm" to 170))
        assertEquals("I'm", Suggest.candidates("im", d).first().word)
    }

    @Test
    fun `a learned word is a word`() {
        assertTrue(Suggest.candidates("kubernetes", dict).isEmpty())
        val c = Suggest.candidates("kubernetes", dict, learned = listOf("Kubernetes"))
        assertEquals("Kubernetes", c.first().word)
        assertEquals(Kind.EXACT, c.first().kind)
    }

    @Test
    fun `nothing typed, nothing offered`() {
        assertTrue(Suggest.candidates("", dict).isEmpty())
    }

    // ── autocorrect ─────────────────────────────────────────────────────────────────────────

    private fun corr(word: String, cost: Float, score: Float) = Candidate(word, Kind.CORRECTION, cost, score)
    private val unknown: (String) -> Boolean = { false }

    @Test
    fun `a clear slip is corrected`() {
        assertEquals("the", Autocorrect.decide("teh", listOf(corr("the", 0.5f, 0.37f), corr("ten", 0.5f, 0.07f)), unknown, true))
    }

    @Test
    fun `nothing is corrected where the field does not allow it, or that is already a word`() {
        val c = listOf(corr("the", 0.5f, 0.37f))
        assertNull(Autocorrect.decide("teh", c, unknown, allowed = false))
        assertNull(Autocorrect.decide("teh", c, { true }, true))
    }

    @Test
    fun `a letter, a code or a name is left alone`() {
        val c = listOf(corr("the", 0.5f, 0.37f))
        assertNull(Autocorrect.decide("t", c, unknown, true))
        assertNull(Autocorrect.decide("te1", c, unknown, true))
        assertNull(Autocorrect.decide("tEh", c, unknown, true))
    }

    @Test
    fun `a completion is offered, never forced`() {
        assertNull(Autocorrect.decide("hel", listOf(Candidate("help", Kind.COMPLETION, 0.4f, 0.5f)), unknown, true))
    }

    @Test
    fun `a correction too far from what was typed is not made`() {
        assertNull(Autocorrect.decide("abcd", listOf(corr("wxyz", 1.5f, 0.9f)), unknown, true))
        // A longer word may be further off.
        assertEquals("definitely", Autocorrect.decide("definately", listOf(corr("definitely", 1.5f, 0.9f)), unknown, true))
    }

    @Test
    fun `a near tie is left for the strip`() {
        val c = listOf(corr("help", 0.5f, 0.08f), corr("hello", 0.5f, -0.03f))
        assertNull(Autocorrect.decide("helo", c, unknown, true))
    }
}
