package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sky's brightness and the air's extinction: a dark sky is the identity, and everything else is
 * monotone, pinned at its knots, and — for the per-star tables — within a ten-thousandth of a
 * magnitude of the exact form.
 *
 * ⚠️ Every expected value here was printed by the shipped functions first
 * (`scratchpad/sky/SkyBrightnessMeasure.kt`) and copied in, not recalled: Rozenberg's 5.6386 at ten
 * degrees is a textbook figure, and it is asserted at the digits the code produces rather than the
 * two the textbook prints.
 */
class SkyBrightnessTest {

    @Test
    fun `with the Sun eighteen degrees down or lower, every brightness term is the identity`() {
        for (a in listOf(-90.0, -45.0, -30.0, -18.0001, SkyBrightness.NIGHT_SUN_ALT_DEG)) {
            assertEquals("sky factor at $a", 0.0, SkyBrightness.skyFactor(a), 0.0)
            assertEquals("Milky Way factor at $a", 1.0, SkyBrightness.milkyWayFactor(a), 0.0)
            assertEquals("glow at $a", 0.0, SkyBrightness.glowStrength(a), 0.0)
            assertEquals("dimming at $a", 0.0, SkyBrightness.dimmingMag(a), 0.0)
            // ⚠️ Bit for bit, not within a delta: `limit - 0.0` is exact and this is the property
            // the chart's night invariance rests on.
            for (limit in listOf(6.0, 4.2, 13.37, 17.7, SkyProjection.WIDEST_LIMIT)) {
                assertEquals(
                    "the cut must be the map's own at Sun altitude $a",
                    limit.toRawBits(), SkyBrightness.limitingMagnitude(limit, a).toRawBits(),
                )
            }
        }
        // The stars are ALL still drawn to the end of nautical twilight: only the diffuse glow and
        // the background move between -18 and -12.
        assertEquals(0.0, SkyBrightness.dimmingMag(SkyBrightness.NAUTICAL_SUN_ALT_DEG), 0.0)
        assertEquals(
            6.0.toRawBits(),
            SkyBrightness.limitingMagnitude(6.0, SkyBrightness.NAUTICAL_SUN_ALT_DEG).toRawBits(),
        )
        assertEquals(0.0, SkyBrightness.glowStrength(SkyBrightness.NAUTICAL_SUN_ALT_DEG), 0.0)
    }

    @Test
    fun `the sky brightens, the Milky Way fades and the cut shallows monotonically with the Sun`() {
        var prevSky = -1.0
        var prevDim = -1.0
        var prevMilky = 2.0
        var a = -40.0
        while (a <= 40.0) {
            val s = SkyBrightness.skyFactor(a)
            val d = SkyBrightness.dimmingMag(a)
            val m = SkyBrightness.milkyWayFactor(a)
            assertTrue("sky factor fell at $a", s >= prevSky)
            assertTrue("dimming fell at $a", d >= prevDim)
            assertTrue("Milky Way factor rose at $a", m <= prevMilky)
            assertTrue("sky factor out of range at $a", s in 0.0..1.0)
            assertTrue("Milky Way factor out of range at $a", m in 0.0..1.0)
            assertTrue("glow out of range at $a", SkyBrightness.glowStrength(a) in 0.0..1.0)
            prevSky = s
            prevDim = d
            prevMilky = m
            a += 0.01
        }
        // And each reaches its far end and stays there.
        assertEquals(1.0, SkyBrightness.skyFactor(SkyBrightness.DAY_SUN_ALT_DEG), 0.0)
        assertEquals(1.0, SkyBrightness.skyFactor(89.0), 0.0)
        assertEquals(SkyBrightness.DAY_DIMMING_MAG, SkyBrightness.dimmingMag(89.0), 0.0)
        assertEquals(0.0, SkyBrightness.milkyWayFactor(SkyBrightness.NAUTICAL_SUN_ALT_DEG), 0.0)
        assertEquals(0.0, SkyBrightness.milkyWayFactor(30.0), 0.0)
    }

