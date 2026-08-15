package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfPropagationTest {

    private fun band(f107: Double?, kp: Double?, xray: Double?, name: String) =
        HfPropagation.report(f107, kp, xray).first { it.name == name }

    @Test fun criticalFrequencyRisesWithSolarFluxAndFallsAtNight() {
        val quiet = HfPropagation.criticalFrequencyMhz(70.0, daytime = true)
        val active = HfPropagation.criticalFrequencyMhz(200.0, daytime = true)
        assertTrue("an active Sun must lift foF2 ($quiet -> $active)", active > quiet)
        val night = HfPropagation.criticalFrequencyMhz(200.0, daytime = false)
        assertTrue("the F2 layer decays after dark", night < active)
        // Absurd inputs clamp rather than producing a nonsense ionosphere.
        assertTrue(HfPropagation.criticalFrequencyMhz(9999.0, true) <= 16.0)
        assertTrue(HfPropagation.criticalFrequencyMhz(-50.0, true) >= 2.0)
        // A missing flux reading falls back to the quiet Sun, not to zero.
        assertEquals(quiet, HfPropagation.criticalFrequencyMhz(null, daytime = true), 1e-9)
    }

    @Test fun aGeomagneticStormDepressesTheMuf() {
        val calm = HfPropagation.mufMhz(150.0, kp = 1.0, daytime = true)
        val unsettled = HfPropagation.mufMhz(150.0, kp = 3.0, daytime = true)
        val storm = HfPropagation.mufMhz(150.0, kp = 8.0, daytime = true)
        // Up to Kp 3 there is no penalty at all.
        assertEquals(calm, unsettled, 1e-9)
        assertTrue("a severe storm must drop the MUF ($calm -> $storm)", storm < calm * 0.8)
        // It never collapses to zero — that would read as "no ionosphere".
        assertTrue(HfPropagation.mufMhz(60.0, kp = 9.0, daytime = false) >= 1.0)
    }

    @Test fun theHighBandsOpenOnlyWhenTheSunIsActive() {
        // Solar minimum: 10 m is shut by day.
        assertEquals(HfPropagation.Quality.CLOSED, band(70.0, 0.0, null, "10m").day)
        // Solar maximum: it opens.
        val tenAtMax = band(230.0, 0.0, null, "10m").day
        assertTrue("10m should open at solar max, got $tenAtMax", tenAtMax != HfPropagation.Quality.CLOSED)
        // 20 m is the workhorse — open by day across the range.
        assertTrue(band(140.0, 0.0, null, "20m").day != HfPropagation.Quality.CLOSED)
    }

    @Test fun theLowBandsComeAliveAfterDarkWhichIsTheWholePoint() {
        val eighty = band(120.0, 0.0, null, "80m")
        assertEquals(HfPropagation.Quality.FAIR, eighty.day)   // D layer absorbs it in daylight
        assertEquals(HfPropagation.Quality.GOOD, eighty.night) // and lets go after sunset
        val oneSixty = band(120.0, 0.0, null, "160m")
        assertTrue("160m must be better at night", oneSixty.night > oneSixty.day)
    }

    @Test fun aFlareBlacksOutTheDaylightLowBandsAndLeavesTheNightSideAlone() {
        val calmDay = band(120.0, 0.0, null, "40m")
        val flareDay = band(120.0, 0.0, 2e-4, "40m")  // X2 — an R3 blackout
        assertEquals(HfPropagation.Quality.CLOSED, flareDay.day)
        assertTrue("without the flare 40m was open by day", calmDay.day != HfPropagation.Quality.CLOSED)
        // Night is untouched: no sunlight, no D layer, no absorption.
        assertEquals(calmDay.night, flareDay.night)
        // A modest M1 costs one step rather than everything.
        val m1 = band(120.0, 0.0, 1e-5, "40m")
        assertTrue("an M1 should degrade but not necessarily close 40m", m1.day <= calmDay.day)
    }

    @Test fun absorptionStepsTrackTheBlackoutScale() {
        assertEquals(0, HfPropagation.absorptionSteps(null))
        assertEquals(0, HfPropagation.absorptionSteps(1e-6))  // C class, no blackout
        assertEquals(1, HfPropagation.absorptionSteps(1e-5))  // R1
        assertEquals(2, HfPropagation.absorptionSteps(5e-5))  // R2
        assertTrue(HfPropagation.absorptionSteps(1e-4) >= 3)  // R3+
    }

    @Test fun theBandTableIsCompleteAndOrdered() {
        val report = HfPropagation.report(140.0, 2.0, null)
        assertEquals(HfPropagation.BANDS.size, report.size)
        assertEquals(10, report.size)
        assertEquals("160m", report.first().name)
        assertEquals("6m", report.last().name)
        // Frequencies ascend, so the table reads bottom-of-the-spectrum first.
        assertEquals(report.map { it.megahertz }.sorted(), report.map { it.megahertz })
    }

    @Test fun bestDayBandPrefersTheHighestOpenBand() {
        val max = HfPropagation.report(230.0, 0.0, null)
        val best = HfPropagation.bestDayBand(max)
        assertNotNull(best)
        // At solar max the best daytime band should be well up the spectrum.
        assertTrue("expected a high band at solar max, got ${best!!.name}", best.megahertz >= 14.0)
        // Everything shut: a total blackout leaves nothing to recommend.
        val blackedOut = HfPropagation.report(70.0, 9.0, 1e-3)
            .map { it.copy(day = HfPropagation.Quality.CLOSED) }
        assertNull(HfPropagation.bestDayBand(blackedOut))
    }

    @Test fun summaryStaysReadableAndLocaleStable() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val s = HfPropagation.summary(150.0, 2.0, null)
            assertTrue("summary should quote the MUF, got '$s'", s.contains("MUF"))
            assertTrue("a comma decimal must not leak in, got '$s'", !s.contains(","))
        } finally {
            java.util.Locale.setDefault(previous)
        }
        // A hard blackout says so instead of recommending a band.
        assertTrue(HfPropagation.summary(150.0, 2.0, 5e-4).contains("blacked out"))
    }

    @Test fun mufDisplayIsNullOnlyWhenThereIsNothingToGoOn() {
        assertNull(HfPropagation.mufDisplay(null, null))
        assertNotNull(HfPropagation.mufDisplay(150.0, null))
        assertNotNull(HfPropagation.mufDisplay(null, 4.0))
    }
}
