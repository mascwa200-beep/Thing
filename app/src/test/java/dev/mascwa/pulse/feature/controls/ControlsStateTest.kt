package dev.mascwa.pulse.feature.controls

import dev.mascwa.pulse.feature.controls.ControlsState.Control
import dev.mascwa.pulse.feature.controls.ControlsState.Reading
import dev.mascwa.pulse.feature.controls.ControlsState.Ringer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsStateTest {

    private fun tile(r: Reading, c: Control) = ControlsState.tiles(r).single { it.control == c }

    @Test
    fun `every control appears exactly once`() {
        val controls = ControlsState.tiles(Reading()).map { it.control }
        assertEquals(Control.entries.toList(), controls)
    }

    @Test
    fun `unknown reads as unknown, never as off`() {
        val t = tile(Reading(wifi = null, deviceOwner = true), Control.WIFI)
        assertEquals(ControlsState.UNKNOWN, t.state)
        assertNull(t.on)
    }

    @Test
    fun `owner-only controls act only for the device owner, and say why otherwise`() {
        for (c in listOf(Control.WIFI, Control.LOCATION, Control.BRIGHTNESS, Control.TIMEOUT)) {
            assertTrue(c.name, tile(Reading(deviceOwner = true), c).canAct)
            val refused = tile(Reading(deviceOwner = false), c)
            assertFalse(c.name, refused.canAct)
            assertEquals(ControlsState.OWNER_ONLY, refused.why)
        }
    }

    @Test
    fun `a phone with no flash has a torch that says so`() {
        val t = tile(Reading(torch = null), Control.TORCH)
        assertFalse(t.canAct)
        assertEquals(ControlsState.NO_FLASH, t.why)
        assertTrue(tile(Reading(torch = false), Control.TORCH).canAct)
    }

    @Test
    fun `do not disturb needs the notification reader`() {
        val off = tile(Reading(listener = false), Control.DND)
        assertFalse(off.canAct)
        assertEquals(ControlsState.NEEDS_NOTICES, off.why)
        assertTrue(tile(Reading(listener = true), Control.DND).canAct)
    }

    @Test
    fun `bluetooth names the first thing missing`() {
        assertEquals(ControlsState.NO_BLUETOOTH, tile(Reading(bluetooth = null, deviceOwner = true), Control.BLUETOOTH).why)
        assertEquals(ControlsState.OWNER_ONLY, tile(Reading(bluetooth = true, deviceOwner = false), Control.BLUETOOTH).why)
        assertEquals(
            ControlsState.BLUETOOTH_PERMISSION,
            tile(Reading(bluetooth = true, deviceOwner = true, bluetoothPermission = false), Control.BLUETOOTH).why,
        )
        assertTrue(tile(Reading(bluetooth = false, deviceOwner = true, bluetoothPermission = true), Control.BLUETOOTH).canAct)
    }

    @Test
    fun `the ringer cycles ring, vibrate, silent`() {
        assertEquals(Ringer.VIBRATE, ControlsState.nextRinger(Ringer.NORMAL, policyAccess = true))
        assertEquals(Ringer.SILENT, ControlsState.nextRinger(Ringer.VIBRATE, policyAccess = true))
        assertEquals(Ringer.NORMAL, ControlsState.nextRinger(Ringer.SILENT, policyAccess = true))
        assertEquals(Ringer.NORMAL, ControlsState.nextRinger(null, policyAccess = true))
    }

    @Test
    fun `without the policy grant silent is skipped rather than stuck on`() {
        assertEquals(Ringer.NORMAL, ControlsState.nextRinger(Ringer.VIBRATE, policyAccess = false))
    }

    @Test
    fun `brightness steps from auto through the levels and back to auto`() {
        assertEquals(26, ControlsState.nextBrightness(200, auto = true))
        assertEquals(64, ControlsState.nextBrightness(26, auto = false))
        assertEquals(ControlsState.AUTO, ControlsState.nextBrightness(255, auto = false))
        assertEquals(26, ControlsState.nextBrightness(null, auto = null))
    }

    @Test
    fun `a brightness the slider left between steps goes to the step after its nearest`() {
        // 100 is nearest 128 (28 away) rather than 64 (36 away), so the next step is 191.
        assertEquals(191, ControlsState.nextBrightness(100, auto = false))
    }

    @Test
    fun `screen-off time cycles and wraps, nearest-matched`() {
        assertEquals(30_000L, ControlsState.nextTimeout(15_000))
        assertEquals(15_000L, ControlsState.nextTimeout(600_000))
        assertEquals(15_000L, ControlsState.nextTimeout(null))
        // 50 s is nearest 60 s (10 s away, against 20 s from 30 s), so the next is 2 min.
        assertEquals(120_000L, ControlsState.nextTimeout(50_000))
    }

    @Test
    fun `words for brightness and timeout`() {
        assertEquals("Auto", ControlsState.brightnessWord(10, auto = true))
        assertEquals("100%", ControlsState.brightnessWord(255, auto = false))
        assertEquals("50%", ControlsState.brightnessWord(128, auto = false))
        assertEquals("10%", ControlsState.brightnessWord(26, auto = false))
        // Rounded, not truncated: 191 is 74.9%, which reads 75.
        assertEquals("75%", ControlsState.brightnessWord(191, auto = false))
        assertEquals("30s", ControlsState.timeoutWord(30_000))
        assertEquals("10m", ControlsState.timeoutWord(600_000))
        assertEquals(ControlsState.UNKNOWN, ControlsState.timeoutWord(null))
    }
}
