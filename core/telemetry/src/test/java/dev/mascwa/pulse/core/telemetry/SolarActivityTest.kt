package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarActivityTest {

    @Test fun flareClassMatchesTheGoesDecades() {
        // The value the live GOES feed was serving when this was written: 5.83e-7 W/m^2 -> B5.8.
        assertEquals("B5.8", SolarActivity.flareClass(5.832874e-7)?.label)
        assertEquals("A1.0", SolarActivity.flareClass(1e-8)?.label)
        assertEquals("B1.0", SolarActivity.flareClass(1e-7)?.label)
        assertEquals("C1.0", SolarActivity.flareClass(1e-6)?.label)
        assertEquals("M1.0", SolarActivity.flareClass(1e-5)?.label)
        assertEquals("X1.0", SolarActivity.flareClass(1e-4)?.label)
        assertEquals('M', SolarActivity.flareClass(5.4e-5)?.letter)
        assertEquals(5.4, SolarActivity.flareClass(5.4e-5)!!.magnitude, 0.01)
    }

    @Test fun theXClassIsOpenEndedRatherThanRollingOver() {
        // The 2003 event saturated near X20 — an X class does not become "Y".
        assertEquals("X20", SolarActivity.flareClass(2e-3)?.label)
        assertEquals("X45", SolarActivity.flareClass(4.5e-3)?.label)
        assertEquals('X', SolarActivity.flareClass(1e-2)?.letter)
    }

    @Test fun aQuietSunHasNoFlareClassAtAll() {
        assertNull(SolarActivity.flareClass(null))
        assertNull(SolarActivity.flareClass(9e-9))          // below the A decade
        assertNull(SolarActivity.flareClass(Double.NaN))
        assertNull(SolarActivity.flareClass(0.0))
    }

    @Test fun radioBlackoutFollowsTheRScale() {
        assertEquals(0, SolarActivity.radioBlackout(null))
        assertEquals(0, SolarActivity.radioBlackout(9.9e-6))  // just under M1
        assertEquals(1, SolarActivity.radioBlackout(1e-5))    // M1
        assertEquals(2, SolarActivity.radioBlackout(5e-5))    // M5
        assertEquals(3, SolarActivity.radioBlackout(1e-4))    // X1
        assertEquals(4, SolarActivity.radioBlackout(1e-3))    // X10
        assertEquals(5, SolarActivity.radioBlackout(2e-3))    // X20
    }

    @Test fun radiationStormFollowsTheSScaleDecades() {
        assertEquals(0, SolarActivity.radiationStorm(9.9))
        assertEquals(1, SolarActivity.radiationStorm(10.0))
        assertEquals(2, SolarActivity.radiationStorm(100.0))
        assertEquals(3, SolarActivity.radiationStorm(1_000.0))
        assertEquals(4, SolarActivity.radiationStorm(10_000.0))
        assertEquals(5, SolarActivity.radiationStorm(100_000.0))
        assertEquals(0, SolarActivity.radiationStorm(null))
    }

    @Test fun geomagneticStormFollowsKp() {
        assertEquals(0, SolarActivity.geomagneticStorm(4.9))
        assertEquals(1, SolarActivity.geomagneticStorm(5.0))
        assertEquals(2, SolarActivity.geomagneticStorm(6.0))
        assertEquals(3, SolarActivity.geomagneticStorm(7.0))
        assertEquals(4, SolarActivity.geomagneticStorm(8.0))
        assertEquals(5, SolarActivity.geomagneticStorm(9.0))
        assertEquals(0, SolarActivity.geomagneticStorm(null))
        // This must agree with the label the app has always shown for Kp.
        assertEquals("G1 Minor", SolarActivity.scaleLabel('G', SolarActivity.geomagneticStorm(5.0)))
        assertEquals("G5 Extreme", SolarActivity.scaleLabel('G', SolarActivity.geomagneticStorm(9.0)))
    }

    @Test fun labelsAndTokensReadTheWayNoaaWritesThem() {
        assertEquals("None", SolarActivity.scaleLabel('G', 0))
        assertEquals("R3 Strong", SolarActivity.scaleLabel('R', 3))
        assertEquals("S5 Extreme", SolarActivity.scaleLabel('S', 5))
        assertEquals("G2", SolarActivity.scaleToken('G', 2))
        // Out-of-range levels clamp rather than producing "G7".
        assertEquals("G5", SolarActivity.scaleToken('G', 9))
        assertEquals("G0", SolarActivity.scaleToken('G', -3))
    }

    @Test fun headlineNamesTheWorstScaleAndBreaksTiesTowardTheVisibleOne() {
        assertEquals("Quiet", SolarActivity.headline(0, 0, 0))
        assertEquals("R3 Strong", SolarActivity.headline(3, 1, 2))
        assertEquals("S4 Severe", SolarActivity.headline(1, 4, 2))
        assertEquals("G2 Moderate", SolarActivity.headline(0, 0, 2))
        // Tie between all three: the geomagnetic storm is the one you can walk outside and see.
        assertEquals("G3 Strong", SolarActivity.headline(3, 3, 3))
        // Tie between R and S with no storm: radio blackout wins.
        assertEquals("R2 Moderate", SolarActivity.headline(2, 2, 0))
    }

    @Test fun everyScaleLevelHasARealConsequenceSentence() {
        for (prefix in listOf('R', 'S', 'G')) {
            for (level in 0..5) {
                val text = SolarActivity.effect(prefix, level)
                assertTrue("$prefix$level has no effect text", text.length > 10)
                assertTrue("$prefix$level should end in a full stop", text.endsWith("."))
            }
        }
        assertNotNull(SolarActivity.effect('G', 99)) // clamps instead of throwing
    }

    @Test fun labelFormattingIsLocaleStable() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // comma decimal separator
            assertEquals("M5.4", SolarActivity.flareClass(5.4e-5)?.label)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
