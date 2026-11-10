package dev.mascwa.pulse.feature.phone

import dev.mascwa.pulse.feature.phone.CallDisplay.Phase
import dev.mascwa.pulse.feature.phone.CallDisplay.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallDisplayTest {

    private val everything = CallDisplay.CAPABILITY_HOLD or CallDisplay.CAPABILITY_SUPPORT_HOLD or
        CallDisplay.CAPABILITY_MERGE_CONFERENCE or CallDisplay.CAPABILITY_SWAP_CONFERENCE or CallDisplay.CAPABILITY_MUTE

    @Test
    fun `every Telecom state has a phase`() {
        assertEquals(Phase.RINGING, CallDisplay.phase(CallDisplay.STATE_RINGING))
        assertEquals(Phase.RINGING, CallDisplay.phase(CallDisplay.STATE_SIMULATED_RINGING))
        assertEquals(Phase.DIALING, CallDisplay.phase(CallDisplay.STATE_DIALING))
        assertEquals(Phase.CONNECTING, CallDisplay.phase(CallDisplay.STATE_CONNECTING))
        assertEquals(Phase.ACTIVE, CallDisplay.phase(CallDisplay.STATE_ACTIVE))
        assertEquals(Phase.HOLDING, CallDisplay.phase(CallDisplay.STATE_HOLDING))
        assertEquals(Phase.ENDING, CallDisplay.phase(CallDisplay.STATE_DISCONNECTING))
        assertEquals(Phase.ENDED, CallDisplay.phase(CallDisplay.STATE_DISCONNECTED))
        assertEquals(Phase.WAITING, CallDisplay.phase(CallDisplay.STATE_SELECT_PHONE_ACCOUNT))
        assertEquals(Phase.WAITING, CallDisplay.phase(99))
    }

    @Test
    fun `answer and decline appear while ringing and nowhere else`() {
        // ⚠️ Anywhere else, decline would reject a call somebody is on.
        for (p in Phase.values()) {
            val c = CallDisplay.controls(p, everything, otherCalls = 0, otherHeld = false)
            assertEquals("$p answer", p == Phase.RINGING, c.answer)
            assertEquals("$p decline", p == Phase.RINGING, c.decline)
        }
        val ringing = CallDisplay.controls(Phase.RINGING, everything, 0, false)
        assertFalse("a ringing call is declined, not ended", ringing.end)
        assertFalse(ringing.mute)
    }

    @Test
    fun `a button appears only when the network says the call can do it`() {
        val bare = CallDisplay.controls(Phase.ACTIVE, 0, otherCalls = 1, otherHeld = false)
        assertFalse(bare.hold)
        assertFalse(bare.mute)
        assertFalse(bare.merge)
        assertFalse(bare.swap)
        val able = CallDisplay.controls(Phase.ACTIVE, everything, otherCalls = 1, otherHeld = false)
        assertTrue(able.hold)
        assertTrue(able.mute)
        assertTrue(able.merge)
        assertTrue(able.swap)
    }

    @Test
    fun `a second call held makes swap possible without a conference`() {
        assertTrue(CallDisplay.controls(Phase.ACTIVE, 0, otherCalls = 1, otherHeld = true).swap)
        assertFalse("no other call, nothing to swap", CallDisplay.controls(Phase.ACTIVE, everything, 0, false).swap)
    }

    @Test
    fun `a call can be added only while it is the only call`() {
        assertTrue(CallDisplay.controls(Phase.ACTIVE, 0, 0, false).addCall)
        assertFalse(CallDisplay.controls(Phase.ACTIVE, 0, 1, true).addCall)
    }

    @Test
    fun `a held call can be resumed or ended, and nothing else`() {
        val c = CallDisplay.controls(Phase.HOLDING, CallDisplay.CAPABILITY_HOLD, 1, false)
        assertEquals(CallDisplay.Controls(end = true, resume = true), c)
    }

    @Test
    fun `an ended call offers nothing`() {
        assertEquals(CallDisplay.Controls(), CallDisplay.controls(Phase.ENDED, everything, 0, false))
        assertEquals(CallDisplay.Controls(), CallDisplay.controls(Phase.ENDING, everything, 0, false))
    }

    @Test
    fun `the label reads the call`() {
        assertEquals("INCOMING CALL", CallDisplay.label(Phase.RINGING, 0, 0))
        assertEquals("CONNECTED", CallDisplay.label(Phase.ACTIVE, 0, 5_000))
        assertEquals("01:05", CallDisplay.label(Phase.ACTIVE, 1_000, 66_000))
        assertEquals("ON HOLD", CallDisplay.label(Phase.HOLDING, 1_000, 66_000))
    }

    @Test
    fun `elapsed time reads like a call timer, never negative`() {
        assertEquals("00:00", CallDisplay.elapsed(10_000, 10_999))
        assertEquals("59:59", CallDisplay.elapsed(0, 3_599_000))
        assertEquals("1:00:00", CallDisplay.elapsed(0, 3_600_000))
        assertEquals("00:00", CallDisplay.elapsed(10_000, 0))
    }

    @Test
    fun `the two route APIs are read with their own tables`() {
        // ⚠️ Speaker is 8 in one and 4 in the other; 4 in the first is the wired headset.
        assertEquals(Route.SPEAKER, CallDisplay.routeFromLegacy(8))
        assertEquals(Route.WIRED, CallDisplay.routeFromLegacy(4))
        assertEquals(Route.SPEAKER, CallDisplay.routeFromEndpoint(4))
        assertEquals(Route.WIRED, CallDisplay.routeFromEndpoint(3))
        assertEquals(Route.UNKNOWN, CallDisplay.routeFromEndpoint(-1))
        for (r in Route.values()) {
            val bit = CallDisplay.legacyRoute(r) ?: continue
            assertEquals(r, CallDisplay.routeFromLegacy(bit))
        }
        assertEquals(setOf(Route.EARPIECE, Route.SPEAKER, Route.BLUETOOTH), CallDisplay.routesFromLegacyMask(1 or 2 or 8))
    }

    @Test
    fun `speaker off goes back to the best private route`() {
        assertEquals(Route.SPEAKER, CallDisplay.speakerToggle(Route.EARPIECE, setOf(Route.EARPIECE, Route.SPEAKER)))
        assertEquals(Route.BLUETOOTH, CallDisplay.speakerToggle(Route.SPEAKER, setOf(Route.EARPIECE, Route.SPEAKER, Route.BLUETOOTH, Route.WIRED)))
        assertEquals(Route.WIRED, CallDisplay.speakerToggle(Route.SPEAKER, setOf(Route.EARPIECE, Route.SPEAKER, Route.WIRED)))
        assertEquals(Route.EARPIECE, CallDisplay.speakerToggle(Route.SPEAKER, setOf(Route.EARPIECE, Route.SPEAKER)))
    }

    @Test
    fun `the screen blanks at the ear only on the earpiece of a live call`() {
        assertTrue(CallDisplay.heldToEar(Route.EARPIECE, Phase.ACTIVE))
        assertFalse(CallDisplay.heldToEar(Route.SPEAKER, Phase.ACTIVE))
        assertFalse("a ringing phone is looked at, not held up", CallDisplay.heldToEar(Route.EARPIECE, Phase.RINGING))
        assertFalse(CallDisplay.heldToEar(Route.EARPIECE, Phase.ENDED))
    }
}
