package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.Composer.Edit
import dev.mascwa.pulse.feature.keyboard.Composer.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {

    @Test
    fun `letters build the composing word`() {
        val (s1, _) = Composer.letter(State(), "h")
        val (s2, e) = Composer.letter(s1, "i")
        assertEquals("hi", s2.word)
        assertEquals(Edit.Compose("hi"), e)
    }

    @Test
    fun `a separator finishes the word as its correction, and remembers what was typed`() {
        val (s, e) = Composer.separator(State(word = "teh"), " ", "the")
        assertEquals(Edit.Commit("the "), e)
        assertEquals(Composer.Undo("teh", "the "), s.undo)
        assertEquals("", s.word)
    }

    @Test
    fun `a separator with no correction finishes the word as typed and remembers nothing`() {
        val (s, e) = Composer.separator(State(word = "hi"), ",", null)
        assertEquals(Edit.Commit("hi,"), e)
        assertNull(s.undo)
        // A "correction" to the same word is not one.
        assertNull(Composer.separator(State(word = "hi"), " ", "hi").first.undo)
    }

    @Test
    fun `a separator with no word is just the separator`() {
        assertEquals(Edit.Commit(" "), Composer.separator(State(), " ", "the").second)
    }

    @Test
    fun `delete takes a letter off the word`() {
        val (s, e) = Composer.backspace(State(word = "hel"), "anything")
        assertEquals("he", s.word)
        assertEquals(Edit.Compose("he"), e)
    }

    @Test
    fun `delete straight after a correction puts back what was typed`() {
        val after = State(undo = Composer.Undo("teh", "the "))
        val (s, e) = Composer.backspace(after, "I saw the ")
        assertEquals(Edit.Revert(4, "teh"), e)
        assertNull(s.undo)
    }

    @Test
    fun `but only if the text is still what was put in`() {
        val after = State(undo = Composer.Undo("teh", "the "))
        assertEquals(Edit.Backspace, Composer.backspace(after, "I saw thx ").second)
        assertEquals(Edit.Backspace, Composer.backspace(after, null).second)
    }

    @Test
    fun `with no word and nothing to undo, delete is a delete`() {
        assertEquals(Edit.Backspace, Composer.backspace(State(), "text").second)
    }

    @Test
    fun `any letter after a correction forgets it`() {
        val after = State(undo = Composer.Undo("teh", "the "))
        assertNull(Composer.letter(after, "a").first.undo)
    }

    @Test
    fun `enter finishes the word as it stands`() {
        assertEquals(Edit.Commit("hi"), Composer.finish(State(word = "hi")).second)
        assertNull(Composer.finish(State()).second)
    }

    @Test
    fun `a tapped suggestion goes in with a space`() {
        assertEquals(Edit.Commit("hello "), Composer.pick("hello").second)
    }

    @Test
    fun `a word is letters and apostrophes, and sentence marks are when it is corrected`() {
        assertTrue(Composer.isWordText("don't"))
        assertFalse(Composer.isWordText("1"))
        assertFalse(Composer.isWordText(""))
        assertTrue(Composer.correctsBefore(" "))
        assertTrue(Composer.correctsBefore("?"))
        assertFalse(Composer.correctsBefore("7"))
        assertFalse(Composer.correctsBefore("@"))
    }

    // ── what a field allows ─────────────────────────────────────────────────────────────────

    private val text = 0x01

    @Test
    fun `ordinary text allows everything`() {
        assertTrue(KeyboardPrivacy.maySuggest(text))
        assertTrue(KeyboardPrivacy.mayAutocorrect(text))
        assertTrue(KeyboardPrivacy.mayLearn(text, 0))
    }

    @Test
    fun `a password allows nothing`() {
        for (variation in listOf(0x80, 0x90, 0xE0)) {
            assertFalse(KeyboardPrivacy.maySuggest(text or variation))
            assertFalse(KeyboardPrivacy.mayLearn(text or variation, 0))
        }
        assertFalse(KeyboardPrivacy.maySuggest(0x02 or 0x10)) // a number password
    }

    // ── a glided word ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a glided word goes in whole, and comes out whole`() {
        val (s, e) = Composer.glide("hello")
        assertEquals(State(word = "hello", glided = true), s)
        assertEquals(Edit.Compose("hello"), e)
        val (after, del) = Composer.backspace(s, null)
        assertEquals(State(), after)
        assertEquals(Edit.Compose(""), del)
        // A word typed by hand still loses one letter at a time.
        assertEquals(Edit.Compose("hell"), Composer.backspace(State(word = "hello"), null).second)
    }

    @Test
    fun `a letter after a glided word starts the next word, with the space between`() {
        val (s, e) = Composer.letter(State(word = "hello", glided = true), "w")
        assertEquals(State(word = "w"), s)
        assertEquals(Edit.CommitThenCompose("hello ", "w"), e)
    }

    @Test
    fun `an apostrophe after a glided word joins it`() {
        val (s, e) = Composer.letter(State(word = "don", glided = true), "'")
        assertEquals(State(word = "don'"), s)
        assertEquals(Edit.Compose("don'"), e)
    }

    @Test
    fun `a separator after a glided word sits against it`() {
        assertEquals(Edit.Commit("hello."), Composer.separator(State(word = "hello", glided = true), ".", null).second)
    }

    @Test
    fun `a glided word gets a space before it only when the text before ends a word`() {
        for (before in listOf("hello", "end.", "yes,", "I", "that's", "done!", "4")) {
            assertTrue("'$before'", Composer.spaceBeforeGlide(before))
        }
        for (before in listOf(null, "", "hello ", "line\n", "(", "\"", "well-", "and/", "@", "#", "\u201C")) {
            assertFalse("'$before'", Composer.spaceBeforeGlide(before))
        }
    }

    @Test
    fun `a glided word is capitalised as shift says`() {
        assertEquals("hello", Composer.glideCase("hello", KeyboardModel.ShiftState.OFF))
        assertEquals("Hello", Composer.glideCase("hello", KeyboardModel.ShiftState.ONCE))
        assertEquals("HELLO", Composer.glideCase("hello", KeyboardModel.ShiftState.LOCKED))
        // A word the list spells with a capital keeps it.
        assertEquals("London", Composer.glideCase("London", KeyboardModel.ShiftState.OFF))
    }

    @Test
    fun `a field that asks for no suggestions, or no learning, gets none`() {
        assertFalse(KeyboardPrivacy.maySuggest(text or 0x80000))
        assertFalse(KeyboardPrivacy.mayLearn(text, 0x1000000))
        assertTrue(KeyboardPrivacy.maySuggest(text))
    }

    @Test
    fun `an address gets suggestions but no corrections`() {
        assertTrue(KeyboardPrivacy.maySuggest(text or 0x10))
        assertFalse(KeyboardPrivacy.mayAutocorrect(text or 0x10))
        assertFalse(KeyboardPrivacy.mayAutocorrect(text or 0x20))
    }

    @Test
    fun `nothing typed in an address is learned`() {
        assertFalse(KeyboardPrivacy.mayLearn(text or 0x10, 0)) // a web address
        assertFalse(KeyboardPrivacy.mayLearn(text or 0x20, 0)) // an email address
    }
}