    @Test
    fun `the twilight knots are where the file says they are`() {
        val t = 1e-12
        assertEquals(0.03, SkyBrightness.skyFactor(-12.0), t)
        assertEquals(0.20, SkyBrightness.skyFactor(-6.0), t)
        assertEquals(0.60, SkyBrightness.skyFactor(0.0), t)
        assertEquals(1.0, SkyBrightness.skyFactor(10.0), t)
        // Half way through astronomical twilight, half the Milky Way.
        assertEquals(0.5, SkyBrightness.milkyWayFactor(-15.0), t)
        // Civil twilight leaves 3.5 at the widest field; sunrise -0.5; full day -4.0 — Venus alone.
        assertEquals(2.5, SkyBrightness.dimmingMag(-6.0), t)
        assertEquals(6.5, SkyBrightness.dimmingMag(0.0), t)
        assertEquals(10.0, SkyBrightness.dimmingMag(10.0), t)
        assertEquals(3.5, SkyBrightness.limitingMagnitude(SkyProjection.WIDEST_LIMIT, -6.0), t)
        assertEquals(-0.5, SkyBrightness.limitingMagnitude(SkyProjection.WIDEST_LIMIT, 0.0), t)
        assertEquals(-4.0, SkyBrightness.limitingMagnitude(SkyProjection.WIDEST_LIMIT, 10.0), t)
        // The same loss at a narrow field: a telescope in daylight keeps its zoom's own depth.
        val narrow = SkyProjection.magnitudeLimit(SkyProjection.MIN_FOV_DEG, 15.0)
        assertEquals(narrow - 10.0, SkyBrightness.limitingMagnitude(narrow, 10.0), t)
        // The glow is a bump: nothing at -12, everything at -2, nothing again by +8.
        assertEquals(0.0, SkyBrightness.glowStrength(-12.0), t)
        assertEquals(0.3, SkyBrightness.glowStrength(-9.0), t)
        assertEquals(0.5, SkyBrightness.glowStrength(-7.0), t)
        assertEquals(1.0, SkyBrightness.glowStrength(SkyBrightness.GLOW_PEAK_SUN_ALT_DEG), t)
        assertEquals(0.8, SkyBrightness.glowStrength(0.0), t)
        assertEquals(0.5, SkyBrightness.glowStrength(3.0), t)
        assertEquals(0.0, SkyBrightness.glowStrength(8.0), t)
        assertEquals(0.0, SkyBrightness.glowStrength(40.0), t)
    }

    @Test
    fun `Rozenberg airmass is one at the zenith, forty on the horizon, and the textbook values between`() {
        // 1 / (1 + 0.025 e^-11): a shade under one, which extinctionMag clamps away.
        assertEquals(1.0, SkyBrightness.airmass(90.0), 1e-6)
        assertEquals(40.0, SkyBrightness.airmass(0.0), 1e-9)
        // Below the horizon the form is clamped at its horizon value rather than run away.
        assertEquals(40.0, SkyBrightness.airmass(-1.0), 1e-9)
        assertEquals(40.0, SkyBrightness.airmass(-30.0), 1e-9)
        // sec z would give 2.000 at 30° and 5.759 at 10°; Rozenberg is lower because the air curves.
        assertEquals(1.999591406, SkyBrightness.airmass(30.0), 1e-8)
        assertEquals(5.638577142, SkyBrightness.airmass(10.0), 1e-8)
        assertEquals(10.336943980, SkyBrightness.airmass(5.0), 1e-8)
        assertEquals(26.256668008, SkyBrightness.airmass(1.0), 1e-8)
    }

