package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.Suggest.Candidate
import dev.mascwa.pulse.feature.keyboard.Suggest.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class StripWordsTest {

    private fun c(word: String) = Candidate(word, Kind.CORRECTION, 0.5f, 0f)

    @Test
    fun `about to correct, the typed word is on the left and the correction in the middle`() {
        val s = StripWords.arrange("teh", listOf(c("the"), c("ten"), c("tea")), correction = "the")
        assertEquals(StripWord("teh", typed = true), s[0])
        assertEquals(StripWord("the", primary = true), s[1])
        assertEquals(StripWord("ten"), s[2])
    }

    @Test
    fun `otherwise the best suggestion is in the middle`() {
        val s = StripWords.arrange("hel", listOf(c("help"), c("hello"), c("held")), correction = null)
        assertEquals(listOf("hello", "help", "held"), s.map { it?.word })
        assertEquals(listOf(false, false, false), s.map { it?.primary })
    }

    @Test
    fun `one suggestion sits in the middle, with nothing either side`() {
        assertEquals(listOf(null, "help", null), StripWords.arrange("hel", listOf(c("help")), null).map { it?.word })
    }

    @Test
    fun `no word is offered twice, whatever its capitals`() {
        val s = StripWords.arrange("teh", listOf(c("the"), c("The"), c("ten")), correction = "the")
        assertEquals("ten", s[2]?.word)
        // Without a correction the second "the" is dropped too, so "ten" moves up beside it.
        assertEquals(listOf("ten", "the", null), StripWords.arrange("th", listOf(c("the"), c("The"), c("ten")), null).map { it?.word })
    }

    @Test
    fun `nothing typed, nothing shown`() {
        assertEquals(listOf(null, null, null), StripWords.arrange("", listOf(c("the")), null))
    }
}
