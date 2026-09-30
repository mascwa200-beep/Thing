package dev.mascwa.pulse.feature.phone

import dev.mascwa.pulse.feature.phone.MissedCalls.Claim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedCallsTest {

    @Test
    fun `LCARS claims missed calls only when every condition holds`() {
        // ⚠️ Every one of the other fifteen combinations must leave them to Android: a claim LCARS
        // cannot honour is a missed call nobody hears about.
        for (phone in listOf(true, false)) for (state in listOf(true, false))
            for (notes in listOf(true, false)) for (down in listOf(true, false)) {
                val c = MissedCalls.claim(phone, state, notes, down)
                assertEquals("$phone $state $notes $down", phone && state && notes && !down, c == Claim.LCARS)
            }
    }

    @Test
    fun `the reason given is the first thing to fix`() {
        assertEquals(Claim.STOOD_DOWN, MissedCalls.claim(false, false, false, stoodDown = true))
        assertEquals(Claim.NOT_PHONE_APP, MissedCalls.claim(false, false, false, stoodDown = false))
        assertEquals(Claim.NO_PHONE_STATE, MissedCalls.claim(true, false, false, stoodDown = false))
        assertEquals(Claim.NOTIFICATIONS_OFF, MissedCalls.claim(true, true, false, stoodDown = false))
    }

    @Test
    fun `every reason Android posts them says so`() {
        for (c in Claim.values()) {
            val s = MissedCalls.sentence(c)
            assertEquals(c.name, c == Claim.LCARS, s.startsWith("LCARS"))
            if (c != Claim.LCARS) assertTrue(s.startsWith("Android posts them"))
        }
    }

    @Test
    fun `nothing missed means no notice`() {
        assertNull(MissedCalls.text(0, "Ann", "5550100"))
        assertNull(MissedCalls.text(-1, "Ann", "5550100"))
    }

    @Test
    fun `one missed call names the caller and offers a call back`() {
        val t = MissedCalls.text(1, "Ann Lee", "5550100")!!
        assertEquals("Missed call", t.title)
        assertEquals("Ann Lee", t.body)
        assertTrue(t.callBack)
    }

    @Test
    fun `a caller not in contacts is named by number`() {
        assertEquals("+1 616 555 0100", MissedCalls.text(1, "  ", " +1 616 555 0100 ")!!.body)
    }

    @Test
    fun `a withheld caller cannot be called back`() {
        val t = MissedCalls.text(1, null, "")!!
        assertEquals("Private number", t.body)
        assertFalse(t.callBack)
    }

    @Test
    fun `several missed calls are counted, and not called back`() {
        val t = MissedCalls.text(3, "Ann", "5550100")!!
        assertEquals("3 missed calls", t.title)
        assertEquals("Latest: Ann", t.body)
        assertFalse(t.callBack)
    }
}