    @Test
    fun `extinction is zero at the zenith, five point one on the drawn horizon, nothing below it, and never rises going up`() {
        // Exactly zero, not a negative eighty-billionth: the air cannot brighten a star.
        assertEquals(0.0, SkyBrightness.extinctionMag(90.0), 0.0)
        assertEquals(1.0, SkyBrightness.transmission(90.0), 0.0)
        val horizon = Refraction.TRUE_AT_APPARENT_HORIZON_DEG
        // k * (40 - 1) = 0.13 * 39: the apparent altitude there is zero to a rounding error.
        assertEquals(5.07, SkyBrightness.extinctionMag(horizon), 1e-9)
        // And the step: a hair below the drawn horizon is the map's dimmed-by-alpha region, where
        // the air takes nothing. Both sides of the line are pinned so the boundary cannot drift
        // from the one the renderer splits its batches on.
        assertEquals(0.0, SkyBrightness.extinctionMag(horizon - 1e-9), 0.0)
        assertEquals(0.0, SkyBrightness.extinctionMag(-1.0), 0.0)
        assertEquals(0.0, SkyBrightness.extinctionMag(-30.0), 0.0)
        assertEquals(1.0, SkyBrightness.transmission(-1.0), 0.0)
        assertEquals(0.0, SkyBrightness.extinctionMag(Double.NaN), 0.0)
        // Textbook-shaped at k = 0.13: about half a magnitude at ten degrees, one at five, four on
        // the true horizon (whose APPARENT altitude is half a degree up, hence less than the drawn
        // horizon's 5.07). Digits as the shipped function printed them.
        assertEquals(0.053791899, SkyBrightness.extinctionMag(45.0), 1e-8)
        assertEquals(0.596990729, SkyBrightness.extinctionMag(10.0), 1e-8)
        assertEquals(1.179924958, SkyBrightness.extinctionMag(5.0), 1e-8)
        assertEquals(4.035909651, SkyBrightness.extinctionMag(0.0), 1e-8)
        assertEquals(0.577037063, SkyBrightness.transmission(10.0), 1e-8)
        assertEquals(10.0.pow(-0.4 * SkyBrightness.extinctionMag(10.0)), SkyBrightness.transmission(10.0), 1e-15)
        var prev = Double.MAX_VALUE
        var t = horizon
        while (t <= 90.0) {
            val x = SkyBrightness.extinctionMag(t)
            assertTrue("extinction rose going up, at $t", x <= prev + 1e-12)
            assertTrue("negative extinction at $t", x >= 0.0)
            prev = x
            t += 0.01
        }
    }

    @Test
    fun `the per-star tables agree with the exact forms to a ten-thousandth of a magnitude`() {
        val lo = SkyBrightness.SIN_DRAWN_HORIZON
        // Measured 3.8e-5 magnitudes (at the horizon, where the curvature is) and 6.5e-7 in
        // transmission over this same sweep; the bars are set at what was measured with headroom.
        var worstExtinction = 0.0
        var worstTransmission = 0.0
        val n = 20_000
        for (i in 0..n) {
            val s = lo + (1.0 - lo) * i / n
            val alt = Math.toDegrees(asin(s.coerceIn(-1.0, 1.0)))
            worstExtinction = maxOf(
                worstExtinction,
                abs(SkyBrightness.fastExtinctionMag(s) - SkyBrightness.extinctionMag(alt)),
            )
            worstTransmission = maxOf(
                worstTransmission,
                abs(SkyBrightness.fastTransmission(s) - SkyBrightness.transmission(alt)),
            )
        }
        assertTrue("table extinction worst $worstExtinction", worstExtinction < 1e-4)
        assertTrue("table transmission worst $worstTransmission", worstTransmission < 1e-6)
        // The ends: the last entry is the zenith's exact zero, and below the drawn horizon the
        // lookup answers the same nothing the exact form does.
        assertEquals(0.0, SkyBrightness.fastExtinctionMag(1.0), 0.0)
        assertEquals(1.0, SkyBrightness.fastTransmission(1.0), 0.0)
        assertEquals(0.0, SkyBrightness.fastExtinctionMag(lo - 1e-12), 0.0)
        assertEquals(1.0, SkyBrightness.fastTransmission(lo - 1e-12), 0.0)
        assertEquals(0.0, SkyBrightness.fastExtinctionMag(-1.0), 0.0)
        assertEquals(0.0, SkyBrightness.fastExtinctionMag(Double.NaN), 0.0)
        assertEquals(1.0, SkyBrightness.fastTransmission(Double.NaN), 0.0)
        // And the first entry IS the drawn horizon's 5.07.
        assertEquals(5.07, SkyBrightness.fastExtinctionMag(lo), 1e-9)
        assertFalse(SkyBrightness.fastExtinctionMag(lo).isNaN())
    }
}
