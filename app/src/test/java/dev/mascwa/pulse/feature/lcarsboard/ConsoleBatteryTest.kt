package dev.mascwa.pulse.feature.lcarsboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConsoleBatteryTest {

    @Test
    fun `a level and a scale give a whole percentage`() {
        assertEquals(ConsoleBattery.Reading(84, charging = false), ConsoleBattery.reading(84, 100, 0))
        // A scale that is not 100: 150 of 200 is 75%, not 150%.
        assertEquals(75, ConsoleBattery.reading(150, 200, 0)?.percent)
        // Rounded, not truncated: 2 of 3 is 66.67, so 67.
        assertEquals(67, ConsoleBattery.reading(2, 3, 0)?.percent)
    }

    @Test
    fun `nothing measured is no reading, never zero percent`() {
        // The extras default to -1 when the broadcast did not carry them.
        assertNull(ConsoleBattery.reading(-1, 100, 0))
        assertNull(ConsoleBattery.reading(50, -1, 0))
        // A scale of zero is not a battery, and dividing by it must not be attempted.
        assertNull(ConsoleBattery.reading(50, 0, 0))
    }

    @Test
    fun `any plug source counts as charging`() {
        // EXTRA_PLUGGED is a bit set: AC 1, USB 2, wireless 4, dock 8.
        for (plugged in listOf(1, 2, 4, 8)) {
            assertEquals(true, ConsoleBattery.reading(90, 100, plugged)?.charging)
        }
        assertEquals(false, ConsoleBattery.reading(90, 100, 0)?.charging)
    }

    @Test
    fun `the label says the percentage and whether it is charging`() {
        assertEquals("84%", ConsoleBattery.label(ConsoleBattery.Reading(84, charging = false)))
        assertEquals("100% CHG", ConsoleBattery.label(ConsoleBattery.Reading(100, charging = true)))
    }
}
