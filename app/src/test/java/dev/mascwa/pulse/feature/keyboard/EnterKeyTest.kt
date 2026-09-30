package dev.mascwa.pulse.feature.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnterKeyTest {

    @Test
    fun `each action the field asks for is performed and named`() {
        val expected = mapOf(
            EnterKey.IME_ACTION_GO to "GO",
            EnterKey.IME_ACTION_SEARCH to "SEARCH",
            EnterKey.IME_ACTION_SEND to "SEND",
            EnterKey.IME_ACTION_NEXT to "NEXT",
            EnterKey.IME_ACTION_DONE to "DONE",
            EnterKey.IME_ACTION_PREVIOUS to "PREV",
        )
        for ((action, label) in expected) {
            assertEquals(EnterKey.Spec(action, label), EnterKey.of(action))
        }
    }

    @Test
    fun `a field that asks for no enter action gets a return, whatever action it also names`() {
        val spec = EnterKey.of(EnterKey.IME_ACTION_SEND or EnterKey.IME_FLAG_NO_ENTER_ACTION)
        assertEquals(EnterKey.RETURN, spec)
        assertNull("a return is a key event, not an editor action", spec.action)
    }

    @Test
    fun `a field that names no action, or one this keyboard does not know, gets a return`() {
        assertEquals(EnterKey.RETURN, EnterKey.of(EnterKey.IME_ACTION_UNSPECIFIED))
        assertEquals(EnterKey.RETURN, EnterKey.of(EnterKey.IME_ACTION_NONE))
        assertEquals(EnterKey.RETURN, EnterKey.of(0x55))
    }

    @Test
    fun `other option flags do not hide the action`() {
        // IME_FLAG_NO_FULLSCREEN and IME_FLAG_NO_EXTRACT_UI, set by plenty of ordinary fields.
        val flags = 0x02000000 or 0x10000000
        assertEquals(EnterKey.Spec(EnterKey.IME_ACTION_DONE, "DONE"), EnterKey.of(EnterKey.IME_ACTION_DONE or flags))
    }
}
