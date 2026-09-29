package dev.mascwa.pulse.feature.lockboard

import dev.mascwa.pulse.feature.lockboard.LockBoardPolicy.Facts
import dev.mascwa.pulse.feature.lockboard.LockBoardPolicy.Outcome as O
import dev.mascwa.pulse.feature.lockboard.LockBoardPolicy.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockBoardPolicyTest {

    /** Every fact set so the board WOULD be shown on either trigger; each test breaks one thing. */
    private val clear = Facts(
        enabled = true,
        canLaunch = true,
        inCall = false,
        alertSounding = false,
        takeoverAlive = false,
        boardAlive = false,
        keyguardLocked = true,
    )

    @Test
    fun `with nothing in the way it launches on both triggers`() {
        assertNull(LockBoardPolicy.decide(Trigger.SCREEN_OFF, clear))
        assertNull(LockBoardPolicy.decide(Trigger.SCREEN_ON, clear))
    }

    @Test
    fun `switched off, or unable to start from the background, it never launches`() {
        for (t in Trigger.entries) {
            assertNotNull(LockBoardPolicy.decide(t, clear.copy(enabled = false)))
            assertNotNull(LockBoardPolicy.decide(t, clear.copy(canLaunch = false)))
        }
    }

    @Test
    fun `a call keeps the board away, and a call is the reason given before any other`() {
        // Proximity turns the screen off during a call, so screen-off is exactly when this bites.
        val inCall = clear.copy(inCall = true, takeoverAlive = true, alertSounding = true)
        assertEquals(
            "a call is ringing or in progress",
            LockBoardPolicy.decide(Trigger.SCREEN_OFF, inCall),
        )
    }

    @Test
    fun `an alert of our own is never covered`() {
        for (t in Trigger.entries) {
            assertNotNull(LockBoardPolicy.decide(t, clear.copy(takeoverAlive = true)))
        }
    }

    @Test
    fun `an alarm or ringtone that woke the phone is left in front`() {
        for (t in Trigger.entries) {
            assertNotNull(LockBoardPolicy.decide(t, clear.copy(alertSounding = true)))
        }
    }

    @Test
    fun `a board already up is not started twice`() {
        assertNotNull(LockBoardPolicy.decide(Trigger.SCREEN_OFF, clear.copy(boardAlive = true)))
    }

    @Test
    fun `screen-on needs a locked phone, screen-off does not ask`() {
        val unlocked = clear.copy(keyguardLocked = false)
        assertEquals("the phone is not locked", LockBoardPolicy.decide(Trigger.SCREEN_ON, unlocked))
        // At screen-off the lock delay has not run out, so "not locked" is the ordinary answer and
        // must not stop the board being put in place for when the screen comes back.
        assertNull(LockBoardPolicy.decide(Trigger.SCREEN_OFF, unlocked))
    }

    @Test
    fun `a device owner can always start the board`() {
        assertTrue(LockBoardPolicy.canLaunch(deviceOwner = true, overlayGranted = false, sdkInt = 36, targetSdk = 35))
    }

    @Test
    fun `an overlay grant is enough only before the Android 15 rule applies`() {
        assertTrue(LockBoardPolicy.canLaunch(false, overlayGranted = true, sdkInt = 34, targetSdk = 35))
        assertFalse(LockBoardPolicy.canLaunch(false, overlayGranted = true, sdkInt = 35, targetSdk = 35))
        assertFalse(LockBoardPolicy.canLaunch(false, overlayGranted = true, sdkInt = 36, targetSdk = 35))
        // The rule is keyed on the TARGET as well: an app targeting below 35 keeps the old exemption.
        assertTrue(LockBoardPolicy.canLaunch(false, overlayGranted = true, sdkInt = 36, targetSdk = 34))
        assertFalse(LockBoardPolicy.canLaunch(false, overlayGranted = false, sdkInt = 30, targetSdk = 30))
    }

    @Test
    fun `the board steps aside only for a lit, unlocked phone`() {
        assertTrue(LockBoardPolicy.finishOnResume(interactive = true, keyguardLocked = false))
        assertFalse(LockBoardPolicy.finishOnResume(interactive = true, keyguardLocked = true))
        // Started at screen-off and resumed in the dark before the lock delay ran out: keep it.
        assertFalse(LockBoardPolicy.finishOnResume(interactive = false, keyguardLocked = false))
    }

    @Test
    fun `a board is re-read when missing, old, or stamped in the future`() {
        val now = 1_800_000_000_000L
        assertTrue(LockBoardPolicy.isStale(null, now))
        assertFalse(LockBoardPolicy.isStale(now - 60_000L, now))
        assertFalse(LockBoardPolicy.isStale(now - LockBoardPolicy.STALE_AFTER_MS, now))
        assertTrue(LockBoardPolicy.isStale(now - LockBoardPolicy.STALE_AFTER_MS - 1, now))
        assertTrue(LockBoardPolicy.isStale(now + 60_000L, now))
    }

    // ── the outcome log ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the two ordinary skips are not recorded, every other reason is`() {
        // ALREADY_UP happens on every screen-on while the board works; recording it would overwrite
        // the SHOWN that says so.
        assertFalse(LockBoardPolicy.worthRecording(LockBoardPolicy.decide(Trigger.SCREEN_ON, clear.copy(boardAlive = true))!!))
        assertFalse(LockBoardPolicy.worthRecording(LockBoardPolicy.decide(Trigger.SCREEN_ON, clear.copy(keyguardLocked = false))!!))
        for (f in listOf(
            clear.copy(enabled = false), clear.copy(canLaunch = false), clear.copy(inCall = true),
            clear.copy(takeoverAlive = true), clear.copy(alertSounding = true),
        )) {
            assertTrue(LockBoardPolicy.worthRecording(LockBoardPolicy.decide(Trigger.SCREEN_OFF, f)!!))
        }
    }

    @Test
    fun `a board asked for and never created is a refusal, and only that`() {
        assertTrue(LockBoardPolicy.refused(O.REQUESTED, boardAlive = false))
        // Created late but alive by now: not refused, whatever the log says.
        assertFalse(LockBoardPolicy.refused(O.REQUESTED, boardAlive = true))
        assertFalse(LockBoardPolicy.refused(O.SHOWN, boardAlive = false))
        assertFalse(LockBoardPolicy.refused(O.SKIPPED, boardAlive = false))
        assertFalse(LockBoardPolicy.refused(null, boardAlive = false))
    }

    @Test
    fun `the activity log gets changes, not every screen-off`() {
        // A refusal is logged, and a second identical one is not.
        assertTrue(LockBoardPolicy.worthLogging(O.REFUSED, null, lastLogged = null))
        assertFalse(LockBoardPolicy.worthLogging(O.REFUSED, null, lastLogged = O.REFUSED to null))
        // A different skip reason is a change.
        assertTrue(LockBoardPolicy.worthLogging(O.SKIPPED, "a call", lastLogged = O.SKIPPED to "an alarm"))
        assertFalse(LockBoardPolicy.worthLogging(O.SKIPPED, "a call", lastLogged = O.SKIPPED to "a call"))
        // SHOWN is the normal state: logged only as the recovery from a refusal or a skip.
        assertFalse(LockBoardPolicy.worthLogging(O.SHOWN, null, lastLogged = null))
        assertTrue(LockBoardPolicy.worthLogging(O.SHOWN, null, lastLogged = O.REFUSED to null))
        assertFalse(LockBoardPolicy.worthLogging(O.SHOWN, null, lastLogged = O.SHOWN to null))
        assertFalse(LockBoardPolicy.worthLogging(O.REQUESTED, null, lastLogged = O.REFUSED to null))
    }

    // ── the power-down measurement ─────────────────────────────────────────────────────────────

    @Test
    fun `the power-down is described by how much of it there was time for`() {
        val d = 650L
        assertEquals(LockBoardPolicy.PowerDownSeen.NONE, LockBoardPolicy.powerDownSeen(10, d))
        assertEquals(LockBoardPolicy.PowerDownSeen.BRIEF, LockBoardPolicy.powerDownSeen(120, d))
        assertEquals(LockBoardPolicy.PowerDownSeen.PARTIAL, LockBoardPolicy.powerDownSeen(400, d))
        assertEquals(LockBoardPolicy.PowerDownSeen.WHOLE, LockBoardPolicy.powerDownSeen(650, d))
        // The partial sentence names both numbers, so nobody has to know the duration to read it.
        assertEquals(
            "400 of 650 ms of the power-down before the screen went dark",
            LockBoardPolicy.describePowerDown(400, d),
        )
    }

    @Test
    fun `a power-down measurement is logged only when it changes band`() {
        assertTrue(LockBoardPolicy.powerDownWorthLogging(120, lastLoggedMs = null))
        assertFalse(LockBoardPolicy.powerDownWorthLogging(130, lastLoggedMs = 120))
        assertTrue(LockBoardPolicy.powerDownWorthLogging(400, lastLoggedMs = 120))
    }
}
