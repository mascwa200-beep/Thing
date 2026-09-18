package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expectation here is computed from the shipped rule before it is written down, and the
 * arithmetic is in the comment where there is any. That habit has caught roughly twenty mistakes of
 * mine across this project where the code turned out to be right.
 */
class SenseContextTest {

    // ---- attention ----

    @Test
    fun `nothing read the screen, so nothing is claimed about the person`() {
        // ⚠️ The lock state alone is NOT second-best evidence: a phone can sit locked in a drawer or
        // locked in a hand a second after the display timed out, and they are not the same thing.
        assertEquals(Attention.UNKNOWN, SenseContext().attention())
        assertEquals(Attention.UNKNOWN, SenseContext(locked = false).attention())
        assertEquals(Attention.UNKNOWN, SenseContext(locked = true, idle = true).attention())
    }

    @Test
    fun `a lit unlocked screen is the only thing that means somebody is looking`() {
        assertEquals(Attention.IN_USE, SenseContext(screenOn = true, locked = false).attention())
    }

    @Test
    fun `a lit but locked screen is a glance, not use`() {
        assertEquals(Attention.PRESENT, SenseContext(screenOn = true, locked = true).attention())
    }

    @Test
    fun `a lit screen with the lock state unknown does not claim use`() {
        assertEquals(Attention.PRESENT, SenseContext(screenOn = true).attention())
    }

    @Test
    fun `doze beats a recent unlock, because the OS knows more than this class does`() {
        // Both signals point opposite ways on purpose: Android would not have entered Doze if the
        // phone had genuinely been unlocked a moment ago, so the recency is the stale one.
        val c = SenseContext(screenOn = false, idle = true, msSinceUnlock = 1_000L)
        assertEquals(Attention.AWAY, c.attention())
    }

    @Test
    fun `a phone put down a minute ago still has somebody next to it`() {
        // 60_000 <= RECENTLY_PUT_DOWN_MS (120_000) -> PRESENT
        val c = SenseContext(screenOn = false, locked = true, msSinceUnlock = 60_000L)
        assertEquals(Attention.PRESENT, c.attention())
    }

    @Test
    fun `the recency window has an edge and it is inclusive`() {
        val at = SenseContext(screenOn = false, msSinceUnlock = SenseContext.RECENTLY_PUT_DOWN_MS)
        val past = SenseContext(screenOn = false, msSinceUnlock = SenseContext.RECENTLY_PUT_DOWN_MS + 1)
        assertEquals(Attention.PRESENT, at.attention())
        assertEquals(Attention.AWAY, past.attention())
    }

    @Test
    fun `an unlock nobody saw falls to away rather than to present`() {
        // ⚠️ The conservative direction. Holding an interruption back because we believe nobody is
        // looking is a smaller mistake than interrupting a person who has left the room.
        assertEquals(Attention.AWAY, SenseContext(screenOn = false).attention())
    }

    // ---- wantsQuiet ----

    @Test
    fun `nothing known means nothing claimed, not a quiet phone`() {
        assertNull(SenseContext().wantsQuiet())
    }

    @Test
    fun `an unknown DND filter is not evidence that DND is off`() {
        // ⚠️ This is the whole reason DndFilter.UNKNOWN exists separately from OFF. The platform
        // answers INTERRUPTION_FILTER_UNKNOWN when it will not say, and reading that as "DND is off"
        // would let a rule make a noise on the strength of an answer it never got.
        assertNull(SenseContext(dnd = DndFilter.UNKNOWN).wantsQuiet())
    }

    @Test
    fun `a normal ringer with nothing else known is a real answer`() {
        assertEquals(false, SenseContext(ringer = RingerState.NORMAL).wantsQuiet())
    }

    @Test
    fun `any one signal asking for quiet is enough`() {
        assertEquals(true, SenseContext(ringer = RingerState.SILENT).wantsQuiet())
        assertEquals(true, SenseContext(ringer = RingerState.VIBRATE).wantsQuiet())
        assertEquals(true, SenseContext(dnd = DndFilter.PRIORITY).wantsQuiet())
        assertEquals(true, SenseContext(dnd = DndFilter.TOTAL_SILENCE).wantsQuiet())
        assertEquals(true, SenseContext(posture = Posture.FACE_DOWN).wantsQuiet())
        // One signal saying quiet outvotes the others saying otherwise — it is `any`, not a majority.
        val mixed = SenseContext(ringer = RingerState.NORMAL, dnd = DndFilter.OFF, posture = Posture.FACE_DOWN)
        assertEquals(true, mixed.wantsQuiet())
    }

    @Test
    fun `DND off means the person has not silenced anything`() {
        // Pins the renaming: OFF is `INTERRUPTION_FILTER_ALL`, which lets everything through.
        assertEquals(false, SenseContext(dnd = DndFilter.OFF).wantsQuiet())
    }

    @Test
    fun `face-up is not a request for quiet`() {
        assertEquals(false, SenseContext(posture = Posture.FACE_UP).wantsQuiet())
        assertEquals(false, SenseContext(posture = Posture.UPRIGHT).wantsQuiet())
        assertEquals(false, SenseContext(posture = Posture.UNKNOWN).wantsQuiet())
    }

    // ---- describe ----

    @Test
    fun `an ordinary phone says only what is notable about it`() {
        val c = SenseContext(
            screenOn = true, locked = false, charging = false, idle = false,
            posture = Posture.UPRIGHT, thermal = DeviceClass.Pressure.NONE,
            onCall = false, musicPlaying = false, ringer = RingerState.NORMAL,
            route = AudioRoute.SPEAKER, dnd = DndFilter.OFF,
            awayFromHome = false,
        )
        // Twelve fields set and exactly one clause, because none of the rest is worth saying.
        assertEquals("in use", c.describe())
    }

    @Test
    fun `an empty context still says something honest`() {
        assertEquals("attention unknown", SenseContext().describe())
    }

    @Test
    fun `the charger is named, because a pad and a car mean different things`() {
        val pad = SenseContext(screenOn = false, charging = true, power = PowerSource.WIRELESS)
        val usb = SenseContext(screenOn = false, charging = true, power = PowerSource.USB)
        assertTrue(pad.describe().contains("charging on a pad"))
        assertTrue(usb.describe().contains("charging over USB"))
    }

    @Test
    fun `a plug type with nothing plugged in is not mentioned`() {
        // ⚠️ `power` is PowerSource.BATTERY whenever nothing is connected, and printing "charging"
        // off the plug type alone would announce a charger on a phone running down its battery.
        val c = SenseContext(screenOn = false, charging = false, power = PowerSource.BATTERY)
        assertFalse(c.describe().contains("charging"))
    }

    @Test
    fun `every notable state reaches the line`() {
        val c = SenseContext(
            screenOn = false, locked = true, msSinceUnlock = 30_000L, idle = true,
            posture = Posture.FACE_DOWN, charging = true, power = PowerSource.AC,
            thermal = DeviceClass.Pressure.HOT, onCall = true, musicPlaying = true,
            ringer = RingerState.VIBRATE, route = AudioRoute.BLUETOOTH,
            dnd = DndFilter.PRIORITY, awayFromHome = true,
        )
        val line = c.describe()
        // idle = true, so attention is AWAY whatever the 30 s unlock says — see the Doze rule above.
        for (expected in listOf(
            "put down", "face-down", "on mains", "hot", "on a call", "playing audio",
            "over Bluetooth", "on vibrate", "priority only",
            "away from home", "dozing",
        )) {
            assertTrue("missing '$expected' in: $line", line.contains(expected))
        }
    }

    @Test
    fun `a cool phone is not described as cool`() {
        // NONE contributes nothing: a temperature clause on every reading is a clause nobody reads.
        val c = SenseContext(screenOn = true, locked = false, thermal = DeviceClass.Pressure.NONE)
        assertEquals("in use", c.describe())
    }

    @Test
    fun `warm and overheating are different words`() {
        assertTrue(SenseContext(thermal = DeviceClass.Pressure.WARM).describe().contains("warm"))
        assertTrue(SenseContext(thermal = DeviceClass.Pressure.CRITICAL).describe().contains("overheating"))
    }

    // ---- posture ----

    @Test
    fun `a phone resting flat says which way up it is`() {
        assertEquals(Posture.FACE_UP, Posture.from(zG = 1f, restDeviationG = 0f))
        assertEquals(Posture.FACE_DOWN, Posture.from(zG = -1f, restDeviationG = 0f))
        assertEquals(Posture.UPRIGHT, Posture.from(zG = 0f, restDeviationG = 0f))
    }

    @Test
    fun `a moving phone is not asked which way up it is`() {
        // ⚠️ The whole point. z says "face-up" in both and the phone is being carried, so the
        // accelerometer is measuring the walk rather than gravity. 0.16 is just past
        // RESTING_TOLERANCE_G (0.15); 0.40 is an ordinary walking EWMA.
        assertEquals(Posture.UNKNOWN, Posture.from(zG = 1f, restDeviationG = 0.16f))
        assertEquals(Posture.UNKNOWN, Posture.from(zG = 1f, restDeviationG = 0.40f))
        assertEquals(Posture.UNKNOWN, Posture.from(zG = -1f, restDeviationG = 1f))
    }

    @Test
    fun `the resting band has an edge and it is inclusive`() {
        // Exactly RESTING_TOLERANCE_G is still read as at rest; one float past it is not.
        assertEquals(Posture.FACE_UP, Posture.from(zG = 1f, restDeviationG = Posture.RESTING_TOLERANCE_G))
        assertEquals(Posture.UNKNOWN, Posture.from(zG = 1f, restDeviationG = 0.151f))
    }

    @Test
    fun `a phone propped at a steep angle is upright, not lying down`() {
        // FLAT_G is 0.80 — cos(37°). 0.79 is 37.8° from horizontal, so it is being held or propped.
        assertEquals(Posture.UPRIGHT, Posture.from(zG = 0.79f, restDeviationG = 0f))
        assertEquals(Posture.UPRIGHT, Posture.from(zG = -0.79f, restDeviationG = 0f))
        assertEquals(Posture.FACE_UP, Posture.from(zG = Posture.FLAT_G, restDeviationG = 0f))
        assertEquals(Posture.FACE_DOWN, Posture.from(zG = -Posture.FLAT_G, restDeviationG = 0f))
    }

    @Test
    fun `a sensor that reported nothing usable is refused`() {
        // ⚠️ Not redundant with the band check: every comparison against NaN is false, so without
        // the finite guard a NaN z falls past both FLAT_G branches and lands on UPRIGHT — a posture
        // reported with total confidence from a reading that does not exist.
        assertEquals(Posture.UNKNOWN, Posture.from(zG = Float.NaN, restDeviationG = 0f))
        assertEquals(Posture.UNKNOWN, Posture.from(zG = 1f, restDeviationG = Float.NaN))
        assertEquals(Posture.UNKNOWN, Posture.from(zG = Float.POSITIVE_INFINITY, restDeviationG = 0f))
    }
}
